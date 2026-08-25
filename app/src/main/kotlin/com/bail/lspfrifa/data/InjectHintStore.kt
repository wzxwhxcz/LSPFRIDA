package com.bail.lspfrifa.data

import android.content.Context
import com.bail.lspfrifa.FrameworkState
import com.bail.lspfrifa.ipc.ScriptStore

/**
 * 注入成功提示开关（宿主侧设置）：目标进程注入/热更脚本成功后弹 Toast（含包名）。
 * 下发通道（t14 双通道对齐，目标进程冷注入路径也可读）：
 * - 热更（AIDL）：IpcManager.pushScript 随 loadScript(script, hintInject) 下发到目标进程；
 * - 冷注入（remote prefs）：[setEnabled] 同步写框架远程 prefs（lspfrifa_config 组，key=hint_inject），
 *   目标进程 LSPFRIFAModule 经 XposedInterface.getRemotePreferences 只读（与 enabled/script 同通道）。
 */
object InjectHintStore {
    private const val PREFS = "lspfrifa_settings"
    private const val KEY = "hint_inject"

    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 默认开启（用户显式关闭才关闭）。 */
    fun isEnabled(): Boolean =
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, true) ?: true

    fun setEnabled(v: Boolean) {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY, v)?.apply()
        // t14：双通道——remote prefs 同步写（与 ScriptStore.enableTarget 同模式；目标进程冷注入读取）
        // 框架服务瞬时不可用（remote 为 null）时静默跳过：与 InjectHintStore 默认 true 保持自洽（remote 缺键=默认开）
        try {
            FrameworkState.current()
                ?.getRemotePreferences(ScriptStore.REMOTE_GROUP)
                ?.edit()?.putBoolean(KEY, v)?.apply()
        } catch (_: Throwable) {
        }
    }
}
