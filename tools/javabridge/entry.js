/*
 * LSPFRIDA agent 的 Java bridge 入口（被打包成单文件 IIFE，注入顺序见 GumJsBridge.kt）。
 *
 * 与上游 `globalThis.Java = runtime` 的差异只有一个：在挂载全局 Java 之前先装 LSP 适配层
 * （把 `implementation` 的替换落点改道 LSPlant，见 adapter.js 顶部注释与 BRIDGE-CONTRACT.md）。
 *
 * 回退：`globalThis.__lspNativeReplace === true`（用户脚本/宿主可设）或同步通道缺失时，
 * 不注册 backend，行为退回上游原生 ArtMethod entry 改写（对照用，不删）。
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

  let syncCall;
  let newGlobalRefFn;
  let deleteGlobalRefFn;
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
    const unpinFn = new NativeFunction(
      module.getExportByName('lsp_delete_global_ref'), 'void', ['pointer'], { exceptions: 'propagate' });
    deleteGlobalRefFn = (handle) => unpinFn(handle);
  } catch (e) {
    logToConsole('global-ref helpers missing: ' + e);
    newGlobalRefFn = () => null;
    deleteGlobalRefFn = () => {};
  }

  return { syncCall, newGlobalRef: newGlobalRefFn, deleteGlobalRef: deleteGlobalRefFn };
}

function makeIsInt64 () {
  let ctor = null;
  try {
    ctor = int64(0).constructor;
  } catch (e) {
  }
  return (v) => v !== null && typeof v === 'object' && ctor !== null && v instanceof ctor;
}

function bootstrap () {
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
    deleteGlobalRef: native.deleteGlobalRef,
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
