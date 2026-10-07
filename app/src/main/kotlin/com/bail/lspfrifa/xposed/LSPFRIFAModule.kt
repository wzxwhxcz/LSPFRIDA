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

        /** 旧键（升级前数据，仅回退读）。 */
        private fun scriptKey(pkg: String) = "script." + pkg

        /** D15：指针键 -> 该包专属 group 名。 */
        private fun scriptGroupPointerKey(pkg: String) = "scriptgroup." + pkg

        /** D15：专属 group 名（与宿主 ScriptStore.scriptGroupOf 严格一致）。 */
        private fun scriptGroupOf(pkg: String) =
            "lspfrifa_s_" + pkg.replace('.', '_').replace('-', '_')

        /** D15：专属 group 内的键。 */
        private const val INNER_KEY_CODE = "code"

        /** D12：熔断闸键（与宿主 ScriptStore.circuitKey 严格一致）。闸断 = 不尝试注入。 */
        private fun circuitKey(pkg: String) = "circuitOpen." + pkg

        /**
         * D3③：类加载监听开关（与宿主 HookModeStore.KEY_LOADCLASS_WATCH 严格一致）。
         * 默认关（激进模式）——loadClass 是目标 App 的热路径，由用户显式开启。
         */
        private const val KEY_LOADCLASS_WATCH = "loadclass_watch"

        /**
         * D2：早期注入总开关。
         *
         * 关掉即退回“仅 Application 后注入”的旧行为（零风险回滚路径）。
         * 保留它的直接原因：早期挂载与目标自身启动并行，存在时序竞争
         * （“脚本一定早于 Application.onCreate”并非保证，见设计文档 D2），
         * 出问题时用户需要一个不重新发包就能退回的开关。
         */
        private const val EARLY_INJECT_ENABLED = true

        /**
         * 防重入：onPackageReady 已处理过该包（不代表成功 —— 成功与否看下面的集合）。
         */
        private val earlyAttempted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * 关键语义（曾因混用而引入 bug）：**只有在提前阶段“真的把脚本载进引擎”时才置位**。
         *
         * 它唯一的作用是让 Application 阶段跳过重复 loadScript（重载会 unload+recreate，
         * 清掉已挂 Interceptor 制造失效窗口）。
         *
         * 反例（若不区分“尝试过”与“成功过”）：提前阶段因引擎加载失败/读不到脚本/无 remote 配置
         * 等原因未载脚本时，Application 阶段会误以为已载而跳过 loadInitialScript ——
         * 那么 Provider 回退与三态延迟重试全被绕过，表现为“永不注入”。
         */
        private val earlyScriptLoaded = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    /**
     * D2：提前阶段建好的路由（供 Application 阶段接管，避免双实例导致 hook 手柄丢失）。
     * onPackageReady 里已用 hook() 挂上手柄（存在 router 内部）；Application 阶段若再造一个，
     * 旧手柄就无人释放、也无法按 tag 卸载 —— 泄漏且关不掉。
     */
    @Volatile
    private var earlyRouter: HookRouter? = null

    /**
     * D14：统一事件日志（本地 logcat + 早期 ring buffer）。
     * 握手前 logReceiver 尚未建立，此期间的 record 会在宿主上线后补发
     * （对接点：TargetIpcServer 握手成功时调用 EarlyLogBuffer.flush）。
     */
    private fun record(priority: Int, message: String) {
        log(priority, TAG, message)
        EarlyLogBuffer.add(message)
    }

    /** 启用检查三态：Provider 明确回答 开/关；无法判定时为 UNKNOWN（延迟重试）。 */
    private enum class TargetCheck { ENABLED, DISABLED, UNKNOWN }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        record(Log.INFO, "event=module_loaded process=${param.processName}")
    }

    /**
     * D1/A：提前注入入口（libxposed 102 的第二阶段回调，官方定位：“app classloader 已就绪、
     * 准备创建 Application”）。
     *
     * 为何能提前：注入链四段里，①引擎准备（只需进程存在）②hook 就绪（需 app classloader）
     * ④启用判定（只需 remote prefs）都不依赖 Context —— 只有③宿主协同（register_ipc/日志上 UI）
     * 需要。官方示例 ModuleMainKt.kt 已证实本回调内可直接用 getRemotePreferences / hook(...)。
     *
     * 关键约束（否则全部 hook 静默 MISS）：必须用 param.classLoader —— 它是 AppComponentFactory
     * 实际使用的那个 CL，可能与 getDefaultClassLoader() 不同。
     *
     * 性能纪律（F2 的教训）：重活全部丢守护线程，绝不阻塞框架回调所在的目标主线程。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!EARLY_INJECT_ENABLED) return
        val targetPackage = param.packageName
        if (targetPackage in SYSTEM_CRITICAL) return

        val appClassLoader = param.classLoader
        Thread {
            runEarlyInject(targetPackage, appClassLoader)
        }.apply {
            isDaemon = true
            name = "lspfrifa-early"
        }.start()
    }

    /**
     * 提前注入：只做不需 Context 的部分（引擎 + 路由 + 脚本）。
     *
     * 不在这里做的：register_ipc 握手、日志上行、Toast —— 它们都要 Context，
     * 留在 Application 阶段的 [runInitChain] 完成（后者仅在 earlyScriptLoaded 置位时才跳过重载）。
     */
    private fun runEarlyInject(targetPackage: String, appClassLoader: ClassLoader) {
        try {
            if (!earlyAttempted.add(targetPackage)) {
                record(Log.INFO, "event=early_skip_already pkg=" + targetPackage)
                return
            }

            // 熔断闸（与 Application 阶段同源）
            try {
                val open = getRemotePreferences(REMOTE_GROUP).getBoolean(circuitKey(targetPackage), false)
                if (open) {
                    record(Log.WARN, "event=early_circuit_open_skip pkg=" + targetPackage)
                    return
                }
            } catch (_: Throwable) {
            }

            // 启用判定：只能用 remote prefs（此刻无 Context，查不了 Provider）
            val enabled = try {
                val set = getRemotePreferences(REMOTE_GROUP).getStringSet(KEY_ENABLED, null)
                set?.contains(targetPackage)
            } catch (_: Throwable) {
                null
            }
            if (enabled != true) {
                // null = 读不到（框架未下发）→ 不在此阶段判定，留给 Application 阶段走三态逻辑
                record(Log.INFO, "event=early_check_skip pkg=" + targetPackage + " enabled=" + enabled)
                return
            }

            // ① 引擎（无任何依赖）
            System.loadLibrary("gumjs_bridge")
            GumJsBridge.init()

            // ② hook 路由（用 param.classLoader，非 getDefaultClassLoader）
            val router = HookRouter(
                targetPackage = targetPackage,
                targetLoader = appClassLoader,
                appContext = null,
                hooker = { m -> hook(m) },
                hostLog = { msg -> record(Log.INFO, msg) },
            )
            earlyRouter = router

            // ★ 关键：必须先把消息回调挂上，再去加载脚本。
            //
            // 为何（这是一个真实缺陷的修复，不是防御性代码）：
            //   GumJsBridge._messageCallback 默认为 null，全项目唯一赋值点是 TargetIpcServer.init。
            //   而本函数（提前阶段）**不创建 TargetIpcServer**（它需要 Context，留到 App 阶段）。
            //   若此处不注册，早期加载的脚本执行 LSP.hook() → send() → cpp on_message
            //   → GumJsBridge.dispatchMessage → _messageCallback==null → **消息被静默丢弃**；
            //   随后 App 阶段又因 earlyScriptLoaded 已置位而跳过 loadInitialScript
            //   → hook 永远不会被注册，表现为“脚本加载成功但什么也没发生”。
            //
            // 回调内容与 TargetIpcServer.init 保持一致（先给 HookRouter，再上行宿主）；
            // 宿主通道此刻不存在，故未消费的消息写入 EarlyLogBuffer，握手后由
            // registerLogReceiver 一次性补发（这正是 D14 缓冲存在的意义）。
            GumJsBridge.registerMessageCallback(object : GumJsBridge.OnScriptMessage {
                override fun onScriptMessage(message: String) {
                    // 本回调运行在 cpp 的 gum-js-loop 线程：任意未捕获异常会杀掉**整个目标进程**，
                    // 故最外层必须全局兜底（record/EarlyLogBuffer 本身也可能在极端情况下抛）。
                    runCatching {
                        Log.i("LSPFRIFA-Frida", "[" + targetPackage + "] " + message)
                        try {
                            if (earlyRouter?.tryHandle(message) == true) return@runCatching
                        } catch (t: Throwable) {
                            record(Log.WARN, "event=early_dispatch_err err=" + t.message)
                        }
                        // 未消费（脚本 console.log / 自定义 send / 暂无 context 的 toast）：
                        // 此时无 logReceiver，不补缓冲就会永久丢失（flush 后 add 为空操作，无重复风险）。
                        EarlyLogBuffer.add(message)
                    }
                }
            })
            record(Log.INFO, "event=early_router_ready pkg=" + targetPackage)

            // D3①：路由就绪后立即 flush 一次（脚本重跑场景下，待挂项可能已可解析）
            runCatching { router.flushAtAnchor() }

            // ④ 脚本：只读 remote prefs（Provider 需要 Context，留给后续）。
            //    只有确实载入成功才置位 earlyScriptLoaded —— 否则 App 阶段必须继续尝试
            //    （它还有 Provider 回退与三态重试，这些是早期阶段拿不到的）。
            if (loadScriptEarly(targetPackage)) {
                earlyScriptLoaded.add(targetPackage)
            }
        } catch (t: Throwable) {
            record(Log.WARN, "event=early_inject_failed pkg=" + targetPackage + " err=" + t.message)
        }
    }

    /**
     * 提前阶段的脚本读取（仅 remote prefs 两通道，不碰 Provider）。
     * @return true = 脚本确实已载入引擎（此时 App 阶段才允许跳过重载）
     */
    private fun loadScriptEarly(packageName: String): Boolean {
        try {
            val prefs = getRemotePreferences(REMOTE_GROUP)
            val pointer = prefs.getString(scriptGroupPointerKey(packageName), null)
            val code = if (!pointer.isNullOrBlank()) {
                getRemotePreferences(pointer).getString(INNER_KEY_CODE, null)
            } else {
                prefs.getString(scriptKey(packageName), null)
            }
            if (code.isNullOrBlank()) {
                record(Log.INFO, "event=early_no_script pkg=" + packageName + " (App 阶段可走 Provider 回退)")
                return false
            }
            if (GumJsBridge.loadScript(code)) {
                record(
                    Log.INFO,
                    "event=early_script_loaded pkg=" + packageName + " size=" + code.length,
                )
                return true
            }
            record(Log.WARN, "event=early_script_load_failed pkg=" + packageName)
            return false
        } catch (t: Throwable) {
            record(Log.WARN, "event=early_script_read_failed pkg=" + packageName + " err=" + t.message)
            return false
        }
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
                record(Log.INFO, "event=application_created pkg=$targetPackage")
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

                // D3①：锚点 flush —— 在 Application.onCreate 执行**之前**（proceed 前），
                // 尝试把待挂队列里“此刻已可解析”的 hook 挂上。
                //
                // 为何值得跑在主线程：Application 之前的资源极其稀缺，这是少数几个
                // “框架保证会执行且我们已拦到”的时机；且 flushPending 有上限（FLUSH_MAX_ITEMS）
                // 且队列空时立即返回（成本≈0），因此可估界。
                runCatching { earlyRouter?.flushAtAnchor() }

                chain.proceed()
            }
            record(Log.INFO, "event=app_hook_armed pkg=$targetPackage")
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
        // D12 熔断闸门：坏脚本已被宿主断闸 → 本次启动直接跳过注入（避免每次启动都受影响）。
        // 闸写在 remote prefs（目标进程可读、宿主可写），用户可在宿主 UI 一键合闸重试。
        try {
            val open = getRemotePreferences(REMOTE_GROUP).getBoolean(circuitKey(targetPackage), false)
            if (open) {
                record(Log.WARN, "event=circuit_open_skip pkg=" + targetPackage)
                return
            }
        } catch (_: Throwable) {
            // 读不到闸（框架未下发 remote 配置）→ 按未断闸处理，不因读失败而阻断注入
        }

        try {
            // 1. 加载 Native 引擎
            System.loadLibrary("gumjs_bridge")
            GumJsBridge.init()

            // 2. 官方通道路由（P0）：JS 脚本 LSP.hook(...) → 本路由 → libxposed hook()（LSPlant）。
            //    目标类必须已被进程加载（framework 类总是可用；应用类需等其加载后再发请求）。
            //    D2：若提前阶段已建路由，则**接管**它而非新建 —— 提前挂的 hook 手柄在旧实例里，
            //    重建会丢失引用（泄漏）且无法卸载。
            // 前向引用破环：路由的 hostLog 需要 ipcServer.hostLog，而 ipcServer 又需要路由
            // ——用 ipcRef；其赋值前的日志回落 record（logcat + ring buffer），不丢。
            var ipcRef: TargetIpcServer? = null
            val upstream: (String) -> Unit = { msg ->
                val s = ipcRef
                if (s != null) s.hostLog(msg) else record(Log.INFO, msg)
            }
            val existed = earlyRouter
            val hookRouter = if (existed != null) {
                existed.attachRuntime(app, upstream)
                record(Log.INFO, "event=early_router_taken_over pkg=" + targetPackage)
                existed
            } else {
                HookRouter(
                    targetPackage = targetPackage,
                    targetLoader = app.classLoader,
                    appContext = app,
                    hooker = { m -> hook(m) },
                    hostLog = upstream,
                )
            }

            // 2.5 构造 IPC 实体，路由经构造器注入 —— 回调注册那一刻 router 即非 null，
            //     不存在“构造完→setHookRouter”的丢消息窗口（该窗口在提前注入下是真实漏洞：
            //     旧版脚本在 setHookRouter 之后才加载所以无害，现在脚本早已在跑）。
            //     传入 app 上下文：宿主进程死亡后 TargetIpcServer 需要它重新注册。
            val ipcServer = TargetIpcServer(targetPackage, app, hookRouter)
            ipcRef = ipcServer

            // D3③：按开关决定是否安装类加载监听（激进模式，默认关）。
            // 失败不影响主链：监听只是“更快挂上”的优化，缺失时退化为 D3② 轮询。
            val watchOn = runCatching {
                getRemotePreferences(REMOTE_GROUP).getBoolean(KEY_LOADCLASS_WATCH, false)
            }.getOrDefault(false)
            if (watchOn) {
                runCatching { hookRouter.installClassLoaderWatcher() }
                record(Log.INFO, "event=loadclass_watch_requested pkg=" + targetPackage)
            }

            // 3. 将 Binder 通过 Provider 传递给宿主进程完成握手
            registerBinderToHost(app, targetPackage, ipcServer)

            // 4. 尝试拉取持久化初始脚本并加载
            //    D2：提前阶段已载过同一脚本时不再重载 —— 重载会 unload+recreate 整个 GumScript，
            //    把早期挂的 Interceptor 全部清掉，反而制造“hook 突然失效”的窗口。
            if (earlyScriptLoaded.contains(targetPackage)) {
                record(Log.INFO, "event=skip_reload_early_loaded pkg=" + targetPackage)
            } else {
                loadInitialScript(app, targetPackage)
            }

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
        // D15 读取链（2026-08-26 三查后冻结）：
        //   1. remote prefs 指针 scriptgroup.<pkg> -> 专属 group 的 code（新格式，永不受他包体积影响）
        //   2. remote prefs 旧键 script.<pkg>（升级前数据，一次性回退）
        //   3. Provider get_script（兜底；仅前两者都不可用时）
        // remote prefs 优先的根因：不依赖宿主进程存活（D4），而 Provider 会受 stopped /
        // 包可见性 / 鉴权等影响（t16/t17 已实证 Unknown authority）。
        val remotePrefs = try {
            getRemotePreferences(REMOTE_GROUP)
        } catch (t: Throwable) {
            null
        }

        // ---- 1. 新格式：指针 -> 专属 group ----
        if (remotePrefs != null) {
            try {
                val pointer = remotePrefs.getString(scriptGroupPointerKey(packageName), null)
                if (!pointer.isNullOrBlank()) {
                    val code = getRemotePreferences(pointer).getString(INNER_KEY_CODE, null)
                    if (!code.isNullOrBlank()) {
                        record(
                            Log.INFO,
                            "event=load_persisted_script pkg=" + packageName +
                                " size=" + code.length + " src=remote_group:" + pointer
                        )
                        if (GumJsBridge.loadScript(code)) {
                            notifyInjectionHint(context, packageName)
                        } else {
                            record(Log.WARN, "event=script_load_failed pkg=" + packageName + " src=remote_group")
                            reportLoadFailure(context, packageName, "remote_group")
                        }
                        return
                    }
                }
            } catch (t: Throwable) {
                record(Log.WARN, "event=script_group_read_failed pkg=" + packageName + " err=" + t.message)
            }
        }

        // ---- 2. 旧键回退（升级前数据） ----
        if (remotePrefs != null) {
            try {
                val legacy = remotePrefs.getString(scriptKey(packageName), null)
                if (!legacy.isNullOrBlank()) {
                    record(
                        Log.INFO,
                        "event=load_persisted_script pkg=" + packageName +
                            " size=" + legacy.length + " src=remote_prefs_legacy"
                    )
                    if (GumJsBridge.loadScript(legacy)) {
                        notifyInjectionHint(context, packageName)
                    } else {
                        record(Log.WARN, "event=script_load_failed pkg=" + packageName + " src=remote_prefs_legacy")
                        reportLoadFailure(context, packageName, "remote_prefs_legacy")
                    }
                    return
                }
            } catch (_: Throwable) {
            }
        }

        // ---- 3. Provider 兜底 ----
        try {
            val uri = android.net.Uri.parse(PROVIDER_URI + "/scripts")
            val bundle = context.contentResolver.call(uri, "get_script", packageName, null)
            val scriptCode = bundle?.getString("script_content")
            if (!scriptCode.isNullOrBlank()) {
                record(
                    Log.INFO,
                    "event=load_persisted_script pkg=" + packageName +
                        " size=" + scriptCode.length + " src=provider"
                )
                if (GumJsBridge.loadScript(scriptCode)) {
                    notifyInjectionHint(context, packageName)
                } else {
                    record(Log.WARN, "event=script_load_failed pkg=" + packageName + " src=provider")
                    reportLoadFailure(context, packageName, "provider")
                }
            } else {
                record(Log.INFO, "event=no_persisted_script pkg=" + packageName + " waiting_for_ipc")
            }
        } catch (e: Exception) {
            record(Log.WARN, "event=load_script_deferred pkg=" + packageName + " err=" + e.message)
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

    /**
     * D12：脚本加载失败上报。
     *
     * 目标进程不写熔断状态（它读到的 remote prefs 是只读的），只经 Binder 把失败告知宿主；
     * 由宿主累计计数并在达阈时断闸（真正生效的闸供下一次启动读取）。
     * 目标不在线/通道未建立时静默丢弃——宿主会在下次握手时从其日志里重新观察。
     */
    private fun reportLoadFailure(context: android.content.Context, packageName: String, src: String) {
        try {
            val extras = android.os.Bundle().apply {
                putString("src", src)
                putString("reason", "gum_script_load_failed")
            }
            context.contentResolver.call(
                android.net.Uri.parse(PROVIDER_URI + "/scripts"),
                "report_load_failure", packageName, extras,
            )
        } catch (e: Exception) {
            // 宿主不可达（未启动/被停用）：不重试、不阻塞；失败信息仍留在本地 logcat 与早期缓冲
            record(Log.WARN, "event=report_failure_skipped pkg=" + packageName + " err=" + e.message)
        }
    }

    private fun registerBinderToHost(
        context: android.content.Context,
        packageName: String,
        server: TargetIpcServer,
    ) {
        try {
            // 首次调用必须抛出异常以进入分类（retry 路径用 registerIpcCall 静默版）
            val bundle = android.os.Bundle().apply {
                putBinder("ipc_binder", server.asBinder())
            }
            context.contentResolver.call(
                android.net.Uri.parse("$PROVIDER_URI/scripts"),
                "register_ipc", packageName, bundle
            )
            log(Log.INFO, TAG, "event=binder_handshake_ok pkg=$packageName")
        } catch (e: Exception) {
            // t17：错误分类 + 可操作提示（单次日志，不刷屏）
            val message = e.message ?: ""
            log(
                Log.WARN, TAG,
                when {
                    message.contains("Unknown authority", ignoreCase = true) ->
                        "event=binder_handshake_deferred pkg=$packageName err=${e.message} " +
                            "[可操作] 模块 App 可能处于停止/被清理状态：请打开 LSPFRIFA 应用一次，等待自动重连；" +
                            "若打开后仍失败：目标 App 可能无 provider 可见性（结构性；见 provider_probe 信息记录）"
                    e is SecurityException ->
                        "event=binder_handshake_denied pkg=$packageName err=${e.message} [鉴权] Binder 通道被拒绝"
                    else -> "event=binder_handshake_deferred pkg=$packageName err=${e.message}"
                }
            )
            // t17-P0 探针（仅失败分支执行，单次）：provider_probe——**信息性记录**，不构成根因判定。
            // 【cap 修正 2026-08-26】getPackageInfo/resolveContentProvider/getApplicationInfo 均为 query
            //   门控（package visibility）API：目标 App 无 <queries> 声明时 visible=N 是"必然出现"的结果，
            //   无论真实根因如何——故本探针三值（visible/stopped/pkgVer）仅如实记录、供人工交叉；
            //   真判定路径 = 用户实验（打开模块 App → 重启目标进程 → binder_handshake_ok? 停表判定 R2；
            //   仍失败且 stopped=false → R1/R3 方向，可见性字段此时才有辅助参考意义——仍属间接证据）。
            //   此外 FLAG_STOPPED 仅在 filtered API 全部通过（visible=Y）时才有意义。
            val probe = runCatching {
                val pm = context.packageManager
                val api = pm.getApplicationInfo(MODULE_PACKAGE, 0)
                val prov = pm.resolveContentProvider("$MODULE_PACKAGE.config_provider", 0)
                val pkg = pm.getPackageInfo(MODULE_PACKAGE, 0)
                "visible=Y prov=${prov?.authority} " +
                    "stopped=${(api.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0} " +
                    "pkgVer=${pkg.versionName}"
            }.getOrElse { e -> "visible=N(${e.javaClass.simpleName})" }
            log(Log.WARN, TAG, "event=provider_probe(info) pkg=$MODULE_PACKAGE $probe")
            // t17：自动恢复握手（有限重试 10×3s）。与 TargetIpcServer 内宿主死亡重连彼此独立：
            // 本路径仅在【初始注册失败】时启动——此时 logReceiver 尚未建立、hostDeathRecipient
            // 不会触发（无 receiver 即无死亡监听），因此不存在重复触发；两者并行安全：
            // 重连成功后目标侧 registerLogReceiver 重新链接死亡监听，此后切换为宿主死亡重连路径。
            // 降噪：重试期间每轮静默（仅初始分类 WARN 一次 + 成功 INFO / giveup WARN 一次）。
            scheduleBinderReconnect(context, packageName, server)
        }
    }

    /** 单次 register_ipc 调用（失败不抛——结果由调用方处理；重试路径静默）。 */
    private fun registerIpcCall(context: android.content.Context, packageName: String, server: TargetIpcServer): Boolean {
        return try {
            val bundle = android.os.Bundle().apply {
                putBinder("ipc_binder", server.asBinder())
            }
            context.contentResolver.call(
                android.net.Uri.parse("$PROVIDER_URI/scripts"),
                "register_ipc", packageName, bundle
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** t17：初始注册失败后的有限重试（10×3s，守护线程）；用户打开模块 App 后自动恢复在线。 */
    private fun scheduleBinderReconnect(
        context: android.content.Context,
        packageName: String,
        server: TargetIpcServer,
    ) {
        Thread {
            var attempt = 0
            try {
                while (attempt < DEFERRED_CHECK_ATTEMPTS) {
                    attempt++
                    Thread.sleep(DEFERRED_CHECK_DELAY_MS)
                    if (registerIpcCall(context, packageName, server)) {
                        log(Log.INFO, TAG, "event=binder_handshake_recovered pkg=$packageName attempt=$attempt")
                        return@Thread
                    }
                }
            } catch (_: InterruptedException) {
                return@Thread
            }
            log(
                Log.WARN, TAG,
                "event=binder_reconnect_giveup pkg=$packageName attempts=$attempt " +
                    "(目标进程存活期内不再重试；下次目标启动或宿主恢复后自动重试)"
            )
        }.apply {
            isDaemon = true
            name = "lspfrifa-binder-reconnect"
        }.start()
    }
}