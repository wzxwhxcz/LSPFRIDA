package com.bail.lspfrifa.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 官方通道路由（P0）——主修复方向 + RouteB B1 replace 语义。
 *
 * 背景：frida-java-bridge 的 Java.use().implementation 在 Android 15 / ART 35 上静默失效
 * （上游 frida-java-bridge#334 open）。libxposed 的 hook() = 框架自带 LSPlant 引擎（官方在
 * A12~A16 全量验证）。因此业务 Java hook 一律转接 libxposed hook()，GumJS 仅做业务层与
 * 进程内 hook 请求转发（send 消息 → on_message → 本路由）。
 *
 * 本文件是 **hook 注册 + hook 回调** 两侧的唯一 Kotlin 落点（契约见 tools/javabridge/
 * BRIDGE-CONTRACT.md §2/§3；跨语言边界的任何改动都必须先改那份契约）。
 *
 * 链路（hook 注册，第一阶段 = 同步通道，契约 §2）：
 *   JS: Java.use("x.y").m.implementation = fn
 *   → adapter → GumJsBridge.lspSync(json) → cpp lsp_sync_call → [handleBridgeSync]：
 *       ① 优先用 JS 钉住的 Class 全局引用（clsRef → GumJsBridge.newLocalRef）拿 Class，
 *          绕开 Class.forName 的单点 loader 缺陷；拿到即 deleteGlobalRef（一次性凭据）。
 *       ② 缺失/无效才回退 Class.forName(cls, false, targetLoader)。
 *       ③ ctor=true 走 declaredConstructors，否则 declaredMethods 按名过滤，再按 sigs 归一化过滤。
 *       ④ hook(executable).intercept(Hooker) → 同步返回 {"ok":true,"armed":n}；
 *          失败 {"ok":false,"err":…,"kind":"cnfe|overload|arm|badreq"} → JS 侧同步 throw。
 *   ★ 同步通道**失败不入队**：用户此刻正等着结果，D3 的迟到再武装只服务 legacy send 路径
 *     （脚本类未加载时先 send 再等类出现，那个场景没有同步返回者）。
 *
 * 链路（hook 命中回调，主路径 = 同线程同步 dispatcher，契约 §3；上游 implement() 的等价物）：
 *   adapter 在脚本加载时创建 NativeCallback（入参/返回都是 payload JSON 的 UTF-8 指针），
 *   经同步通道 {"op":"set_dispatcher","ptr":"0x…"} 交给 Kotlin（GumJsBridge.setDispatcher）。
 *   命中时（app 线程）：
 *     chain.getArgs()/getThisObject() 编码（对象/数组 = 线程局部引用裸指针 {"__p":"0x…","__c":…}）
 *     → GumJsBridge.dispatchHook(payload)：**在同一 app 线程上执行 JS**（无 rpc 往返、无超时、
 *       无 inner 循环）→ 返回 reply JSON → 按 kind 分派（§3.3）：
 *        {"kind":"value","r":<E>}  → 解码 r 作为返回值，**不执行原方法**
 *        {"kind":"default"}        → 返回类型默认值，**不执行原方法**
 *        {"kind":"proceed"}        → chain.proceed()（"JS 侧 handler 未注册"兜底）
 *        解析失败 / JS 抛错        → JS_ERR/DECODE_ERR + 返回类型默认值（**不 proceed**，上游语义）
 *     dispatchHook 返回 null（dispatcher 未注册/cpp 执行失败）→ 退回下面的 async rpc 路径。
 *   ★ 重入守卫（上游 pendingCalls 的等价物）：JS 回调里 this.foo() 的 JNI 调用会重新命中
 *     LSPlant 并回到本文件——同线程同步调度下若再进 JS 就是无限递归，因此
 *     `hookDepth`(ThreadLocal) > 0 时**直接 chain.proceed()**，不再进 JS。
 *
 * 链路（hook 命中回调，fallback = 旧 async rpc，保留不动）：
 *   GumJsBridge.callJs(id, payload)（cpp 登记 pending + frida:rpc post）→ JS
 *   rpc.exports.__lspHookReply(id,payloadJson) → 标准 reply → cpp → callJs 返回：
 *        ""                            → 超时/引擎异常 → 兜底 proceed 原方法（REPLACE_TIMEOUT）
 *        {"kind":"inner","args":[…]|null} → chain.proceed(转换后参数) + postOriginalReply
 *                                        → 同 id 续等最终 reply（innerExecuted 防二次执行）
 *   ★ 与旧 RouteB B1 的方向差异（最易踩）：fn 返回 undefined / 抛异常 / 返回不兼容值，
 *     上游语义都是**跳过原方法**（Java 调用方拿默认值），本文件的旧实现曾反向 proceed。
 *
 * 对象生命周期（契约 §3.2，最易出事的一条）：
 *  · 主路径用 `__p`（本线程局部引用裸指针）：不创建全局引用，JS 在同线程同步执行期间引用有效。
 *  · 防护性回退 `__h`（GumJsBridge.newGlobalRef）：生命周期严格限定"本次回调"（含所有 inner
 *    往返），[interceptReplace] 的 finally 统一 delete；sync 路径的 finally 同样释放。
 *  · JS 反向给回的 `__h` 由 Kotlin newLocalRef 后**立即** deleteGlobalRef（局部引用独立
 *    持有对象，删除全局引用不影响它）。
 */
class HookRouter(
    private val targetPackage: String,
    private val targetLoader: ClassLoader,
    appContext: Context? = null,
    private val hooker: (Executable) -> XposedInterface.HookBuilder,
    hostLog: (String) -> Unit,
) {
    /**
     * D2：这两个依赖在提前阶段拿不到（无 Context），因此可在 Application 阶段经
     * [attachRuntime] 补上。其余字段（loader/hooker）提前阶段就有，保持 val。
     */
    @Volatile
    private var appContext: Context? = appContext

    @Volatile
    private var hostLog: (String) -> Unit = hostLog

    /**
     * D2：接管时注入运行期依赖（Application 上下文 + 日志上行通道）。
     * 专为“onPackageReady 先建、Application 阶段接管”设计，只补缺不重建 ——
     * 重建会丢掉已挂的 hook 手柄。
     */
    fun attachRuntime(context: Context?, log: (String) -> Unit) {
        // 注意：参数名不能与属性同名，否则赋值会砸到参数上（Kotlin 参数不可变 = 编译错误）
        this.appContext = context
        this.hostLog = log
    }

    // ==================== D3：类加载感知（三级再武装） ====================

    /** D3：入待挂队列，并确保轮询在跑（幂等）。 */
    private fun enqueuePending(req: HookRequest) {
        val key = req.clsName + "#" + req.methodName + "#" + req.tag
        val isNew = pendingHooks.putIfAbsent(key, req) == null
        hostLog(
            "[lsp-hook] MISS_CLASS_QUEUED ${req.clsName}#${req.methodName} " +
                (if (isNew) "(queued)" else "(already queued)") +
                " pending=" + pendingHooks.size
        )
        ensurePolling()
    }

    /**
     * D3②：启动轮询（幂等）。
     * 为何不用 Handler.postDelayed：本类可能运行在任何线程，handler 常驻会增加状态；
     * 一个"队列空就退出"的守护线程语义更简单、更易推理。
     */
    @Synchronized
    private fun ensurePolling() {
        val cur = pollThread
        if (cur != null && cur.isAlive) return
        val th = Thread {
            var ticks = 0
            try {
                while (ticks < POLL_MAX_TICKS) {
                    if (pendingHooks.isEmpty()) break
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    ticks++
                    // 逐次兜底：单次 flush 出错不应终止轮询
                    runCatching { flushPending(Int.MAX_VALUE, "poll") }
                }
                if (pendingHooks.isNotEmpty()) {
                    hostLog(
                        "[lsp-hook] POLL_GIVEUP pending=" + pendingHooks.size +
                            " ticks=" + ticks + " (类始终未加载；等下次脚本重跑)"
                    )
                }
            } catch (t: Throwable) {
                // ★ 安卓：任意线程的未捕获异常都会由默认处理器杀掉**整个应用进程**。
                //   本线程运行在目标 App 内，因此必须把所有异常拦在此处，绝不外抛。
                runCatching { hostLog("[lsp-hook] POLL_ERR " + t.message) }
            } finally {
                pollThread = null
            }
        }
        th.isDaemon = true
        th.name = "lspfrifa-hook-poll"
        pollThread = th
        th.start()
    }

    /**
     * D3①：锚点 flush —— 在已知的早期时机（callApplicationOnCreate 拦截内、proceed 之前）
     * 尝试挂载一批待挂请求。
     * @return 已成功挂上的条数（0 = 无进展，调用方可据此决定是否继续）
     */
    fun flushAtAnchor(): Int {
        if (pendingHooks.isEmpty()) return 0
        return flushPending(FLUSH_MAX_ITEMS, "anchor")
    }

    /**
     * 逐条尝试重挂（类可解析则挂上并出队）。
     * @param limit 本轮处理上限（锚点路径必须可估界，防目标主线程卡顿）
     * @param source 日志来源标记（anchor / poll）
     */
    private fun flushPending(limit: Int, source: String): Int {
        if (pendingHooks.isEmpty()) return 0
        var done = 0
        // 预算修复（引擎审查，2026-10-05）：原条件 `done < limit` 只统计“成功挂上”的条数，
        // 而“类仍未加载”（CNFE，最常见情形）不增 done —— 于是锚点路径（跑在目标主线程）
        // 会把整个队列扫完，limit=8 形同虚设（队列大时主线程被拖住）。
        // 改为同时限制“扫描次数”，让 FLUSH_MAX_ITEMS 真正约束主线程工作量。
        var scanned = 0
        val it = pendingHooks.entries.iterator()
        while (it.hasNext() && done < limit && scanned < limit) {
            scanned++
            val req = it.next().value

            // 第一阶段：解析类（决定“出队”还是“留队”）。
            // 用 null 作哨兵值，而非在 catch 里 continue —— 后者在“try 作表达式”的场景下
            // 对 Kotlin 版本有兼容不确定性，而此处无法编译验证，故取无争议写法。
            var resolved: Class<*>? = null
            try {
                resolved = Class.forName(req.clsName, false, targetLoader)
            } catch (_: ClassNotFoundException) {
                // 仍未加载：留在队列等下一轮（预期路径，不打日志避免刷屏）
            } catch (t: Throwable) {
                // 解析期其它错误（如 NoClassDefFoundError）：出队防死循环
                it.remove()
                hostLog("[lsp-hook] LATE_ARM_ERR ${req.clsName}#${req.methodName} err=" + t.message)
            }
            val clazz = resolved
            if (clazz == null) continue

            // 第二阶段：出队 + 挂载。
            // remove() 只在此处调用一次 —— ConcurrentHashMap 迭代器连续两次 remove()
            // （中间无 next()）会抛 IllegalStateException，而其中一条路径暴露在目标主线程上，
            // 故绝不允许双重出队。
            it.remove()
            try {
                armOnClass(req, clazz)
                done++
                hostLog("[lsp-hook] ARMED_LATE ${req.clsName}#${req.methodName} src=$source")
            } catch (t: Throwable) {
                // 已在上面出队，这里不再 remove（防 ISE）
                hostLog("[lsp-hook] LATE_ARM_ERR ${req.clsName}#${req.methodName} err=" + t.message)
            }
        }
        return done
    }

    /** 当前待挂数（诊断/UI 用）。 */
    fun pendingCount(): Int = pendingHooks.size

    /** D3：卸载时清队列（脚本重跑时旧请求已无意义）。 */
    private fun clearPending() {
        if (pendingHooks.isNotEmpty()) {
            hostLog("[lsp-hook] PENDING_CLEARED n=" + pendingHooks.size)
        }
        pendingHooks.clear()
    }

    /**
     * D3③：安装类加载监听 —— 把“类加载后多久能挂上”从轮询的 200ms 降到下一个消息循环。
     *
     * **激进模式**：hook 的是 ClassLoader.loadClass(String, boolean)，JVM 里最热的路径之一，
     * 因此默认不装，由宿主设置显式开启（remote prefs "loadclass_watch"）。
     *
     * 三条安全纪律（缺一不可）：
     *  1. **proceed 之后才处理**：绝不在类加载路径中同步安装 hook ——
     *     安装过程本身会加载类，与当前加载共用 per-loader 锁，同步做等于自己抢自己的锁（可死锁）。
     *  2. **经 mainHandler.post 投递**：彻底离开类加载锁域再动手，代价仅几十毫秒延迟（远优于轮询 200ms）。
     *  3. **先判空再扫描**：稳态（队列空）下本路径的开销就是一次 isEmpty()，可忽略。
     *
     * 幂等：重复调用只装一次；失败时重置标志以便下次重试。
     */
    fun installClassLoaderWatcher() {
        if (classLoaderWatcherInstalled) return
        try {
            val m = ClassLoader::class.java.getDeclaredMethod(
                "loadClass",
                String::class.java,
                java.lang.Boolean.TYPE,
            )
            classLoaderWatcherHandle = hooker.invoke(m).intercept { chain ->
                val name = runCatching { chain.getArg(0) as? String }.getOrNull()
                // 完全放行：不改参数、不改返回值、不吞异常（loadClass 抛 CNFE 时自然上抛）
                val loaded = chain.proceed()
                if (name != null && !pendingHooks.isEmpty()) {
                    // 队列非空才做这次扫描；命中才投递（避免每次类加载都排一个消息）
                    val relevant = pendingHooks.values.any { it.clsName == name }
                    if (relevant) {
                        // 预算修复（引擎审查，2026-10-05）：本 flush 投递到**主线程**执行，
                        // 原 Int.MAX_VALUE 会在队列大时卡主线程。改为与锚点同额（FLUSH_MAX_ITEMS）——
                        // 未被本轮处理完的条目由轮询线程（非主线程）接手，不丢功能。
                        mainHandler.post {
                            runCatching { flushPending(FLUSH_MAX_ITEMS, "loadclass") }
                        }
                    }
                }
                loaded
            }
            classLoaderWatcherInstalled = true
            hostLog("[lsp-hook] CLASSLOADER_WATCH_ON")
        } catch (t: Throwable) {
            classLoaderWatcherInstalled = false
            hostLog("[lsp-hook] CLASSLOADER_WATCH_FAIL err=" + t.message)
        }
    }

    /** D3③：卸载类加载监听（目前供设置关闭/诊断用；脚本重载不调用它）。 */
    fun uninstallClassLoaderWatcher() {
        runCatching { classLoaderWatcherHandle?.unhook() }
        classLoaderWatcherHandle = null
        classLoaderWatcherInstalled = false
        hostLog("[lsp-hook] CLASSLOADER_WATCH_OFF")
    }

    private companion object {
        /**
         * D3②：轮询间隔。与设计文档 D3 一致（200ms）。
         *
         * 【2026-10-05 更正】原注释写“devkit QuickJS 未实现 setInterval/setTimeout”——**该结论错误**。
         * 实核：devkit 完整实现 timer（gumquickcore.c: JS_CFUNC_DEF("_setTimeout"/"_setInterval")
         * → gum_quick_core_schedule_callback → g_timeout_source_new 挂 js_context，
         * 由 gum-js-loop 调度器泵送；core.js 把 setTimeout/setInterval/setImmediate 暴露为全局）。
         * 旧误诊来源：用户脚本心跳的**输出**（send）长期不可见（缺常驻泵，已于 e659ce9 修复），
         * 叠加当时探针恰好中断，被误判为“timer 不触发”。
         *
         * 本调度仍留在 Kotlin 侧——**不是**因为缺 timer，而是设计选择：
         * 不依赖用户脚本执行状态、不受脚本异常影响、可在无脚本时独立运行。
         */
        const val POLL_INTERVAL_MS = 200L

        /** D3②：轮询上限 ≈ 2 分钟（600 × 200ms）。超时放弃并日志，防线程永久驻留。 */
        const val POLL_MAX_TICKS = 600

        /**
         * 单次锚点 flush 的处理上限。
         *
         * 为何是 8 而不是更大：锚点 flush 跑在目标**主线程**（callApplicationOnCreate 拦截内、
         * proceed 之前），每一项都可能触发 LSPlant 安装（含 deopt），必须给主线程留预算；
         * 剩下的条目由轮询线程（非主线程，200ms 后接手）继续处理，代价几乎为零。
         * 若真机观察到启动卡顿，继续下调此值即可（无需改结构）。
         */
        const val FLUSH_MAX_ITEMS = 8

        /**
         * encodeValue 用：|v| > 2^53 的 Long 经 JSON double 往返会丢精度，仅告警不拦截。
         *
         * 为何合并在本 companion：Kotlin 每类只允许**一个** companion object。
         * 本文件原先在末尾另有一个 companion（只含此常量），W4 插入轮询常量时
         * 又新建了一个 → 双 companion 编译失败（Conflicting declarations / Only one
         * companion object is allowed）。本处为合并修复。
         */
        const val MAX_SAFE_LONG = 9007199254740992L   // 2^53
    }

    private data class HookRequest(
        val clsName: String,
        val methodName: String,
        val tag: String,
        val act: String,
        val mode: String,
        /** B2：overload 精确选择（参数类型数组，如 ["I","java.lang.String"]）；null=挂全部 */
        val sigs: List<String>? = null,
        /**
         * 第一阶段（契约 §2.1）：true = 这次 methodName 指的是**构造器**（上游 bridge 的 `$init`
         * wrapper），挂 declaredConstructors 而不是 declaredMethods。
         * 缺省 false —— legacy send 路径（LSP.hook 观察模式）没有这个概念，行为零改动。
         */
        val ctor: Boolean = false,
    )

    /**
     * [armOnClass] 的结果（同步通道要把它变成回复 JSON，见 installSync）。
     * @param reason 非 null = 一个候选都没找到（方法名/overload 不匹配），文本直接进 `err`。
     */
    private class ArmOutcome(val armed: Int, val failed: Int, val reason: String?)

    /** 已挂 handle（key = cls#method#sig#tag；t7 修复 t2 P0#1：签名入键，overload 不再互相覆盖/泄漏） */
    private val handles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    /**
     * D3：待挂队列 —— 类尚未被加载时的 hook 请求（原逻辑直接 MISS 丢弃）。
     *
     * 为何需要：LSPlant 要求 ArtMethod 已存在，而脚本常在目标类加载前就跑；
     * 尤其是提前注入（W3）后脚本时机更早，MISS 概率也随之上升。
     *
     * 去重维度用 cls#method#tag（**不含签名**）：同一语义的请求重复入队无意义，
     * 真正多 overload 的区分在 armOnClass 里靠 sigs 完成。
     */
    private val pendingHooks = ConcurrentHashMap<String, HookRequest>()

    /** D3②：轮询线程只允许一个；队列空即退出。 */
    @Volatile
    private var pollThread: Thread? = null

    /** D3③：类加载监听是否已安装（幂等守卫）。 */
    @Volatile
    private var classLoaderWatcherInstalled = false

    /**
     * D3③：监听手柄。单独持有、不进 handles：
     *  1. 它不是脚本注册的 hook，不应随脚本重载被 unhookAll 卸掉；
     *  2. 必须强引用持住 —— 丢了引用等于给 GC 开绿灯（框架侧未必保活）。
     */
    @Volatile
    private var classLoaderWatcherHandle: XposedInterface.HookHandle? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * replace 全局串行互斥（B1 冻结）：**仅旧 async rpc 路径**用（一次一个在途请求 + ≤500ms/阶段）。
     * 同步 dispatcher 路径**不用**它：JS 侧在同一 app 线程同步执行，再加锁只会在多线程并发命中时
     * 互相拖住；GumJS 运行时自己按 scope 串行化回调（frida 的 NativeCallback 本就是多线程并发进 JS）。
     */
    private val replaceLock = Any()

    /**
     * 同步 dispatcher 路径的重入深度（每个 router 实例 + 每条线程各一份）。
     *
     * 为什么必须有：sync dispatcher 是"在 app 线程直接执行 JS"。JS 回调里 `this.foo()`（真实
     * wrapper 的方法调用）会再次命中 LSPlant 并回到 interceptReplace —— 若此时又进 JS，就是
     * 无限递归 / 同线程自锁。上游用 `pendingCalls` + `STRATEGY_DIRECT` 达到同样效果：
     * 重入的调用**直接执行原方法**，不再经过 user fn。
     *
     * 用匿名子类而不是 ThreadLocal.withInitial：withInitial 是 Java 8 API（Android 需 API 24+
     * 或 desugaring），本模块 minSdk 不保证，故用最保守写法（各 API 级别都可用）。
     */
    private val hookDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    private val reqSeq = AtomicLong(0)

    private fun buildKey(req: HookRequest, sig: String) = "${req.clsName}#${req.methodName}#$sig#${req.tag}"

    private fun methodSig(m: Executable): String = m.toString()

    /**
     * 处理一条来自 GumJS 上行的消息。
     * @return true = 消息已被本路由消费（不再上行原始 JSON 到宿主 UI）。
     */
    fun tryHandle(message: String): Boolean {
        val req = parseHook(message)
        if (req != null) {
            handle(req)
            return true
        }
        if (isUnhookAll(message)) {
            unhookAll()
            return true
        }
        if (parseUnhook(message)) {
            return true
        }
        return handleToastMessage(message)
    }

    /** P1：t="lsp.unhook_all" 消息判断（脚本热更前批量卸载 LSPlant 手柄）。 */
    private fun isUnhookAll(message: String): Boolean {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            outer.optJSONObject("payload")?.optString("t") == "lsp.unhook_all"
        } catch (_: Throwable) {
            false
        }
    }

    /** RouteB：t="lsp.unhook"（JS 侧 implementation=null / 重复赋值前先卸）→ 按 tag 卸全部 overload 手柄。 */
    private fun parseUnhook(message: String): Boolean {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            val payload = outer.optJSONObject("payload") ?: return false
            if (payload.optString("t") != "lsp.unhook") return false
            val tag = payload.optString("tag")
            if (tag.isEmpty()) return false
            unhookByTag(tag)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 按 tag 卸掉全部 overload 手柄。
     *
     * @return 命中的已挂 handle 条数（0 = 没挂过）。同步 uninstall 通道需要这个计数来回答
     *         `{"ok":false,"missing":true}`，legacy send 路径忽略返回值（行为零改动）。
     *
     * 锁纪律：**只把"摘 key"放进监视器，`unhook()` 一律在锁外调用**。
     * 本函数的调用方之一是 JS 线程（同步 uninstall 通道）。`HookHandle.unhook()` 会等该方法的
     * 在途回调退出，而在途回调可能正卡在 `dispatchHook` 里等 JS 引擎锁——那把锁正被调用本函数的
     * JS 线程持有。锁内 unhook 就是 AB/BA 死锁：JS 线程等在途回调结束，在途回调等 JS 线程。
     * 「摘 key」与「arm 同一个 key」的竞态仍然由监视器挡住（摘掉的 key 不会再被卸第二次）。
     */
    private fun unhookByTag(tag: String): Int {
        val doomed = ArrayList<XposedInterface.HookHandle>(4)
        val n = synchronized(this) {
            // D3：同 tag 的待挂项也该撤（否则“卸了又被迟到挂上”语义矛盾）。
            // 用“先筛键再逐个 remove”，不依赖 entrySet().removeIf 的容器实现细节。
            pendingHooks.filterValues { it.tag == tag }.keys.toList()
                .forEach { k -> pendingHooks.remove(k) }
            var removed = 0
            for (key in handles.keys.filter { it.endsWith("#$tag") }) {
                handles.remove(key)?.let { h -> doomed.add(h); removed++ }
            }
            removed
        }
        doomed.forEach { h -> runCatching { h.unhook() } }
        if (n > 0) hostLog("[lsp-hook] UNHOOKED tag=$tag count=$n")
        return n
    }

    /**
     * P1：卸载全部已注册 LSPlant 手柄（HookHandle.unhook 框架 API，幂等）。
     * 用于脚本热更：重载前不清理旧 hook，会导致同方法重复拦截语义残留。
     *
     * 锁纪律同 [unhookByTag]：先摘 key，锁外 unhook（这里不取监视器，但同理避免
     * 持锁做会阻塞的操作）。
     */
    fun unhookAll() {
        // D3：脚本重跑时旧待挂请求已无意义 —— 一并清掉，防止“上一轮脚本的 hook 迟到生效”
        clearPending()
        val doomed = synchronized(this) {
            val all = ArrayList<XposedInterface.HookHandle>(handles.values)
            handles.clear()
            all
        }
        doomed.forEach { h -> runCatching { h.unhook() } }
        hostLog("[lsp-hook] UNHOOKED all=${doomed.size}")
    }

    // ==================== 同步安装通道（契约 §2；JS → lsp_sync_call → 本函数） ====================

    /**
     * 同步通道总入口：cpp `lsp_sync_call` → `GumJsBridge.lspSync` → 这里。
     *
     * 三条纪律（缺一即毁引擎）：
     *  1. **永不抛**：调用方是 GumJS 的 JS 线程，Kotlin 异常逃到 JNI 边界 = 引擎直接不可用；
     *     故最外层 catch Throwable，连 Error 族一起转成 `{"ok":false,…}`。
     *  2. **不入队**：与 legacy send 路径相反，同步通道的调用方正阻塞等待结果，所以"类还没加载"
     *     也必须立刻同步失败（kind=cnfe），不能丢进 D3 待挂队列让用户等到下次脚本重跑。
     *  3. 只在真正需要跨线程状态时才加锁（armOnClass/unhookByTag 是 @Synchronized；
     *     本函数自身不持任何 cpp 侧锁 —— 契约 §1.3）。
     *
     * @param json 形如 `{"op":"install"|"uninstall"|"set_dispatcher",…}`
     * @return 结果 JSON：`{"ok":true,…}` / `{"ok":false,"err":…,"kind":…}`
     */
    fun handleBridgeSync(json: String): String {
        var op = "?"
        return try {
            val req = JSONObject(json)
            op = strField(req, "op")
            when (op) {
                "install" -> installSync(req)
                "uninstall" -> uninstallSync(req)
                "set_dispatcher" -> setDispatcherSync(req)
                else -> syncFail("badreq", "unknown op='$op'")
            }
        } catch (t: Throwable) {
            // 连这里的日志/拼串都再兜一层 runCatching：极端情况下（栈溢出后内存紧张）任何
            // 二次异常都不许逃出去——宁可返回一个固定字符串。
            val msg = t.message ?: t.javaClass.simpleName
            runCatching { hostLog("[lsp-hook] SYNC_ERR op=$op err=$msg") }
            runCatching { syncFail("badreq", "sync handler crashed: $msg") }
                .getOrElse { "{\"ok\":false,\"err\":\"sync handler crashed\",\"kind\":\"badreq\"}" }
        }
    }

    /**
     * 契约 §2.1：install —— 同步挂一个 overload（或构造器）并回报 armed 计数。
     *
     * 类解析优先级：clsRef（JS 钉住的 Class 全局引用）→ Class.forName(targetLoader)。
     * 前者是本阶段最关键的改进：`Java.use()` 在 bridge 里已经解析出了真实 Class 对象
     * （可能来自插件/加固/isolated-split 的 loader），Kotlin 这边的 targetLoader 只是
     * onPackageReady/Application 阶段的一次性快照，未必看得见同一份类（REPORT §3.4）。
     */
    private fun installSync(req: JSONObject): String {
        val cls = strField(req, "cls")
        val method = strField(req, "method")
        val tag = strField(req, "tag")
        if (cls.isEmpty() || method.isEmpty() || tag.isEmpty()) {
            return syncFail("badreq", "install needs cls/method/tag")
        }
        val ctor = req.optBoolean("ctor")
        // 契约 §2.1：sigs 是"该 overload 的参数类型 className 列表"；**缺失**（null）与
        // **空数组**（无参 overload）语义不同，不能合并：空数组必须精确匹配"零参数"。
        val sigsArr = req.optJSONArray("sigs")
        val sigs: List<String>? = if (sigsArr == null) {
            null
        } else {
            (0 until sigsArr.length()).map { sigsArr.getString(it) }
        }
        // isStatic 由 Kotlin 侧从 Modifier 取实际值（intercept 时按 executable 判定），
        // 协议里的字段仅作 JS 侧声明，这里不采信（避免"声明与事实不符"污染 payload）。

        val lookup = lookupClass(cls, strField(req, "clsRef"))
        val clazz = lookup.clazz
            ?: return syncFail("cnfe", lookup.err ?: ("class not found: " + cls))

        val hookReq = HookRequest(
            clsName = cls,
            methodName = method,
            tag = tag,
            act = "",
            mode = "replace",
            sigs = sigs,
            ctor = ctor,
        )
        val outcome = armOnClass(hookReq, clazz)
        val reason = outcome.reason
        if (reason != null) {
            return syncFail("overload", reason)
        }
        if (outcome.armed == 0 && outcome.failed > 0) {
            // 全部候选都挂失败（LSPlant 拒绝/框架异常）→ 明确报 arm 失败，别让用户以为挂上了
            return syncFail(
                "arm",
                "all ${outcome.failed} target(s) failed to arm " +
                    "(cls=$cls method=$method; see logcat ARM_FAIL)",
            )
        }
        return syncOk(outcome.armed)
    }

    /** 契约 §2.2：uninstall —— 按 tag 卸 handle；没挂过要如实回答 missing（JS 侧只 warn 不抛）。 */
    private fun uninstallSync(req: JSONObject): String {
        val tag = strField(req, "tag")
        if (tag.isEmpty()) return syncFail("badreq", "uninstall needs tag")
        val n = unhookByTag(tag)
        if (n > 0) {
            return JSONObject().put("ok", true).put("unhooked", n).toString()
        }
        return JSONObject()
            .put("ok", false)
            .put("err", "no armed hook with tag=$tag")
            .put("missing", true)
            .toString()
    }

    /**
     * 契约 §3 主路径的注册：JS adapter 建好 NativeCallback 后把函数指针交过来。
     *
     * ptr 的字符串形态：**十进制**（Lead 侧 `ptr.toString(10)`）。这里同时容忍 `0x…`
     * 十六进制写法和负数十进制（指针位型按 jlong 传递，不做算术）。
     * ptr="0" 或 "0x0" = 清除（脚本卸载/重载时用），返回 ok。
     */
    private fun setDispatcherSync(req: JSONObject): String {
        val raw = strField(req, "ptr")
        if (raw.isEmpty()) return syncFail("badreq", "set_dispatcher needs ptr")
        val ptr = parseHandle(raw)
        GumJsBridge.setDispatcher(ptr)
        if (ptr == 0L) {
            hostLog("[lsp-hook] SYNC_DISPATCHER_CLEAR")
        } else {
            hostLog("[lsp-hook] SYNC_DISPATCHER_SET ptr=" + handleHex(ptr))
        }
        return JSONObject().put("ok", true).toString()
    }

    /** 类解析结果（同步通道用）：clazz 非空即成功；err/cnfe 供 kind=cnfe 回答。 */
    private class ClassLookup(val clazz: Class<*>?, val err: String?)

    /**
     * 契约 §2.1 的类解析：**先用 clsRef**，无效才回退 forName。
     *
     * 关于 deleteGlobalRef 的时机：clsRef 是**JS 侧 `newGlobalRef` 创建的**一次性凭据，
     * 所有权在这里移交——契约 §2.1 要求 Kotlin"拿到后**立即**删除"。所以只要拿到非 0 句柄
     * 就必须删；newLocalRef 失败（引用失效/已被释放）时同样要删，否则每失败一次泄漏一个
     * 全局引用（ART 默认 512 项）。
     * 注意：局部引用独立持有对象，删掉全局引用后 `clazz` 仍有效。
     *
     * `parseHandle` 返回 0（clsRef 缺失/格式非法）= JS 根本没建引用，这里也就没有可删的东西。
     */
    private fun lookupClass(clsName: String, clsRefRaw: String): ClassLookup {
        val handle = parseHandle(clsRefRaw)
        if (handle != 0L) {
            var fromRef: Any? = null
            try {
                // 先取局部引用，再删全局引用（顺序不能反：删早了局部引用还没拿到）
                fromRef = GumJsBridge.newLocalRef(handle)
            } catch (t: Throwable) {
                runCatching { hostLog("[lsp-hook] SYNC_REF_ERR cls=$clsName err=" + t.message) }
            } finally {
                runCatching { GumJsBridge.deleteGlobalRef(handle) }
            }
            val fromRefCls = fromRef as? Class<*>
            when {
                fromRefCls == null -> hostLog(
                    "[lsp-hook] SYNC_REF_INVALID cls=$clsName ref=" + handleHex(handle) +
                        " → 回退 Class.forName"
                )
                fromRefCls.name != clsName ->
                    // 名字不符 = JS 侧钉错类（协议违例）。不静默采信：采信了会让 handles 的
                    // key（按 clsName 拼）与实际被 hook 的类不一致，卸载/去重都会错乱。
                    hostLog(
                        "[lsp-hook] SYNC_REF_NAME_MISMATCH want=$clsName got=" + fromRefCls.name +
                            " loader=" + fromRefCls.classLoader + " → 回退 Class.forName"
                    )
                else -> {
                    // defining loader 校验的落地：不去比较"是否等于 targetLoader"（同名类多 loader
                    // 时那一份可能正是业务实际使用的那份），而是把真实定义 loader 记下来——
                    // "挂上了但业务走另一份 Class"这类问题只能靠这条日志定位（REPORT §3.3/§3.4）。
                    hostLog(
                        "[lsp-hook] SYNC_CLS_BY_REF cls=$clsName loader=" + fromRefCls.classLoader
                    )
                    return ClassLookup(fromRefCls, null)
                }
            }
        }
        return try {
            val c = Class.forName(clsName, false, targetLoader)
            hostLog("[lsp-hook] SYNC_CLS_BY_FORNAME cls=$clsName loader=" + c.classLoader)
            ClassLookup(c, null)
        } catch (e: ClassNotFoundException) {
            ClassLookup(null, "class not found: $clsName (loader=$targetLoader)")
        } catch (t: Throwable) {
            ClassLookup(null, "class resolve failed: $clsName err=" + t.message)
        }
    }

    /** 同步通道成功回答。`{"ok":true,"armed":n}`（契约 §2.1 冻结字段，不加额外字段）。 */
    private fun syncOk(armed: Int): String =
        JSONObject().put("ok", true).put("armed", armed).toString()

    /** 同步通道失败回答：kind ∈ cnfe|overload|arm|badreq（契约 §2.1；JS 侧据此同步 throw）。 */
    private fun syncFail(kind: String, err: String): String =
        JSONObject().put("ok", false).put("err", err).put("kind", kind).toString()

    /** JSON 字段取字符串：optString 对显式 JSON null 会返回字面量 "null"，不可直接采信。 */
    private fun strField(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return if (v is String) v else ""
    }

    /**
     * 句柄/指针的十六进制编码：`java.lang.Long.toHexString` 按**无符号**解释补码，
     * 与 [parseHandle] 的手工无符号解析严格互逆（高位为 1 的 jlong 也能原样往返）。
     */
    private fun handleHex(h: Long): String = "0x" + java.lang.Long.toHexString(h)

    /**
     * 解析句柄/指针字符串 → jlong 位型（不做算术）。支持三种写法：
     *  · 十进制（`140234567890123`，Lead 侧的 set_dispatcher 约定；负数亦按位型接受）
     *  · `0x…` / `0X…` 十六进制（JS 侧 `ptr(h).toString()` 的形态，如 clsRef）
     *  · 裸十六进制（无前缀，长度 ≤16 位时按十六进制）
     *
     * 为何不直接 `toLong(16)`：① 带 `0x` 前缀会抛 NumberFormatException；② 高位为 1 的指针
     * 用有符号解析会溢出抛错。手工按位累积（移位或）对 64 位是精确定义的，且不依赖
     * `parseUnsignedLong`（Java 8 API，minSdk 不保证）。解析失败一律返回 0（= 无效句柄）。
     */
    private fun parseHandle(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        if (t.startsWith("0x") || t.startsWith("0X")) {
            val hex = t.substring(2)
            if (hex.isEmpty() || hex.length > 16) return 0L
            var v = 0L
            for (c in hex) {
                val d = Character.digit(c, 16)
                if (d < 0) return 0L
                v = (v shl 4) or d.toLong()
            }
            return v
        }
        // 带符号十进制（含负数）优先；不是十进制再按裸十六进制处理
        t.toLongOrNull()?.let { return it }
        if (t.length > 16) return 0L
        var v = 0L
        for (c in t) {
            val d = Character.digit(c, 16)
            if (d < 0) return 0L
            v = (v shl 4) or d.toLong()
        }
        return v
    }

    /** 解析 Frida 消息协议 {type:"send", payload:{t:"lsp.hook",...}}；非本类消息返回 null。 */
    private fun parseHook(message: String): HookRequest? {
        return try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return null
            val payload = outer.optJSONObject("payload") ?: return null
            if (payload.optString("t") != "lsp.hook") return null
            val cls = payload.optString("cls")
            val method = payload.optString("method")
            if (cls.isEmpty() || method.isEmpty()) return null
            val sigsArr = payload.optJSONArray("sigs")
            HookRequest(
                clsName = cls,
                methodName = method,
                tag = payload.optString("tag"),
                act = payload.optString("act"),
                mode = payload.optString("mode"),
                sigs = if (sigsArr == null) null else (0 until sigsArr.length()).map { sigsArr.getString(it) },
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 直击弹窗（无 hook 依赖的链路诊断）：解析 {type:"send", payload:{t:"lsp.toast", msg}}，
     * post 到目标进程主线程用应用 Context 弹出。返回 true=已消费。
     */
    private fun handleToastMessage(message: String): Boolean {
        val msg = try {
            val outer = JSONObject(message)
            if (outer.optString("type") != "send") return false
            val payload = outer.optJSONObject("payload") ?: return false
            if (payload.optString("t") != "lsp.toast") return false
            payload.optString("msg").takeIf { it.isNotEmpty() } ?: return false
        } catch (_: Throwable) {
            return false
        }
        // 提前注入阶段 appContext 为 null（Context 要到 App 阶段才由 attachRuntime 补上）。
        // 此时不能 return true（那会把消息“消费掉”，宿主 UI 永远看不到这次请求），
        // 而要返回 false 让调用方继续走未消费分支（early 回调会入 EarlyLogBuffer 补发）。
        val ctx = appContext ?: run {
            hostLog("[lsp-toast] deferred (no context yet) msg=$msg")
            return false
        }
        hostLog("[lsp-toast] requested msg=$msg")
        mainHandler.post {
            runCatching {
                Toast.makeText(ctx, "[lsp-toast] $msg", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }

    private fun handle(req: HookRequest) {
        // 类必须已被目标进程加载才能挂（LSPlant 不支持未加载类自动延迟挂，P0 不做）；
        // framework 类（android.*）经 targetLoader 可 delegate 到 boot classloader。
        val clazz = try {
            Class.forName(req.clsName, false, targetLoader)
        } catch (_: ClassNotFoundException) {
            // D3：不再直接丢弃 —— 入待挂队列，等类可解析后再挂（锚点 flush / Kotlin 轮询）
            enqueuePending(req)
            return
        } catch (t: Throwable) {
            hostLog("[lsp-hook] MISS_ERR class=${req.clsName} err=${t.message}")
            return
        }
        armOnClass(req, clazz)
    }

    /**
     * D3：真正的挂载路径（类已可解析）。由 legacy 消息路径（handle）、flush 路径（锚点/轮询）
     * 与第一阶段同步 install 通道共用。
     *
     * @Synchronized 的必要性：调用方有多条并发路径 —— 消息路径（handle）、flush 路径（锚点/轮询）、
     * 同步 install（JS 线程）。不加锁时，两个线程可能同时通过 handles.containsKey 检查，
     * 于是对同一方法挂两次：后一个 handle 覆盖 map 中的前一个，前者失去引用 → **泄漏且卸不掉**。
     * 锁粒度是整个 router 实例；arm 是低频操作（仅注册时刻），无性能顾虑。
     *
     * @return armed/failed 计数 + 未命中原因（reason 非 null = 一条都没挂，且原因是"没这个
     *         overload/方法"）。为什么改成返回值：同步通道必须把结果变成回复 JSON（契约 §2.1），
     *         而 legacy 路径照旧忽略返回值 —— 因此这里不抛异常、也不改任何既有日志行的文本。
     */
    @Synchronized
    private fun armOnClass(req: HookRequest, clazz: Class<*>): ArmOutcome {
        // 第一阶段：候选 executable。
        //  · ctor=true → declaredConstructors（构造器没有 name 可筛；req.methodName 只是 bridge
        //    的 $init wrapper 名，不参与匹配）
        //  · 否则 → declaredMethods 按名过滤（只挂"本类直接声明"的方法，与现状一致）
        val candidates: List<Executable> = if (req.ctor) {
            clazz.declaredConstructors.toList()
        } else {
            clazz.declaredMethods.filter { it.name == req.methodName }
        }
        if (candidates.isEmpty()) {
            // 方法不是"本类直接声明"（如只写接口方法名）或重载名不存在：提示而非静默
            if (req.ctor) {
                hostLog("[lsp-hook] MISS ctor=${req.clsName} (declared constructors only)")
                return ArmOutcome(0, 0, "no declared constructor: " + req.clsName)
            }
            hostLog("[lsp-hook] MISS method=${req.clsName}#${req.methodName} (declared methods only)")
            return ArmOutcome(
                0, 0,
                "no declared method ${req.clsName}#${req.methodName} (declared methods only)"
            )
        }

        // B2/第一阶段：overload 精确选择——按 parameterTypes 描述符串过滤
        // （缺省 sigs=null 时挂全部，legacy send 路径行为零改动）
        val targets = filterBySigs(candidates, req.sigs)
        if (targets.isEmpty()) {
            hostLog(
                "[lsp-hook] MISS_OVERLOAD ${req.clsName}#${req.methodName} sigs=${req.sigs} " +
                    "(found=${candidates.size} overloads)"
            )
            return ArmOutcome(
                0, 0,
                "no overload matching sigs=${req.sigs} (found=${candidates.size} overload(s))"
            )
        }

        var armed = 0
        var failed = 0
        for (m in targets) {
            // t7（修复 t2 P0#1）：签名入键——每个 overload 独立 key，unhookAll/重注册不再互相覆盖/泄漏
            val key = buildKey(req, methodSig(m))
            if (handles.containsKey(key)) continue
            try {
                val h = hooker.invoke(m).intercept { chain -> intercept(chain, req) }
                handles[key] = h
                armed++
            } catch (t: Throwable) {
                // HookFailedError(Error 族) 也在此兜底记录，不向上抛
                failed++
                hostLog("[lsp-hook] ARM_FAIL ${req.clsName}#${req.methodName} err=${t.message}")
            }
        }
        hostLog(
            "[lsp-hook] ARMED ${req.clsName}#${req.methodName} overloads=$armed mode=${req.mode} " +
                "sigs=${req.sigs ?: "all"} tag=${req.tag}" +
                (if (failed > 0) " failed=$failed" else "")
        )
        return ArmOutcome(armed, failed, null)
    }

    /** intercept 主体：observe（现状）与 replace（RouteB）分流。 */
    private fun intercept(chain: XposedInterface.Chain, req: HookRequest): Any? {
        return if (req.mode == "replace") {
            interceptReplace(chain, req)
        } else {
            interceptObserve(chain, req)
        }
    }

    /** observe：命中即日志（现状行为，零改动）。 */
    private fun interceptObserve(chain: XposedInterface.Chain, req: HookRequest): Any? {
        val args = try { chain.getArgs() } catch (_: Throwable) { null }
        hostLog(
            "[lsp-hook] HIT ${req.clsName}#${chain.executable.name} tag=${req.tag} args=${argsSummary(args)}"
        )
        if (req.act == "toast") {
            val ctx = runCatching { chain.getThisObject() }.getOrNull() as? Context ?: appContext
            if (ctx != null) {
                mainHandler.post {
                    runCatching {
                        Toast.makeText(ctx, "[lsp-hook] HIT ${req.clsName}#${req.methodName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return chain.proceed()
    }

    /**
     * replace 分流（契约 §3）：主路径 = **同线程同步 dispatcher**；fallback = 旧 async rpc。
     *
     * 重入守卫必须放在**最前面**（连 hasDispatcher 判断与 payload 编码都不做）：
     * JS 回调里 `this.foo()`（真实 wrapper 的方法调用）会经 JNI 再次命中 LSPlant 回到这里，
     * 同线程同步调度下若再进 JS 就是无限递归/自锁。上游的做法（`pendingCalls` +
     * `STRATEGY_DIRECT`）也是这一层直接走原方法。
     *
     * 守卫只在 replace 模式生效：observe 模式没有"进 JS"的路径（只记日志 + proceed），
     * 且 `this.foo()` 在 observe 下本来就该正常命中并再记一次日志（诊断价值）。
     */
    private fun interceptReplace(chain: XposedInterface.Chain, req: HookRequest): Any? {
        // 用 ThreadLocal 而不是全局标志：并发命中的不同 app 线程各有各的深度，不能互相干扰。
        val depth = hookDepth.get() ?: 0
        if (depth > 0) {
            hostLog("[lsp-hook] REENTRY_PROCEED tag=${req.tag} depth=$depth")
            return chain.proceed()
        }
        if (!GumJsBridge.hasDispatcher()) {
            // 没有 dispatcher = 引擎侧还没装 adapter 的同步回调（老脚本/降级）→ 走旧 async rpc，
            // 语义与改造前完全一致。
            return interceptReplaceAsync(chain, req)
        }
        hookDepth.set(depth + 1)
        val refs = ArrayList<Long>(4)
        try {
            return interceptReplaceSync(chain, req, refs)
        } catch (t: Throwable) {
            // 与 async 路径同一纪律：不在此处再次 chain.proceed()——若异常来自原方法
            // （proceed 抛出），再 proceed 会让原方法执行两遍；按框架语义直接上抛
            // （protective 下由框架捕获记录）。
            hostLog("[lsp-hook] REPLACE_ERR tag=${req.tag} err=" + t.message)
            throw t
        } finally {
            // ThreadLocal 重置（不用 remove：拦截线程会复用，remove 只会让下次重新分配）
            hookDepth.set(depth)
            releaseGlobalRefs(refs)
        }
    }

    /**
     * 主路径（契约 §3）：同步 dispatcher —— 在**当前 app 线程**上执行 JS 并直接拿回 reply JSON。
     *
     * 为什么必须同步：上游 `implementation` 回调就是 ART 在调用线程上直接调的 NativeCallback，
     * 因此 JS 里 `this.foo()` 想拿到原方法的**返回值**，只有当"原方法执行"发生在同一次同步
     * 调用里才可能；rpc 往返（投递到 gum-js-loop 线程）天然做不到（旧 B1 只能返回 Promise）。
     *
     * 无 timeout / 无 inner 循环 / 无 postOriginalReply：JS 就在本线程栈上，异常由 adapter
     * 内部兜成合法 JSON；`dispatchHook` 返回 null 的唯一含义是"dispatcher 未注册 / 没进 JS"。
     *
     * @param refs 本路径可能创建的全局引用（`__p` 编码失败时的 `__h` 回退），由调用方 finally 释放
     */
    private fun interceptReplaceSync(
        chain: XposedInterface.Chain,
        req: HookRequest,
        refs: MutableList<Long>,
    ): Any? {
        val payload = buildHookPayload(chain, req.tag, refs, rawPtr = true)
        val reply = GumJsBridge.dispatchHook(payload)
        if (reply == null) {
            // null 只可能是"dispatcher 未注册 / 未进 JS"（adapter 内部保证任何异常都返回合法
            // JSON）——因此退回 async rpc 不会二次执行用户 fn，安全。
            hostLog("[lsp-hook] DISPATCH_FALLBACK tag=" + req.tag)
            return interceptReplaceAsync(chain, req)
        }
        return decodeReply(reply, chain, req, false, null, unparsableToDefault = true)
    }

    /**
     * fallback：旧 async rpc 路径（cpp pending + frida:rpc 往返 + inner 嵌套循环），逻辑不动。
     * 只在没有 dispatcher 或 dispatcher 调用失败时进入；[replaceLock] 也只在这条路径上生效
     * （同步路径在同一 app 线程执行 JS，加锁只会互相拖住）。
     */
    private fun interceptReplaceAsync(chain: XposedInterface.Chain, req: HookRequest): Any? {
        return synchronized(replaceLock) {
            val refs = ArrayList<Long>(4)
            try {
                val payload = buildHookPayload(chain, req.tag, refs, rawPtr = false)
                val id = "lsp-${reqSeq.incrementAndGet()}"
                var reply = GumJsBridge.callJs(id, payload)

                // 嵌套服务循环：async fn 内 await this.method(...)（同 id 栈复用；支持自定义参数）。
                // innerExecuted/lastInnerResult：原方法已在内层执行过 → 后续超时/proceed 兜底
                // 一律复用其结果（**不再二次 chain.proceed()——否则原方法执行两遍**）。
                var innerExecuted = false
                var lastInnerResult: Any? = null
                var inner = parseInner(reply)
                while (inner != null) {
                    // args 缺失/null = 原参回退；给了数组就按参数类型转换（转换失败也回退原参）
                    val argsArr = inner.optJSONArray("args")
                    val conv = if (argsArr != null) {
                        decodeArgs(chain.executable.parameterTypes, argsArr)
                    } else {
                        null
                    }
                    val origResult = if (conv != null) chain.proceed(conv) else chain.proceed()
                    lastInnerResult = origResult
                    innerExecuted = true
                    GumJsBridge.postOriginalReply(id, encodeRet(origResult, refs))
                    reply = GumJsBridge.callJs(id, "")
                    inner = parseInner(reply)
                }

                if (reply.isEmpty()) {
                    // 超时兜底：内层未执行 → 原方法必执行；已执行 → 复用其结果
                    hostLog("[lsp-hook] REPLACE_TIMEOUT tag=${req.tag} id=$id")
                    return@synchronized if (innerExecuted) lastInnerResult else chain.proceed()
                }
                decodeReply(reply, chain, req, innerExecuted, lastInnerResult, unparsableToDefault = false)
            } catch (t: Throwable) {
                // 注意：不在此处再次 chain.proceed()——若异常来自原方法（proceed 抛出），
                // 再 proceed 会导致原方法二次执行；按框架语义直接上抛（protective 下由框架捕获记录）。
                hostLog("[lsp-hook] REPLACE_ERR tag=${req.tag} err=" + t.message)
                throw t
            } finally {
                releaseGlobalRefs(refs)
            }
        }
    }

    /**
     * 契约 §3.3 回复解码。
     *
     * **语义方向（与旧 RouteB B1 相反，最容易踩）**：`undefined` / JS 抛错 / 返回不兼容值
     * 都是"**跳过原方法**、Java 调用方拿返回类型默认值"，不是 proceed。proceed 只有两个
     * 合法来源：`{"kind":"proceed"}`（JS 侧 handler 未注册的兜底）与 async 路径的超时。
     *
     * @param innerExecuted/lastInnerResult async 路径守卫：原方法已在内层执行过时不能再 proceed
     * @param unparsableToDefault 协议不可解析时的方向：
     *        true（同步主路径）= 返回默认值、不 proceed（Lead 2026 最新裁定，上游 error 语义）；
     *        false（async fallback）= proceed 兜底（冻结合同 §3.3 最后一行，保持旧路径行为）。
     */
    private fun decodeReply(
        reply: String,
        chain: XposedInterface.Chain,
        req: HookRequest,
        innerExecuted: Boolean,
        lastInnerResult: Any?,
        unparsableToDefault: Boolean,
    ): Any? {
        val retType = returnTypeOf(chain.executable)
        return try {
            val json = JSONObject(reply)
            // 不用 optString(name, null)：
            //   ① Kotlin 对它报类型不匹配（Java 签名标 @NonNull，传 null 是平台类型灰色地带）；
            //   ② 若 reply 带 JSON null（{"err":null}），opt 返回 JSONObject.NULL，
            //      String.valueOf 会给出字串 "null"，从而被误判为 JS 错误。
            val errRaw = json.opt("err")
            val err = if (errRaw == null || errRaw === JSONObject.NULL) null else errRaw.toString()
            if (err != null) {
                // JS 抛错（adapter 的 error 分支）：上游语义 = Java 拿返回类型默认值、
                // 原方法**不执行**（旧实现这里错了，改成 proceed）
                hostLog("[lsp-hook] JS_ERR tag=${req.tag} err=$err")
                return defaultValueFor(retType)
            }
            when (strField(json, "kind")) {
                "value" -> return decodeValueReply(retType, json.opt("r"), req)
                "default" -> return defaultValueFor(retType)
                "proceed" -> return if (innerExecuted) lastInnerResult else chain.proceed()
                else -> {
                    hostLog("[lsp-hook] REPLACE_BADKIND tag=${req.tag} kind=" + strField(json, "kind"))
                    if (unparsableToDefault) {
                        defaultValueFor(retType)
                    } else {
                        if (innerExecuted) lastInnerResult else chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            hostLog("[lsp-hook] DECODE_ERR tag=${req.tag} err=" + t.message)
            if (unparsableToDefault) {
                defaultValueFor(retType)
            } else {
                if (innerExecuted) lastInnerResult else chain.proceed()
            }
        }
    }

    // ---- 编解码（契约 §3.2） ----

    private object RET_FALLBACK

    /**
     * 回调载荷（契约 §3.1）：`{"key","args","this","ret","isStatic"}`。
     *
     * 对象值的编码方式随通道而变（adapter 两种都解码，见 BRIDGE-CONTRACT §3.2）：
     *  · rawPtr=true（同步 dispatcher 主路径）：`{"__p":"0x<线程局部引用裸指针>","__c":…}`。
     *    最贴近上游：JS 在**同一条 app 线程**上同步执行，局部引用在回调期间始终有效，
     *    Kotlin 无需创建/释放任何全局引用（JS 侧只读、不释放 = owned=false）。
     *  · rawPtr=false（async rpc fallback）：`{"__h":"0x<全局引用>","__c":…}`，句柄挂进 [refs]，
     *    由调用方 finally 统一 delete（生命周期 = 本次回调，含所有 inner 往返）。
     *  两条都失败 → `{"__o":"<simpleName>@<id>"}` 只读占位。
     *
     * @param refs 出参：本函数创建的全局引用句柄（只有 `__p` 不可用而回退 `__h` 时才有值）
     */
    private fun buildHookPayload(
        chain: XposedInterface.Chain,
        tag: String,
        refs: MutableList<Long>,
        rawPtr: Boolean,
    ): String {
        val ex = chain.executable
        val json = JSONObject()
        json.put("key", tag)
        val args = JSONArray()
        for (a in chain.getArgs()) args.put(encodeValue(a, refs, rawPtr))
        json.put("args", args)
        val thisObj = chain.getThisObject()
        json.put("this", if (thisObj != null) encodeValue(thisObj, refs, rawPtr) else JSONObject.NULL)
        // ret = JVM 描述符（adapter 用它判断返回值兼容性）；Constructor 无 returnType → "V"
        json.put("ret", descriptorOf(returnTypeOf(ex)))
        // isStatic 取**实际值**（反射 Modifier，不采信协议里的声明）：JS 侧要据此决定是否
        // 把 this 当真实实例 wrapper 用；static 方法的 chain.getThisObject() 恒为 null。
        json.put("isStatic", Modifier.isStatic(ex.modifiers))
        return json.toString()
    }

    /**
     * 契约 §3.2 值编码：原始类型/字符串直入 JSON；char → 单字符串；long 超 2^53 → `__i64`
     * 十进制字符串（JSON number 是 double，直接放会**静默丢精度**，而 Java long 是 64 位）；
     * 对象/数组 → `__p`/`__h` 句柄；编码失败 → `__o` 占位。
     *
     * NaN/Infinity 不能进 JSON（org.json 对非法 double 直接抛 JSONException），仍按现状转字符串
     * ——adapter 侧对"该位置出现字符串"要能容忍（否则会变成一次 CAST_FAIL）。
     */
    private fun encodeValue(v: Any?, refs: MutableList<Long>, rawPtr: Boolean): Any = when (v) {
        null -> JSONObject.NULL
        is String -> v
        is Char -> v.toString()
        is Boolean -> v
        is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Long -> if (v > MAX_SAFE_LONG || v < -MAX_SAFE_LONG) {
            hostLog("[lsp-hook] LONG_PRECISION warn |v|>2^53 value=$v (__i64)")
            JSONObject().put("__i64", v.toString())
        } else {
            v
        }
        is Number -> v
        else -> encodeObjectRef(v, refs, rawPtr)
    }

    /**
     * 对象/数组 → `__p`（局部引用裸指针）/ `__h`（全局引用）/ `__o`（占位）三选一。
     *
     * 为何优先 `__p`：同步路径下 JS 与 Kotlin 在同一条 app 线程，jobject 局部引用天然有效，
     * 交给 JS 直接包 wrapper 即可，**零生命周期管理**。`__h` 只在 ptrOf 不可用时兜底。
     */
    private fun encodeObjectRef(v: Any, refs: MutableList<Long>, rawPtr: Boolean): Any {
        val clsName = v.javaClass.name
        if (rawPtr) {
            val p = try {
                GumJsBridge.ptrOf(v)
            } catch (t: Throwable) {
                hostLog("[lsp-hook] PTR_FAIL cls=$clsName err=" + t.message)
                0L
            }
            if (p != 0L) return JSONObject().put("__p", handleHex(p)).put("__c", clsName)
            // ptrOf 不可用/失败 → 落到下面 __h 回退（防护性路径，不能丢参）
        }
        val h = try {
            GumJsBridge.newGlobalRef(v)
        } catch (t: Throwable) {
            hostLog("[lsp-hook] GREF_FAIL cls=$clsName err=" + t.message)
            0L
        }
        if (h != 0L) {
            refs.add(h)
            return JSONObject().put("__h", handleHex(h)).put("__c", clsName)
        }
        // 编码失败：只读占位（契约 §3.2）。**不抛**——一个参数编码失败不该让整次回调失败。
        return JSONObject().put(
            "__o",
            v.javaClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(v))
        )
    }

    /**
     * 回复值解码（契约 §3.2/§3.3）：Number→按返回类型强转；Boolean→boolean；String→String/null；
     * `{"__h":…}` → 本线程局部引用；`{"__i64":…}` → long；其它 → [RET_FALLBACK]（调用方转默认值）。
     */
    private fun decodeRet(retType: Class<*>, r: Any?): Any? {
        return when {
            r == null || r === JSONObject.NULL -> null
            r is JSONObject -> decodeStructured(r, retType)
            // 下面同时比 Java 包装类与 Kotlin 原始类型（如 java.lang.Character vs Char）：
            // 反射拿到的 retType 对 char/int 等方法返回的是原始类型（Character.TYPE），
            // 而包装类场景返回 java.lang.Character —— 两者不等价，必须都查。
            retType == java.lang.String::class.java -> r as? String ?: RET_FALLBACK
            retType == java.lang.Character::class.java || retType == Char::class.java ->
                (r as? String)?.firstOrNull() ?: RET_FALLBACK
            retType == java.lang.Boolean::class.java || retType == Boolean::class.java ->
                r as? Boolean ?: RET_FALLBACK
            retType == java.lang.Integer::class.java || retType == Int::class.java ->
                (r as? Number)?.toInt() ?: RET_FALLBACK
            retType == java.lang.Long::class.java || retType == Long::class.java ->
                (r as? Number)?.toLong() ?: RET_FALLBACK
            retType == java.lang.Short::class.java || retType == Short::class.java ->
                (r as? Number)?.toShort() ?: RET_FALLBACK
            retType == java.lang.Byte::class.java || retType == Byte::class.java ->
                (r as? Number)?.toByte() ?: RET_FALLBACK
            retType == java.lang.Float::class.java || retType == Float::class.java ->
                (r as? Number)?.toFloat() ?: RET_FALLBACK
            retType == java.lang.Double::class.java || retType == Double::class.java ->
                (r as? Number)?.toDouble() ?: RET_FALLBACK
            else -> RET_FALLBACK   // 对象/数组返回：非句柄形态无法转换
        }
    }

    /**
     * 结构化值解码：`__h`（JS 侧 wrapper.$h，可能是 global 或 local ref）→ 本线程局部引用；
     * `__i64`（>2^53 的 long 十进制串）→ 按返回类型收窄。
     *
     * `__h` 的纪律（契约 §3.2）：newLocalRef 拿到局部引用后**立即** deleteGlobalRef。
     * 局部引用独立持有对象（删全局引用不影响它）；不删则每次命中泄漏一个全局引用，
     * 而 ART 的全局引用表很小（默认 512 项），泄漏几十次就足以让整个进程的 JNI 挂掉。
     */
    private fun decodeStructured(obj: JSONObject, retType: Class<*>): Any? {
        val hRaw = obj.opt("__h")
        if (hRaw is String && hRaw.isNotEmpty()) {
            val h = parseHandle(hRaw)
            if (h == 0L) return RET_FALLBACK
            var local: Any? = null
            try {
                local = GumJsBridge.newLocalRef(h)
            } catch (t: Throwable) {
                hostLog("[lsp-hook] JS_REF_ERR handle=$hRaw err=" + t.message)
            } finally {
                runCatching { GumJsBridge.deleteGlobalRef(h) }
            }
            return local ?: RET_FALLBACK
        }
        val i64 = obj.opt("__i64")
        if (i64 is String) {
            val l = i64.toLongOrNull() ?: return RET_FALLBACK
            return when {
                retType == java.lang.Long::class.java || retType == Long::class.java -> l
                retType == java.lang.Integer::class.java || retType == Int::class.java -> l.toInt()
                retType == java.lang.Short::class.java || retType == Short::class.java -> l.toShort()
                retType == java.lang.Byte::class.java || retType == Byte::class.java -> l.toByte()
                retType == java.lang.Float::class.java || retType == Float::class.java -> l.toFloat()
                retType == java.lang.Double::class.java || retType == Double::class.java -> l.toDouble()
                else -> RET_FALLBACK
            }
        }
        return RET_FALLBACK
    }

    /**
     * 原方法结果回投（async 路径的 `nativePostOriginalReply` 载荷，`{"__ret":<E>}`）。
     * 同步主路径没有这个往返（原方法结果由 JS 在同一线程里直接拿到）。
     */
    private fun encodeRet(v: Any?, refs: MutableList<Long>): String {
        return JSONObject().put("__ret", encodeValue(v, refs, rawPtr = false)).toString()
    }

    /** `{"kind":"value","r":…}` 解码：r=null 且返回类型是原始类型 → 该类型默认值（否则拆箱 NPE）。 */
    private fun decodeValueReply(retType: Class<*>, r: Any?, req: HookRequest): Any? {
        if (retType == java.lang.Void.TYPE) return null   // void / 构造器：返回值被框架忽略
        if (r == null || r === JSONObject.NULL) return defaultValueFor(retType)
        val v = decodeRet(retType, r)
        if (v === RET_FALLBACK) {
            hostLog("[lsp-hook] CAST_FAIL tag=" + req.tag + " retType=" + retType.name + " r=" + r)
            // 上游语义：返回值与返回类型不兼容 = Java 调用方拿默认值 + （JS 侧）nextTick 重抛；
            // 原方法**不执行**。Kotlin 只能做到"给默认值"这一半。
            return defaultValueFor(retType)
        }
        return v
    }

    /**
     * 返回类型默认值（契约 §3.3）：原始类型取 0/false/'\u0000'，其它 null。
     *
     * 必须按**原始类型**判：反射对 `boolean m()` 给的是 Boolean.TYPE（不是 java.lang.Boolean），
     * 若要给 null 会被框架拆箱成 NPE —— 这条路径恰恰是"JS 抛错/返回不兼容"的兜底，
     * 一旦自己再抛就等于把异常甩回目标 App。
     */
    private fun defaultValueFor(t: Class<*>): Any? = if (!t.isPrimitive) {
        null
    } else {
        when (t) {
            java.lang.Boolean.TYPE -> false
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null   // void：框架忽略返回值
        }
    }

    /**
     * 被 hook executable 的返回类型。**Constructor 没有 returnType**（Java 反射 API 如此），
     * 而框架对构造器也忽略返回值 → 视为 void（"V"）。
     */
    private fun returnTypeOf(ex: Executable): Class<*> = (ex as? Method)?.returnType ?: Void.TYPE

    /** 释放本回调创建的全局引用（sync / async 两条路径的 finally 都调；幂等、不抛）。 */
    private fun releaseGlobalRefs(refs: MutableList<Long>) {
        if (refs.isEmpty()) return
        for (h in refs) runCatching { GumJsBridge.deleteGlobalRef(h) }
        refs.clear()
    }

    private fun argsSummary(args: List<Any?>?): String {
        if (args == null) return "?"
        return args.joinToString(",") { a ->
            when (a) {
                null -> "null"
                is String -> if (a.length > 48) a.substring(0, 48) + ".." else a
                else -> a.javaClass.simpleName
            }
        }
    }

    // ---- overload sigs 归一化 / 内层参数解码 ----

    /**
     * 解析 async 路径的内层请求：`{"kind":"inner","args":[…]|null}`。
     * args 缺失/JSON null = 原参回退；给了数组就按 parameterTypes 转换（转换失败也回退原参）。
     */
    private fun parseInner(reply: String): JSONObject? {
        if (reply.isEmpty()) return null
        return try {
            val json = JSONObject(reply)
            if (strField(json, "kind") == "inner") json else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 按参数类型把 JSON 数组转换为 Object[]；任何一项不可转换 → null（调用方回退原参） */
    private fun decodeArgs(types: Array<Class<*>>, arr: JSONArray): Array<Any?>? {
        if (arr.length() != types.size) return null
        val out = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val v = decodeRet(types[i], arr.opt(i))
            if (v === RET_FALLBACK) return null
            // 原始类型形参不能传 null：proceed(null) 会在框架里拆箱抛 NPE（异常会甩给目标 App），
            // 因此宁可整组回退"原参"——那也是 this.foo() 的语义基线。
            if (v == null && types[i].isPrimitive) return null
            out[i] = v
        }
        return out
    }

    /**
     * 按 sigs 过滤候选 executable（契约 §2.1：sigs 是该 overload 的**参数类型 className 列表**）。
     * sigs=null → 不过滤（legacy 路径"挂全部"）；空列表 → 精确匹配"无参 overload"，
     * 二者语义不同不能合并（空数组合并成 null 会把 `Foo()` 挂成"全部重载"）。
     */
    private fun filterBySigs(list: List<Executable>, sigs: List<String>?): List<Executable> {
        if (sigs == null) return list
        val want = sigs.map { normalizeSig(it) }
        return list.filter { ex -> ex.parameterTypes.map { descriptorOf(it) } == want }
    }

    /** Java 类型 → JVM 描述符（数组递归） */
    private fun descriptorOf(c: Class<*>): String = when {
        // isArray 为 true 时 componentType 必然非空；Kotlin 无法从 Java 平台类型推断，故显式断言
        c.isArray -> "[" + descriptorOf(c.componentType!!)
        c.isPrimitive -> when (c) {
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Short.TYPE -> "S"
            java.lang.Character.TYPE -> "C"
            java.lang.Void.TYPE -> "V"
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    /**
     * JS 侧 sig 归一化（'I'/'int'/'java.lang.String'/'int[]'/'[I'/'[Ljava.lang.String;' 等形态
     * → JVM 描述符）。
     *
     * 两处修正（REPORT §1.4 的已知缺陷）：
     *  ① 数组形态原来遇到 `[` 直接原样返回 → `"[Ljava.lang.String;"`（点号）与
     *     `descriptorOf` 的 `"[Ljava/lang/String;"`（斜杠）永不相等，表现为必 MISS_OVERLOAD；
     *     现在递归归一化数组元素。
     *  ② `L…;` 形态原来也原样返回（点号同样不归一），现在同样把内部点号换成斜杠。
     */
    private fun normalizeSig(s: String): String {
        val t = s.trim()
        if (t.endsWith("[]")) return "[" + normalizeSig(t.substring(0, t.length - 2))
        if (t.startsWith("[") && t.length > 1) return "[" + normalizeSig(t.substring(1))
        return when (t) {
            "I", "int" -> "I"
            "J", "long" -> "J"
            "F", "float" -> "F"
            "D", "double" -> "D"
            "Z", "boolean" -> "Z"
            "B", "byte" -> "B"
            "S", "short" -> "S"
            "C", "char" -> "C"
            "V", "void" -> "V"
            else -> {
                // "L…;" 已是描述符外壳，只需归一内部点号；裸类名补外壳
                val body = if (t.length > 2 && t.startsWith("L") && t.endsWith(";")) {
                    t.substring(1, t.length - 1)
                } else {
                    t
                }
                "L" + body.replace('.', '/') + ";"
            }
        }
    }

}
