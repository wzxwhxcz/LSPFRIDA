# RouteB-Facts：路线B 实核事实簿（cap 第一手，2026-08-25）
> 供 t6/t7 实现直接引用。全部为源码级实核（本地 api-src / 官方 devkit 17.9.3 tarball / frida-gum 17.9.3 tag 源码），非推断。临时实核材料在 .tmp-ref/（frida-gumjs.h、frida-gum-17.9.3/、devkit.tar.xz）。

## 1. libxposed api 102.0.0 HookChain（源码：~/miuix-src/api-src/io/github/libxposed/api/XposedInterface.java）
- `interface Chain`（L190-263）：`getExecutable()` / `getThisObject()`（static=null）/ `getArgs(): List<Object>`（不可变）/ `getArg(int)` / `proceed(): Object` / `proceed(Object[] args)` / `proceedWith(Object thisObject)` / `proceedWith(Object thisObject, Object[] args)`。
- **无 setArgs/setResult/getResult**。
- `interface Hooker`（L271）：`Object intercept(Chain chain) throws Throwable`——**返回值=方法最终返回值**（源码注释："return the result to be returned from the interceptor. If the hooker does not want to change the result, it should call chain.proceed() and return its result. For void methods and constructors, the return value is ignored by the framework."）；**不调 proceed=原方法不执行**；异常=传播。
- `interface HookHandle`（L294）：`getExecutable()` / `unhook()`（幂等）。

## 2. frida-gum 17.9.3 devkit（官方 tarball frida-gumjs-devkit-17.9.3-android-arm64.tar.xz）
- 唯一头 `frida-gumjs.h`（3.3MB 自包含）：`gum_script_post(GumScript*, const gchar* message, GBytes* data)`（头 L86990）；`gum_script_set_message_handler`（L86987）；`gum_script_scheduler_push_job_on_js_thread`（L87035）；`gum_script_scheduler_get_js_context`（L87032）。
- **无公开"直接调用 JS 函数"API**（gumjs_* 为内部私有）。

## 3. post 语义链（frida-gum 17.9.3 源码：bindings/gumjs/gumquickscript.c + gumquickcore.c + runtime/message-dispatcher.js）
- `gum_quick_script_post`（gumquickscript.c≈L1000）→ `gum_script_scheduler_push_job_on_js_thread(scheduler, G_PRIORITY_DEFAULT, do_post, d, destroy)`——**异步投递 JS 线程**（不在调用线程执行 JS）。
- JS 线程执行 `_gum_quick_core_post`（gumquickcore.c L1881）→ `gum_quick_message_sink_post(incoming_message_sink, message, data, &scope)`。
- incoming sink 注册（runtime message-dispatcher.js）：`initialize()` → `_setIncomingMessageCallback(handleMessage)`；`handleMessage(raw)`：`JSON.parse(raw)`；`msg[0]==="frida:rpc"` → `handleRpcMessage(msg[1]=id, msg[2]=operation, params=msg.slice(3), data)`；`operation=="call"` → `rpc.exports[params[0]].call(exports, ...params[1], data)` → `reply(id, 'ok'|'error', ...)`（JS→C send 回程；Promise 支持：then/catch 后 reply）。
- **闭合方案**：C 线程 `gum_script_post(JSON.stringify(["frida:rpc", <id>, "call", ["__lspHookReply", [<id>, <argsJson>]]]))` → JS 自动调 `rpc.exports.__lspHookReply(id, argsJson)`（**shim JS 必须注册该 export**）→ reply 经现有 send（set_message_handler 链路）回 C。
- `rpc` 全局对象由 runtime core.js 提供；rpc.exports 仅能经 message 协议调用（无直接 C API）。

## 4. 线程模型（t7 实现必须遵守）
```
Java 拦截线程 A（HookRouter intercept 内）
  └─ argsJson=encodeArgs(chain.getArgs()) → JNI nativeCallJs(id, argsJson): String
     └─ cpp: GumPending{cond(GMutex), result, done} 入 pending map（id 唯一自增）
        └─ gum_script_post(rpc-call) ← 异步，A 不阻塞于此；然后等 cond（≤500ms）
           ├─ 超时 → 返回 {"over":false}（保证原方法执行，不卡死目标）
           └─ 唤醒 → 返回 JS 结果 JSON
JS 线程 B：handleMessage → rpc call → __lspHookReply(id, argsJson)
  └─ shim 解码 args → 用户 fn → 编码 {over, r} → send({t:"lsp.rpc_reply", id, over, r})
C message handler（JS 线程回调）: 解析 reply → pending map 取 id → write result + signal
A 唤醒 → 解码 → over? 返回转换后的 r（不 proceed）: chain.proceed() 返回原值
```
- 死锁安全：A 只等 B；B 不依赖 A（send 后不需要 A 确认）；B 繁忙 → A 超时兜底；无环。
- 并发（B1 冻结 = 全局串行，2026-08-25 采纳 t5 论证）：单互斥 + 单在途——多 Java 线程拦截退化为排队（≤500ms/个；热方法命中会排队/超时，replace 语义仅建议低频方法，与 t2 P1 限频合并）；正确性可证、复杂度低。per-id 状态机并发升级 = P2（价值：高并发热方法，收益不明确）。
- 生命周期：unload/reload 清空 pending（全部 signal + 超时默认值）。

## 5. B1 语义定案（cap 拍板 2026-08-25——t6/t7 按此实现，勿再争论）
**重入事实**：`this.method(...)`（fn 内同步调原方法）在"post 队列 + A 同步等待"架构下必然死锁（JS 线程忙执行外层 fn，内层 rpc 排队；A 等内层 reply，fn 等内层结果）；QuickJS 单线程无法嵌套处理；frida 本尊用其私有 native 重入能力（devkit 未公开）。
**B1 定案（语义等值度 ≈90%）**：
- `implementation = function(...)` 返回值语义：
  - fn 返回 undefined（未显式 return）→ **自动执行原方法**（chain.proceed() 返回原值；"观察继续"友好默认。frida 差异注明：frida 下不调原方法=返回 null）；
  - fn 返回非 undefined → **替换返回**（不 proceed，跳过原方法；类型转换后作为方法返回值——frida 语义一致）。
- **B1 this.method 支持规则（cap 融合裁定 2026-08-25，采纳 t5 async 契约）**：仅 **async implementation**（fn 返回 Promise）内可用——`await this.method(...)`：async 挂起让出 JS 线程 → 内层 rpc（复用同 id 嵌套 pending，cpp pending map 天然支持递归）可被处理 → **无死锁**（message-dispatcher 原生支持 Promise reply，源码 confirmed）；**同步 fn** 内访问 this.method → 抛可读错误（"RouteB B1: 同步 fn 内不支持 this.method()，请改 async function 或用返回值处理"→ error reply → A 超时自动 proceed，目标不崩）。判断依据：fn 调用后返回值为 Promise（shim 以返回值类型判定，不依赖构造器名）。
- **B1 不支持**：①同步 fn 内 this.method(...)（上条）；②改参（留 B2）。
- JSON：args 编码 = 基础类型直 JSON；对象/数组 → {"__obj":"<simpleName>@<addr>"} 占位；r 解码 Number→int/long/float/double（按签名匹配类型；long>2^53 警告）、Boolean→boolean、String→String、null→null。
- 注册消息：lsp.hook 加 `mode:"replace"`（缺省 observe=现状不变）；replace 回调带 hookedMethodSignature（Chain.getExecutable().toString()，供 r 类型转换）。
- reply 通道：C→JS 走 §3 frida:rpc；JS→C 复用现有 send（{t:"lsp.rpc_reply", id, over, r}）。
