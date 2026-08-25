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
 * 链路（observe，与现状零差异）：
 *   JS: LSP.hook(cls, method, tag) → send → on_message → 本路由 tryHandle()
 *   → Class.forName + 反射取方法 → hook(executable).intercept(Hooker) → 命中 hostLog。
 *
 * 链路（RouteB B1 replace，t7）：
 *   JS: Java.use("x.y").m.implementation = fn → send({t:"lsp.hook", mode:"replace", tag:"x.y#m"})
 *   → 本路由按 mode 建 replace intercept：
 *      chain.getArgs() 编码 → GumJsBridge.callJs(id, payload)（cpp 登记 pending + frida:rpc post）
 *      → JS rpc.exports.__lspHookReply(id, argsJson) 执行用户 fn → dispatcher 自动标准 reply
 *      → cpp on_message 消费 reply（命中 pending 才回传 Kotlin，其余静默）→ callJs 返回：
 *        ""                → 超时/异常 → chain.proceed()（原参，原方法必执行——目标不崩）
 *        {"__inner":true[,"args":[...]]} → async fn 内 await this.method(...) 原调用请求（B2：args 为
 *                            自定义参数，按 parameterTypes 转换后 proceed(convArgs)；无 args/不可转换
 *                            → 安全回退原参 proceed()）→ nativePostOriginalReply
 *                            → 再次 callJs(id,"") 续等最终 reply（同 id 栈复用，最多 500ms/阶段）
 *        {"over":false}    → fn 返回 undefined（观察语义）→ chain.proceed() 返回原值
 *        {"over":true,"r":…} → 解码 r（按 executable 返回类型转换）→ return r（跳过原方法）
 *
 * B1/B2 已定案（Facts §5 / cap 裁定）：对象参数/返回为占位/透传（CAST_FAIL 安全回退原参）；
 * 同步 fn 内 this.method → shim 抛可读错误 → error reply → 按 over=false 处理。
 * B2（t15）：overload('I','java.lang.String') 精确选择（sigs 过滤）+ this.method 自定义参数。
 */
class HookRouter(
    private val targetPackage: String,
    private val targetLoader: ClassLoader,
    private val appContext: Context? = null,
    private val hooker: (Executable) -> XposedInterface.HookBuilder,
    private val hostLog: (String) -> Unit,
) {

    private data class HookRequest(
        val clsName: String,
        val methodName: String,
        val tag: String,
        val act: String,
        val mode: String,
        /** B2：overload 精确选择（参数类型数组，如 ["I","java.lang.String"]）；null=挂全部 */
        val sigs: List<String>? = null,
    )

    /** 已挂 handle（key = cls#method#sig#tag；t7 修复 t2 P0#1：签名入键，overload 不再互相覆盖/泄漏） */
    private val handles = ConcurrentHashMap<String, XposedInterface.HookHandle>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** replace 全局串行互斥（B1 冻结）：一次一个在途 replace 请求；并发命中排队（≤500ms/个） */
    private val replaceLock = Any()

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

    private fun unhookByTag(tag: String) {
        val matched = handles.keys.filter { it.endsWith("#$tag") }
        matched.forEach { key ->
            handles.remove(key)?.let { h -> runCatching { h.unhook() } }
        }
        if (matched.isNotEmpty()) hostLog("[lsp-hook] UNHOOKED tag=$tag count=${matched.size}")
    }

    /**
     * P1：卸载全部已注册 LSPlant 手柄（HookHandle.unhook 框架 API，幂等）。
     * 用于脚本热更：重载前不清理旧 hook，会导致同方法重复拦截语义残留。
     */
    fun unhookAll() {
        val n = handles.size
        handles.values.forEach { h -> runCatching { h.unhook() } }
        handles.clear()
        hostLog("[lsp-hook] UNHOOKED all=$n")
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
        val ctx = appContext ?: return true
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
            hostLog("[lsp-hook] MISS class=${req.clsName} (not loaded by ${targetLoader.javaClass.name}; use a loaded class)")
            return
        } catch (t: Throwable) {
            hostLog("[lsp-hook] MISS_ERR class=${req.clsName} err=${t.message}")
            return
        }

        val allMethods = clazz.declaredMethods.filter { it.name == req.methodName }
        if (allMethods.isEmpty()) {
            // 方法不是"本类直接声明"（如只写接口方法名）或重载名不存在：提示而非静默
            hostLog("[lsp-hook] MISS method=${req.clsName}#${req.methodName} (declared methods only)")
            return
        }

        // B2：overload 精确选择——按 parameterTypes 描述符串过滤（缺省 sigs=null 时挂全部，现状零改动）
        val methods: List<Method> = if (req.sigs == null) {
            allMethods
        } else {
            val want = req.sigs.map { normalizeSig(it) }
            allMethods.filter { m ->
                m.parameterTypes.map { descriptorOf(it) } == want
            }.also { filtered ->
                if (filtered.isEmpty()) {
                    hostLog(
                        "[lsp-hook] MISS_OVERLOAD ${req.clsName}#${req.methodName} sigs=${req.sigs} " +
                            "(found=${allMethods.size} overloads)"
                    )
                }
            }
        }

        var armed = 0
        var failed = 0
        for (m in methods) {
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

    /** replace（RouteB B1）：参数编码 → 同步 JS 调用 → 按回复决定 proceed/替换返回。 */
    private fun interceptReplace(chain: XposedInterface.Chain, req: HookRequest): Any? {
        return synchronized(replaceLock) {
            try {
                val payload = buildHookPayload(chain, req.tag)
                val id = "lsp-${reqSeq.incrementAndGet()}"
                var reply = GumJsBridge.callJs(id, payload)

                // 嵌套服务循环：async fn 内 await this.method(...)（同 id 栈复用；B2 支持自定义参数）。
                // innerExecuted/lastInnerResult：原方法已在内层执行过 → 后续超时/观察/CAST/JS_ERR
                // 一律复用其结果（**不再二次 chain.proceed()——否则原方法执行两遍**）。
                var innerExecuted = false
                var lastInnerResult: Any? = null
                var inner = parseInnerArgs(reply)
                while (inner != null) {
                    // B2：非空 args 且按参数类型转换成功 → proceed(convArgs)；否则安全回退原参
                    val method = chain.executable as? Method
                    val conv = if (inner.length() > 0 && method != null) {
                        decodeArgs(method.parameterTypes, inner)
                    } else null
                    val origResult = if (conv != null) chain.proceed(conv) else chain.proceed()
                    lastInnerResult = origResult
                    innerExecuted = true
                    GumJsBridge.postOriginalReply(id, encodeRet(origResult))
                    reply = GumJsBridge.callJs(id, "")
                    inner = parseInnerArgs(reply)
                }

                when {
                    reply.isEmpty() -> {
                        hostLog("[lsp-hook] REPLACE_TIMEOUT tag=${req.tag} id=$id")
                        if (innerExecuted) lastInnerResult else chain.proceed()
                        // 超时兜底：内层未执行 → 原方法必执行；已执行 → 复用其结果
                    }
                    else -> decodeReply(reply, chain, req, innerExecuted, lastInnerResult)
                }
            } catch (t: Throwable) {
                // 注意：不在此处再次 chain.proceed()——若异常来自原方法（proceed 抛出），
                // 再 proceed 会导致原方法二次执行；按框架语义直接上抛（protective 下由框架捕获记录）。
                hostLog("[lsp-hook] REPLACE_ERR tag=${req.tag} err=${t.message}")
                throw t
            }
        }
    }

    private fun decodeReply(
        reply: String,
        chain: XposedInterface.Chain,
        req: HookRequest,
        innerExecuted: Boolean,
        lastInnerResult: Any?,
    ): Any? {
        return try {
            val json = JSONObject(reply)
            val err = json.optString("err", null)
            if (err != null) {
                hostLog("[lsp-hook] JS_ERR tag=${req.tag} err=$err")
                // JS 抛错：内层已执行 → 复用原方法结果（不二次执行）；否则 proceed
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            if (!json.optBoolean("over")) {
                // 观察语义（返回原值）：内层已执行 → 该值即原方法结果；否则 proceed
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            val r = json.opt("r")
            val retType = (chain.executable as? Method)?.returnType ?: Void.TYPE
            if (retType == Void.TYPE) {
                return null   // void：返回被框架忽略
            }
            val v = decodeRet(retType, r)
            if (v === RET_FALLBACK) {
                hostLog("[lsp-hook] CAST_FAIL tag=${req.tag} retType=${retType.simpleName} r=$r")
                return if (innerExecuted) lastInnerResult else chain.proceed()
            }
            v
        } catch (t: Throwable) {
            hostLog("[lsp-hook] DECODE_ERR tag=${req.tag} err=${t.message}")
            if (innerExecuted) lastInnerResult else chain.proceed()
        }
    }

    // ---- 编解码 ----

    private object RET_FALLBACK

    /** argsJson 契约（与 t6 shim 严格一致）：{"key":tag,"args":[...],"this":{...}|null} */
    private fun buildHookPayload(chain: XposedInterface.Chain, tag: String): String {
        val json = JSONObject()
        json.put("key", tag)
        val args = JSONArray()
        val raw = chain.getArgs()
        for (a in raw) args.put(encodeValue(a))
        json.put("args", args)
        val thisObj = chain.getThisObject()
        json.put("this", if (thisObj != null) encodeValue(thisObj) else JSONObject.NULL)
        return json.toString()
    }

    /**
     * 编码（Facts §5）：基础类型直 JSON；对象/数组 → {"__obj":"<simpleName>@<addr>"} 占位
     * （B1 只读不解析；addr = System.identityHashCode 十六进制，进程内稳定）。
     */
    private fun encodeValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is String -> v
        is Char -> v.toString()
        is Boolean -> v
        is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Number -> {
            if (v is Long && (v > MAX_SAFE_LONG || v < -MAX_SAFE_LONG)) {
                hostLog("[lsp-hook] LONG_PRECISION warn |v|>2^53 value=$v")
            }
            v
        }
        else -> JSONObject().put(
            "__obj",
            "${v.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(v))}"
        )
    }

    /** r 解码（Facts §5）：Number→按返回类型强转；Boolean→boolean；String→String/null；其它→fallback */
    private fun decodeRet(retType: Class<*>, r: Any?): Any? {
        return when {
            r == null || r === JSONObject.NULL -> null
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
            else -> RET_FALLBACK   // 对象/数组返回：B1 不支持（透传原值）
        }
    }

    /** 原方法结果编码（nativePostOriginalReply 回投载荷）——基础类型透传、对象 → 占位。 */
    private fun encodeRet(v: Any?): String {
        return JSONObject().put("__ret", encodeValue(v)).toString()
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

    // ---- B2：overload sigs 归一化 / 内层参数解码 ----

    /** 解析内层标记：{"__inner":true[, "args":[…]]} → args（无 args 字段/非数组 → null=原参回退） */
    private fun parseInnerArgs(reply: String): JSONArray? {
        return try {
            val json = JSONObject(reply)
            if (!json.optBoolean("__inner")) null else json.optJSONArray("args")
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
            out[i] = v
        }
        return out
    }

    /** Java 类型 → JVM 描述符（数组递归） */
    private fun descriptorOf(c: Class<*>): String = when {
        c.isArray -> "[" + descriptorOf(c.componentType)
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

    /** JS 侧 sig 归一化（'I'/'int'/'java.lang.String'/'int[]'/'[I' 等形态 → JVM 描述符） */
    private fun normalizeSig(s: String): String {
        val t = s.trim()
        if (t.endsWith("[]")) return "[" + normalizeSig(t.substring(0, t.length - 2))
        if (t.startsWith("[") && t.length > 1) return t   // 已描述符形态
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
            else -> if (t.startsWith("L") && t.endsWith(";")) t else "L" + t.replace('.', '/') + ";"
        }
    }

    private companion object {
        const val MAX_SAFE_LONG = 9007199254740992L   // 2^53
    }
}
