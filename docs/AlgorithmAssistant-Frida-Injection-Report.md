# 算法助手Pro 1.1.0 debug — Frida 注入逻辑拆解报告

- 目标：`算法助手Pro_1.1.0 debug.apk`（22.8 MB）
- 包名/版本：com.junge.algorithmAidePro / 1.1.0 debug (versionCode 110)
- minSdk 24，targetSdk 29，单 dex（4117 类）
- 签名：V1+V2+V3，CN=Siyu, OU=E4A, L=NanChang, ST=JiangXi, C=CN
- 分析工作区：`s45zrswn`（MT MCP，temporary=false，可复用）
- 证据标注约定：**【直接证据·来源】**=从 APK 条目/反汇编/字符串直接读取；**【推断·理由】**=由证据推断；**【未确认】**=无证据，不猜。

---

## 0. 总体判定

**【直接证据】** 算法助手Pro 是 **Xposed 模块 + 内嵌定制 Frida 运行时（GumJS/QuickJS）的自实现注入**：
- 不是 frida-gadget 静态嵌入：无 `libfrida-gadget.so`/`libgadget.so`；so 的 27 个 ELF section 中**无 `.frida` 配置段**；dex+native 中"gadget"字样 0 命中与"Gadget"类名 0 命中（仅残留构建串 `--only-section=.frida` @0x44911，说明编译基是 frida-gadget 源码，但最终未走 `.frida` 静态配置路径）。
- 不是 frida-server 外部工具：APK 内无 frida-server 二进制、无 root/注入器产物；注入完全发生在目标进程内部（Xposed 上下文）。
- 是：**Xposed 模块在目标进程内加载定制 frida 运行时 .so，经 JNI 推送脚本与 pipe**（"自实现 GumJS"）。

**【推断·理由】** 该 .so 内嵌 `frida:rpc`、`/frida/runtime/*.js`、`_frida_worker_runtime.js`、`/frida/bridges/java.js`、`tcc 0.9.27-frida`、构建路径 `/__w/frida/frida/deps/src/_sdk.out/android-arm64/...`（直接证据字符串），为官方 frida CI 构建树产物再定制（与 LSPFRIFA 同源做法）。具体上游变体（frida-gadget fork vs frida-core agent fork）**【未确认】**。

---

## 1. 依赖形态（lib/*/*.so）

**【直接证据·zip 条目 mt_apk_list zip_entries】**

| 条目 | 大小 | 判定 |
|---|---|---|
| lib/arm64-v8a/libalgorithmAidePro.so | 6,124,408 B | **Frida 运行时定制版（注入核心）** |
| lib/arm64-v8a/libcrashsdk.so | 647,928 B | UC crashsdk（dex `com.uc.crashsdk.*`/JNIBridge，与注入无关） |
| lib/arm64-v8a/libumeng-spy.so | 239,848 B | 友盟设备指纹（`.dynstr`: `Java_com_umeng_umzid_Spy_getNativeID` / `_getNativeLibraryVersion`，与注入无关） |
| lib/armeabi-v7a/*（同 3 个） | — | 同上，libalgorithmAidePro.so 4.22 MB |
| lib/x86_64/libalgorithmAidePro.so | 7,195,888 B | 模拟器 ABI，无 crash/umeng（精简 build） |

**无任何 frida-gadget/agent/注入器命名的 .so**（全 APK zip 条目仅 7 个 so，上面列全）。

### 特征字符串矩阵

**【直接证据·dex_strings / native_strings 搜索】**

| 关键词 | dex | native（libalgorithmAidePro.so） |
|---|---|---|
| frida | 22 命中，全部为 `com.junge.algorithmAidePro` 自有类型/字符串：`FridaLog`、`FridaLogData`、`FridaLogEntry`、`FridaScriptData`、`FridaScriptAppData`、`FridaLogFrameCodec`、`FridaLogDataDao`、`isFridaLog`、`fridaLogs`、`fridaStats`、"Frida pipe failed: package="、"Frida pipe closed: package="、"Frida日志已清理"、"Frida script reload broadcast received: " | 大量：`Frida`、`FridaGum`、`frida:rpc`、`/frida/runtime/...` 全套、`_frida_worker_runtime.js`、`/frida/bridges/java.js`、`0.9.27-frida`（tcc）、`setFridaLogPipe`、`isFridaLogPipeReady`、"Missing AlgorithmAidePro Frida bootstrap data"、"installFrida17Compatibility()" |
| gadget | **0** | **0**（仅字符串 `--only-section=.frida` @0x44911 与 ` .frida 0x%zx: {` @0x6402e 为 gadget 源码残留） |
| FridaRuntime | **0** | 0 |
| gumjs（小写） | 0 | 0 |
| GumJS | 0 | **2**："Unable to create GumJS script: %s" @0x4b901；"Unable to start GumJS main loop" @0x65c21 |
| scoped-lock / scoped_lock | 0 | 0 |

**【推断·理由】** 判定为 **frida GumJS 运行时（gum+quickjs）**；"scoped-lock"零命中说明该术语（frida 内部 C++ 类）在此构建中被剥离/内联，**无证据表明存在其他注入引擎**。

---

## 2. 注入链路（5 段聚焦）

### 2.1 入口（manifest Application/Activity + dex 启动链）

**【直接证据·axml AndroidManifest.xml + assets】**
- Application：`com.junge.algorithmAidePro.MyApplication`
- LAUNCHER Activity：`SplashActivity`（MAIN/LAUNCHER）→ 主界面 `MainActivity`
- **Xposed 模块元数据**：`xposedmodule=true`、`xposeddescription="算法助手，做最好的调试工具"`、`xposedminversion=54`、`xposedsharedprefs=true`、`xposedscope=@7F030003`
- `assets/xposed_init` 内容（全文 38 字节）：`com.junge.algorithmAidePro.hook.XpInit` —— **Xposed 模块入口类**
- `XpInit implements de.robv.android.xposed.IXposedHookLoadPackage`（旧版 Xposed API，LSPosed 兼容层）
- 宿主进程组件（与注入相关）：`AlgorithmServiceProvider`（process=`:algorithm_ipc`，exported=true，AIDL `IAlgorithmService`）、`ConfigProvider`（authorities=`algorithmAidePro`，exported=true）

**【直接证据·dex 类 XpInit.methods】** `handleLoadPackage(LoadPackageParam)` 829 指令，含 `XposedBridge.hookAllMethods` 调用。

### 2.2 配置（assets 等效物）

**【直接证据·zip 条目 assets/】** `frida-gadget.config.json` **不存在**；`script.js` **不存在**。assets 仅有：`eula.html`、`help.zh.html`、`log.html`、`webui/index.html`、`xposed_init`。

**【直接证据·native 残串】** 无 `.frida` 段 → 标准 gadget 静态配置路径不成立。

**【直接证据·.so .rodata @0xb3846 附近 bootstrap JS 反解】**
```
(function () {
    const bootstrap = globalThis.__algorithmAideBootstrap;
    delete globalThis.__algorithmAideBootstrap;
    if (bootstrap === null || typeof bootstrap !== 'object') {
        throw new Error('Missing AlgorithmAidePro Frida bootstrap data');
    }
    installFrida17Compatibility();
    installLazyJavaBridge(patchJavaBridge(bootstrap.bridgeSource));
    Script.evaluate(bootstrap.userScriptName, bootstrap.userScriptSource);
    ...
```
- 配置载体 = native 注入的全局对象 `__algorithmAideBootstrap`，字段：`bridgeSource`（Java bridge JS 源）、`userScriptName`、`userScriptSource`。
- 即：**配置不是文件，而是 JNI 从 Java 层推送的运行时对象**（自定义 bootstrap，注释 "runs before every user script"）。

### 2.3 .so 加载（System.loadLibrary / load 调用点）

**【直接证据·dex 类 Lg3/e（LibraryLoader）smali 直读 `b()V`】**
- 不调用 `System.loadLibrary()`（目标进程无法直接加载模块 APK 的 so），而是：
  1. `<context>/lib` 私有目录（`Context.getDir("lib", 0)` + mkdirs）
  2. `AlgorithmClient.getClient(context).service_getLibrary("libalgorithmAidePro.so", abi)` → 跨进程 Binder 从宿主 `:algorithm_ipc` 服务取得 `ParcelFileDescriptor`（流式传输 .so 内容）
  3. 写为 `<context>/lib/<UUID>` 临时文件（0x100000 块拷贝；日志："Copied so file from IPC: ..." / "service_getLibrary returned null for ..., abi: ..." / "copyMySoFromIPC error"）
  4. `System.load(临时文件绝对路径)` → 成功删除临时文件；`HashSet<String>` 防重复加载
- ABI 选择 `a()Ljava/lang/String;`：`Process.is64Bit()` + 平台判定（"arm64-v8a"/"armeabi-v7a"/"x86_64"、"Error while checking 64-bit support"）。
- 支持 `La5/n;->f(String,String)` 去 `.so` 前缀（库名规范化）。

**【直接证据·native 符号表 + 反汇编】**
- 导出符号：`JNI_OnLoad` @0x39abdc（212B）、`hookNetwork()`、`hookSignal()`、`print_stacktrace`
- `JNI_OnLoad` 反汇编：GetEnv → `memcpy(表, 0x5b6298, 0x90)` → 调用 wrapper @0x39a9dc（该 wrapper 最终走 JNIEnv 槽 0x6b8=**RegisterNatives**，参数 6 项）→ **注册 6 个 JNI native 方法**
- 与 dex 类 `com.junge.algorithmAidePro.hook.NativeHook` 的 6 个 `private native` 一一对应：
  - `init(String) V`
  - `loadScript(String, String) I`（脚本名+源码）
  - `setFridaLogPipe(int) Z`（fd）
  - `isFridaLogPipeReady() Z`
  - `unload() I`
  - `hookNetwork() I`
- 导入函数含 `pipe2`、`socketpair`、`socket`、`bind`、`connect`、`waitpid`、`dl_iterate_phdr`、`mprotect`、`mmap`、`__system_property_get` —— 与 frida gum（dyld/内存扫描）及自定义管道逻辑一致。

### 2.4 目标进程注入方式

**【直接证据·smali】**
- 目标进程 = 任一被 LSPosed 勾选作用域的应用进程（Xposed 模块代码随目标进程加载）。
- `XpInit.handleLoadPackage` → `new XpInit$a()`（`XC_MethodHook`，beforeHookedMethod 113 指令）→ `XposedBridge.hookAllMethods(类, 方法, hook)`（类/方法名经字符串加密，运行期解密）→ 回调触发 `XpInit.a(context)`。
- `XpInit.a(context)` 核心分支（sswitch_153）：`Class.forName("Ll3/h").newInstance()` → 强转为 `Ll3/p`（NativeHook 基类）→ `ConfigReader.getInstanceByHookApp(context, 目标包名)` → `Ll3/p.b(ConfigReader)`（即 `NativeHook.b(ConfigReader)`，686 指令编排：加载 so→建 pipe→init→loadScript）。
- 注入时机 = 目标应用生命周期早期（hookAllMethods 在 `beforeHookedMethod` 触发），**具体被 hook 的类/方法名【未确认】**（字符串加密）。

**【未确认】** `init(String)` 参数内容（推测为会话/配置 JSON 或脚本标识）；宿主主进程是否也加载该运行时（未见证据，`Lg3/e` 仅出现在 Xposed hook 路径）。

### 2.5 脚本送达（assets 脚本 / 远程 / 预打包 → 实际是 IPC 配送）

**【直接证据·dex 接口 IAlgorithmService（26 个方法）】**
- `app_getScript(String packageName, String scriptName) → String`（**目标进程拉取脚本源码**）
- `app_importScript(pkg, name, source) / app_deleteScript / app_setScriptAlias / scriptList`（宿主 UI 管理脚本）
- `service_getLibrary(name, abi) → ParcelFileDescriptor`（.so 传输，见 2.3）
- `service_startApp / forceStopApp / appIsRunning / getAppsWithSwitch / getFridaScriptApps`（应用管控）
- `getLogManager() → ILogManager`
- 数据类：`FridaScriptData`、`FridaScriptAppData`（Parcelable），宿主 db：`algorithmAidePro.db`（`DatabaseContext`/`FridaLogDataDao`）
- 脚本以 **(包名, 脚本名)** 维度组织，源码为字符串经 **Binder** 配送 —— **非 assets 预打包、非远程下载**。
- 热更新：【直接证据·dex】`ConfigAction` 广播（`Lx2/b`："Frida script reload broadcast received: "）→ 目标进程 `ConfigReader.updateFromBroadcast(String)`（ConfigReader implements SharedPreferences，对应 manifest `xposedsharedprefs=true`）→ 重新拉取/重载。

**【直接证据·dex ILogManager 实现 Lm3/g + Lm3/c】**
- 目标进程侧回程：`openFridaWritePipe(packageName, process) → ParcelFileDescriptor`（宿主 `:algorithm_ipc` 的 LogManagerService 创建 pipe，把**写端**给调用方；含 uid 校验日志："Rejected Frida writer: uid=..., package=..."、"[A-Za-z0-9_.:-]+" 包名校验）
- agent 拿到 fd 后 `NativeHook.setFridaLogPipe(fd)` → native runtime 把 frida 日志写入 pipe。
- 宿主读线程 `Lm3/c.run()`（Runnable）：`DataInputStream(AutoCloseInputStream(pfd))` 循环读，失败提示 "Frida pipe failed: package=, process=" / "Frida pipe closed: package=" → `FridaLogFrameCodec` 解码 → `FridaLogData` → 按包名独立 db（`DatabaseContext.getDatabaseFile`）→ UI。
- 其它：`openWritePipe`（非 frida 通用日志）、`executeCommand/executeBinaryCommand/executeQuery/exportLogs`（"Operation requires pipe:"、"Rejected log command from uid="、fridaStats、logJson/logJsonBatch/search/export 等 operation 分发）。

---

## 3. 注入目标（作用域/scope）

**【直接证据·resource 0x7f030003 xposed_scope】**
```
<string-array name="xposed_scope">
    <item>android</item>             <!-- 系统框架（"系统服务"所在） -->
    <item>com.reqable.android</item> <!-- Reqable 抓包工具 -->
</string-array>
```
- 用户可通过 LSPosed 勾选任意目标 App（FAQ：勾选"系统框架"+目标 App）。
- 虚拟环境兼容：【直接证据·dex】`La3/g` 引用 `content://me.weishu.exposed.CP/`（VirtualXposed）；FAQ 提及 BlackBox、VMOS、应用转生。

## 4. 错误反馈 / 状态显示（与 LSPFRIFA 对齐要点）

**【直接证据·assets/help.zh.html 官方 FAQ】** 宿主 UI 以 4 级状态反馈，做法与 LSPFRIFA 同构：
1. **模块未激活**（红）——设备无 Xposed 环境 / 未勾选激活 / 未重启 / 框架不可识别（应用转生）/ 虚拟框架未适配
2. **系统服务未启动**（橙）——未勾选"系统框架"作用域 / 虚拟框架未适配
3. **系统服务版本错误**（橙）——更新后重启即可（对应 `IAlgorithmService.service_isRunning()/service_getVersion()`）
4. **日志列表为空**——作用域未选 App / 功能无日志输出 / **目标 App 存在对抗（hook 检测）** / 模块未生效

**【直接证据·manifest】** 相关界面：`FridaEditActivity`（脚本编辑）、`HookListActivity`、`LogListViewActivity`/`LogReadActivity`（日志展示）、`AddDynamicClass`、`DexViewActivity`。与注入弱相关（UI 层）。

---

## 5. 与 LSPFRIFA 对比要点（供整合参考）

| 维度 | 算法助手Pro（本报告） | LSPFRIFA（对照参考，未在本 APK 内验证） |
|---|---|---|
| 载体 | Xposed 模块（de.robv 旧 API，min 54） | Xposed 模块（同代 API） |
| 运行时 | 定制 frida GumJS/QuickJS .so（JNI 6 natives 直控） | GumJsBridge（JS↔native 桥） |
| 注入 | 目标进程经 Binder 拉 .so → System.load | 类似自研注入链 |
| 脚本配送 | DB+`app_getScript(pkg,name)` Binder 字符串 | （参考其脚本管理） |
| 日志回传 | pipe-over-Binder（`openFridaWritePipe`+fd） | TargetIpcServer |
| 错误反馈 | 4 级（模块未激活/系统服务未启动/版本错误/日志为空） | 同类反馈模式 |
| UI | Miuix 系 + WebUI（远程 junge666.cn）+ MCP 服务 | Miuix UI |

**【未确认】** 算法助手Pro 是否直接复用 LSPFRIFA 代码（仅"同为 Xposed+Frida 定制运行时"跨证据可比，代码级同源性未验证）。

---

## 6. 已确认 / 未确认清单

### 已确认（均有直接证据）
1. Xposed 模块载体：xposed_init→XpInit（IXposedHookLoadPackage），元数据 xposedmodule/min54/sharedprefs/scope=android+com.reqable.android
2. 无 frida-gadget 静态嵌入（无 gadget so / 无 .frida section / 无 gadget 字符串）
3. libalgorithmAidePro.so = frida 官方 CI 构建树定制运行时（GumJS+QuickJS+frida:rpc+java bridge；tcc 0.9.27-frida）
4. .so 经宿主 `:algorithm_ipc` Binder `service_getLibrary`→PFD→`<ctx>/lib/<UUID>`→`System.load`
5. 6 个 JNI natives（init/loadScript/setFridaLogPipe/isFridaLogPipeReady/unload/hookNetwork）+ 导出 hookNetwork/hookSignal/JNI_OnLoad
6. 配置体 = `globalThis.__algorithmAideBootstrap{bridgeSource,userScriptName,userScriptSource}`；bootstrap JS 先装 java bridge 再 `Script.evaluate(userScriptName,userScriptSource)`
7. 脚本配送 = (包名,脚本名) 维度，`app_getScript` Binder 字符串；宿主 db=`algorithmAidePro.db`
8. 日志回传 = `openFridaWritePipe(pkg,process)`→PFD→`setFridaLogPipe(fd)`→pipe→`FridaLogFrameCodec`→按包名 db；含 uid 校验
9. 热更新 = ConfigAction 广播 → ConfigReader.updateFromBroadcast
10. 注入触发链：handleLoadPackage→XpInit$a(hookAllMethods)→XpInit.a(ctx)→Ll3/h.newInstance()→NativeHook.b(ConfigReader)
11. 错误反馈 4 级（官方 FAQ）

### 未确认
1. `handleLoadPackage` 具体 hook 的类/方法名（字符串加密运行期解密）
2. `NativeHook.init(String)` 参数内容
3. 运行时上游变体（gadget fork vs core agent fork）
4. 宿主主进程是否也加载运行时
5. `app_getScript→loadScript` 的直接调用点未逐指令回溯（接口已证实）
6. 与 LSPFRIFA 的代码级同源性
