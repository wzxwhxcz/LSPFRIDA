package com.bail.lspfrifa.ipc

import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.bail.lspfrifa.FrameworkState
import com.bail.lspfrifa.data.InjectHintStore
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主全局管理单例：
 * 维护目标进程连接池，提供脚本推送、卸载、日志监听与存活探测，
 * 并联动 ScriptStore 完成脚本持久化与目标包开关。
 */
object IpcManager {
    private const val TAG = "LSPFRIFA-IpcManager"

    // PackageName -> IScriptExecutor
    private val activeTargets = ConcurrentHashMap<String, IScriptExecutor>()

    // UI 日志监听回调列表
    private val logListeners = mutableListOf<(pkg: String, log: String) -> Unit>()

    private val logReceiverStub = object : ILogReceiver.Stub() {
        override fun onLog(packageName: String, message: String) {
            // 持久化：按包名+日期落盘（异步，不阻塞 IPC 回调），详情页未打开时日志也不丢
            LogStore.append(packageName, message)
            synchronized(logListeners) {
                logListeners.forEach { it.invoke(packageName, message) }
            }
        }
    }

    fun registerTarget(packageName: String, binder: IBinder) {
        val executor = IScriptExecutor.Stub.asInterface(binder)

        // 监听目标进程死亡（失效的 linkToDeath 会异步回调，此处兜底防御异常路径）
        val deathRecipient = object : IBinder.DeathRecipient {
            override fun binderDied() {
                Log.w(TAG, "[!] 目标进程已退出: $packageName")
                activeTargets.remove(packageName)
            }
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            Log.w(TAG, "[-] 绑定死亡监听失败（目标可能已死）: $packageName ${e.message}")
            return
        }

        try {
            // 绑定宿主日志接收器
            executor.registerLogReceiver(logReceiverStub)
            activeTargets[packageName] = executor
            Log.i(TAG, "[+] 目标进程注册成功，当前在线进程数: ${activeTargets.size}")
        } catch (e: RemoteException) {
            // 注册失败：解除死亡监听，避免泄漏回调
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            Log.e(TAG, "[-] 注册日志通道失败", e)
        }
    }

    /**
     * 向指定目标进程下发并热加载 JS 脚本。
     * 无论目标是否在线，都先持久化，保证冷启动可恢复。
     */
    fun pushScript(packageName: String, scriptCode: String): Boolean {
        ScriptStore.saveScript(packageName, scriptCode)
        val executor = activeTargets[packageName] ?: return false
        return try {
            executor.loadScript(scriptCode, InjectHintStore.isEnabled())
        } catch (e: RemoteException) {
            activeTargets.remove(packageName)
            false
        }
    }

    /** 仅持久化脚本，不主动推送（例如编辑器自动保存）。 */
    fun saveScript(packageName: String, scriptCode: String) {
        ScriptStore.saveScript(packageName, scriptCode)
    }

    fun loadScript(packageName: String): String? = ScriptStore.loadScript(packageName)

    fun removeScript(packageName: String) {
        ScriptStore.removeScript(packageName)
    }

    /** 标记该包为注入目标（宿主侧），模块冷启动据此过滤。 */
    fun enableTarget(packageName: String) {
        ScriptStore.enableTarget(packageName)
    }

    fun disableTarget(packageName: String) {
        ScriptStore.disableTarget(packageName)
    }

    fun isTargetEnabled(packageName: String): Boolean = ScriptStore.isTargetEnabled(packageName)

    /**
     * 卸载脚本
     */
    fun stopScript(packageName: String) {
        try {
            activeTargets[packageName]?.unloadScript()
        } catch (e: RemoteException) {
            activeTargets.remove(packageName)
        }
    }

    /**
     * 注册 UI 层日志流监听
     * @return 注销函数，页面销毁时调用
     */
    fun addLogListener(listener: (pkg: String, log: String) -> Unit): () -> Unit {
        synchronized(logListeners) { logListeners.add(listener) }
        return { synchronized(logListeners) { logListeners.remove(listener) } }
    }

    fun removeLogListener(listener: (pkg: String, log: String) -> Unit) {
        synchronized(logListeners) { logListeners.remove(listener) }
    }

    /**
     * 返回当前所有在线目标包名
     */
    fun activeTargets(): List<String> = activeTargets.keys.toList()

    fun isTargetAlive(packageName: String): Boolean {
        val executor = activeTargets[packageName] ?: return false
        return try {
            executor.ping()
        } catch (e: RemoteException) {
            activeTargets.remove(packageName)
            false
        }
    }

    /**
     * R1.2：在线进程数（框架级 + 引擎级取较大者）。
     * - 引擎级 = 自建 Binder 注册表（精确"JS 引擎活着"，但宿主进程死后为 0）；
     * - 框架级 = getRunningTargets() 中 UP_TO_DATE/RELOADING 的目标（框架可观测，宿主死后仍反映注入状态）；
     * 两者互补：宿主未运行时 Dashboard 不再误报 0。
     */
    fun onlineCount(): Int {
        val engine = activeTargets.size
        val framework = try {
            FrameworkState.current()?.getRunningTargets()
                ?.count { t -> t.state == HookedTarget.State.UP_TO_DATE || t.state == HookedTarget.State.RELOADING }
                ?: 0
        } catch (_: Throwable) {
            0
        }
        return maxOf(engine, framework)
    }

    /**
     * R1.2：向框架动态申请作用域（替代/加速"用户去 LSPosed Manager 手动勾选"）。
     * 回调投递到主线程（onScopeRequest 在 Binder 线程回调）。无需申请/未激活时先回调失败。
     */
    fun requestScope(packageName: String, onResult: (ok: Boolean, message: String) -> Unit) {
        val service = try { FrameworkState.current() } catch (_: Throwable) { null }
        val mainHandler = Handler(Looper.getMainLooper())
        if (service == null) {
            mainHandler.post { onResult(false, "未连接 LSPosed XposedService") }
            return
        }
        try {
            service.requestScope(listOf(packageName), object : io.github.libxposed.service.XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    mainHandler.post { onResult(true, "已授权: ${approved.joinToString(", ")}") }
                }

                override fun onScopeRequestFailed(message: String) {
                    mainHandler.post { onResult(false, message) }
                }
            })
        } catch (t: Throwable) {
            mainHandler.post { onResult(false, "请求异常: ${t.message}") }
        }
    }

    /**
     * R1.3：热重载指定目标（对其运行的模块代码做热重载；STALE 目标=跑着旧代码）。
     * 回调投递主线程。data 固定空 Bundle（不放模块自定义 Parcelable——框架限制）。
     */
    fun hotReloadTarget(target: HookedTarget, onResult: (HotReloadResult) -> Unit) {
        val service = try { FrameworkState.current() } catch (_: Throwable) { null }
        val mainHandler = Handler(Looper.getMainLooper())
        if (service == null) {
            mainHandler.post { onResult(HotReloadResult(HotReloadResult.Status.FAILED, "未连接 LSPosed XposedService")) }
            return
        }
        try {
            service.hotReloadModule(target, android.os.Bundle(), object : io.github.libxposed.service.XposedService.HotReloadCallback {
                override fun onHotReloadResult(t: HookedTarget, result: HotReloadResult) {
                    mainHandler.post { onResult(result) }
                }
            })
        } catch (t: Throwable) {
            mainHandler.post { onResult(HotReloadResult(HotReloadResult.Status.FAILED, "请求异常: ${t.message}")) }
        }
    }

    /** R1.3：对当前所有 STALE 目标逐个发起热重载；全部完成后回调（done/total/汇总消息）。回调主线程。 */
    fun hotReloadStaleTargets(onResult: (done: Int, total: Int, message: String) -> Unit) {
        val stale = try {
            FrameworkState.current()?.getRunningTargets()
                ?.filter { it.state == HookedTarget.State.STALE }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        val total = stale.size
        if (total == 0) {
            Handler(Looper.getMainLooper()).post { onResult(0, 0, "无待热重载目标（STALE）") }
            return
        }
        var done = 0
        val lock = Any()
        stale.forEach { target ->
            hotReloadTarget(target) { result ->
                synchronized(lock) { done++ }
                Log.i(TAG, "[hot] ${target.processName} -> ${result.status} ${result.message ?: ""}")
                if (done == total) {
                    Handler(Looper.getMainLooper()).post {
                        onResult(done, total, "热重载完成 $done/$total（最后: ${result.status}${result.message?.let { " $it" } ?: ""}）")
                    }
                }
            }
        }
    }
}