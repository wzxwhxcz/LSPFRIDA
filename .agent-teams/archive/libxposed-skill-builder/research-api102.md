# libxposed API 102 研究报告（权威事实源，t2 唯一依据）

> 本项目组研究产物。**本文件为 skill 编写（t2）的唯一事实源**：SKILL.md 与 references/*.md 中每一条 API 事实都应能回溯到本文件标注的来源文件；无来源标注的结论不得写入。
>
> - 研究者：api-researcher（agent team libxposed-skill-builder）
> - 研究对象：`io.github.libxposed:api:102.0.0`（仓库 https://github.com/libxposed/api ，tag = `102.0.0`）
> - 核验方法（三重交叉，均为 2026-08-24 实测）：
>   1. **codeload 整仓库 tarball**：`https://codeload.github.com/libxposed/api/tar.gz/refs/tags/102.0.0`（完整下载并展开，用作文件树与逐字全文）；
>   2. **raw.githubusercontent 逐文件拉取**：`https://raw.githubusercontent.com/libxposed/api/102.0.0/<path>`，README + api/src 下全部 7 个源文件 + 3 个构建文件全部 HTTP 200，且与 tarball 内容 **md5 逐字节一致**（见附录 A）；
>   3. **Maven Central 发布物**：`api-102.0.0.aar` 的 `classes.jar` 全量 class 清单（20 个 class），与源码树一一对应（无任何额外/缺失类）。
> - 佐证材料：官方示例 `libxposed/example`（master）、`libxposed/helper`、`libxposed/service`（同 org）、LSPosed 官方 wiki《Develop Xposed Modules Using Modern Xposed API》、LSPosed 框架源码（daemon + core）。
> - 局限（诚实标注）：GitHub REST API（`api.github.com/repos/libxposed/api/git/trees/...`）本次被未认证匿名限流（403 rate limit exceeded，出口 IP 共享），无法直接取得 API 树；已用等价且更完整的 codeload 全量 tarball（内容即完整 git 树）替代。框架侧 LSPosed GitHub master 快照（见 §6.3）停留于旧 libxposed API，未同步 102，"已发布框架实现 102 特性的程度"无法从框架源码验证。

---

## 1. 仓库完整文件清单（git tree，取自 tag=102.0.0 tarball）

```
api-102.0.0/
├── .github/workflows/android.yml / deploy.yml / snapshot.yml
├── .gitignore / LICENSE / README.md
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradle/libs.versions.toml / gradle/wrapper/*
├── gradlew / gradlew.bat
└── api/
    ├── build.gradle.kts          # libVersion = "102.0.0"（:35）；enableKotlin = false（:12）
    ├── proguard-rules.pro        # 框架侧 keep 规则
    └── src/main/
        ├── AndroidManifest.xml   # minSdkVersion = 26
        └── java/io/github/libxposed/api/
            ├── XposedInterface.java            (564 行)
            ├── XposedInterfaceWrapper.java     (192 行)
            ├── XposedModule.java               (  9 行)
            ├── XposedModuleInterface.java      (299 行)
            ├── error/HookFailedError.java      ( 25 行)
            ├── error/XposedFrameworkError.java ( 19 行)
            └── package-info.java               (137 行)
```

**关键事实**：
- api/src 下共 **7 个 Java 源文件（1245 行）**，**0 个 Kotlin 源文件**（全仓库 `*.kt` 计数 = 0；仅 3 个 `*.kts` Gradle 脚本）。`api/build.gradle.kts:12` 明文 `enableKotlin = false`。
  → **题面假设的 `XposedModule.kt`/`XposedModuleInterface.kt`/`extensions/`/`ModuleHelper.kt` 不存在**；现代 API 也没有 `XposedEntry`/`XposedHelpers`/`Hooker.kt`/`XposedBridge` 兼容层。全仓库 grep `XposedEntry|ModuleHelper|XposedHelpers|XposedBridge|XposedResponder|SystemLoaded` **零命中**（唯一出现 "XposedBridge" 的是 README.md:8 与 package-info.java:5 的说明文字 "replacement for the legacy XposedBridge API"）。
- tag `102.0.0` 与 master 分支**仅 package-info.java 有 1 行差异**（master 多一句 "At least one Java entry must be provided."，见 master package-info.java:23），其余全部文件（含 README）逐字节相同 —— 本报告以 tag=102.0.0 为准。

---

## 2. 全量类清单（AAR classes.jar 20 个 class 文件，与源码一一对应）

| 类（含嵌套） | 源码文件（路径相对仓库根） | 语义 |
|---|---|---|
| `XposedInterface` | api/src/main/java/io/github/libxposed/api/XposedInterface.java | 模块在 hooked 进程中的**操作接口**：hook/Invoker/deoptimize/日志/框架探测/远程 prefs 与文件 |
| `XposedInterfaceWrapper` | …/XposedInterfaceWrapper.java | XposedInterface 的模块侧包装：`attachFramework()`（框架专用）、`detach()`（102） |
| `XposedModule` | …/XposedModule.java | 入口抽象基类（全文 9 行，见 §4.1） |
| `XposedModuleInterface` | …/XposedModuleInterface.java | 生命周期接口：6 回调 + 6 参数接口 |
| `error.XposedFrameworkError` | …/error/XposedFrameworkError.java | 框架错误基类，`extends Error` |
| `error.HookFailedError` | …/error/HookFailedError.java | `extends XposedFrameworkError`；hook 失败=框架内部 bug，模块不应捕获 |
| `package-info` | …/package-info.java | 包级权威文档：入口注册/module.prop/scope/hook 模型/生命周期/热重载约束 |
| 嵌套：`XposedInterface.Invoker`、`Invoker.Type`、`Invoker.Type.Origin`、`Invoker.Type.Chain`、`CtorInvoker`、`Chain`、`Hooker`、`HookHandle`、`HookBuilder`、`ExceptionMode` | 在 XposedInterface.java 内 | 见 §5 |
| 嵌套：`XposedModuleInterface.ModuleLoadedParam`、`PackageLoadedParam`、`PackageReadyParam`、`SystemServerStartingParam`、`HotReloadingParam`、`HotReloadedParam` | 在 XposedModuleInterface.java 内 | 见 §4.2 |

AAR 另含 `proguard.txt`（框架侧规则）、`lint.jar`（官方 lint，`io.github.libxposed:lint:1.0.0`）、`AndroidManifest.xml`（minSdk 26）。AAR 内**没有** `io.github.libxposed.annotation.*`（`@SinceApi`/`@InternalApi` 来自独立 artifact `io.github.libxposed:annotation:1.0.0` jar；api 的 POM 无任何 dependencies，仅 api/build.gradle.kts:44 以 `compileOnly` 引用 annotation）。

---

## 3. 常量与模块配置（每项标注来源）

### 3.1 XposedInterface 常量（来源：XposedInterface.java）

| 常量 | 值 | 说明（注释原文语义） | 行号 |
|---|---|---|---|
| `API_101` | 101 | 行为变化：所有模块——**不能注入 zygote**，只在 scope 进程内加载；targeting 101+ 模块："这是第一个 API 版本" | :37-52 |
| `API_102` | 102 | 新特性：热重载；模块入口可停止接收后续生命周期回调；hook 可按 id 原子替换。行为变化：targeting 102+ 的 libxposed 模块**不能调用 legacy de.robv.android.xposed API** | :39-52 |
| `LIB_API` | API_102（=102） | 本库静态版本；模块应运行时用 `getApiVersion()` 检查 | :54-58 |
| `PROP_CAP_SYSTEM` | 1L | 框架能 hook system_server 与其他系统进程 | :63 |
| `PROP_CAP_REMOTE` | 1L<<1 | 框架提供远程偏好与远程文件 | :67 |
| `PROP_RT_API_PROTECTION` | 1L<<2 | 框架禁止经反射/动态加载代码访问 Xposed API | :71 |
| `PRIORITY_DEFAULT` | 50 | 默认 hook 优先级 | :76 |
| `PRIORITY_LOWEST` | Integer.MIN_VALUE | 拦截链末尾执行 | :80 |
| `PRIORITY_HIGHEST` | Integer.MAX_VALUE | 拦截链开头执行 | :84 |

### 3.2 module.prop 键（来源：package-info.java:25-45 "Module Configuration"）

| 键 | 必填 | 语义 |
|---|---|---|
| `minApiVersion` | 是 | 模块所需最小 Xposed API 版本（libxposed 版本号：101/102；**不是** legacy 的 82/93） |
| `targetApiVersion` | 是 | 模块目标 Xposed API 版本 |
| `staticScope` | 否(boolean) | scope 固定，用户不应把模块用到 scope 外的 App |
| `exceptionMode` | 否(`protective`\|`passthrough`) | 缺省 protective；对应 ExceptionMode 语义 |
| `autoHotReload` | 否(boolean, API 102+) | App 更新自动触发热重载；**仍需** onHotReloading 返回 true 才放行（package-info.java:41-44） |

官方示例值（example-master/app/src/main/resources/META-INF/xposed/module.prop）：`minApiVersion=101, targetApiVersion=102, staticScope=true, autoHotReload=true`。

### 3.3 META-INF/xposed/ 注册文件（来源：package-info.java:18-23 + LSPosed ConfigFileManager）

| 文件 | 用途 | 来源 |
|---|---|---|
| `META-INF/xposed/java_init.list` | Java 入口，一行一个 FQCN，`#` 允许注释；**至少 1 个** | package-info.java:20-23 |
| `META-INF/xposed/native_init.list` | native 入口（.so 库名，可选） | package-info.java:21 |
| `META-INF/xposed/module.prop` | 模块配置（§3.2） | package-info.java:28-45 |
| `META-INF/xposed/scope.list` | 一行一个包名；`system`=system server 虚拟包名 | package-info.java:47-68 |

---

## 4. 入口类与生命周期（逐条标注来源）

### 4.1 XposedModule（XposedModule.java 全文 9 行）

```java
// api/src/main/java/io/github/libxposed/api/XposedModule.java:1-9
package io.github.libxposed.api;
public abstract class XposedModule extends XposedInterfaceWrapper implements XposedModuleInterface {}
```

- **抽象类**，无显式构造器 → 隐式 `public XposedModule()`（无参构造）。
- 注释（:3-6）："Super class which all Xposed module entry classes should extend. Entry classes will be instantiated once for each loaded module generation in a process."
- 框架注入方式（XposedInterfaceWrapper.java:27-41）：`public final void attachFramework(@NonNull XposedInterface base, @NonNull Runnable detachImpl)` 标注 `@InternalApi`，注释明确 "Modules **must not** call this method. It is reserved for framework implementations..."。模块在框架附着前调用任何接口方法 → `IllegalStateException("Framework not attached")`（XposedInterfaceWrapper.java:43-47）。
- package-info.java:10-16：入口类 extend XposedModule；框架自动调用内部 attachFramework 桥；**不要在 onModuleLoaded() 之前做初始化**。

### 4.2 生命周期回调（来源：XposedModuleInterface.java，全部为 default 方法，模块按需覆写）

| 回调 | 参数接口 | 关键方法 | 来源行 |
|---|---|---|---|
| `default void onModuleLoaded(@NonNull ModuleLoadedParam param)` | ModuleLoadedParam | `boolean isSystemServer()`；`@NonNull String getProcessName()` | :26-41（param）；:173-184（回调） |
| `@RequiresApi(Q) default void onPackageLoaded(@NonNull PackageLoadedParam param)` | PackageLoadedParam | `getPackageName()`；`getApplicationInfo()`；`isFirstPackage()`；`@RequiresApi(Q) getDefaultClassLoader()` | :47-78；:202-204 |
| `default void onPackageReady(@NonNull PackageReadyParam param)` | PackageReadyParam extends PackageLoadedParam | + `getClassLoader()`；`@RequiresApi(P) getAppComponentFactory()` | :84-99；:221 |
| `default void onSystemServerStarting(@NonNull SystemServerStartingParam param)` | SystemServerStartingParam | `getClassLoader()`（system server 的 classloader） | :105-111；:233 |
| `@SinceApi(API_102) default boolean onHotReloading(@NonNull HotReloadingParam param)` | HotReloadingParam | `getExtras()`(Bundle, 可 null)；`setSavedInstanceState(Object)`（拒收旧 classloader 对象，否则 IllegalArgumentException） | :116-144；:264-267 |
| `@SinceApi(API_102) default void onHotReloaded(@NonNull HotReloadedParam param)` | HotReloadedParam extends ModuleLoadedParam | `getExtras()`；`getSavedInstanceState()`；`@NonNull List<XposedInterface.HookHandle> getOldHookHandles()` | :149-171；:295-298 |

语义要点（原文注释）：
- onPackageLoaded：`android.R.attr#hasCode` 包加载时触发；**默认 classloader 就绪、AppComponentFactory 实例化之前**；每进程每包名只调一次；system server 的首回调被 onSystemServerStarting 替代，因此此处 `isFirstPackage()` 永不为 true（:186-204）。
- onPackageReady：AppComponentFactory 已建出 classloader、将要创建 Application 之前（:206-222）。`getClassLoader()` 可能与 `getDefaultClassLoader()` 不同（模块自定义 ACF 时）（:84-92）。
- onModuleLoaded：模块 generation 注入进程时调用一次；**热重载不会重放**此回调或包生命周期回调（:173-179）。
- onHotReloading：**旧代码**运行；返回 true 才放行；true 前须停所有模块自有 Java/native 线程、卸 native hook 与外部回调、释放 JNI 全局引用、清系统/App 类对模块对象的引用（:241-267）。
- onHotReloaded：**新代码**运行；默认实现= `param.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook)`（:295-298）；推荐用 `HookHandle.replaceHook()` 原子替换（:269-294）。框架不调用 UnregisterNatives/JNI_OnUnload/dlclose（:286-291）。

### 4.3 API 常量版本语义（补充 XposedInterface.java:20-52）

API_101 即"第一版 API"（targeting 101 起无 zygote 注入）；API_102 新增三能力（热重载 / 入口停止回调 / hook 按 id 原子替换）。

---

## 5. Hook API 全量签名（来源：XposedInterface.java）

| 接口/枚举 | 方法/字段 | 语义 | 来源行 |
|---|---|---|---|
| `XposedInterface.hook(@NonNull Executable origin)` → `HookBuilder` | — | 主入口；`IllegalArgumentException`（origin 是框架内部方法或 Constructor.newInstance）+ `HookFailedError` | :441-448 |
| `XposedInterface.hookClassInitializer(@NonNull Class<?> origin)` → `HookBuilder` | — | hook `<clinit>`：合成 static void 无参 Method；this 恒 null；args 空；proceed 返回 null；**类已初始化则永不触发** | :450-469 |
| `XposedInterface.deoptimize(@NonNull Executable)` → `boolean` | — | 反优化防内联（hook 点被内联时不触发，可配 DexKit 找调用点） | :471-487 |
| `getInvoker(Method)` → `Invoker<?,Method>`；`getInvoker(Constructor<T>)` → `CtorInvoker<T>` | — | 绕过访问检查调用；默认 `Type.Chain.FULL` | :489-508 |
| `getFrameworkName()/getFrameworkVersion()` → String；`getFrameworkVersionCode()` → long；`getFrameworkProperties()` → long | — | 框架探测；PROP_RT_* 每次启动可能变化 | :418-439 |
| `getModuleApplicationInfo()` / `getRemotePreferences(String group)` / `listRemoteFiles()` / `openRemoteFile(String)` | — | 模块元信息与远程数据；嵌入框架时 UnsupportedOperationException；openRemoteFile 文件名不得含分隔符与 `.`/`..` | :529-563 |
| `log(int priority, @Nullable String tag, @NonNull String msg[, Throwable tr])` | — | Xposed 日志 | :510-527 |
| `interface Hooker` | `Object intercept(@NonNull Chain chain) throws Throwable` | **非泛型**；void 方法/构造器返回值被忽略；不改变结果时应 `chain.proceed()` 并返回其值 | :268-283 |
| `interface Chain` | `getExecutable()`；`getThisObject()`（static 为 null）；`getArgs()`（不可变 List）；`getArg(int)`；`proceed()`；`proceed(@NonNull Object[] args)`；`proceedWith(@NonNull Object thisObject)`；`proceedWith(@NonNull Object thisObject, @NonNull Object[] args)` | 链对象**不可跨线程共享、不可在 intercept 结束后续用**；void/构造器 proceed 恒返回 null；全部 `throws Throwable` | :187-266 |
| `interface HookBuilder` | `setPriority(int)`（默认 50）；`setExceptionMode(ExceptionMode)`（默认 DEFAULT）；`@SinceApi(102) setId(@Nullable String id)`；`@NonNull HookHandle intercept(@NonNull Hooker hooker)` | setId：同模块同 executable 同 id 的新 hook **原子替换**旧的，旧 handle 失效；id 模块间隔离 | :362-409 |
| `interface HookHandle` | `getExecutable()`；`unhook()`（幂等）；`@SinceApi(102) getNullableId()`；`@SinceApi(102) @NonNull replaceHook(@NonNull Hooker)` | replaceHook 保留 executable/priority/exceptionMode/id；替换后本 handle 失效；非法 hooker → IllegalArgumentException；失效 handle → IllegalStateException；框架内部错误 → HookFailedError；**hook 链是快照式**，替换不影响 in-flight 调用 | :285-329 |
| `enum ExceptionMode` | `DEFAULT`（跟随 module.prop，未配置时缺省 `PROTECTIVE`）；`PROTECTIVE`（hooker 异常捕获记录，视同无 hook——proceed 前抛→跳过该 hook 继续链；proceed 后抛→按 proceed 结果返回）；`PASSTHROUGH`（异常照常传播，调试用） | — | :331-360 |
| `interface Invoker<T extends Invoker<T,U>, U extends Executable>` | `T setType(@NonNull Type)`；`Object invoke(Object thisObject, Object... args)`；`Object invokeSpecial(@NonNull Object thisObject, Object... args)` | invoke 绕过访问检查；invokeSpecial 非虚调用（hooked 构造器里调 super.xxx）；均 `throws InvocationTargetException, IllegalArgumentException, IllegalAccessException`；void/构造器恒返回 null | :86-152 |
| `interface Invoker.Type`（sealed） | `Origin`（跳全部 hook 调原执行体）；`Chain(int maxPriority)`（从链中部开始，跳过优先级更高的 hook）；`Chain.FULL`（全链） | sealed，permit Origin/Chain | :93-117 |
| `interface CtorInvoker<T>` | `T newInstance(Object... args)`；`<U> U newInstanceSpecial(@NonNull Class<U> subClass, Object... args)` | newInstanceSpecial 用父构造器初始化子类实例（子类构造器不执行、字段未初始化，慎用） | :154-185 |
| `getApiVersion()` default | 返回 `LIB_API`；框架实现**不得覆写** | :411-416 |

---

## 6. 入口加载机制

### 6.1 现代 vs legacy 分流（框架 daemon，权威证据）

`daemon/src/main/java/org/lsposed/lspd/service/ConfigFileManager.java`：
- :380 `static PreLoadedApk loadModule(String path, boolean obfuscate)`
- :388 `readName(apkFile, "META-INF/xposed/java_init.list", moduleClassNames)` ← 先读现代入口
- :389-391 `if (moduleClassNames.isEmpty()) { file.legacy = true; readName(apkFile, "assets/xposed_init", moduleClassNames); ... }`
- :394-395 `else { file.legacy = false; readName(apkFile, "META-INF/xposed/native_init.list", moduleLibraryNames); }`

→ **判断标准：模块 APK 内存在 `META-INF/xposed/java_init.list` 即为现代（libxposed）模块；否则回退 legacy 模式（assets/xposed_init + assets/native_init）**。

`daemon/src/main/java/org/lsposed/lspd/service/LSPosedService.java`：
- :69-84 `isModernModules(ApplicationInfo)`：zip 内 `META-INF/xposed/java_init.list` 存在即 true。
- :136 `isXposedModule = (applicationInfo.metaData != null && applicationInfo.metaData.containsKey("xposedminversion")) || isModernModules(applicationInfo)` ← 兼容旧 meta-data 探测。

### 6.2 模块侧契约（101+，含 102）

- package-info.java:10-16：入口类 extend XposedModule；**框架自动调用 attachFramework 桥**；模块不得前置初始化。
- wiki（Develop-Xposed-Modules-Using-Modern-Xposed-API, 第 3 条）："XposedModule no longer receives `XposedInterface` and `ModuleLoadedParam` in its constructor; the framework calls `attachFramework(XposedInterface)` automatically."（现代 101+ 的入口构造为无参；旧 100 时代才是 `(XposedInterface, ModuleLoadedParam)` 双参构造。）
- package-info.java:18-23：注册文件放 `src/main/resources/META-INF/xposed/`，Gradle 自动打进 APK；"At least one Java entry must be provided."（master 版 package-info.java:23）。
- package-info.java:118-124：热重载仅支持**恰好一个** Java 入口类的模块；0 个或多个入口不可热重载。
- 入口类被混淆时：java_init.list 由 `-adaptresourcefilecontents` 同步改写（README.md:21-30 + example proguard-rules.pro）。

### 6.3 ⚠️ 框架侧版本注意（诚实标注）

当前 `github.com/LSPosed/LSPosed` master 快照（tarball 文件时间 2024-01）`core/src/main/java/org/lsposed/lspd/impl/LSPosedContext.java` 仍是**旧 libxposed API** 风格：
- :114 `moduleClass.getConstructor(XposedInterface.class, XposedModuleInterface.ModuleLoadedParam.class)`（100 时代双参构造）；
- :41 `io.github.libxposed.api.errors.XposedFrameworkError`（复数包名，102 为 `error`）；
- :77 `XposedModuleInterface.SystemServerLoadedParam`（102 为 `SystemServerStartingParam`）；
- `hook(Method, Class<? extends Hooker>) → MethodUnhooker`（102 为 `hook(Executable) → HookBuilder`）。

即框架公开仓库尚未同步 102；102 的入口契约以 libxposed/api 102.0.0 源码 + wiki 为准。模块侧应 `getApiVersion()` 运行时探测框架能力（XposedInterface.java:411-416，模块用法见 example ModuleMain.java:26）。

---

## 7. API 82（legacy XposedBridge）→ API 102 差异专项表（逐行标来源）

legacy 侧来源 = LSPosed 框架 `core/src/main/java/de/robv/android/xposed/`（该目录即 LSPosed 的 legacy 兼容实现；行号来自当前 master 快照）。

| # | 维度 | api82（legacy XposedBridge 时代） | api102（libxposed 102.0.0） | 来源（两侧） |
|---|---|---|---|---|
| 1 | 依赖声明 | `provided 'de.robv.android.xposed:api:82'`（经典写法；LSPosed 时代实际由框架提供） | `compileOnly("io.github.libxposed:api:102.0.0")`（运行时不打包，框架提供；打包 API 类会被框架拒载） | README.md:10-18；LSPosedContext.java:99-103（打包检测拒绝） |
| 2 | 模块声明 | AndroidManifest meta-data：`xposedmodule`/`xposedminversion`/`xposeddescription`/`xposedscope` | `META-INF/xposed/{java_init.list, native_init.list, module.prop, scope.list}`；模块名/描述=`android:label`/`android:description` 资源 | package-info.java:18-45；wiki 第 2 条；LSPosedService.java:136（旧 meta-data 仅作兼容探测） |
| 3 | Java 入口 | `assets/xposed_init`（每行一个类名）；实现 `IXposedHookLoadPackage`/`IXposedHookZygoteInit`/`IXposedHookInitPackageResources`（均 extends IXposedMod） | `META-INF/xposed/java_init.list`；入口类 `extends XposedModule` | 现代：package-info.java:18-23；legacy：XposedInit.java:259-290 + :301 注释 + IXposedMod 层级（IXposedHookLoadPackage.java:24 等） |
| 4 | 进程/zygote | 可 hook zygote（`IXposedHookZygoteInit.initZygote(StartupParam)`），handleLoadPackage 在 zygote 阶段注册、进程早启动 | **API 101 起禁止注入 zygote**；模块只加载进 scope 进程 | XposedInterface.java:27-37（API_101 注释）；legacy 对照 XposedInit.java:274-283 |
| 5 | 载荷回调 | `handleLoadPackage(XC_LoadPackage.LoadPackageParam)`；lpparam.packageName/classLoader（XC_LoadPackage.java:63,74,84） | `onModuleLoaded(ModuleLoadedParam)`/`onPackageLoaded(PackageLoadedParam)`/`onPackageReady(PackageReadyParam)`/`onSystemServerStarting(SystemServerStartingParam)`（+102 热重载两回调） | 现代：XposedModuleInterface.java（§4.2 表）；legacy：IXposedHookLoadPackage.java:24、XC_LoadPackage.java:63-84 |
| 6 | 挂 hook | `XposedBridge.hookMethod(Member, XC_MethodHook) → XC_MethodHook.Unhook`；`XposedBridge.hookAllMethods/hookAllConstructors` | `hook(Executable) → HookBuilder → .intercept(Hooker) → HookHandle` | 现代：XposedInterface.java:441-448 + :362-409；legacy：XposedBridge.java:195、:248-263 |
| 7 | hook 回调 | `XC_MethodHook.beforeHookedMethod/afterHookedMethod(MethodHookParam)`；改参=param.args[index]；调原方法=param.callMethod(...) | `Hooker.intercept(Chain)` 单方法；**前置**=intercept 内 proceed() 前，**后置**=proceed() 后；改参=proceed(newArgs)；改 this=proceedWith(...)；调原方法=proceed() 或 Invoker(Type.ORIGIN) | 现代：XposedInterface.java:187-283；legacy：XC_MethodHook.java:70,88,98 |
| 8 | 工具查找 API | `XposedHelpers.findAndHookMethod(clazz/name, ...)`（XposedHelpers.java:310,389） | **框架不再提供 XposedHelpers**；反射拿 `Executable` 后 `hook()`；官方推荐 `io.github.libxposed:helper`（HookBuilder.buildHooks 批量 Dex 分析 hook，helper 版本 100.0.1） | 现代：仓库树（§1）+ wiki 第 4 条；helper：helper-master/helper/src/main/java/io/github/libxposed/helper/HookBuilder.java |
| 9 | 构造器 | `XposedBridge.hookAllConstructors`/hookMethod(Constructor)；构造中调 super 用 `param.callMethod` 技巧 | `hook(Constructor)` + `CtorInvoker.newInstance/newInstanceSpecial`；`Invoker.invokeSpecial` 调非虚方法 | 现代：XposedInterface.java:154-185,489-508；legacy：XposedBridge.java:263 |
| 10 | 反内联 | 无主动手段 | `deoptimize(Executable)` 主动反优化 | XposedInterface.java:471-487 |
| 11 | 资源 hook | `IXposedHookInitPackageResources.handleInitPackageResources` + XC_InitPackageResources | **已移除，不支持** | 现代：仓库零资源 API（§1 grep 证据）+ wiki 第 6 条；legacy：XposedInit.java:287-289 |
| 12 | 模块标识 | xposedminversion=82/93（legacy API 数值） | module.prop `minApiVersion`/`targetApiVersion`=101/102（libxposed API 数值） | 现代：package-info.java:28-45；legacy：LSPosedService.java:136 |
| 13 | 兼容性 | legacy 模块在 LSPosed 上以 legacy 模式运行（initModule 分发，XposedInit.java:259-303） | targeting 102+ 的 libxposed 模块**禁止调用 legacy de.robv.android.xposed API** | XposedInterface.java:47-52（API_102 注释） |
| 14 | 模块-宿主通信 | XSharedPreferences（XSharedPreferences.java，文件式） | `io.github.libxposed:service:102.0.0`：`XposedServiceHelper.registerListener` → `XposedService`（getScope/requestScope/removeScope/getRunningTargets/hotReloadModule(HookedTarget, Bundle, HotReloadCallback)） | service-master/service/src/main/java/io/github/libxposed/service/XposedService.java、XposedServiceHelper.java |
| 15 | 安全模型 | 全局回调、无反射限制 | `PROP_RT_API_PROTECTION`（禁反射/动态代码访问 API）；模块 App 不再被自身 hook（wiki 第 7 条） | XposedInterface.java:71；wiki 第 7 条 |
| 16 | 日志 | XposedBridge.log / logcat 全局 | `log(int, String, ...)` 实例方法 | XposedInterface.java:510-527 |

**API 102 相对 101 的新增（XposedInterface.java:39-52 注释）**：① 热重载（免重启更新模块）；② 入口可停止接收后续生命周期回调（`detach()`，XposedInterfaceWrapper.java:49-85）；③ hook 可按 id 原子替换（`HookBuilder.setId` + `HookHandle.replaceHook`）。

---

## 8. 防编造清单（明确不在 102.0.0 中的符号，附证明）

| 符号 | 判定 | 证明 |
|---|---|---|
| `XposedEntry` | **不存在** | AAR classes.jar 清单（§2，20 个 class 无此名）；全树 grep 零命中 |
| `ModuleHelper` | **不存在**（api 中无任何 Helper 类） | 同上；helper 库中只有 HookBuilder/HookBuilderImpl/Reflector/Misc |
| `XposedHelpers` | 不在 api 102.0.0（属 legacy `de.robv.android.xposed` 或 libxposed/helper 的近似物） | legacy：LSPosed core …/XposedHelpers.java；wiki 第 4 条 |
| `XposedBridge` | 不在 api 102.0.0；legacy 类；102+ 模块禁调 | LSPosed core …/XposedBridge.java:195；XposedInterface.java:47-52 |
| `XposedResponder` | **不存在**于 libxposed org 全部 5 仓库 | org 仓库列表：api/example/helper/lint/service（github.com/orgs/libxposed/repositories 实测）；全 grep 零命中 |
| `Hooker` 顶层类 | 不存在；是 `XposedInterface.Hooker` 嵌套接口 | XposedInterface.java:268-283 |
| `onSystemLoaded` / `SystemLoadedParam` | **不存在** | XposedModuleInterface.java 全文件 grep 零命中；对应物 `onSystemServerStarting`/`SystemServerStartingParam`（:105-111, :233） |
| Kotlin 源文件（XposedModule.kt 等） | **不存在**（仓库 0 个 .kt） | `find -name "*.kt"` 计数 0；api/build.gradle.kts:12 `enableKotlin = false` |
| 资源 hook API | 不存在 | §1 grep + wiki 第 6 条 |

---

## 9. Scope 语义与热重载要点（来源：package-info.java）

- **scope.list**：每行一个包名；框架按此注入该包声明的所有常规进程；**注入后进程内每个被加载包都触发回调** → 模块必须按 processName/packageName 过滤；不需要的进程调 `detach()`（package-info.java:47-55）。
- **`system`**：特殊虚拟包名，代表 system server；组件全跑在 system server 的包（如 com.android.providers.settings）把包名加入 scope 无效，应显式用 `system`（:57-61, :66-68）。
- **`android` 包仍合法 scope**（部分组件声明 `android:process=":ui"` 且无 code，仍能收到加载事件）（:63-68）。
- **热重载限制**：恰好 1 个 Java 入口类（:118-124）；onHotReloading 返回 false 拒绝（service 触发 → HotReloadResult.Status.FAILED + null message）；App 更新触发的也必须返回 true（XposedModuleInterface.java:236-267）。

---

## 10. 混淆与打包（README.md:20-30、example app/build.gradle.kts）

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

- `-adaptresourcefilecontents`：入口类被混淆后自动改写 java_init.list（必须，否则 R8 构建后入口失效）。
- 模块侧勿 keep `attachFramework`/`detach`（框架反射调用；XposedInterfaceWrapper 由框架 classloader 提供）。
- example 的 AGP 打包规则：`packaging { resources { merges += "META-INF/xposed/*"; excludes += "**" } }`（防依赖互斥；service 102.0.0 AAR 实测不含该目录，风险低但为官方标配）。

---

## 11. LSPFRIFA 现状核对（cwd 项目，2026-08-24 实测）

| 项 | 现状 | 判定 |
|---|---|---|
| 入口文件 | app/src/main/resources/META-INF/xposed/java_init.list → `com.bail.lspfrifa.xposed.LSPFRIFAModule`（单入口） | ✅ 102 正统 |
| module.prop | minApiVersion=102, targetApiVersion=102, staticScope=false, exceptionMode=protective, autoHotReload=false | ✅ 合规（值为官方示例语义的子集） |
| 入口类 | `class LSPFRIFAModule : XposedModule()`（Kotlin 无参构造） | ✅ 合规 |
| 依赖 | `compileOnly("io.github.libxposed:api:102.0.0")` + `implementation("io.github.libxposed:service:102.0.0")` | ✅ 合规 |
| scope.list | 无（scope 由 LSPosed Manager 按用户选择配置——动态 scope 需求合理） | ⚠️ 可接受，非错误 |
| packaging merges 规则 | 未配置 | ⚠️ 可选优化（风险低） |
| onPackageLoaded 内做 GumJsBridge/IPC 初始化 | onPackageLoaded 语义=默认 classloader 就绪、AppComponentFactory 实例化**之前**；若初始化依赖 App classloader 就绪，应移至 onPackageReady | ⚠️ 语义风险点，值得在 skill 中提示 |

---

## 附录 A：拉取与校验日志（可复核）

- GitHub REST API 树：两次返回 403（rate limit，未认证出口 IP）→ 采用 codeload 全量 tarball 替代。
- `codeload.github.com/libxposed/api/tar.gz/refs/tags/102.0.0` → HTTP 200，69986 字节，解包 `api-102.0.0/`。
- `raw.githubusercontent.com/libxposed/api/102.0.0/...` 11 个文件全部 HTTP 200；与 tarball 逐文件 md5：README/README == `83e2d9cf95773d932d22a3ee584afb6c`；XposedInterface/XposedInterfaceWrapper/XposedModule/XposedModuleInterface/error/* 全部 **IDENTICAL**。
- 行数：XposedInterface.java 564 / XposedModuleInterface.java 299 / XposedInterfaceWrapper.java 192 / package-info.java 137 / HookFailedError.java 25 / XposedFrameworkError.java 19 / XposedModule.java 9（合计 1245 行）。
- 官方 example（codeload master）：java_init.list=`io.github.libxposed.example.ModuleMainKt`；module.prop（§3.2 值）；scope.list=`com.android.settings`；ModuleMain.java/ModuleMainKt.kt 全文已读取（§5 用法示例与 §9 语义互证）。

## 附录 B：本报告引用的外部仓库路径清单

- libxposed/api tag 102.0.0：`api/src/main/java/io/github/libxposed/api/*.java`、`api/build.gradle.kts`、`README.md`
- libxposed/example master：`app/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}`、`app/src/main/java/io/github/libxposed/example/ModuleMain.java`、`app/build.gradle.kts`、`app/proguard-rules.pro`
- libxposed/helper master：`helper/src/main/java/io/github/libxposed/helper/{HookBuilder.java,Reflector.java,Misc.java}`、`helper/build.gradle.kts`（version 100.0.1）
- libxposed/service master：`service/src/main/java/io/github/libxposed/service/{XposedService.java,XposedServiceHelper.java,HotReloadResult.java,HookedTarget.java,RemotePreferences.java,XposedProvider.java}`
- LSPosed master（快照 2024-01）：`daemon/src/main/java/org/lsposed/lspd/service/ConfigFileManager.java`（:380-419）、`LSPosedService.java`（:69-84, :136）、`core/src/main/java/org/lsposed/lspd/impl/LSPosedContext.java`（:41-42, :77, :99-115）、`core/src/main/java/de/robv/android/xposed/{XposedBridge.java,XposedHelpers.java,XC_MethodHook.java,XposedInit.java,IXposedHookLoadPackage.java}`、`core/src/main/java/de/robv/android/xposed/callbacks/XC_LoadPackage.java`
- LSPosed wiki：Develop-Xposed-Modules-Using-Modern-Xposed-API.md（raw.githubusercontent.com/wiki/LSPosed/LSPosed/...）
