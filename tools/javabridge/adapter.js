/*
 * LSPFRIDA × frida-java-bridge 7.0.13 适配层（第一阶段「接通上游 Java 桥」的 JS 侧）
 *
 * 职责（唯一魔改落点，契约见 tools/javabridge/BRIDGE-CONTRACT.md）：
 *   1. 把上游 `ArtMethodMangler.replace / revert / resolveTarget` 改道到 LSPlant：
 *      `implementation = fn` 时**同步**经 cpp `lsp_sync_call` 通知 Kotlin 挂 LSPlant hook，
 *      拿到结果（成功/失败）后同步返回或同步抛错；不再做上游的 ArtMethod entry 改写。
 *   2. 提供一个 JS `NativeCallback`：Kotlin 的 LSPlant 拦截体在 **app 线程**同步调用它，
 *      请求里是 Java 值的 JSON 编码，响应决定「返回用户值 / 跳过原方法 / 返回默认值」。
 *      这正是上游 `implement()` 的机制（`new NativeCallback(f, ...)` + ART 在 app 线程调用），
 *      因此 `this` 是真实 wrapper、`this.method()` 是同步原调用（靠 Kotlin 侧重入守卫）。
 *   3. 保留上游原生 ArtMethod 路径作为回退对照：只要 sync 通道不可用（cpp 导出缺失 /
 *      `globalThis.__lspNativeReplace === true`），就不注册 backend，行为与上游一致。
 *   4. 第二阶段附加（不改上面任何语义）：`install({ zygoteGate })` 把 Kotlin 侧探测到的
 *      zygote gate 标记（设计文档 §3.5）规整为返回 info 的 `zygote: {hit,name,ppid,proc}`；
 *      缺失/非法则**不加该字段**（`__lspBridgeInfo` 与第一阶段逐字段一致）。契约见
 *      BRIDGE-CONTRACT.md §6。
 *
 * 与上游的显式偏差（见 DONE.md）：
 *   - implementation 不允许 async（上游同样不允许；sync 路径下 thenable 按不兼容处理）。
 *   - ArtMethod 未被改写（这是本阶段的目的），检测面见 DONE.md。
 */

export const ADAPTER_VERSION = '1.0.0-rc1';

const INSTANCE_METHOD = 1;
const STATIC_METHOD = 2;
const CONSTRUCTOR_METHOD = 3;

const STRATEGY_VIRTUAL = 1;

/**
 * @param {object} deps
 * @param {object} deps.Java             上游 Runtime 实例（globalThis.Java 同一对象）
 * @param {function} deps.setReplacementBackend  打过 LSPFRIDA patch 的 android.js 导出
 * @param {function} deps.syncCall       (json: string) => string          JS→Kotlin 同步通道
 * @param {function} deps.newGlobalRef   (ptr) => ptr|null                 钉住 jobject
 * @param {function} deps.deleteGlobalRef (ptr) => void
 * @param {function} deps.ptr            frida `ptr`
 * @param {function} deps.int64          frida `int64`
 * @param {function} deps.nextTick       (fn) => void                      Script.nextTick
 * @param {function} deps.log            (msg: string) => void
 * @param {function} deps.isInt64        (v) => boolean
 * @param {object}   deps.Memory         frida `Memory`（dispatcher 返回值缓冲）
 * @param {function} deps.NativeCallback frida `NativeCallback`
 */
export function installLspBackend (deps) {
  const {
    Java,
    setReplacementBackend,
    syncCall,
    newGlobalRef,
    deleteGlobalRef,
    ptr,
    int64,
    nextTick,
    log,
    isInt64,
    Memory,
    NativeCallback,
  } = deps;

  if (typeof setReplacementBackend !== 'function') {
    throw new Error('[lsp] android.js patch missing: setReplacementBackend unavailable');
  }

  /** tag -> { handler, meta }（Kotlin 回调按 tag 找用户函数） */
  const registry = Object.create(null);
  /** 类名 -> wrapper（Java.use 的缓存，避免重复解析） */
  const classWrappers = Object.create(null);
  /** dispatcher 返回值保活（Memory.allocUtf8String 的指针被 GC 后 Kotlin 会读到野内存） */
  const keepAlive = [];
  const KEEP_ALIVE_MAX = 32;

  let dispatcher = null;
  let dispatcherRegistered = false;
  let unloadHooked = false;

  // ---------------------------------------------------------------------------
  // 小工具
  // ---------------------------------------------------------------------------

  function use (name) {
    let w = classWrappers[name];
    if (w === undefined) {
      w = Java.use(name);
      classWrappers[name] = w;
    }
    return w;
  }

  function hex (p) {
    return p === null || p === undefined ? null : p.toString(16);
  }

  function toPtr (h) {
    if (h === null || h === undefined) return null;
    return typeof h === 'string' ? ptr(h) : h;
  }

  function isArrayClassName (name) {
    return typeof name === 'string' && name.charCodeAt(0) === 0x5b; // '['
  }

  function basename (className) {
    return className.slice(className.lastIndexOf('.') + 1);
  }

  function classNameOf (wrapper) {
    const actual = wrapper.$className;
    return actual !== undefined ? actual : undefined;
  }

  // ---------------------------------------------------------------------------
  // 值编解码（契约 §3.2；sync 主路径用 __p，async 回退用 __h）
  // ---------------------------------------------------------------------------

  /**
   * wire -> JS。opts: { deleteRefs?: boolean, declaredClassName?: string }
   * - `__p`：Kotlin 在本线程拿到的 jobject 裸指针（只读，释放责任在 Kotlin）
   * - `__h`：Kotlin 钉住的全局引用（只读，Kotlin 在本次回调收尾时释放）
   * 两者都用 owned=false 包成 wrapper：生命周期 = 本次回调（与上游回调 frame 语义一致）。
   */
  function decodeValue (v, opts = {}) {
    if (v === null || v === undefined) return null;
    const t = typeof v;
    if (t === 'string') {
      if (v === 'NaN') return NaN;
      if (v === 'Infinity') return Infinity;
      if (v === '-Infinity') return -Infinity;
      return v;
    }
    if (t === 'boolean' || t === 'number') return v;
    if (t === 'object') {
      if (v.__i64 !== undefined) return int64(v.__i64);
      if (v.__h !== undefined || v.__p !== undefined) {
        const h = toPtr(v.__h !== undefined ? v.__h : v.__p);
        try {
          return castHandle(h, v.__c !== undefined ? v.__c : opts.declaredClassName, false);
        } finally {
          if (v.__h !== undefined && opts.deleteRefs === true) {
            deleteGlobalRef(h);
          }
        }
      }
      if (v.__o !== undefined) {
        log('[lsp] WARN: object argument passed as placeholder (' + v.__o + ')，JS 侧不可用');
        return null;
      }
    }
    return null;
  }

  function castHandle (handle, className, owned) {
    let klass;
    if (isArrayClassName(className)) {
      // 数组：桥没有可直接 Java.use 的描述符类名，用 Object 承载 handle（实例方法/字段受限，
      // 但作为参数回传给 Java 时 toJni 直接用 $h，可用）
      klass = use('java.lang.Object');
    } else if (typeof className === 'string' && className.length !== 0) {
      klass = use(className);
    } else {
      klass = use('java.lang.Object');
    }
    return Java.cast(handle, klass, owned);
  }

  /**
   * JS -> wire（用于 async 回退路径的 inner 参数；sync 主路径不需要——JNI 直接传值）。
   * 返回 { ok:true, value } | { ok:false, reason }
   */
  function encodeValue (v) {
    if (v === null || v === undefined) return { ok: true, value: null };
    const t = typeof v;
    if (t === 'string' || t === 'boolean') return { ok: true, value: v };
    if (t === 'number') {
      if (v !== v) return { ok: true, value: 'NaN' };
      if (v === Infinity) return { ok: true, value: 'Infinity' };
      if (v === -Infinity) return { ok: true, value: '-Infinity' };
      return { ok: true, value: v };
    }
    if (t === 'object') {
      if (isInt64(v)) return { ok: true, value: { __i64: v.toString() } };
      const h = v.$h;
      if (h === undefined || h === null) return { ok: false, reason: 'not a Java object' };
      const g = newGlobalRef(h);
      if (g === null || g.isNull()) return { ok: false, reason: 'newGlobalRef failed' };
      return { ok: true, value: { __h: hex(g), __c: classNameOf(v) } };
    }
    return { ok: false, reason: 'unsupported value type: ' + t };
  }

  // ---------------------------------------------------------------------------
  // hook 回调：payload(JSON) -> reply(JSON)，全程同步（运行在 app 线程）
  // ---------------------------------------------------------------------------

  function makeReply (meta, retval) {
    const retType = meta.retType;

    if (meta.type === CONSTRUCTOR_METHOD) {
      return defaultReply('Reimplementing $new is not possible; replace implementation of $init instead');
    }

    if (!retType.isCompatible(retval)) {
      return defaultReply('Implementation for ' + meta.methodName +
        ' expected return value compatible with ' + retType.className);
    }

    const className = retType.className;
    if (className === 'void') {
      return JSON.stringify({ kind: 'value', r: null });
    }
    if (className === 'char') {
      return JSON.stringify({ kind: 'value', r: retval });
    }
    if (retType.type !== 'pointer') {
      const enc = encodePrimitive(retval);
      return JSON.stringify({ kind: 'value', r: enc });
    }
    if (className === 'java.lang.String') {
      return JSON.stringify({ kind: 'value', r: retval === null ? null : retval });
    }
    if (retval === null) {
      return JSON.stringify({ kind: 'value', r: null });
    }
    const h = retval.$h;
    if (h === undefined || h === null) {
      return defaultReply('Implementation for ' + meta.methodName + ' returned a non-Java object');
    }
    return JSON.stringify({ kind: 'value', r: { __h: hex(h) } });
  }

  function encodePrimitive (v) {
    if (isInt64(v)) return { __i64: v.toString() };
    if (typeof v === 'number') {
      if (v !== v) return 'NaN';
      if (v === Infinity) return 'Infinity';
      if (v === -Infinity) return '-Infinity';
    }
    return v;
  }

  function defaultReply (message) {
    const err = new Error(message);
    nextTick(() => {
      throw err;
    });
    return JSON.stringify({ kind: 'default' });
  }

  function handleHookPayload (payloadJson) {
    let payload;
    try {
      payload = JSON.parse(payloadJson);
    } catch (e) {
      return JSON.stringify({ kind: 'proceed' });
    }

    const entry = registry[payload.key];
    if (entry === undefined) {
      log('[lsp] WARN: no handler registered for ' + payload.key + ' → proceed 原方法');
      return JSON.stringify({ kind: 'proceed' });
    }

    const meta = entry.meta;
    let self;
    let args;
    try {
      if (meta.type === INSTANCE_METHOD) {
        if (payload.this === null || payload.this === undefined) {
          throw new Error('[lsp] instance method hook without `this`');
        }
        self = decodeValue(payload.this, { declaredClassName: meta.holder.$n });
      } else {
        self = meta.holder; // 静态方法：this = 类 wrapper（上游同）
      }

      args = [];
      for (let i = 0; i !== meta.argTypes.length; i++) {
        args.push(decodeValue(payload.args[i], { declaredClassName: meta.argTypes[i].className }));
      }

      const retval = entry.handler.apply(self, args);
      if (retval !== null && retval !== undefined && typeof retval.then === 'function') {
        return defaultReply('Implementation for ' + meta.methodName +
          ' returned a Promise; sync hook callbacks must return a value (upstream-compatible)');
      }
      return makeReply(meta, retval);
    } catch (e) {
      nextTick(() => {
        throw e;
      });
      return JSON.stringify({ kind: 'default' });
    }
  }

  function installDispatcher () {
    if (dispatcher !== null) return dispatcher;
    dispatcher = new NativeCallback((payloadPtr) => {
      let out;
      try {
        out = handleHookPayload(payloadPtr.readUtf8String());
      } catch (e) {
        nextTick(() => {
          throw e;
        });
        out = JSON.stringify({ kind: 'default' });
      }
      if (typeof out !== 'string') out = JSON.stringify({ kind: 'default' });
      const buf = Memory.allocUtf8String(out);
      keepAlive.push(buf);
      if (keepAlive.length > KEEP_ALIVE_MAX) keepAlive.shift();
      return buf;
    }, 'pointer', ['pointer']);
    return dispatcher;
  }

  function registerDispatcher () {
    if (dispatcherRegistered) return true;
    try {
      const out = syncCall(JSON.stringify({
        op: 'set_dispatcher',
        ptr: installDispatcher().toString(10),
      }));
      const res = JSON.parse(out);
      if (res.ok !== true) {
        log('[lsp] WARN: set_dispatcher rejected: ' + JSON.stringify(res));
        return false;
      }
      dispatcherRegistered = true;
      return true;
    } catch (e) {
      log('[lsp] WARN: set_dispatcher failed: ' + e);
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // replacement backend（上游 ArtMethodMangler 的替身）
  // ---------------------------------------------------------------------------

  const backend = {
    /**
     * @param {object} mangler  上游 MethodMangler 实例（我们只借它的 methodId 原值）
     * @param {object} impl     `implement()` 创建的 NativeCallback（带 _lspMeta）
     */
    replace (mangler, impl, isInstanceMethod, argTypes, vm, api) {
      const meta = impl._lspMeta;
      if (meta === undefined) {
        throw new Error('[lsp] internal: replacement metadata missing (class-factory patch)');
      }
      if (!registerDispatcher()) {
        throw new Error('[lsp] hook install failed: JS dispatcher unavailable');
      }

      const cls = meta.holder.$n;
      const methodName = meta.methodName;
      const sigs = argTypes.map(t => t.className);
      const tag = cls + '#' + methodName + '#' + sigs.join(',');
      const ctor = meta.ctorInit === true;

      // 类对象全局引用：让 Kotlin 用 bridge 解析出的这个 Class（defining loader 正确），
      // 而不是它自己的 targetLoader + Class.forName（审查 §3 的单点快照缺陷）。
      let clsRef = null;
      try {
        const env = Java.vm.getEnv();
        const ch = meta.holder.$borrowClassHandle(env);
        try {
          clsRef = newGlobalRef(ch.value);
        } finally {
          ch.unref(env);
        }
      } catch (e) {
        log('[lsp] WARN: borrowClassHandle failed for ' + cls + ': ' + e);
      }

      let res;
      try {
        res = JSON.parse(syncCall(JSON.stringify({
          op: 'install',
          cls,
          method: methodName,
          sigs,
          isStatic: !isInstanceMethod,
          ctor,
          tag,
          clsRef: clsRef === null ? null : hex(clsRef),
        })));
      } finally {
        if (clsRef !== null) deleteGlobalRef(clsRef);
      }

      if (res === null || res === undefined || res.ok !== true) {
        throw new Error('[lsp] hook install failed (' + (res && res.kind) + '): ' + (res && res.err));
      }

      mangler._lspTag = tag;
      mangler.hookedMethodId = mangler.methodId;
      registry[tag] = { handler: meta.handler, meta, tag };
    },

    revert (mangler, vm) {
      const tag = mangler._lspTag;
      if (tag === undefined) return;
      delete registry[tag];
      mangler._lspTag = undefined;
      try {
        const res = JSON.parse(syncCall(JSON.stringify({ op: 'uninstall', tag })));
        if (res.ok !== true) {
          log('[lsp] WARN: unhook failed tag=' + tag + ' err=' + res.err);
        }
      } catch (e) {
        log('[lsp] WARN: unhook error tag=' + tag + ' err=' + e);
      }
    },

    resolveTarget (mangler, wrapper, isInstanceMethod, env, api) {
      return mangler.methodId;
    },

    /** 脚本卸载前清空注册表（LSPlant 手柄由 Kotlin unhookAll 负责）。 */
    dispose () {
      for (const tag of Object.keys(registry)) delete registry[tag];
      setReplacementBackend(null);
    },
  };

  // async 回退通道：与旧 RouteB 协议兼容（cpp 的 frida:rpc 泵）。仅当 dispatcher 不可用，
  // 或 Kotlin 侧 hasDispatcher() 为 false 时被调用；它跑在 JS 线程，语义降级为「异步 + Promise 支持」。
  function installAsyncFallback (rpc) {
    if (rpc === undefined || rpc.exports === undefined) return false;
    rpc.exports.__lspHookReply = function (id, payloadJson) {
      const out = handleHookPayload(payloadJson);
      try {
        const reply = JSON.parse(out);
        if (reply.kind === 'value') {
          return JSON.stringify({ over: true, r: reply.r === undefined ? null : reply.r });
        }
        if (reply.kind === 'proceed') return JSON.stringify({ over: false, r: null });
        return JSON.stringify({ over: true, r: null });
      } catch (e) {
        return JSON.stringify({ over: true, r: null });
      }
    };
    return true;
  }

  // ---------------------------------------------------------------------------
  // 第二阶段：zygote gate 标记（docs/Zygote-Injection-Design.md §3.6）
  // ---------------------------------------------------------------------------

  /**
   * 入参 `zygoteGate`（Kotlin 侧 ZygoteGate.probe 的 JSON）→ `info.zygote` 的规范形状。
   *
   * 合法 = 对象且 `hit` 是 boolean（其余字段类型不对时按 null 补齐，不因单个字段畸形丢弃整条：
   * gate 是观测信息）。非法/缺失 → null（= 返回的 info 里**不出现** zygote 键，
   * 此时 `__lspBridgeInfo` 与第一阶段逐字段一致）。
   *
   * @returns {{hit: boolean, name: string|null, ppid: number|null, proc: string|null}|null}
   */
  function normalizeZygoteGate (zygoteGate) {
    if (zygoteGate === null || typeof zygoteGate !== 'object' || Array.isArray(zygoteGate)) {
      return null;
    }
    if (typeof zygoteGate.hit !== 'boolean') return null;
    return {
      hit: zygoteGate.hit,
      name: typeof zygoteGate.name === 'string' ? zygoteGate.name : null,
      ppid: typeof zygoteGate.ppid === 'number' && Number.isFinite(zygoteGate.ppid)
        ? Math.trunc(zygoteGate.ppid)
        : null,
      proc: typeof zygoteGate.proc === 'string' ? zygoteGate.proc : null,
    };
  }

  /** 合法 gate 才附加字段；非法/缺失保持 phase-1 的 info 形状不变。 */
  function withZygoteGate (info, zygote) {
    if (zygote !== null) info.zygote = zygote;
    return info;
  }

  // ---------------------------------------------------------------------------
  // 安装
  // ---------------------------------------------------------------------------

  function install (options = {}) {
    const { rpc, nativeFallback = false, zygoteGate } = options;
    const zygote = normalizeZygoteGate(zygoteGate);

    if (nativeFallback === true) {
      log('[lsp] Java bridge: native ArtMethod replacement path selected (fallback)');
      return withZygoteGate({ mode: 'native', version: ADAPTER_VERSION }, zygote);
    }

    const asyncOk = installAsyncFallback(rpc);
    const syncOk = registerDispatcher();
    if (!syncOk && !asyncOk) {
      log('[lsp] Java bridge: no sync channel and no rpc → native ArtMethod path (fallback)');
      return withZygoteGate({ mode: 'native', version: ADAPTER_VERSION }, zygote);
    }

    setReplacementBackend(backend);
    log('[lsp] Java bridge adapter ' + ADAPTER_VERSION + ' installed (mode=' +
      (syncOk ? 'lsplant-sync' : 'lsplant-async-fallback') + ')');
    return withZygoteGate({
      mode: syncOk ? 'lsplant-sync' : 'lsplant-async-fallback',
      version: ADAPTER_VERSION,
    }, zygote);
  }

  return {
    install,
    backend,
    handleHookPayload,
    // 测试与诊断
    _internal: { decodeValue, encodeValue, makeReply, registry, installDispatcher },
  };
}
