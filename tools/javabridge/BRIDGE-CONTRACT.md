# frida-java-bridge 7.0.13 × LSPlant 桥接契约（第一阶段冻结版）

本文件是第一阶段（接通上游 Java 桥）**跨语言边界的唯一契约**。三个实现面
（JS 适配层 / Kotlin 路由 / cpp 原生）只通过这里的协议交互，任何一侧改动都必须先改本文件。

基线：上游 `frida-java-bridge@7.0.13`（npm，gitHead `6ab2a63e4b02ab881f11ea06632cebea4b1b435e`），
内联 bundle 已实测与「npm 包 + buffer@6.0.3 + esbuild-wasm@0.28.2（iife / target=es2022 /
legalComments=eof）」的产物 **逐字节一致**（见 `verify-upstream.mjs`）。

## 0. 总原则

1. **语义以 7.0.13 源码为准**。本阶段只改一个落点：`ArtMethodMangler.replace / revert /
   resolveTarget` 改道 LSPlant（由 `tools/javabridge/adapter.js` 注册的 replacement backend 接管）。
   原生 ArtMethod 改写路径**原样保留**，可用 `globalThis.__lspNativeReplace = true` 切回（回退对照）。
2. **不再有 JS shim**：`GumJsBridge.kt` 里的 `LSP_SHIM_JAVA`（340 行兼容子集 / 19 stub / 4 trap /
   版本号 token）删除；`globalThis.Java` 就是上游 Runtime 实例。
3. **同步反馈**：`implementation = fn` / `= null` 必须在 JS 侧同步得到路由结果并同步抛错
   （走 §1 的 `lsp_sync_call`），不允许 fire-and-forget。
4. **`undefined` = 跳过原方法**（上游语义），不是 proceed。见 §3 回复表。
5. 原生 ArtMethod 路径不得删除；LSPlant 主路径出问题时靠 `__lspNativeReplace` 回退。

## 1. cpp 导出面（`libgumjs_bridge.so`，C ABI，默认可见性）

### 1.1 给 JS 直接调用的导出（JS 线程，经 `NativeFunction`）

均以 `Process.getModuleByName("libgumjs_bridge.so").getExportByName(<name>)` 取址。
JS 线程在被调用时可能尚未 attach JVM：实现内部 `GetEnv` 失败就 `AttachCurrentThread`，
**JS 线程不 detach**（与上游 `vm.perform` 对 jsThread 的处理一致）。

```c
/* 同步回调 Kotlin：com.bail.lspfrifa.xposed.GumJsBridge.lspSync(String): String
 * 返回 thread_local UTF-8 NUL 结尾缓冲（下一次同线程调用前有效）。
 * 任何 JNI/异常/空返回都不得崩：失败返回 {"ok":false,"err":"..."}。 */
const char * lsp_sync_call (const char * json);

/* 把 JS 线程上的 jobject 局部引用钉成全局引用，返回全局引用句柄（失败 NULL）。 */
void * lsp_new_global_ref (void * localRef);

/* 删除 lsp_new_global_ref 返回的全局引用。 */
void   lsp_delete_global_ref (void * globalRef);
```

### 1.2 Kotlin 侧 JNI（符号名固定，cpp 实现；Kotlin 声明为 private external）

```c
jlong  Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeNewGlobalRef (JNIEnv *, jclass, jobject obj); /* null -> 0 */
jobject Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeNewLocalRef (JNIEnv *, jclass, jlong handle); /* 0 -> null */
void   Java_com_bail_lspfrifa_xposed_GumJsBridge_nativeDeleteGlobalRef (JNIEnv *, jclass, jlong handle);
```

`lspSync` 的目标方法（`GumJsBridge` 是 Kotlin `object` + `@JvmStatic`）：
`static java.lang.String lspSync(java.lang.String)`。jclass / jmethodID 在
`nativeInitEngine` 里用传入的 `jclass` 参数 `NewGlobalRef` 缓存（**不要 FindClass**：
模块类不在 app classloader 里，attach 线程的 FindClass 找不到）。

### 1.3 线程/锁纪律

- `lsp_sync_call` 内**不得**持有 `g_pending_lock` / `g_script_lock` / `g_callback_lock`
  再进 Kotlin（Kotlin 侧可能回到 `nativeCallJs`）。
- 句柄是 `(jlong)(intptr_t)jobject` 原值；Kotlin 侧一律用 `Long` 传递，不做算术。
- 所有导出函数 `extern "C"`，且在 NDK 默认可见性下（不加 `-fvisibility=hidden`）。

## 2. 同步安装通道：JS → Kotlin（`lsp_sync_call` 的 JSON）

### 2.1 install

```json
{"op":"install","cls":"com.foo.Bar","method":"m","sigs":["int","java.lang.String"],
 "isStatic":false,"ctor":false,"tag":"com.foo.Bar#m#int,java.lang.String","clsRef":"0x7f12345678"}
```

- `sigs`：**该 overload 的参数类型 className 列表**（bridge `argTypes[i].className` 形态：
  `"int"` / `"java.lang.String"` / `"java.lang.String[]"` / `"byte[]"`），由 Kotlin 侧
  `normalizeSig` 归一化；空数组 = 无参 overload。
- `tag`：`<cls>#<method>`（无 sigs 时）或 `<cls>#<method>#<sigs 逗号拼接>`。**本次实现始终带 sigs**，
  因为 mangler 是 per-overload 的。
- `clsRef`：JS 用 `lsp_new_global_ref` 钉住的 `java.lang.Class` 对象全局引用（十六进制）。
  Kotlin 必须用它拿到 `Class<?>`（`GumJsBridge.newLocalRef`），从而绕开 `Class.forName`
  的单点 loader 缺陷（defining loader 校验 + 插件/加固 loader）；拿到后**立即**
  `deleteGlobalRef`。`clsRef` 缺失/无效时 Kotlin 回退 `Class.forName(cls, false, targetLoader)`。
- `ctor=true`：`method` 是构造器（bridge `$init` 的 wrapper 名是类 simpleName）；Kotlin 走
  `declaredConstructors`。否则走 `declaredMethods`。
- 返回 `{"ok":true,"armed":<n>}`；失败 `{"ok":false,"err":"...","kind":"cnfe|overload|arm|badreq"}`。
  JS 侧对 `ok:false` **同步 throw**（`Error`，message 含 kind/err）。

### 2.2 uninstall

```json
{"op":"uninstall","tag":"com.foo.Bar#m#int"}
```
返回 `{"ok":true,"unhooked":<n>}` 或 `{"ok":false,"err":"...","missing":true}`（未挂过）。
JS 的 `implementation = null` 用这个：同步发出，失败**不抛**（上游 revert 不抛），只 console.warn。

## 3. Hook 回调协议：Kotlin → JS → Kotlin

### 3.1 调用（沿用现有 `frida:rpc` 通道，不改）

cpp `nativeCallJs(id, payload)` → `["frida:rpc", id, "call", ["__lspHookReply", [id, payloadJson]]]`
→ adapter 返回 JSON 字符串（可为 Promise）。`payloadJson`：

```json
{"key":"<tag>",
 "args":[<E>...],
 "this":<E>|null,
 "ret":"<JVM 描述符，如 I / V / Ljava/lang/String; / [B>",
 "isStatic":false}
```

### 3.2 值编码 `<E>`

| Java 侧 | JSON |
|---|---|
| null | `null` |
| boolean | `true` / `false` |
| char | 单字符串（长度 1） |
| byte/short/int/long/float/double | number（long > 2^53 只告警，沿用现状） |
| java.lang.String | string |
| 其它对象/数组 | `{"__h":"0x<全局引用>","__c":"<className>"}` |
| 编码失败 | `{"__o":"<simpleName>@<id>"}`（只读占位；JS 传回时不可转换） |

- `__h` 的全局引用由 Kotlin `GumJsBridge.newGlobalRef(obj)` 创建；**生命周期 = 本次回调
  （含所有 inner 往返），回调结束由 Kotlin 删除**。JS 侧用
  `Java.cast(ptr(h), Java.use(c))` 或 `Java.cast(ptr(h), classWrapper)` 包成真实 wrapper
  （`Java.cast` 的 owned=false 语义：wrapper 不负责释放该引用）。
- JS 返回对象时反向：用当前 attached env `newGlobalRef(wrapper.$h)` 钉住，回复里给
  `{"__h":"0x..."}`；Kotlin 收到后 `newLocalRef` 再 `deleteGlobalRef`。

### 3.3 回复（`__lspHookReply` 的返回值，JSON 字符串；可为 Promise resolve 成该字符串）

| kind | 含义 | Kotlin 行为 |
|---|---|---|
| `value` | `{"kind":"value","r":<E>}` | 解码 `r` 作为 Java 返回值（**不执行原方法**）；`r` 为 null 且返回类型是原始类型时取该类型默认值（0/false/'\u0000'） |
| `default` | 跳过原方法，返回类型默认值 | 0 / false / '\u0000' / null / 0.0f / 0.0 |
| `inner` | `{"kind":"inner","args":[<E>...]|null}` async `this.<hooked>(...)` 请求（仅 legacy async 回退路径） | `chain.proceed(args)`（null=原参）；结果经 `nativePostOriginalReply` 回投后继续等同一 id 的最终 reply |
| `proceed` | 执行原方法并返回其结果 | `chain.proceed()`（仅"handler 未注册"兜底用） |

语义对照（必须与上游一致，证据见 DONE §4）：
- `fn` 返回 `undefined` 且返回类型是 `void` → `value` + `r=null`（跳过原方法）；
- `fn` 返回 `undefined` 而返回类型是**对象/数组/非 void 原始类型** → `default`，并
  `Script.nextTick(() => { throw Error("Implementation for … expected return value compatible with …") })`
  （跳过原方法，Java 拿默认值）；
  证据：上游 `lib/types.js:215-224`（`void.isCompatible(undefined)===true`）、
  `lib/types.js:268-274` / `:340+`（对象类型 `isCompatible(undefined)===false`）、
  `lib/class-factory.js:1928-1938`（catch → nextTick 重抛 → `return retType.defaultValue`）；
- `fn` 抛异常 / 返回不兼容值 → 同上（`default` + nextTick 重抛），**不得** proceed 原方法；
- `proceed` 只由"handler 未注册"兜底产生（同步主路径永不因超时产生）；
- 超时/协议不可解析（仅 legacy async 回退路径）→ proceed 原方法并记
  `REPLACE_TIMEOUT` / `REPLACE_BADKIND`（目标不崩）。

`this` 规则：hook 回调里 `this` 是**真实 Java wrapper**（`Java.cast` 得到：字段 / `$h` /
`$className` / 其它方法 / `$init` 等同步可用）。**同步主路径没有 Promise 偏差**：调用
被 hook 的那个方法（同名 + 该 overload）会在同一条 app 线程上再次命中 LSPlant 拦截，
HookRouter 的 `hookDepth > 0` 重入守卫直接 `chain.proceed()`，JS 同步拿到原方法的真实
返回值（等价上游 `pendingCalls` + `STRATEGY_DIRECT`）。`kind:"inner"` 只是 legacy async
回退协议的兼容分支（`nativePostOriginalReply`），同步主路径从不产生它。

## 4. 文件归属（写作用域，不要越界）

| 归属 | 文件 |
|---|---|
| Lead | `tools/javabridge/**`、`app/src/main/kotlin/.../JavaBridgeBundle.kt`、`.../GumJsBridge.kt`、`DONE.md` |
| cpp-bridge | `app/src/main/cpp/gumjs_bridge.cpp`（必要时 `CMakeLists.txt`） |
| kotlin-router | `.../HookRouter.kt`、`.../LSPFRIFAModule.kt` |
| build-verify | 不写源码；只下 devkit / 装 SDK / 跑构建，结论回传 |

## 5. 构建与验证

- bundle 生成：`node tools/javabridge/build.mjs`（npm install → 打补丁 → esbuild → 重写
  `JavaBridgeBundle.kt`）。
- 上游一致性：`node tools/javabridge/verify-upstream.mjs`（必须 PASS）。
- adapter 单测：`node --test tools/javabridge/test/`。
- 静态检查：`bash tools/check-kt.sh`（若脚本依赖 kotlinc 则记录限制）。
- 真机构建：`./gradlew :app:assembleDebug`（需 devkit + SDK；见 README）。
