package com.bail.lspfrifa.ipc;

interface ILogReceiver {
    /**
     * 目标进程向宿主 UI 实时流式回传 GumJS 日志
     */
    void onLog(String packageName, String message);
}
