package com.bail.lspfrifa.ipc;

import com.bail.lspfrifa.ipc.ILogReceiver;

interface IScriptExecutor {
    /**
     * 动态加载/热替换 JS 脚本（卸旧载新）
     */
    boolean loadScript(String scriptContent, boolean hintInject);

    /**
     * 卸载当前脚本并清理 Hook
     */
    void unloadScript();

    /**
     * 注册宿主日志回调
     */
    void registerLogReceiver(ILogReceiver receiver);

    /**
     * 目标进程存活心跳探测
     */
    boolean ping();
}