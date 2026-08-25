# RouteB-Plan：路线B 实现方案（t6/t7 依据）——对齐 docs/RouteB-Facts.md + cap 补充裁定

> 本卷 = 实现落地方案。**事实性结论以 docs/RouteB-Facts.md 为准（cap 第一手源码实核）**；
> 本卷按 cap 补充裁定（2026-08-25：rpc reply 走标准协议、cpp 原生消费、用户 API 形态、pending 生命周期）编排：
> 实现清单三大块 = ①协议 ②线程模型 ③cpp 消费 reply。

---

## 0. 目标（一句话）

把 `Java.use("x.y").m.implementation = fn` 的 frida 语义接到官方通道
（`LSP.hook → HookRouter → libxposed hook()` = LSPlant），**B1 语义等值度 ≈90%**（Facts §5）。

**用户 API 形态（cap ③，冻结）**：
```js
Java.perform(function(){ ... });            // 直接执行 fn
var C = Java.use("com.example.Foo");        // 轻量代理
C.bar.implementation = function(a){ ... };  // observe：返回 undefined = 观察继续；返回非 undefined = 替换返回
C.bar.implementation = null;                // 卸载（同 tag 全卸）
// B1 无 overload() 选择 API（同名多 overload = 全部挂，语义=frida 差异注明）
```

---

## 1. 通信协议（块①：协议冻结）

### 1.1 C→JS（gum_script_post，异步投递 JS 线程，立即返回）

| # | 用途 | 投递内容（JSON 数组，frida:rpc 文语） |
|---|---|---|
| 1 | 触发替换处理器 | `["frida:rpc", X, "call", ["__lspHookReply", [X, argsJson]]]` |
| 2 | 原方法结果回 JS（async fn 的 `await this.method`） | `["frida:rpc", X2, "call", ["__lspHookOriginalReply", [X, retJson]]]`（X2=新 rpc id，无 pending，**fire-and-forget**；其自动 reply 被 C 无感吞掉） |

### 1.2 JS→C

| # | 用途 | 载体 |
|---|---|---|
| a | **rpc 回复（标准协议，dispatcher 自动，JS 不自造）** | `["frida:rpc", X, "ok", {over, r}]` / `["frida:rpc", X, "error", msg, [name,stack]]`（__lspHookReply 抛错时自动走 error） |
| b | async fn 内 `this.method()` 原调用请求（JS 唯一主动上行） | `{"type":"send","payload":{"t":"lsp.rpc_inner","id":X,"args":[…]}}` |

### 1.3 注册（JS→Kotlin，现有 send 上行，不变）

`{"type":"send","payload":{"t":"lsp.hook","cls":…,"method":…,"tag":…,"act":…,"mode":"replace"}}`
- `mode` 缺省 `"observe"`（现状零改动）；`"replace"` 新值。
- HookRouter 幂等键 **先修 t2 P0#1**：key = `cls#method#sig#tag`，`handles` → `Map<Key,List<HookHandle>>`。
- replace 回调需要 `hookedMethodSignature`（`Chain.getExecutable().toString()`）供 r 类型转换。

### 1.4 args 编码（Java→JSON）

| Java | JSON |
|---|---|
| String | string；Character → 单字符 string |
| Boolean | boolean |
| Byte/Short/Int/Long/Float/Double | number（**long>2^53 精度损失**，警告+文档化） |
| null | null |
| 对象/数组 | `{"__obj":"<simpleName>@<addr>"}` 占位（addr=`System.identityHashCode` 十六进制；**B1 只读不解析**） |
| this（非 static） | 同对象占位（**B1 fn 内 `this` 为 undefined**，用参数或 this.method） |

### 1.5 r 解码（JS→Java；按 hookedMethodSignature）

| JS | Java 目标 | Kotlin |
|---|---|---|
| Number | 数值类型 | 按返回类型 `.toInt()/.toLong()...`（非法 → `RET_TYPE_UNSUPPORTED`/`CAST_FAIL` 回退 proceed） |
| Boolean | boolean | 原样 |
| String | String / char | 原样 / `.first()` |
| null | 非 void | null |
| 其它 | 任意 | 不支持 → 回退 proceed |

`{over, r}`：`over=true` → return conv(r)（**不 proceed，跳过原方法**）；`over=false` → `chain.proceed()` 原值。
`error` reply → hostLog + 按 `over=false` 处理（自动 proceed，目标不崩）。

---

## 2. 线程模型与死锁分析（块②）

```
Java Thread A（HookRouter intercept 内；全局串行互斥已持有）
  ├─ argsJson = encodeArgs(chain.getArgs())
  ├─ retJson = GumJsBridge.callJs(X, argsJson)      ← JNI nativeCallJs(X, argsJson): String
  │    cpp: pending[X] = {cond, result, done}（id 自增）
  │         gum_script_post(rpc-call#1)             ← 异步立即返回；A 等 cond ≤500ms
  │           ├─ 超时 → {"over":false}（原方法执行，不卡死目标）
  │           └─ 唤醒（收到标准 rpc ok/error reply）→ 返回结果 JSON
  ├─ 〔async fn await this.method 嵌套〕A 被 lsp.rpc_inner 唤醒（pending[X].inner 标记）
  │    → chain.proceed(conv(innerArgs)) → gum_script_post(rpc-call#2, 原结果) → 重新等 cond
  └─ 解码 retJson：over=true → return conv(r)；over=false → chain.proceed() 原值
JS Thread B：handleMessage → frida:rpc call#1 → rpc.exports.__lspHookReply(X, argsJson)
  └─ async fn：await this.method(...) 时 JS 线程让出（事件循环自由）→
       ① shim send lsp.rpc_inner(X) → ② A 服务原调用 → ③ rpc-call#2 → __lspHookOriginalReply(X, retJson)
       → resolve this.method 的 Promise → fn 继续 → 返回 {over,r} → dispatcher 自动 reply
  └─ 同步 fn：fn 内 this.method() → shim 抛可读错误（"RouteB B1: 同步 fn 内不支持 this.method()…"）
       → __lspHookReply 抛错 → dispatcher error reply → A 按 over=false 处理
C message handler（JS 线程回调）：
  ① 先判 `["frida:rpc", …]` 前缀 → id 命中 pending → 写 result + signal；
     未命中（fire-and-forget rpc reply）→ 无感丢弃；
  ② 判 `lsp.rpc_inner` → pending[X].inner 标记 + signal；
  ③ 其余消息照旧上行 Kotlin（现有日志流零改动）。
```

**死锁安全**：A 只等 B；B 对 A 的依赖仅"内层原调用请求"（由 A 唤醒回路服务，A 唤醒后即 proceed 再返回）
——无环；B 繁忙 → A 500ms 超时兜底。
**并发（B1 冻结 = 全局串行）**：单互斥 + 单在途；多 Java 线程拦截退化为排队（≤500ms/个）；
热方法命中会排队/超时 → **replace 仅建议低频方法**（与 t2 P1#5 限频合并）；per-id 状态机 = P2。
**pending 生命周期（cap ④）**：`nativeCallJs` 入表；`unload/reload` **清空全部 pending**（逐个 signal +
超时默认值 `{"over":false}`）；id 计数器随脚本世代重置（防跨世代串扰）。

---

## 3. cpp 消费 reply（块③：与 Kotlin 的分工）

| 层 | 消费 | 不上行 |
|---|---|---|
| **C（on_message）** | ① frida:rpc 前缀（标准 ok/error reply）→ 命中 pending → signal；未命中 → 静默 ② `lsp.rpc_inner` → pending 标记+signal | frida:rpc 与 rpc_inner **均不进入 Kotlin、不上行宿主 UI** |
| Kotlin（TargetIpcServer.onScriptMessage） | 其余消息（console/现有 lsp.* 观测消息）照旧 | — |

- 防误吞：C 层 JSON 解析失败/非上述两类 → 原样上行 Kotlin（现有路径）；日志侧不重复。
- `error` reply 的 msg 由 C 层附加到 result（`{"over":false,"err":msg}`）→ Kotlin hostLog 记录。

---

## 4. file-by-file 实现清单（B1）

| 文件 | 改动 |
|---|---|
| `xposed/GumJsBridge.kt` | ① `LSP_SHIM` → shim v2：`globalThis.Java={perform(fn){fn()},use(cls){proxy}}`；proxy：`method.implementation=fn` → tag 注册 + `LSP.hook(...,{mode:"replace"})`；`=null` → 同 tag 全卸；`$new/$className/.value/Java.choose/overload()` → 显式报错；② **注册 `rpc.exports.__lspHookReply(X,argsJson)`**：解码 args → 用户 fn →（fn 返回 Promise？await 后取 {over,r}；同步路径取 {over,r}——按返回值范式）→ **直接 return {over,r}（构造器不依赖名，dispatcher 自动 reply）**；fn 内 this.method()：async 路径 send `lsp.rpc_inner` + 返回 Promise；同步路径抛可读错误；③ **注册 `rpc.exports.__lspHookOriginalReply(X,retJson)`**：resolve 对应 Promise；④ **移除 JavaBridgeBundle 拼接**（`INJECT_JAVA_BUNDLE=false` 常量，bundle 保留回滚）；⑤ `external fun callJs(id: Long, argsJson: String): String` |
| `xposed/HookRouter.kt` | ① HookRequest 加 `mode`；② **先修 t2 P0#1**（key 含签名、handles→List）；③ replace 分支：全局串行互斥 → encodeArgs → `GumJsBridge.callJs` → 超时/over=false → `chain.proceed()`；over=true → decodeRet（按 executable 返回类型）return；④ encodeArgs/decodeRet 工具（含 identityHashCode 占位）；⑤ ARM_FAIL/CAST_FAIL 日志带 mode |
| `cpp/gumjs_bridge.cpp` | ① pending map：`id → {GMutex,GCond,GString result,gboolean done,gboolean inner_got,GBytes inner_args}`（id 自增；世代重置）；② `nativeCallJs`：登记 → `gum_script_post(rpc-call#1)` → `g_cond_timed_wait(≤500ms)` → 循环处理内层（收到 `lsp.rpc_inner` 且自身为服务线程时的唤醒由 Kotlin 侧驱动——**实现按块②时序**）→ 取 result/超时 `{"over":false}`；③ `on_message` 按 §3 分流（frida:rpc 前缀 → pending 消费/静默；lsp.rpc_inner → 标记+signal；其余上行 Kotlin）；④ unload/reload 清空 pending（signal all）；⑤ 新 JNI×1（`nativeCallJs`），无新增线程（复用 gum-js-loop） |
| `xposed/TargetIpcServer.kt` | 不改主逻辑；仅日志兜底说明（frida:rpc/rpc_inner 由 C 消费） |
| `xposed/JavaBridgeBundle.kt` | 不改代码；首部注释标记 B1 不注入（回滚资产） |

**不改**：`LSPFRIFAModule.kt`、`IpcManager/LogStore/ScriptStore`、Provider。
**新 JNI**：`Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeCallJs`（`(JLjava/lang/String;)Ljava/lang/String;`）。

---

## 5. 风险矩阵（B1）

| # | 风险 | 置信度 | 缓解 |
|---|---|---|---|
| R1 | frida:rpc 整链不可达（post→dispatcher→rpc.exports 派发失败） | **低**（core.js:21 + message-dispatcher.js:24-66 本地源码实锤） | probe-rpc.js（R1-A 编译前验 exports；R1-B 随 replace 首测验整链）；失败 → **B- 备选**（gum_script_backend_get_scheduler + push_job_on_js_thread + 自 condvar，全公共 API；**与 JS recv 无关**） |
| R2 | JS 线程 B 繁忙 → A 超时 | 中 | 500ms 兜底 over=false 自动 proceed；低频建议；与 t2 P1#5 限频合并 |
| R3 | 全局串行 → 热方法并发排队/超时 | 中 | 文档化；超时不裂；per-id 状态机 P2 |
| R4 | async/同步 fn 判定错误（Promise 边界） | 低 | 按返回值范式判定 + dispatcher Promise-reply 双保险；error → hostLog+over=false |
| R5 | r 类型转换失败 | 低 | CAST_FAIL 回退 proceed（不抛） |
| R6 | long>2^53 精度损失 | 中 | warning + 文档；P2 `{"__long"}` |
| R7 | t2 P0#1 未修先上 replace → 多 overload 语义错乱 | 高 | 实现顺序硬约束（HookRouter 第②步） |
| R8 | bundle 移除后旧模板脚本行为变化 | 中 | shim 显式报错 + INJECT_JAVA_BUNDLE 回滚 |
| R9 | 嵌套链（await this.method）内层超时 | 低-中 | 内层同样 500ms 上限（总时长 ≤1s）；超时 → reject 该 Promise → fn catch 或 error reply → over=false |

---

## 6. 勘误与核对记录（t5 审查员）

1. **与 Facts 无矛盾**：Chain 方法集/无 setArgs-setResult-getResult/Hooker 返回值语义；devkit 头行号
   （post L86990 / push_job L87035 / get_js_context L87032）；post 异步投递；condvar+pending+全局串行（采纳 t5 论证）；
   "this.method 重入死锁"结论（cap 的 async-fn+await 裁定比我的宽版更严谨，已完全采纳）。
2. **cap 补充裁定覆盖我原方案 1 处**：①标准 rpc reply（`["frida:rpc",id,"ok"|"error",value]`，dispatcher 自动，JS 不自造）
   **取代**我原设计的自造 `send({t:"lsp.rpc_reply"})`——协议更标准、错误路径免费；②cpp 原生消费 frida:rpc/rpc_inner（不上行 Kotlin）与我原分工一致并收紧；③用户 API 形态、④pending 生命周期、⑤三块清单结构均已并入。
3. 勘误 2 处（不影响实现）：①证据路径 `~/miuix-src/api-src/` 与 `~/libxposed-src/…` 为同一文件不同解包；
   ②Facts §1 "异常=传播"为 protective 默认语义的简化（源码 L343-351，我们的 intercept 全 catch 显式降级）。
4. `.tmp-ref/probe-recv.js` **完全作废**（通道与 recv 无关——R1 探针验证的是 post→dispatcher→rpc.exports 整链可达性，cap 澄清 2026-08-25），由 `.tmp-ref/probe-rpc.js` 接替（R1-A/R1-B 两段）；probe-thread.js 仍有效。

---

## 附录：第一手证据索引（Facts，全部本地可复验）

1. `~/miuix-src/api-src/io/github/libxposed/api/XposedInterface.java` L190-294（Chain/Hooker/HookHandle）。
2. `app/src/main/cpp/frida-gumjs-devkit/arm64-v8a/frida-gumjs.h` L86987/86990/87032/87035。
3. `.tmp-ref/frida-gum-17.9.3/bindings/gumjs/gumquickscript.c`（post 异步链）。
4. `.tmp-ref/frida-gum-17.9.3/bindings/gumjs/runtime/message-dispatcher.js` L24-73（frida:rpc 协议 + 标准 ok/error reply + Promise）。
5. `.tmp-ref/frida-gum-17.9.3/bindings/gumjs/runtime/core.js` L21（rpc 全局）。
