package com.bail.lspfrifa.xposed

/**
 * Frida GumJS 引擎的 Kotlin 封装。
 * 在目标 App 进程内由 XposedModule 调用；宿主 App 不加载本类。
 *
 * 使用方式：
 *   GumJsBridge.init()
 *   GumJsBridge.setSyncHandler { json -> router.handleBridgeSync(json) }   // 必须在 loadScript 之前
 *   GumJsBridge.registerMessageCallback(...)
 *   val ok = GumJsBridge.loadScript("console.log('hello')", "demo")
 *
 * ## 第一阶段（frida-java-bridge 7.0.13 接通）后的形态
 *
 * 注入顺序：`LSP_SHIM_BASE`（官方 send 通道，LSP.hook/toast/unhookAll）→ `LSP_SHIM_COMPAT`
 * （queueMicrotask 补齐，非 Java 语义）→ `JavaBridgeBundle`（上游 7.0.13 真 bundle，含 adapter）
 * → 用户脚本。**不再有 LSP_SHIM_JAVA**：`globalThis.Java` 就是上游 Runtime 实例，
 * `perform` / `use` / `overload` / `implementation` / `choose` / `cast` / `factory.loader` 等
 * 全部走上游实现；旧 shim 的 340 行兼容子集（19 stub / 4 trap / androidVersion token）已删除。
 *
 * 跨语言契约见 `tools/javabridge/BRIDGE-CONTRACT.md`：
 *  - 上行（JS → Kotlin）：adapter 在 `implementation = fn|null` 时经 cpp `lsp_sync_call`
 *    同步调用本类 `lspSync(String): String`（@JvmStatic，cpp 用 GetStaticMethodID 取），
 *    由 [setSyncHandler] 注入的 handler（HookRouter.handleBridgeSync）同步返回路由结果；
 *  - 下行（Kotlin → JS）：adapter 在 bundle 装载期把 NativeCallback 函数指针经
 *    `{"op":"set_dispatcher"}` 报给 [setDispatcher]；hook 命中时 Kotlin 调 [dispatchHook]，
 *    cpp 在**同一条 app 线程**上同步执行 JS 并回传 reply JSON（无 rpc 往返、无超时）。
 *
 * 旧 RouteB 通道（[callJs] / [postOriginalReply]，Kotlin→JS 异步 rpc + 500ms 泵等待）
 * 保留为 dispatcher 缺失/失败时的兜底，不得删除。
 */
object GumJsBridge {

    interface OnScriptMessage {
        fun onScriptMessage(message: String)
    }

    @Volatile
    private var _messageCallback: OnScriptMessage? = null

    /**
     * 注册脚本消息回调（C 层 on_message -> Kotlin）。
     * 注意：不能命名为 setMessageCallback，会与 var 的自动 setter 冲突。
     */
    @Synchronized
    fun registerMessageCallback(cb: OnScriptMessage?) {
        _messageCallback = cb
        try {
            nativeSetCallback(cb)
        } catch (_: Throwable) {
            // native 层尚未就绪时忽略；init 后会自动建立
        }
    }

    /** 是否已成功初始化（进程级幂等） */
    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * 同步安装通道 handler（契约 §2）：cpp `lsp_sync_call` → [lspSync] → 这里。
     * 由 LSPFRIFAModule 在 **loadScript 之前**注入（`HookRouter.handleBridgeSync`）。
     */
    @Volatile
    private var syncHandler: ((String) -> String)? = null

    /**
     * JS 侧 dispatcher 是否已注册。cpp 的 nativeLoadScript 会在装载前把 g_js_dispatcher
     * 置空，本字段与之同步：loadScript/unloadScript 先置 false，adapter 在 bundle 装载期
     * 经 `set_dispatcher` 再置 true。
     */
    @Volatile
    private var dispatcherReady: Boolean = false

    private var loaded: Boolean = false

    init {
        System.loadLibrary("gumjs_bridge")
    }

    @Synchronized
    fun init(): Boolean {
        if (initialized) return true
        initialized = try {
            nativeInitEngine()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        return initialized
    }

    // ==================== 上行：JS → Kotlin 同步通道（契约 §2） ====================

    /**
     * 注入同步 handler（HookRouter.handleBridgeSync）。传 null = 解除。
     *
     * 时序要求：必须在第一次 [loadScript] 之前调用——上游 bundle 的 adapter 在**装载期**
     * 就调用 `set_dispatcher`，晚一步注册会让首轮 `implementation` 赋值拿不到同步反馈。
     * 模块侧重复注册是安全的（后注册覆盖前者；early/app 两阶段捕获同一 router 实例）。
     */
    fun setSyncHandler(handler: ((String) -> String)?) {
        syncHandler = handler
    }

    /**
     * cpp `lsp_sync_call` 的目标方法（契约 §1.2：`static java.lang.String lspSync(java.lang.String)`）。
     *
     * 纪律：**永不抛**。调用方是 GumJS 的 JS 线程，异常逃到 JNI 边界会让整个目标进程不可用；
     * 最外层 catch Throwable，连 Error 族一起转成合法失败 JSON（JS 侧据 ok:false 同步 throw）。
     * 这里只做转发与兜底，协议处理全在 HookRouter（单一职责，便于测试）。
     */
    @JvmStatic
    fun lspSync(payload: String): String {
        val handler = syncHandler
            ?: return "{\"ok\":false,\"err\":\"sync handler not registered\",\"kind\":\"badreq\"}"
        return try {
            handler(payload)
        } catch (t: Throwable) {
            val msg = (t.message ?: t.javaClass.simpleName)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            "{\"ok\":false,\"err\":\"lspSync crashed: " + msg + "\",\"kind\":\"badreq\"}"
        }
    }

    // ==================== 下行：Kotlin → JS 同步 dispatcher（契约 §3） ====================

    /**
     * 注册/清除 JS dispatcher 函数指针（ptr == 0 → 清除）。
     * 由 HookRouter.setDispatcherSync 在收到 adapter 的 `{"op":"set_dispatcher"}` 时调用；
     * 卸载脚本时由本类主动传 0 作废（JS 对象随脚本卸载失效，绝不能再回调）。
     */
    fun setDispatcher(ptr: Long) {
        dispatcherReady = ptr != 0L
        try {
            nativeSetDispatcher(ptr)
        } catch (_: Throwable) {
            dispatcherReady = false
        }
    }

    /** dispatcher 是否可用（HookRouter 据此选择同步主路径 / 旧 async 兜底路径）。 */
    fun hasDispatcher(): Boolean = dispatcherReady

    /**
     * 同线程同步执行 JS hook 回调：payload 为契约 §3.1 JSON，返回 reply JSON。
     *
     * 返回 null 的**唯一**含义 = dispatcher 未注册 / cpp 没进 JS（adapter 内部任何异常都会
     * 兜成合法 JSON，不会返回 null）。调用方据此退回旧 async 路径，不会二次执行用户 fn。
     * 纪律：不持 Kotlin 锁（JS 回调内可能经 JNI 回到 HookRouter/LSPlant）。
     */
    fun dispatchHook(payload: String): String? {
        if (!dispatcherReady) return null
        return try {
            nativeDispatchHook(payload)
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== 引用搬运（契约 §3.2 <E> 编码） ====================

    /** 局部引用 → 全局引用句柄；null/失败 → 0。 */
    fun newGlobalRef(obj: Any?): Long {
        if (obj == null) return 0L
        return try {
            nativeNewGlobalRef(obj)
        } catch (_: Throwable) {
            0L
        }
    }

    /** 全局引用句柄 → 局部引用；0 → null。 */
    fun newLocalRef(handle: Long): Any? {
        if (handle == 0L) return null
        return try {
            nativeNewLocalRef(handle)
        } catch (_: Throwable) {
            null
        }
    }

    /** 删除全局引用句柄；0 → 静默。 */
    fun deleteGlobalRef(handle: Long) {
        if (handle == 0L) return
        try {
            nativeDeleteGlobalRef(handle)
        } catch (_: Throwable) {
            // 引用已失效时 JNI 会抛；不影响业务路径
        }
    }

    /** jobject 裸指针（jlong）。仅用于**同线程、回调生命周期内**传给 JS 临时包 wrapper。 */
    fun ptrOf(obj: Any?): Long {
        if (obj == null) return 0L
        return try {
            nativePtrOf(obj)
        } catch (_: Throwable) {
            0L
        }
    }

    // ==================== 脚本装载 ====================

    @Synchronized
    fun loadScript(scriptContent: String, scriptName: String = "main"): Boolean {
        if (!init()) return false
        val bundle = JavaBridgeBundle.SCRIPT
        if (bundle.isBlank()) {
            // bundle 是生成的（tools/javabridge/build.mjs）。空 = 生成流程坏了，
            // 此时放行只会得到"Java.* 全不存在"的静默降级，不如明确失败。
            android.util.Log.e(
                "LSPFRIFA-GumJS",
                "JAVA_BRIDGE_BUNDLE_EMPTY: JavaBridgeBundle.SCRIPT 为空，拒绝加载（Java.* 不可用）",
            )
            return false
        }
        // cpp nativeLoadScript 会在装载前把 JS dispatcher 置空；Kotlin 视图同步作废，
        // 待 bundle 内 adapter 的 set_dispatcher 上报后重新置 true。
        dispatcherReady = false
        loaded = try {
            val combined = buildString {
                append(LSP_SHIM_BASE)
                append("\n;\n")
                append(LSP_SHIM_COMPAT)
                append("\n;\n")
                append(bundle)
                append("\n;\n")
                append(scriptContent)
            }
            nativeLoadScript(combined, scriptName)
        } catch (t: Throwable) {
            android.util.Log.e("LSPFRIFA-GumJS", "loadScript failed: " + t.message)
            false
        }
        return loaded
    }

    @Synchronized
    fun unloadScript() {
        if (loaded) {
            // JS 侧对象（含 dispatcher NativeCallback）随脚本卸载失效：先作废 dispatcher，
            // 否则卸载后若仍有 LSPlant hook 命中，cpp 会回调已释放的函数指针。
            setDispatcher(0L)
            try {
                nativeUnloadScript()
            } catch (_: Throwable) {
            }
            loaded = false
        }
        dispatcherReady = false
    }

    // ==================== 旧 RouteB 通道（async 兜底，保留） ====================

    /**
     * RouteB B1 替换通道（async 兜底路径，仅在 dispatcher 未注册/失败时使用）：
     * intercept 线程 → 登记请求（id）→ gum_script_post(frida:rpc call) → 条件变量等待（≤500ms）。
     * 返回 `{"over":bool,"r":…}` / `{"__inner":true}`；超时/异常 → ""（调用方 proceed 兜底）。
     * 同线程重入时 cpp 直接回 `{"__reentrant":true}`（Kotlin 走 proceed，不吃 500ms 超时）。
     */
    fun callJs(id: String, payload: String): String = try {
        nativeCallJs(id, payload)
    } catch (_: Throwable) {
        ""
    }

    /** RouteB：原方法结果回投 JS（async fn 内 this.method 的 resolve；fire-and-forget）。 */
    fun postOriginalReply(id: String, retJson: String) {
        try {
            nativePostOriginalReply(id, retJson)
        } catch (_: Throwable) {
            // native 未就绪时忽略（回退超时路径）
        }
    }

    /** C 层旧回调入口（保留；当前 on_message 直接调 OnScriptMessage.onScriptMessage） */
    @JvmStatic
    fun dispatchMessage(message: String) {
        _messageCallback?.onScriptMessage(message)
    }

    // ==================== JS 侧 shim（仅官方通道 + 兼容补齐） ====================

    /**
     * JS 侧官方通道 shim【基础段】（最前注入）：
     * LSP.hook(cls, method, tag, act) / LSP.toast(msg) / LSP.unhookAll() → GumJS send → Kotlin HookRouter。
     *
     * 这是 LSPFRIDA 相对上游的形态优势（LSPlant 路由），**不属于** Java API 兼容层，故保留。
     */
    private val LSP_SHIM_BASE: String = """
        globalThis.LSP = globalThis.LSP || Object.create(null);
        (function () {
          function hook(cls, method, tag, act) {
            try {
              send({ t: "lsp.hook", cls: cls, method: method, tag: String(tag || ""), act: String(act || "") });
            } catch (e) {
              console.log("[LSP] send failed: " + e);
            }
            return true;
          }
          function toast(msg) {
            try {
              send({ t: "lsp.toast", msg: String(msg || "") });
            } catch (e) {
              console.log("[LSP] toast send failed: " + e);
            }
            return true;
          }
          function unhookAll() {
            try {
              send({ t: "lsp.unhook_all" });
            } catch (e) {
              console.log("[LSP] unhookAll send failed: " + e);
            }
            return true;
          }
          LSP.hook = hook;
          LSP.toast = toast;
          LSP.unhookAll = unhookAll;
        })();
    """.trimIndent()

    /**
     * 非 Java 语义的运行时兼容补齐（与 Java 桥无关，独立保留）。
     *
     * queueMicrotask 是真实缺口（2026-10-05 实核）：QuickJS 内核与 frida runtime 都**未定义**它
     * （frida runtime 源码 grep 零命中），而它已进入现代脚本常用 API。若用户脚本顶层调用 →
     * ReferenceError → **整个脚本终止**（含后续 timer 注册），症状极易误诊为"timer 不触发"。
     * Promise.resolve().then() 就是标准微任务排队语义（QuickJS 原生 Promise）。
     */
    private val LSP_SHIM_COMPAT: String = """
        (function () {
          if (typeof globalThis.queueMicrotask !== "function") {
            globalThis.queueMicrotask = function (fn) {
              if (typeof fn !== "function") { throw new TypeError("queueMicrotask 需要函数参数"); }
              return Promise.resolve().then(fn);
            };
          }
        })();
    """.trimIndent()

    // ---- JNI 绑定（原名/签名须与 app/src/main/cpp/gumjs_bridge.cpp 严格一致）----

    @JvmStatic
    private external fun nativeInitEngine(): Boolean

    @JvmStatic
    private external fun nativeSetCallback(cb: OnScriptMessage?)

    @JvmStatic
    private external fun nativeLoadScript(script: String, name: String): Boolean

    @JvmStatic
    private external fun nativeUnloadScript()

    @JvmStatic
    private external fun nativeCallJs(id: String, payload: String): String

    @JvmStatic
    private external fun nativePostOriginalReply(id: String, retJson: String)

    /** JS dispatcher 函数指针（jlong，指针位型原值；0 = 清除）。 */
    @JvmStatic
    private external fun nativeSetDispatcher(fn: Long)

    /** 同线程同步执行 JS；未注册/失败 → null。 */
    @JvmStatic
    private external fun nativeDispatchHook(payload: String): String?

    /** jobject → jlong 裸指针（null → 0）。 */
    @JvmStatic
    private external fun nativePtrOf(obj: Any?): Long

    /** jobject 局部引用 → 全局引用句柄（null/失败 → 0）。 */
    @JvmStatic
    private external fun nativeNewGlobalRef(obj: Any?): Long

    /** 全局引用句柄 → 局部引用（0 → null）。 */
    @JvmStatic
    private external fun nativeNewLocalRef(handle: Long): Any?

    /** 删除全局引用句柄（0 → 静默）。 */
    @JvmStatic
    private external fun nativeDeleteGlobalRef(handle: Long)
}
