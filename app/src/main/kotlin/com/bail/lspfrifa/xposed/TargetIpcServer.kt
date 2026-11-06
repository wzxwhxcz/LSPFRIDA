package com.bail.lspfrifa.xposed

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.Handler
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import android.util.Log
import com.bail.lspfrifa.R
import com.bail.lspfrifa.ipc.ILogReceiver
import com.bail.lspfrifa.ipc.IScriptExecutor

/**
 * 运行在目标 App 进程内的 Binder 实体。
 * 由 LSPFRIFAModule 在 Application 就绪后构造，经 ContentProvider 握手发布给宿主。
 *
 * P1 修复（在线进程=0）：宿主 App 进程重启会清空其 activeTargets 内存注册表，
 * 且 Binder 无法跨宿主进程重启存活，因此需要目标侧感知宿主死亡并重新注册。
 * 此处监听宿主侧 ILogReceiver binder 的死亡（宿主进程被杀/重启即触发），
 * 死亡后主动再次调用 Provider register_ipc 完成重注册（宿主未运行时该调用会拉起宿主进程）。
 * GumJsBridge 保持零改动。
 */
class TargetIpcServer(
    private val targetPackage: String,
    private val appContext: Context? = null,
    /** 初始路由：提前注入场景由构造方在构造时立即注入，回调注册那一刻即可用（无丢消息窗口）。 */
    initialRouter: HookRouter? = null,
) : IScriptExecutor.Stub() {

    companion object {
        private const val TAG = "LSPFRIFA-IPC"
        private const val PROVIDER_URI = "content://com.bail.lspfrifa.config_provider/scripts"
        private const val REGISTER_METHOD = "register_ipc"
        private const val RECONNECT_KEY = "ipc_binder"
        private const val RECONNECT_ATTEMPTS = 8
        private const val RECONNECT_DELAY_MS = 3000L
    }

    @Volatile
    private var logReceiver: ILogReceiver? = null

    @Volatile
    private var reconnectInFlight = false

    /** 官方通道路由（P0）：JS 的 LSP.hook 请求先经它转接 libxposed hook()，未消费的消息才上行 UI。 */
    @Volatile
    private var hookRouter: HookRouter? = initialRouter

    fun setHookRouter(router: HookRouter?) {
        hookRouter = router
    }

    init {
        // 注册脚本消息回调（native on_message -> Kotlin -> Binder 上行）
        GumJsBridge.registerMessageCallback(object : GumJsBridge.OnScriptMessage {
            override fun onScriptMessage(message: String) {
                // 本回调运行在 cpp 的 gum-js-loop 线程：任意未捕获异常会杀掉**整个目标进程**，
                // 故最外层必须全局兜底。
                runCatching {
                    // 1. 本地 logcat 备份
                    Log.i("LSPFRIFA-Frida", "[$targetPackage] $message")
                    // 1.5 官方通道路由：LSP.hook 请求被 HookRouter 消费（命中/失败日志经 hostLog 上行）
                    if (hookRouter?.tryHandle(message) == true) return@runCatching
                    // 2. 跨进程推送宿主 Manager UI
                    //    D14：通道未建立时先入 ring buffer（握手后补发；flush 后 add 为空操作，无重复）。
                    //    为何需要：提前注入下脚本在 TargetIpcServer 构造前就已在跑，
                    //    而本回调注册（构造）到 registerLogReceiver（首次握手）之间存在窗口。
                    EarlyLogBuffer.add(message)
                    // 只读一次：logReceiver 是 @Volatile 且会被 binderDied/上面 catch 置 null，
                    // 读两次（`?.` + catch 里的赋值）在断连竞态下会让这一行日志静默丢失。
                    val receiver = logReceiver
                    try {
                        receiver?.onLog(targetPackage, message)
                    } catch (e: RemoteException) {
                        Log.w(TAG, "宿主日志通道已断开: ${e.message}")
                        if (logReceiver === receiver) logReceiver = null
                    }
                }
            }
        })
    }

    /** 模块侧工具日志：本地 logcat + 上行宿主 UI（复用 onLog 通道），供 HookRouter 命中/ARM/MISS 回传。 */
    fun hostLog(message: String) {
        Log.i("LSPFRIFA-Hook", "[$targetPackage] $message")
        // D14：宿主未上线时进 ring buffer，握手后由 registerLogReceiver 一次性补发
        EarlyLogBuffer.add(message)
        val receiver = logReceiver   // 只读一次，理由同 onScriptMessage 里的同名处理
        try {
            receiver?.onLog(targetPackage, message)
        } catch (e: RemoteException) {
            Log.w(TAG, "宿主日志通道已断开: ${e.message}")
            if (logReceiver === receiver) logReceiver = null
        }
    }

    /** 宿主死亡监听：宿主侧 ILogReceiver binder 死亡 ⇒ 宿主进程被杀/重启。 */
    private val hostDeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "[$targetPackage] 宿主进程死亡，尝试重新注册 binder 通道")
            maybeReconnectToHost()
        }
    }

    override fun loadScript(scriptContent: String?, hintInject: Boolean): Boolean {
        if (scriptContent.isNullOrBlank()) return false
        return try {
            // P1：热更前先卸载旧 LSPlant 手柄（LSP.hook 注册的 Java hook 跨脚本存活，
            // 不清除则同名重发被幂等键跳过、旧拦截语义残留）；GumJS Interceptor 由 unload 自动清理。
            hookRouter?.unhookAll()
            val ok = GumJsBridge.loadScript(scriptContent, targetPackage)   // 内部先卸旧再载新
            if (ok && hintInject) {
                // 注入成功提示（设置开关下发）；Toast 必须在主线程，Binder 回调线程 post 兜底
                Handler(Looper.getMainLooper()).post {
                    try {
                        ModuleResources.get(R.string.inject_hint_done, targetPackage)?.let { msg ->
                            appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
                        }
                    } catch (_: Throwable) {}
                }
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "加载脚本失败: ${t.message}", t)
            false
        }
    }

    override fun unloadScript() {
        try { GumJsBridge.unloadScript() } catch (_: Throwable) {}
    }

    override fun registerLogReceiver(receiver: ILogReceiver?) {
        var hostDead = false
        synchronized(this) {
            // 解绑旧 receiver 的死亡监听，避免回调堆积
            try {
                logReceiver?.asBinder()?.unlinkToDeath(hostDeathRecipient, 0)
            } catch (_: Throwable) {}
            logReceiver = receiver
            if (receiver != null) {
                // D14：宿主日志通道首次建立 —— 补发握手前的早期注入日志（幂等，仅首次生效）
                EarlyLogBuffer.flush { line ->
                    runCatching { receiver.onLog(targetPackage, line) }
                }
                try {
                    receiver.asBinder().linkToDeath(hostDeathRecipient, 0)
                } catch (t: Throwable) {
                    // 宿主已死（linkToDeath 失败/立即回调）：走恢复流程
                    Log.w(TAG, "[$targetPackage] 宿主日志通道不可用: ${t.message}")
                    hostDead = true
                }
            }
        }
        if (hostDead) maybeReconnectToHost()
    }

    override fun ping(): Boolean = true

    /**
     * 宿主死亡后重注册（后台线程，带间隔重试）。
     * 前提：宿主未运行时，Provider call 会拉起宿主进程（force-stop 除外）。
     */
    private fun maybeReconnectToHost() {
        if (reconnectInFlight) return
        reconnectInFlight = true
        Thread {
            var ok = false
            try {
                for (attempt in 1..RECONNECT_ATTEMPTS) {
                    if (reconnectToHostOnce()) {
                        Log.i(TAG, "[$targetPackage] 宿主重注册成功 attempt=$attempt")
                        ok = true
                        break
                    }
                    if (attempt < RECONNECT_ATTEMPTS) Thread.sleep(RECONNECT_DELAY_MS)
                }
                if (!ok) {
                    Log.w(TAG, "[$targetPackage] 宿主重注册失败（已重试 $RECONNECT_ATTEMPTS 次，等待目标进程重启再试）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[$targetPackage] 宿主重注册异常: ${t.message}")
            } finally {
                reconnectInFlight = false
            }
        }.apply {
            isDaemon = true
            name = "lspfrifa-host-reconnect"
        }.start()
    }

    /** 单次重注册：把本 Binder 通过 Provider 重新交给宿主。 */
    private fun reconnectToHostOnce(): Boolean {
        val context = appContext ?: return false
        val binder = asBinder()
        return try {
            val extras = Bundle().apply { putBinder(RECONNECT_KEY, binder) }
            val result = context.contentResolver.call(
                Uri.parse(PROVIDER_URI), REGISTER_METHOD, targetPackage, extras
            )
            result?.getBoolean("success") == true
        } catch (e: Exception) {
            false
        }
    }
}
