package com.bail.lspfrifa.xposed

/**
 * Frida GumJS 引擎的 Kotlin 封装。
 * 在目标 App 进程内由 XposedModule 调用；宿主 App 不加载本类。
 *
 * 使用方式：
 *   GumJsBridge.init()
 *   GumJsBridge.setMessageCallback(...)
 *   val ok = GumJsBridge.loadScript("console.log('hello')", "demo")
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

    @Synchronized
    fun loadScript(scriptContent: String, scriptName: String = "main"): Boolean {
        if (!init()) return false
        loaded = try {
            // 注入顺序（cap 裁定 2026-08-25）：
            //   ① LSP shim 基础段（最前，仅依赖内建 send）
            //   ② frida-java-bridge bundle（globalThis.Java，保留兼容——其其它 API 经尾段合并保留）
            //   ③ B1 Java 尾段：globalThis.Java = Object.assign(Object.create(null), bundleJava, ourJava)
            //      （ourJava 的 use/perform 覆盖同名；无 bundle 时直接 ourJava）
            //   ④ 用户脚本
            // 官方通道：LSP.hook() 与 Java.use(...).implementation 的 hook 注册均经 on_message
            // 上行到 HookRouter → libxposed hook()（LSPlant 引擎），彻底绕开 frida-java-bridge
            // 在 A15/ART35 的 ArtMethod entry 替换脆弱性。
            val combined = buildString {
                append(LSP_SHIM_BASE)
                append("\n;\n")
                if (JavaBridgeBundle.SCRIPT.isNotBlank()) {
                    append(JavaBridgeBundle.SCRIPT)
                    append("\n;\n")
                }
                append(LSP_SHIM_JAVA)
                append("\n;\n")
                append(scriptContent)
            }
            nativeLoadScript(combined, scriptName)
        } catch (t: Throwable) {
            false
        }
        return loaded
    }

    /**
     * JS 侧官方通道 shim【基础段】（最前注入，与 B1 前行为完全一致、零改动）：
     * LSP.hook(cls, method, tag, act) / LSP.toast(msg) / LSP.unhookAll() → GumJS send → Kotlin HookRouter。
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
     * RouteB B1 Java 代理段（注入尾序：bundle 之后、用户脚本之前）。契约=cap 冻结 2026-08-25：
     * - 用户 API：`Java.perform(fn)`=直接执行 fn()（无 VM attach 依赖，可多段）；
     *   `Java.use(cls)`=ClassWrapper（per-cls 缓存、不预查类——MISS 由 HookRouter 注册时路由）；
     *   `clazz.method.implementation = fn|null`（fn 返回 undefined=观察语义自动 proceed；
     *   非 undefined=替换返回；null=移除；重复赋值=重注册）；
     *   tag 约定=`<cls>#<method>`（overload 精确选择时为 `<cls>#<method>#<sigs 逗号串>`）；
     *   `clazz.method.overload('I','java.lang.String').implementation = fn|null`（B2 精确选择；
     *   缺省不调 overload()=挂全部 overload，现状零改动）；
     * - 回复通道：`rpc.exports.__lspHookReply(id, argsJson)` 返回 `JSON.stringify({over,r})`（async fn
     *   返回 Promise 亦支持——dispatcher 等待后 reply）；其余由 message-dispatcher 自动发标准 frida:rpc
     *   reply（`["frida:rpc",id,"ok",value]`）；抛异常 → 自动 error reply → cpp 按超时兜底 proceed。
     * - this 桥（t9+B2，cap 裁定更新：不再抛错）：`this.<method>(...)`（仅 ClassWrapper 已物化方法名，
     *   判定=__lspClassMeta[cls].methods）→ send `{t:"lsp.rpc_inner", id:当前rpc id, args:[自定义参数]}`（B2：
     *   参数编码后上行；空数组=未提供 → Kotlin 安全回退原参）+ 返回 Promise；
     *   原方法结果经 `rpc.exports.__lspHookOriginalReply(X, retJson)` 回投 resolve（per-id FIFO）；
     *   其它属性访问/赋值仍抛可读 Error。
     * - argsJson 契约（Kotlin 侧构建，t7 对齐）：`{"key":"<cls>#<method>","args":[...],"this":{...}|null}`；
     *   args 编码=Facts §5：基础类型直 JSON，对象/数组 → `{"__obj":"<simpleName>@<addr>"}` 占位（原样透传）。
     */
    private val LSP_SHIM_JAVA: String = """
        (function () {
          var __lspClassCache = Object.create(null);
          var __lspUserFns = Object.create(null);   // key=cls#method -> fn（intercept 查表入口）
          var __lspRegistered = Object.create(null); // key -> true（曾注册；重复赋值先发 unhook）

          function __lspErr(msg) { return new Error("[lsp] " + msg); }
          function __lspKey(cls, method) { return cls + "#" + method; }
          function __lspUnsupported(what) {
            return __lspErr("unsupported: " + what + "（RouteB B1 不实现，请用返回值/参数处理或 observe 语义）");
          }

          // ---- t9+B2：async this.method 桥（cap 裁定更新：不再抛错，统一 await 桥；B2 支持自定义参数）----
          // this.<method>(...)（仅 ClassWrapper 上已物化过的方法名）→ send({t:"lsp.rpc_inner", id:<当前rpc id>,
          //   args:[...]})（args 为 B2 新增：自定义参数编码；空数组=未提供）→ 返回 Promise；
          // 原方法结果经 __lspHookOriginalReply(同 id) 回投 resolve。
          // 字段契约（t15）：JS 上行 {t:"lsp.rpc_inner", id:X, args:数组|null}（GumJS send 自动包 {type:"send",payload}）；
          // C→JS：["frida:rpc", origId, "call", ["__lspHookOriginalReply", [X, retJson]]]。
          var __lspInnerPending = Object.create(null); // id -> [{resolve,reject}]（FIFO：Kotlin 串行服务，回复按序）
          var __lspClassMeta = Object.create(null);    // cls -> {methods}（已物化方法名集合，this 代理判定源）

          // B2 参数编码（与 Kotlin encodeValue 同构的子集）：基础类型直 JSON；数组递归；占位/其它对象 → 占位
          function __lspEncodeArg(v) {
            if (v === null || v === undefined) { return null; }
            var t = typeof v;
            if (t === "number" || t === "string" || t === "boolean") { return v; }
            if (t === "object") {
              if (Array.isArray(v)) { return v.map(__lspEncodeArg); }
              if (v.__obj !== undefined) { return v; }   // Kotlin 侧占位透传
              return { __obj: "Object@js" };             // 其它对象 → 占位（Kotlin CAST_FAIL → 安全回退原参）
            }
            return { __obj: "Function@js" };
          }
          function __lspEncodeArgs(list) { return list.map(__lspEncodeArg); }

          function __lspInnerBridge(rpcId, method) {
            return function () {
              var args = Array.prototype.slice.call(arguments, 0);
              var q = __lspInnerPending[rpcId] || (__lspInnerPending[rpcId] = []);
              return new Promise(function (resolve, reject) {
                var item = { resolve: resolve, reject: reject };
                q.push(item);
                try {
                  // B2：args 随消息上行（空数组=未提供参数 → Kotlin 安全回退原参）
                  send({ t: "lsp.rpc_inner", id: rpcId, args: __lspEncodeArgs(args) });
                } catch (e) {
                  var i = q.indexOf(item);
                  if (i >= 0) { q.splice(i, 1); }
                  reject(e);
                }
              });
            };
          }

          // this 占位（B1 v2）：方法名（ClassWrapper 已物化）→ await 桥；其它属性访问/赋值 → 可读错误。
          function __lspThisPlaceholder(key, rpcId) {
            var cls = String(key).split("#")[0];
            var meta = __lspClassMeta[cls];
            return new Proxy(Object.create(null), {
              get: function (t, k) {
                if (typeof k !== "string") { return undefined; }
                if (meta && meta.methods[k]) {
                  return __lspInnerBridge(rpcId, k);
                }
                throw new Error("RouteB B1 暂不支持 this." + String(k) + "（非 ClassWrapper 方法名）：请改用返回值或参数处理");
              },
              set: function () {
                throw new Error("RouteB B1 暂不支持 this 属性赋值：请改用返回值或参数处理");
              }
            });
          }

          // 宿主（cpp → frida:rpc）唯一回调：message-dispatcher 调本函数并自动回复其返回值。
          // rpc 由运行时 core.js/message-dispatcher 提供（Facts §3 实核）；未提供时仅替换通道退化，
          // 其余 shim 能力正常（Java.use 注册/观察语义经 send 照常上行）。
          if (typeof rpc !== "undefined" && rpc.exports) {
            rpc.exports.__lspHookReply = function (id, argsJson) {
              var req = JSON.parse(argsJson);
              var fn = __lspUserFns[req.key];
              if (typeof fn !== "function") {
                return JSON.stringify({ over: false, r: null });
              }
              var r = fn.apply(__lspThisPlaceholder(req.key, id), req.args || []);
              if (r && typeof r.then === "function") {
                // async fn：返回 Promise → dispatcher 支持 then/catch 后自动 reply（Facts §3）
                return r.then(function (v) {
                  if (v === undefined) { return JSON.stringify({ over: false, r: null }); }
                  return JSON.stringify({ over: true, r: v });
                }, function (e) { throw e; });
              }
              if (r === undefined) {
                return JSON.stringify({ over: false, r: null }); // 观察语义：自动 proceed
              }
              return JSON.stringify({ over: true, r: r });        // 替换语义：跳过原方法
            };
            // 原方法结果回投（X 与 rpc_inner 上行同源；per-id FIFO 取首个挂起 Promise resolve）
            // B2：解包 __ret——用户 `return this.bar(a)` 应拿到原始结果而非 {"__ret":...} 包装
            rpc.exports.__lspHookOriginalReply = function (X, retJson) {
              var key = String(X);
              var q = __lspInnerPending[key];
              if (!q || q.length === 0) { return undefined; } // 未命中：静默防泄漏（cpp 已兜底）
              var item = q.shift();
              var w;
              try {
                w = JSON.parse(retJson);
              } catch (e) {
                item.reject(e);
                if (q.length === 0) { delete __lspInnerPending[key]; }
                return undefined;
              }
              try {
                item.resolve((w && w.__ret !== undefined) ? w.__ret : w);
              } catch (e) {
                item.reject(e);
              }
              if (q.length === 0) { delete __lspInnerPending[key]; }
              return undefined; // 自动 reply（undefined）由 cpp 静默消费
            };
          } else {
            console.log("[lsp] WARN: rpc global 不可用，替换通道降级为观察语义");
          }

          // B2：MethodWrapper 增加 overload(...sigs) 精确选择（缺省=挂全部，现状零改动）。
          // selKey = <cls>#<method>#<sigs 逗号串>（Kotlin 侧 tag）；lsp.hook 携带 sigs 数组供过滤。
          function __lspMethodWrapper(cls, method) {
            var key = __lspKey(cls, method);
            var impl;
            var w = Object.create(null);

            function __lspApply(selKey, sigs, v) {
              if (v === null) {
                if (__lspRegistered[selKey]) {
                  delete __lspRegistered[selKey];
                  delete __lspUserFns[selKey];
                  try { send({ t: "lsp.unhook", cls: cls, method: method, tag: selKey }); } catch (e) {}
                }
                return;
              }
              if (typeof v !== "function") { throw __lspErr("implementation 必须是函数或 null"); }
              __lspUserFns[selKey] = v;
              if (__lspRegistered[selKey]) {
                // 重复赋值=重注册：先卸旧 handle（Kotlin 侧按 key 幂等 + 重挂即生效）
                try { send({ t: "lsp.unhook", cls: cls, method: method, tag: selKey }); } catch (e) {}
              }
              __lspRegistered[selKey] = true;
              try {
                var msg = { t: "lsp.hook", cls: cls, method: method, tag: selKey, act: "", mode: "replace" };
                if (sigs !== null) { msg.sigs = sigs; }
                send(msg);
              } catch (e) {
                console.log("[lsp] hook send failed: " + e);
              }
            }

            Object.defineProperty(w, "implementation", {
              configurable: true, enumerable: true,
              get: function () { return impl; },
              set: function (v) {
                impl = (v === null ? undefined : v);
                __lspApply(key, null, v);
              }
            });
            Object.defineProperty(w, "overload", {
              configurable: true, enumerable: true,
              value: function () {
                var sigs = Array.prototype.slice.call(arguments, 0).map(String);
                if (sigs.length === 0) { throw __lspErr("overload 需要至少一个类型参数（如 'I'/'java.lang.String'）"); }
                var selKey = key + "#" + sigs.join(",");
                var sel = Object.create(null);
                Object.defineProperty(sel, "implementation", {
                  configurable: true, enumerable: true,
                  get: function () { return __lspUserFns[selKey]; },
                  set: function (v) { __lspApply(selKey, sigs, v); }
                });
                return sel;
              }
            });
            return w;
          }

          function __lspClassWrapper(cls) {
            var cached = __lspClassCache[cls];
            if (cached) { return cached; }
            var methods = Object.create(null);
            var w = new Proxy(Object.create(null), {
              get: function (t, k) {
                if (typeof k !== "string") { return undefined; }
                if (k === "${'$'}new" || k === "${'$'}className" || k === "value" || k === "constructor") {
                  throw __lspUnsupported(k);
                }
                if (k in methods) { return methods[k]; }
                var mw = __lspMethodWrapper(cls, k);
                methods[k] = mw;
                return mw;
              },
              set: function (t, k, v) {
                throw __lspUnsupported("类字段 " + String(k) + " 赋值");
              }
            });
            __lspClassMeta[cls] = { methods: methods }; // this 代理方法名判定源（同 ClassWrapper 缓存）
            __lspClassCache[cls] = w;
            return w;
          }

          var ourJava = Object.create(null);
          ourJava.perform = function (fn) {
            if (typeof fn !== "function") { throw __lspErr("Java.perform 需要函数参数"); }
            return fn();
          };
          ourJava.use = function (cls) {
            if (typeof cls !== "string" || cls.length === 0) { throw __lspErr("Java.use 需要类名字符串"); }
            return __lspClassWrapper(cls);
          };

          // 归属合并（cap 裁定）：use/perform 覆盖同名；bundle 其它 API 保留兼容；无 bundle 时直接 ourJava
          var bundleJava = (typeof globalThis.Java === "object" && globalThis.Java !== null) ? globalThis.Java : null;
          var merged = Object.assign(Object.create(null), bundleJava, ourJava);
          // B1 明确不实现的 bundle 专有 API：bundle 缺失时给可读错误（避免静默 undefined）
          ["choose", "register", "openClassFile", "array", "type"].forEach(function (name) {
            if (!(name in merged)) {
              Object.defineProperty(merged, name, {
                configurable: true, enumerable: true,
                value: function () { throw __lspUnsupported("Java." + name); }
              });
            }
          });
          globalThis.Java = merged;
        })();
    """.trimIndent()

    @Synchronized
    fun unloadScript() {
        if (loaded) {
            try { nativeUnloadScript() } catch (_: Throwable) {}
            loaded = false
        }
    }

    /**
     * RouteB B1 替换通道（Kotlin 侧入口，HookRouter.replace 分支调用）：
     * intercept 线程 → 登记请求（id）→ gum_script_post(frida:rpc call) → 条件变量等待（≤500ms）
     * → JS 结果经 message-dispatcher 自动标准 reply → 本函数返回 reply 的 value JSON
     * （`{"over":bool,"r":…}`；error 时为 `{"over":false,"err":…}`；内层请求为 `{"__inner":true}`）；
     * 超时/异常 → 返回 ""（调用方回退 chain.proceed()）。
     */
    fun callJs(id: String, payload: String): String = try {
        nativeCallJs(id, payload)
    } catch (_: Throwable) {
        ""
    }

    /** RouteB：原方法结果回投 JS（async fn 内 this.method 的 resolve；fire-and-forget，无返回值）。 */
    fun postOriginalReply(id: String, retJson: String) {
        try {
            nativePostOriginalReply(id, retJson)
        } catch (_: Throwable) {
            // native 未就绪时忽略（回退超时路径）
        }
    }

    /** C 层 on_message 通过此函数回传，勿手动调用 */
    @JvmStatic
    fun dispatchMessage(message: String) {
        _messageCallback?.onScriptMessage(message)
    }

    // ---- JNI 绑定（签名须与 gumjs_bridge.cpp 严格一致）----

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
}
