/*
 * LSPFRIDA agent 的 Java bridge 入口（被打包成单文件 IIFE，注入顺序见 GumJsBridge.kt）。
 *
 * 与上游 `globalThis.Java = runtime` 的差异只有一个：在挂载全局 Java 之前先装 LSP 适配层
 * （把 `implementation` 的替换落点改道 LSPlant，见 adapter.js 顶部注释与 BRIDGE-CONTRACT.md）。
 *
 * 回退：`globalThis.__lspNativeReplace === true`（用户脚本/宿主可设）或同步通道缺失时，
 * 不注册 backend，行为退回上游原生 ArtMethod entry 改写（对照用，不删）。
 *
 * 第二阶段（zymbiote 式 zygote 注入）的消费侧入口也在这里：读取 Kotlin 侧注入的
 * `globalThis.__lspZygoteGate`（坏值 → null，永不抛），作为 `zygoteGate` 交给 adapter.install，
 * 最终出现在 `__lspBridgeInfo.zygote`（契约见 BRIDGE-CONTRACT.md §6）。
 */

import JavaBridge from 'frida-java-bridge';
import { setReplacementBackend } from 'frida-java-bridge/lib/android.js';
import { installLspBackend, ADAPTER_VERSION } from './adapter.js';

const LOG_PREFIX = '[lsp-javabridge] ';

function logToConsole (message) {
  try {
    console.log(LOG_PREFIX + message);
  } catch (e) {
  }
}

/**
 * 绑定 libgumjs_bridge.so 的同步导出（cpp 侧实现，契约 §1）。
 * 任一符号缺失 => 返回 null，调用方走原生 ArtMethod 回退。
 */
function bindNativeChannel () {
  let module;
  try {
    module = Process.getModuleByName('libgumjs_bridge.so');
  } catch (e) {
    logToConsole('libgumjs_bridge.so not found: ' + e);
    return null;
  }

  // 只导出 newGlobalRef：adapter 从不删除引用（谁 newGlobalRef 谁负责，删除在 Kotlin 侧）。
  // lsp_delete_global_ref 仍由 cpp 导出给 Kotlin 用，JS 侧不再绑定它。
  let newGlobalRefFn;
  try {
    const syncFn = new NativeFunction(
      module.getExportByName('lsp_sync_call'), 'pointer', ['pointer'], { exceptions: 'propagate' });
    syncCall = (json) => {
      const result = syncFn(Memory.allocUtf8String(json));
      if (result.isNull()) return '{"ok":false,"err":"lsp_sync_call returned NULL"}';
      return result.readUtf8String();
    };
  } catch (e) {
    logToConsole('lsp_sync_call missing (cpp 未更新？): ' + e);
    return null;
  }

  try {
    const pinFn = new NativeFunction(
      module.getExportByName('lsp_new_global_ref'), 'pointer', ['pointer'], { exceptions: 'propagate' });
    newGlobalRefFn = (handle) => pinFn(handle);
  } catch (e) {
    logToConsole('global-ref helper missing: ' + e);
    newGlobalRefFn = () => null;
  }

  return { syncCall, newGlobalRef: newGlobalRefFn };
}

function makeIsInt64 () {
  let ctor = null;
  try {
    ctor = int64(0).constructor;
  } catch (e) {
  }
  return (v) => v !== null && typeof v === 'object' && ctor !== null && v instanceof ctor;
}

/**
 * 第二阶段 zygote gate 标记（docs/Zygote-Injection-Design.md §3.6 / BRIDGE-CONTRACT.md §6）。
 *
 * GumJsBridge.loadScript 在脚本最前面固定注入 `globalThis.__lspZygoteGate = <json 或 null>;`，
 * 这里做**防御式**读取：非 string / JSON 坏 / 形状不对（hit 不是 boolean）→ null，且**永不抛**。
 * gate 只是附加观测信息，任何异常都不允许影响 Java 桥装载（fail-open，第一阶段行为不变）。
 *
 * @returns {{hit: boolean, name?: *, ppid?: *, proc?: *}|null}
 */
function readZygoteGate () {
  let raw;
  try {
    raw = globalThis.__lspZygoteGate;
  } catch (e) {
    return null;
  }
  if (typeof raw !== 'string') return null;
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (e) {
    return null;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  if (typeof parsed.hit !== 'boolean') return null;
  return parsed;
}

function bootstrap () {
  const zygoteGate = readZygoteGate();
  const native = bindNativeChannel();
  if (native === null) {
    logToConsole('native sync channel unavailable → upstream ArtMethod replacement path');
    return { mode: 'native', version: ADAPTER_VERSION };
  }

  const adapter = installLspBackend({
    Java: JavaBridge,
    setReplacementBackend,
    syncCall: native.syncCall,
    newGlobalRef: native.newGlobalRef,
    ptr,
    int64,
    nextTick: (fn) => Script.nextTick(fn),
    log: logToConsole,
    isInt64: makeIsInt64(),
    Memory,
    NativeCallback,
  });

  const info = adapter.install({
    rpc: typeof rpc !== 'undefined' ? rpc : undefined,
    nativeFallback: globalThis.__lspNativeReplace === true,
    zygoteGate,
  });
  globalThis.__lspAdapter = adapter;
  return info;
}

let info;
try {
  info = bootstrap();
} catch (e) {
  info = { mode: 'native', version: ADAPTER_VERSION, error: String(e) };
  logToConsole('adapter bootstrap failed: ' + e + ' → upstream ArtMethod replacement path');
}
globalThis.__lspBridgeInfo = info;

globalThis.Java = JavaBridge;
