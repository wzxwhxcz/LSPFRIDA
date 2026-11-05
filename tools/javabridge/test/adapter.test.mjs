/*
 * adapter.js 单测（Node 无真机环境：用 mock 复刻 frida GumJS 全局 + 上游 bridge 类型对象）。
 *
 * 覆盖的语义全部对应 brief 的"每个恢复的 Java.* 语义至少一条可复核证据"：
 *   - implementation 同步安装/同步抛错（不再 fire-and-forget）
 *   - undefined = 跳过原方法（kind=default，返回类型默认值）
 *   - 真实 this（this 由 Kotlin 传 jobject 指针 → Java.cast 真实 wrapper，非 Proxy 占位）
 *   - 对象返回值用 Java handle（__h）回传、long 用 __i64、NaN/Inf 用字符串
 *   - async/Promise 返回值按上游"不兼容"处理（nextTick 异步抛）
 *
 * 运行：cd tools/javabridge && node --test test/
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { installLspBackend, ADAPTER_VERSION } from '../adapter.js';

// ---- 上游 bridge type 对象的等价 mock ---------------------------------------

const intType = makePrimitiveType('int', v => Number.isInteger(v));
const longType = makePrimitiveType('long', v => typeof v === 'number' || v.__int64 !== undefined);
const doubleType = makePrimitiveType('double', v => typeof v === 'number');
const booleanType = makePrimitiveType('boolean', v => typeof v === 'boolean');
const charType = makePrimitiveType('char', v => typeof v === 'string' && v.length === 1);
const voidType = { className: 'void', type: 'void', isCompatible: v => v === undefined };
const stringType = { className: 'java.lang.String', type: 'pointer', isCompatible: v => typeof v === 'string' };
const objectType = name => ({
  className: name,
  type: 'pointer',
  isCompatible: v => v === null || (typeof v === 'object' && v.$h !== undefined),
});

function makePrimitiveType (className, isCompatible) {
  return { className, type: 'int32', isCompatible };
}

// ---- 测试脚手架 --------------------------------------------------------------

function makeHarness (options = {}) {
  const calls = { sync: [], cast: [], newGlobalRef: [], deleteGlobalRef: [], nextTick: [], log: [] };
  const nextTickQueue = [];
  const classWrappers = Object.create(null);
  let backend = null;
  let nativeCallback = null;

  const ptr = (value) => ({
    __value: value,
    isNull () { return value === '0x0' || value === '0'; },
    toString (radix) {
      if (radix === 16) return value;
      if (radix === 10) return String(Number.parseInt(value.replace(/[^0-9a-f]/gi, ''), 16) || 0);
      return value;
    },
  });

  const Java = {
    vm: {
      getEnv () { return { __env: true }; },
    },
    performNow (fn) { return fn(); },
    use (name) {
      let w = classWrappers[name];
      if (w === undefined) {
        w = {
          $n: name,
          $className: name,
          $borrowClassHandle (env) {
            return { value: ptr('0xclass-' + name), unref () {} };
          },
        };
        classWrappers[name] = w;
      }
      return w;
    },
    cast (handle, klass, owned) {
      if (options.castThrows === true && klass.$n === options.castThrowsFor) {
        throw new Error(`Cast from object to '${klass.$n}' isn't possible`);
      }
      calls.cast.push({ handle, klass: klass.$n, owned });
      return { $h: handle, $className: klass.$n };
    },
  };

  const syncHandler = options.syncResponse !== undefined
    ? options.syncResponse
    : (req) => ({ ok: true, armed: 1 });

  const deps = {
    Java,
    setReplacementBackend (b) { backend = b; },
    syncCall (json) {
      const req = JSON.parse(json);
      calls.sync.push(req);
      return JSON.stringify(syncHandler(req, calls.sync.length - 1));
    },
    newGlobalRef (handle) {
      calls.newGlobalRef.push(handle);
      return ptr('0xglobal-' + handle.__value);
    },
    deleteGlobalRef (handle) {
      calls.deleteGlobalRef.push(handle);
    },
    ptr,
    int64 (value) {
      return { __int64: value, toString: () => String(value) };
    },
    nextTick (fn) { calls.nextTick.push(fn); nextTickQueue.push(fn); },
    log (message) { calls.log.push(message); },
    isInt64 (v) { return v !== null && typeof v === 'object' && v.__int64 !== undefined; },
    Memory: {
      allocUtf8String (text) {
        return { __text: text, readUtf8String: () => text };
      },
    },
    NativeCallback: function NativeCallback (fn, retType, argTypes) {
      nativeCallback = { fn, retType, argTypes };
      return {
        __fn: fn,
        toString (radix) { return radix === 10 ? '140234567890123' : '0x7f0000000000'; },
      };
    },
  };

  const adapter = installLspBackend(deps);
  return {
    adapter,
    Java,
    calls,
    nextTickQueue,
    get backend () { return backend; },
    get nativeCallback () { return nativeCallback; },
    invokeDispatcher (payload) {
      assert.ok(nativeCallback !== null, 'NativeCallback 未创建');
      const resultPtr = nativeCallback.fn({ readUtf8String: () => payload });
      return JSON.parse(resultPtr.readUtf8String());
    },
    flushNextTick (fn) {
      const queue = nextTickQueue.splice(0);
      for (const item of queue) fn(item);
    },
  };
}

let tagSeq = 0;
function setupHook (h, opts = {}) {
  const className = opts.className ?? 'com.foo.Bar';
  const methodName = opts.methodName ?? 'm';
  const holder = h.Java.use(className);
  const argTypes = opts.argTypes ?? [intType, stringType];
  const retType = opts.retType ?? intType;
  const handler = opts.handler ?? ((a, b) => a + 1);
  const meta = {
    methodName,
    holder,
    type: opts.type ?? 1, // INSTANCE_METHOD
    retType,
    argTypes,
    handler,
    ctorInit: opts.ctorInit === true,
  };
  const mangler = { methodId: '0xartmethod', hookedMethodId: null };
  h.adapter.backend.replace(mangler, { _lspMeta: meta }, (meta.type === 1), argTypes, {}, {});
  return { className, methodName, holder, argTypes, retType, handler, meta, mangler };
}

function hookPayload (hook, opts = {}) {
  const args = opts.args ?? hook.argTypes.map((t, i) => defaultArgFor(t, i));
  return JSON.stringify({
    key: opts.key ?? (hook.className + '#' + hook.methodName + '#' + hook.argTypes.map(t => t.className).join(',')),
    args,
    this: opts.this === undefined ? { __p: '0x1234abcd' } : opts.this,
    ret: opts.ret ?? 'I',
    isStatic: opts.isStatic === true,
  });
}

/** handleHookPayload 返回的是 JSON 字符串（cpp 侧按字符串消费），测试里统一解析。 */
function p (json) {
  return JSON.parse(json);
}

function defaultArgFor (type, index) {
  if (type.className === 'int') return index + 1;
  if (type.className === 'java.lang.String') return 's' + index;
  if (type.className === 'boolean') return true;
  return null;
}

// ---- 用例 --------------------------------------------------------------------

test('安装：注册 dispatcher（十进制指针）+ 进入 lsplant-sync 模式', () => {
  const h = makeHarness();
  const info = h.adapter.install({});

  assert.equal(info.mode, 'lsplant-sync');
  assert.equal(info.version, ADAPTER_VERSION);
  assert.equal(h.calls.sync[0].op, 'set_dispatcher');
  assert.equal(h.calls.sync[0].ptr, '140234567890123', 'ptr 必须是十进制字符串（避免 0x/高位置位解析坑）');
  assert.ok(h.nativeCallback !== null);
  assert.deepEqual(h.nativeCallback.argTypes, ['pointer']);
  assert.equal(h.nativeCallback.retType, 'pointer');
  assert.ok(h.backend !== null, 'backend 必须注册给上游 mangler');
});

test('原生回退开关：__lspNativeReplace 时不动 backend', () => {
  const h = makeHarness();
  const info = h.adapter.install({ nativeFallback: true });
  assert.equal(info.mode, 'native');
  assert.equal(h.backend, null);
  assert.equal(h.calls.sync.length, 0);
});

test('implementation = fn：同步 install 消息字段（类/方法/签名/tag/类对象引用）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h);
  const install = h.calls.sync.at(-1);

  assert.equal(install.op, 'install');
  assert.equal(install.cls, 'com.foo.Bar');
  assert.equal(install.method, 'm');
  assert.deepEqual(install.sigs, ['int', 'java.lang.String']);
  assert.equal(install.isStatic, false);
  assert.equal(install.ctor, false);
  assert.equal(install.tag, 'com.foo.Bar#m#int,java.lang.String');
  // 类对象全局引用（defining loader 正确的来源），且用完立即释放
  assert.equal(install.clsRef, '0xglobal-0xclass-com.foo.Bar');
  assert.equal(h.calls.deleteGlobalRef.length, 1);
  assert.equal(h.calls.newGlobalRef[0].__value, '0xclass-com.foo.Bar');
  assert.equal(hook.mangler._lspTag, install.tag);
});

test('implementation = fn：Kotlin 拒绝时**同步抛错**（不再 fire-and-forget）', () => {
  const h = makeHarness({
    syncResponse: (req) => req.op === 'install'
      ? { ok: false, kind: 'arm', err: 'LSPlant HookFailedError' }
      : { ok: true },
  });
  h.adapter.install({});
  assert.throws(() => setupHook(h), /hook install failed \(arm\): LSPlant HookFailedError/);
  // 失败不留 registry 脏数据
  assert.equal(Object.keys(h.adapter._internal.registry).length, 0);
});

test('hook 回调：返回 int → kind=value（不执行原方法）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { handler: (a, b) => a + 1 });
  const reply = h.adapter.handleHookPayload(hookPayload(hook, { args: [41, 'x'] }));
  assert.deepEqual(p(reply), { kind: 'value', r: 42 });
  assert.equal(h.calls.nextTick.length, 0);
});

test('hook 回调：返回 undefined + 非 void → kind=default + nextTick 异步抛（上游语义）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { handler: () => undefined });
  const reply = h.adapter.handleHookPayload(hookPayload(hook));
  assert.deepEqual(p(reply), { kind: 'default' });
  assert.equal(h.calls.nextTick.length, 1);
  assert.throws(() => h.flushNextTick(fn => fn()), /expected return value compatible with int/);
});

test('hook 回调：返回 undefined + void → kind=value/null（跳过原方法但正常返回）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { retType: voidType, argTypes: [intType], handler: () => undefined });
  const reply = h.adapter.handleHookPayload(hookPayload(hook, { args: [1], ret: 'V' }));
  assert.deepEqual(p(reply), { kind: 'value', r: null });
  assert.equal(h.calls.nextTick.length, 0);
});

test('hook 回调：返回对象 wrapper → 用 Java handle 回传（真实对象，不是占位串）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, {
    retType: objectType('java.lang.Object'),
    argTypes: [intType],
    handler: () => ({ $h: '0xobject-handle', $className: 'java.lang.String' }),
  });
  const reply = h.adapter.handleHookPayload(hookPayload(hook, { args: [1] }));
  assert.deepEqual(p(reply), { kind: 'value', r: { __h: '0xobject-handle' } });
});

test('hook 回调：返回 null + 对象类型 → kind=value/null（跳过原方法，Java 拿 null）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, {
    retType: objectType('java.lang.Object'),
    argTypes: [intType],
    handler: () => null,
  });
  assert.deepEqual(p(h.adapter.handleHookPayload(hookPayload(hook, { args: [1] }))),
    { kind: 'value', r: null });
});

test('hook 回调：用户函数抛异常 → kind=default + nextTick 重抛（不 proceed 原方法）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { handler: () => { throw new Error('boom'); } });
  const reply = h.adapter.handleHookPayload(hookPayload(hook));
  assert.deepEqual(p(reply), { kind: 'default' });
  assert.throws(() => h.flushNextTick(fn => fn()), /boom/);
});

test('hook 回调：返回 Promise（async implementation）按上游"不兼容"处理', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { handler: () => Promise.resolve(1) });
  const reply = h.adapter.handleHookPayload(hookPayload(hook));
  assert.deepEqual(p(reply), { kind: 'default' });
  assert.throws(() => h.flushNextTick(fn => fn()), /returned a Promise/);
});

test('hook 回调：未注册的 tag → kind=proceed（兜底执行原方法，不静默跳过）', () => {
  const h = makeHarness();
  h.adapter.install({});
  const reply = h.adapter.handleHookPayload(JSON.stringify({
    key: 'com.nope#x#int', args: [1], this: null, ret: 'I', isStatic: false,
  }));
  assert.deepEqual(p(reply), { kind: 'proceed' });
});

test('真实 this：Kotlin 传 jobject 指针 → Java.cast(ptr, 声明类, owned=false) 的真实 wrapper', () => {
  const h = makeHarness();
  h.adapter.install({});
  let sawThis = null;
  const hook = setupHook(h, {
    handler: function () {
      sawThis = this;
      return 42;
    },
  });
  const reply = h.adapter.handleHookPayload(hookPayload(hook, { this: { __p: '0xfeedbeef' } }));
  assert.deepEqual(p(reply), { kind: 'value', r: 42 });
  assert.ok(sawThis !== null, 'this 必须被真实构造（不是 undefined/null）');
  assert.equal(sawThis.$h.__value, '0xfeedbeef', 'this.$h 是 Kotlin 传来的真实 jobject');

  const cast = h.calls.cast.at(-1);
  assert.equal(cast.klass, 'com.foo.Bar', '用声明类 cast，且不是 Proxy 占位');
  assert.equal(cast.owned, false, '局部引用只借用，不负责释放');
});

test('静态方法：this = 类 wrapper（上游同），不消费 payload.this', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, {
    type: 2,
    retType: stringType,
    argTypes: [],
    handler: function () { return this.$n; },
  });
  const reply = h.adapter.handleHookPayload(hookPayload(hook, { args: [], this: null, isStatic: true }));
  assert.deepEqual(p(reply), { kind: 'value', r: 'com.foo.Bar' });
});

test('参数解码：字符串/int/boolean/long(__i64)/对象(__h)/NaN 字符串', () => {
  const h = makeHarness();
  h.adapter.install({});
  const argTypes = [intType, stringType, booleanType, longType, objectType('java.lang.Object'), doubleType];
  let received = null;
  const hook = setupHook(h, {
    argTypes,
    retType: voidType,
    handler: (...args) => { received = args; return undefined; },
  });
  h.adapter.handleHookPayload(hookPayload(hook, {
    args: [7, 'str', true, { __i64: '9007199254740993' }, { __h: '0xobjref', __c: 'java.lang.String' }, 'NaN'],
  }));

  assert.equal(received[0], 7);
  assert.equal(received[1], 'str');
  assert.equal(received[2], true);
  assert.equal(received[3].toString(), '9007199254740993');
  assert.equal(received[4].$h.__value, '0xobjref');
  assert.ok(Number.isNaN(received[5]));
  assert.equal(h.calls.cast.at(-1).klass, 'java.lang.String');
});

test('参数解码：char 单字符串、__o 占位降级为 null（不崩）', () => {
  const h = makeHarness();
  h.adapter.install({});
  let received = null;
  const hook = setupHook(h, {
    argTypes: [charType, objectType('java.lang.Object')],
    retType: voidType,
    handler: (c, o) => { received = [c, o]; return undefined; },
  });
  h.adapter.handleHookPayload(hookPayload(hook, { args: ['A', { __o: 'Foo@1' }] }));
  assert.deepEqual(received, ['A', null]);
  assert.ok(h.calls.log.some(m => m.includes('placeholder')));
});

test('返回值编码：long(__i64 原样透传) / NaN / -Infinity / char', () => {
  const h = makeHarness();
  h.adapter.install({});

  const longHook = setupHook(h, {
    className: 'com.foo.L',
    methodName: 'l',
    argTypes: [],
    retType: longType,
    handler: () => ({ __int64: '9007199254740993', toString: () => '9007199254740993' }),
  });
  assert.deepEqual(
    p(h.adapter.handleHookPayload(hookPayload(longHook, { args: [] }))),
    { kind: 'value', r: { __i64: '9007199254740993' } });

  const dblHook = setupHook(h, {
    className: 'com.foo.D',
    methodName: 'd',
    argTypes: [],
    retType: doubleType,
    handler: () => NaN,
  });
  assert.deepEqual(p(h.adapter.handleHookPayload(hookPayload(dblHook, { args: [] }))),
    { kind: 'value', r: 'NaN' });

  const charHook = setupHook(h, {
    className: 'com.foo.C',
    methodName: 'c',
    argTypes: [],
    retType: charType,
    handler: () => 'Z',
  });
  assert.deepEqual(p(h.adapter.handleHookPayload(hookPayload(charHook, { args: [] }))),
    { kind: 'value', r: 'Z' });
});

test('构造器：$init 标注 → ctor=true 且 method 为类 simpleName', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, {
    className: 'com.foo.Thing',
    methodName: 'Thing',
    argTypes: [intType],
    retType: voidType,
    ctorInit: true,
  });
  const install = h.calls.sync.at(-1);
  assert.equal(install.ctor, true);
  assert.equal(install.method, 'Thing');
  assert.equal(install.tag, 'com.foo.Thing#Thing#int');
  assert.equal(hook.mangler._lspTag, 'com.foo.Thing#Thing#int');
});

test('dispatcher 端到端：NativeCallback 收到 payload 指针并回 UTF-8 JSON', () => {
  const h = makeHarness();
  h.adapter.install({});
  const hook = setupHook(h, { handler: (a) => a * 2 });
  const reply = h.invokeDispatcher(hookPayload(hook, { args: [21, 'x'] }));
  assert.deepEqual(reply, { kind: 'value', r: 42 });
});

test('implementation = null：同步 uninstall；卸载失败只告警不抛（上游 revert 不抛）', () => {
  const h = makeHarness({
    syncResponse: (req) => req.op === 'uninstall' ? { ok: false, err: 'not found', missing: true } : { ok: true },
  });
  h.adapter.install({});
  const hook = setupHook(h);
  assert.equal(Object.keys(h.adapter._internal.registry).length, 1);

  h.adapter.backend.revert(hook.mangler, {});
  const uninstall = h.calls.sync.at(-1);
  assert.equal(uninstall.op, 'uninstall');
  assert.equal(uninstall.tag, hook.className + '#' + hook.methodName + '#int,java.lang.String');
  assert.equal(Object.keys(h.adapter._internal.registry).length, 0);
  assert.ok(h.calls.log.some(m => m.includes('unhook failed')));
});

test('dispatcher 注册失败时 install 抛错（不让用户以为 hook 装上了）', () => {
  const h = makeHarness({ syncResponse: (req) => req.op === 'set_dispatcher' ? { ok: false, err: 'no handler' } : { ok: true } });
  const info = h.adapter.install({});
  assert.equal(info.mode, 'native', 'sync 不可用且无 rpc → 退回原生 ArtMethod 路径');
  assert.equal(h.backend, null);
});

// ---- 第二阶段：zygote gate 标记（设计 §3.6 / BRIDGE-CONTRACT.md §6） -------------

test('zygoteGate 合法 → info.zygote 精确形状（hit=true 与 hit=false 均是合法值）', () => {
  const h = makeHarness();
  const info = h.adapter.install({
    zygoteGate: {
      hit: true,
      name: '/lspfrifa-zygote-gate',
      ppid: 4242,
      proc: 'com.example.app:push',
    },
  });
  // 既有 mode/version 语义不受影响
  assert.equal(info.mode, 'lsplant-sync');
  assert.equal(info.version, ADAPTER_VERSION);
  assert.deepEqual(info.zygote, {
    hit: true,
    name: '/lspfrifa-zygote-gate',
    ppid: 4242,
    proc: 'com.example.app:push',
  });

  // hit=false = "探测过、本进程不是 gate 子进程"，也是合法形状（字段齐全、hit 为 false）
  const h2 = makeHarness();
  const absent = h2.adapter.install({
    zygoteGate: { hit: false, name: null, ppid: null, proc: null },
  });
  assert.deepEqual(absent.zygote, { hit: false, name: null, ppid: null, proc: null });
});

test('zygoteGate 缺失 → info 无 zygote 键（phase-1 形态逐字段不变）', () => {
  const h = makeHarness();
  const info = h.adapter.install({});
  assert.deepEqual(info, { mode: 'lsplant-sync', version: ADAPTER_VERSION });
  assert.equal('zygote' in info, false);

  // 原生回退路径同样只在"有合法 gate"时才带字段
  const nativeHarness = makeHarness();
  const nativeInfo = nativeHarness.adapter.install({ nativeFallback: true });
  assert.deepEqual(nativeInfo, { mode: 'native', version: ADAPTER_VERSION });
  assert.equal('zygote' in nativeInfo, false);
});

test('zygoteGate 坏数据（类型/形状非法）→ 无 zygote 键且不抛', () => {
  const h = makeHarness();
  const badValues = [
    'not-an-object', // 坏类型（entry.js 解析失败时理论上不会传 string，这里仍防御）
    42,
    null,
    [],
    {},
    { hit: 'yes' }, // hit 必须是 boolean
    { hit: 1, name: 'x' },
  ];
  for (const value of badValues) {
    const info = h.adapter.install({ zygoteGate: value });
    assert.equal(info.mode, 'lsplant-sync');
    assert.equal(
      'zygote' in info,
      false,
      '坏值 ' + JSON.stringify(value) + ' 不应产生 zygote 键');
  }
});
