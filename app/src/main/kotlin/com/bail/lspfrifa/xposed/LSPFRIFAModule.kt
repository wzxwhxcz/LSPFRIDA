package com.bail.lspfrifa.xposed

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed 102 规范标准模块入口。
 * 无参构造函数；框架自动扫描 META-INF/xposed/java_init.list 注册。
 *
 * 初始化时机（照 LSPilot 同款）：onPackageLoaded 触发时目标进程 Application 尚未创建
 * （ActivityThread.currentApplication() 为 null），因此这里只 hook
 * ActivityThread.callApplicationOnCreate，待 Application 真正创建后再执行
 * "是否启用 → 加载引擎 → 握手宿主 → 拉取脚本" 全流程。
 */
class LSPFRIFAModule : XposedModule() {

    companion object {
        private const val TAG = "LSPFRIFA-Hook"
        private const val MODULE_PACKAGE = "com.bail.lspfrifa"
        private const val PROVIDER_URI = "content://$MODULE_PACKAGE.config_provider"

        // R1.1：框架远程 prefs 双端键约定（与宿主 ScriptStore.REMOTE_GROUP/KEY_ENABLED/scriptKey 严格一致；
        // 宿主经 XposedService 写，模块经 XposedInterface 只读——不依赖宿主进程存活）
        private const val REMOTE_GROUP = "lspfrifa_config"
        private const val KEY_ENABLED = "enabled"
        private fun scriptKey(pkg: String) = "script.$pkg"

        // t14：注入提示开关（与宿主 InjectHintStore.KEY 严格一致）——冷注入路径的 hint 源：
        // 宿主 InjectHintStore.setEnabled 双通道写 remote prefs，模块此处只读
        private const val KEY_HINT = "hint_inject"

        // Provider 不可达（宿主未运行/被停用/不可见等）时的启用检查重试：
        // 失败不再视为"未选中"直接放弃，而是延迟重试，等宿主恢复后继续初始化链。
        private const val DEFERRED_CHECK_ATTEMPTS = 10
        private const val DEFERRED_CHECK_DELAY_MS = 3000L

        // 系统关键进程：即便用户误选也不注入，避免系统不稳定
        private val SYSTEM_CRITICAL = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "android",
            MODULE_PACKAGE,
        )
    }

    /** 启用检查三态：Provider 明确回答 开/关；无法判定时为 UNKNOWN（延迟重试）。 */
    private enum class TargetCheck { ENABLED, DISABLED, UNKNOWN }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "event=module_loaded process=${param.processName}")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val targetPackage = param.packageName

        // 过滤：排除自身模块进程与系统关键进程
        if (targetPackage in SYSTEM_CRITICAL) return

        // 时机坑修复：此刻 Application 尚未创建，不能直接初始化；
        // 照 LSPilot 同款 hook Instrumentation.callApplicationOnCreate（公共 API，各 ROM 稳定；
        // 注意不是 ActivityThread 的内部同名方法——小米 ROM 可能魔改/移除），
        // 等目标进程 Application 就绪后再执行完整初始化链。
        try {
            val instrumentation = Class.forName("android.app.Instrumentation")
            val applicationClass = Class.forName("android.app.Application")
            val callAppCreate = instrumentation.getDeclaredMethod("callApplicationOnCreate", applicationClass)
            hook(callAppCreate).intercept { chain ->
                val app = chain.getArg(0) as? android.app.Application
                log(Log.INFO, TAG, "event=application_created pkg=$targetPackage")
                if (app != null) {
                    // F2：初始化链后台化——跨进程 Provider 调用 + Native 引擎加载 + Binder 握手会阻塞主线程，
                    // 直接拖慢目标 App 的 Application.onCreate；移到守护后台线程并行执行。
                    // 流程/策略不变（三态判定、延迟重试、握手/拉取脚本顺序均保持原样）。
                    Thread {
                        onApplicationCreated(targetPackage, app)
                    }.apply {
                        isDaemon = true
                        name = "lspfrifa-init"
                    }.start()
                }
                chain.proceed()
            }
            log(Log.INFO, TAG, "event=app_hook_armed pkg=$targetPackage")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "event=arm_failed pkg=$targetPackage err=${t.message}", t)
        }
    }

    /** Application 就绪后执行完整初始化链。 */
    private fun onApplicationCreated(targetPackage: String, app: android.app.Application) {
        when (checkTargetEnabled(app, targetPackage)) {
            TargetCheck.DISABLED -> {
                log(Log.INFO, TAG, "event=skip_not_selected pkg=$targetPackage")
            }
            TargetCheck.ENABLED -> {
                runInitChain(targetPackage, app)
            }
            TargetCheck.UNKNOWN -> {
                // Provider 此时不可达（宿主未运行/force-stop/可见性等），延迟重试而不是直接放弃
                scheduleDeferredInit(targetPackage, app)
            }
        }
    }

    /** 初始化链：加载引擎 → 构造 IPC 实体 → 握手宿主 → 拉取持久化脚本。 */
    private fun runInitChain(targetPackage: String, app: android.app.Application) {
        try {
            // 1. 加载 Native 引擎
            System.loadLibrary("gumjs_bridge")
            GumJsBridge.init()

            // 2. 构造 IPC 执行实体（内部已绑定 messageCallback）；
            //    传入 app 上下文：宿主进程死亡后 TargetIpcServer 需要它重新注册
            val ipcServer = TargetIpcServer(targetPackage, app)

            // 2.5 官方通道路由（P0）：JS 脚本 LSP.hook(...) → 本路由 → libxposed hook()（LSPlant）。
            //    目标类必须已被进程加载（framework 类总是可用；应用类需等其加载后再发请求）。
            val hookRouter = HookRouter(
                targetPackage = targetPackage,
                targetLoader = app.classLoader,
                appContext = app,
                hooker = { m -> hook(m) },
                hostLog = { msg -> ipcServer.hostLog(msg) },
            )
            ipcServer.setHookRouter(hookRouter)

            // 3. 将 Binder 通过 Provider 传递给宿主进程完成握手
            registerBinderToHost(app, targetPackage, ipcServer)

            // 4. 尝试拉取持久化初始脚本并加载
            loadInitialScript(app, targetPackage)

        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "event=init_failed pkg=$targetPackage err=${t.message}", t)
        }
    }

    /** 单次启用检查（双通道）；Provider 无响应/异常都归类为 UNKNOWN（不可当作"未选中"处理）。 */
    private fun checkTargetEnabled(context: android.content.Context, packageName: String): TargetCheck {
        // R1.1：框架远程 prefs 优先（模块侧只读，不依赖宿主进程存活；宿主写、模块读同组同键）
        try {
            val prefs = getRemotePreferences(REMOTE_GROUP)
            val enabledSet = prefs.getStringSet(KEY_ENABLED, null)
            if (enabledSet != null) {
                log(Log.INFO, TAG, "event=target_check_remote pkg=$packageName enabled=${enabledSet.contains(packageName)}")
                return if (enabledSet.contains(packageName)) TargetCheck.ENABLED else TargetCheck.DISABLED
            }
        } catch (t: Throwable) {
            // 框架未下发远程配置/embedded 等：静默回退 Provider 通道
        }
        return try {
            val uri = android.net.Uri.parse("$PROVIDER_URI/scripts")
            val bundle = context.contentResolver.call(
                uri, "is_target_enabled", packageName, null
            )
            when {
                bundle == null -> {
                    // 宿主 Provider 未响应（跨进程 call 失败/宿主进程未运行/权限等）
                    log(Log.WARN, TAG, "event=provider_no_bundle pkg=$packageName")
                    TargetCheck.UNKNOWN
                }
                bundle.getBoolean("enabled") == true -> TargetCheck.ENABLED
                else -> TargetCheck.DISABLED
            }
        } catch (e: Exception) {
            log(Log.WARN, TAG, "event=target_check_unknown pkg=$packageName err=${e.message}")
            TargetCheck.UNKNOWN
        }
    }

    /**
     * Provider 不可达时的延迟重试（后台线程循环，成功后直接在该后台线程继续初始化链）。
     * 覆盖典型场景：宿主进程冷启动/后台被拉起需要时间；用户随后打开宿主 App 后自动恢复。
     * F2 起初始化链不再回主线程（原 mainHandler.post 会把跨进程 Binder 调用带回主线程）。
     */
    private fun scheduleDeferredInit(targetPackage: String, app: android.app.Application) {
        Thread {
            runDeferredCheckLoop(targetPackage, app)
        }.apply {
            isDaemon = true
            name = "lspfrifa-deferred-check"
        }.start()
    }

    private fun runDeferredCheckLoop(targetPackage: String, app: android.app.Application) {
        var attempt = 0
        while (attempt < DEFERRED_CHECK_ATTEMPTS) {
            attempt++
            try {
                Thread.sleep(DEFERRED_CHECK_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
            when (checkTargetEnabled(app, targetPackage)) {
                TargetCheck.ENABLED -> {
                    log(Log.INFO, TAG, "event=target_check_recovered pkg=$targetPackage attempt=$attempt")
                    // F2：原实现在此 mainHandler.post 回主线程，而初始化链含多次跨进程 Binder 调用
                    // （register_ipc / get_script），会在主线程残留阻塞；现直接在检查线程上继续执行。
                    // 安全性：GumJsBridge 全部 @Synchronized；HookRouter 内部自带 mainHandler 管理
                    // UI 相关投递；TargetIpcServer 无主线程依赖。
                    runInitChain(targetPackage, app)
                    return
                }
                TargetCheck.DISABLED -> {
                    log(Log.INFO, TAG, "event=skip_not_selected pkg=$targetPackage (deferred)")
                    return
                }
                TargetCheck.UNKNOWN -> {
                    log(Log.WARN, TAG, "event=target_check_retry pkg=$targetPackage attempt=$attempt")
                }
            }
        }
        // 重试耗尽可能：区分"宿主不可达/被停用"与"包可见性/未安装"两类根因
        val providerResolved = runCatching {
            app.packageManager.resolveContentProvider("$MODULE_PACKAGE.config_provider", 0)
        }.getOrNull()
        log(
            Log.ERROR, TAG,
            "event=target_check_giveup pkg=$targetPackage " +
                "provider_resolved=${providerResolved != null} " +
                "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}"
        )
    }

    private fun loadInitialScript(context: android.content.Context, packageName: String) {
        // R1.1：框架远程 prefs 优先（模块侧只读，不依赖宿主进程存活）
        try {
            val prefs = getRemotePreferences(REMOTE_GROUP)
            val code = prefs.getString(scriptKey(packageName), null)
            if (!code.isNullOrBlank()) {
                log(Log.INFO, TAG, "event=load_persisted_script pkg=$packageName size=${code.length} src=remote_prefs")
                if (GumJsBridge.loadScript(code)) {
                    // t14：冷注入成功提示（hint 经 remote prefs 下发；此前冷路径直接调
                    // GumJsBridge.loadScript 绕过 TargetIpcServer.loadScript → 永不 Toast）
                    notifyInjectionHint(context, packageName)
                }
                return
            }
        } catch (t: Throwable) {
            // 框架未下发远程配置/embedded 等：静默回退 Provider 通道
        }
        try {
            val uri = android.net.Uri.parse("$PROVIDER_URI/scripts")
            val bundle = context.contentResolver.call(
                uri, "get_script", packageName, null
            )
            val scriptCode = bundle?.getString("script_content")
            if (!scriptCode.isNullOrBlank()) {
                log(Log.INFO, TAG, "event=load_persisted_script pkg=$packageName size=${scriptCode.length} src=provider")
                if (GumJsBridge.loadScript(scriptCode)) {
                    notifyInjectionHint(context, packageName)
                }
            } else {
                log(Log.INFO, TAG, "event=no_persisted_script pkg=$packageName waiting_for_ipc")
            }
        } catch (e: Exception) {
            log(Log.WARN, TAG, "event=load_script_deferred pkg=$packageName err=${e.message}")
        }
    }

    /**
     * t14：冷注入成功 Toast（注入提示开关）。hint 来源=remote prefs（宿主 InjectHintStore.setEnabled
     * 双通道写入；模块只读）；remote 不可读时默认开（与宿主 InjectHintStore.isEnabled() 默认 true 一致）。
     * Toast 必须主线程——本方法可能运行在 lspfrifa-init/后台线程，post 兜底。
     */
    private fun notifyInjectionHint(context: android.content.Context, packageName: String) {
        val enabled = try {
            getRemotePreferences(REMOTE_GROUP).getBoolean(KEY_HINT, true)
        } catch (t: Throwable) {
            true
        }
        if (!enabled) return
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, "LSPFRIFA 已注入: $packageName", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    private fun registerBinderToHost(
        context: android.content.Context,
        packageName: String,
        server: TargetIpcServer,
    ) {
        try {
            val bundle = android.os.Bundle().apply {
                putBinder("ipc_binder", server.asBinder())
            }
            context.contentResolver.call(
                android.net.Uri.parse("$PROVIDER_URI/scripts"),
                "register_ipc", packageName, bundle
            )
            log(Log.INFO, TAG, "event=binder_handshake_ok pkg=$packageName")
        } catch (e: Exception) {
            log(Log.WARN, TAG, "event=binder_handshake_deferred pkg=$packageName err=${e.message}")
        }
    }
}
