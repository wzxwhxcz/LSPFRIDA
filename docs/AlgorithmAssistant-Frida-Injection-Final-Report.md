# 《算法助手Pro Frida 注入逻辑拆解报告》最终整合

> 目标：算法助手Pro 1.1.0 debug（com.junge.algorithmAidePro，versionCode 110，22.8MB，单 dex 4117 类，minSdk 24 / targetSdk 29，签名 CN=Siyu/OU=E4A 南昌）
> 材料：① t1 拆解报告（frida-researcher，workspace s45zrswn）② integrator 独立交叉验证（workspace dkvf4y1n，zip 条目/native symbols/字符串矩阵/JNI 签名/资源表/链路 xref）③ LSPFRIFA 现状源码锚点（LSPFRIFAModule/TargetIpcServer/GumJsBridge/gumjs_bridge.cpp/ScriptStore/ScriptConfigProvider/IpcManager/AIDL，已逐文件核对）
> 证据标注：**【直接证据·来源】**＝直接从 APK 条目/反汇编/字符串/源码读出；**【推断·理由】**；**【未确认】**。Integrator 独立验证与 t1 **互洽，无冲突**；t1 有 4 处遗漏由本次验证补齐（见 §6）。

---

## 1. 依赖形态结论（一句话判定 + 证据）

**判定：自实现注入——Xposed 模块载体 + 目标进程内静态嵌入的官方 Frida 运行时定制版（GumJS/QuickJS agent runtime + 自研 bootstrap），完全排除 frida-gadget 标准静态注入与 frida-server 外部工具两类方案。**

| 候选形态 | 结论 | 证据 |
|---|---|---|
| frida-gadget 标准静态注入 | **排除** | 全 APK zip 仅 7 个 .so：libalgorithmAidePro.so / libcrashsdk.so（UC crashsdk，`com.uc.crashsdk.*`）/ libumeng-spy.so（友盟指纹，`Java_com_umeng_umzid_Spy_*`），**无任何 libfrida-gadget/libgadget/frida-agent 命名的 .so**；ELF 27 个 section 无 `.frida` 配置段；dex「Gadget」类名 0 命中、字符串「gadget」0 命中 |
| frida-server 外部工具 | **排除** | APK 内无 frida-server 二进制、无 root/注入器组件、无外部进程注入痕迹；注入完全发生在目标进程内部（Xposed 上下文） |
| 自实现 GumJS（当前判定） | **确认** | 见下方 5 条直接证据 |

**自实现的 5 条直接证据链**：
1. **载体是 Xposed 模块**：`assets/xposed_init`（38B）=`com.junge.algorithmAidePro.hook.XpInit`，`XpInit implements de.robv.android.xposed.IXposedHookLoadPackage`（旧 API，manifest `xposedminversion=54`，`xposedmodule=true`，`xposedsharedprefs=true`，`xposedscope=@7F030003`）。
2. **核心 .so 内嵌 Frida GumJS/QuickJS agent runtime**【native_strings 直接证据】：`GumQuickJSProbeListener / GumQuickJSEventSink / GumQuickJSCallListener`（QuickJS 后端类名）、`entrypoint-quickjs.js / error-handler-quickjs.js / ./core.js / /frida/runtime/message-dispatcher.js / console.js / hexdump.js / worker.js / _frida_worker_runtime.js / /frida/bridges/java.js`（agent runtime bundle 全套）、`frida:rpc`、`0.9.27-frida`（tcc）、构建路径 `/__w/frida/frida/deps/src/_sdk.out/android-arm64/lib/tcc`、`share/locale`、`var/lib/dbus/machine-id`。
3. **自研 bootstrap 而非 frida-gadget 配置文件**【.rodata 反解】：`(function(){ const bootstrap = globalThis.__algorithmAideBootstrap; ... installFrida17Compatibility(); installLazyJavaBridge(patchJavaBridge(bootstrap.bridgeSource)); Script.evaluate(bootstrap.userScriptName, bootstrap.userScriptSource); ... })` + 错误串 `throw new Error('Missing AlgorithmAidePro Frida bootstrap data')`——配置体是 JNI 从 Java 层推送的运行时对象 `{bridgeSource, userScriptName, userScriptSource}`，**不是文件**。
4. **JNI 动态注册 6 个 native 方法**【exports + dex 双向证据】：.so 导出 `JNI_OnLoad`（@0x39abdc，GetEnv→拷贝注册表→RegisterNatives 6 项），rodata 含类名 `com/junge/algorithmAidePro/hook/NativeHook`；dex `NativeHook` 6 个 `private native` 一一对应：`init(String)V`、`loadScript(String,String)I`、`setFridaLogPipe(I)Z`、`isFridaLogPipeReady()Z`、`unload()I`、`hookNetwork()I`。
5. **自研 native 能力 + 第三方 hook 库**【exports/strings】：导出 `_Z11hookNetworkv`(60B)@0x39b718、`_Z10hookSignalv`(288B)@0x39c110、`print_stacktrace`；rodata 含 **Dobby** 源码路径（`E:/projects/Android/algorithmAidePro/core/src/main/cpp/Dobby/source/MemoryAllocator/MemoryAllocator.cc / InterceptRouting.cpp / Backend/UserMode/UnifiedInterface/platform-posix.cc / InstructionRelocation/InstructionRelocationARM64.cc / FunctionInlineHook.cc / assembler-arm64.h`）→ .so 内嵌 Dobby inline-hook 框架（hookSignal/hookNetwork 的自研实现基座，也常见于 frida 生态做本地强化）。

**上游变体判定**：rodata 残留 `--only-section=.frida`（@0x44911）与 `  .frida 0x%zx: {`（@0x6402e）——这是 **frida-gadget fork 源码**（gadget 以 `.frida` ELF section 作配置载体的实现代码），但该 APK **未走**这条路径（无 .frida section、无配置文件），改为 JNI 运行时推送配置。**【推断·中置信】**：编译基 = frida 官方 CI 构建树（`/__w/frida/frida/deps/src/_sdk.out/...`）上的 gadget/agent 定制 fork，作者自行决定了「JNI 配置推送」路线；具体 fork 分支【未确认】。

---

## 2. 注入链路（5 段：入口 → .so 加载 → 配置 → 目标进程 → 脚本送达/回程）

### 2.1 入口
- 宿主 App（自身 UI 进程）：`MyApplication`；LAUNCHER `SplashActivity`→`MainActivity`；宿主侧服务 `AlgorithmServiceProvider`（**process=`:algorithm_ipc`**，AIDL `IAlgorithmService` 26 方法）、`ConfigProvider`（authority=`algorithmAidePro`，exported）、`McpServerService`（MCP 服务）、`FloatingKeepAliveService`；文件 Provider `fileProvider`。
- 模块入口：LSPosed 勾选作用域后，模块代码在**每个目标进程**加载 → 即 `XpInit.handleLoadPackage(LoadPackageParam)`（829 指令，内含 `XposedBridge.hookAllMethods`，类/方法名字符串加密运行期解密）→ 回调构造 `XpInit$a`（XC_MethodHook，beforeHookedMethod）→ 触发 `XpInit.a(context)`。

### 2.2 .so 加载（**不经 System.loadLibrary**，走 Binder 流式下发）
`XpInit.a(ctx)` → `Class.forName("Ll3/h").newInstance()` 强转 `Ll3/p`（NativeHook 基类）→ `ConfigReader` → `NativeHook.b(ConfigReader)` 编排，其中 `Lg3/e.b()`（LibraryLoader，**smali 逐指令核实**）：
1. `Context.getDir("lib", 0)` + mkdirs；
2. `AlgorithmClient.getClient(ctx).service_getLibrary("libalgorithmAidePro.so", abi)` —— 跨进程 Binder 从宿主 `:algorithm_ipc` 服务取 `ParcelFileDescriptor`；
3. 以 `<ctx>/lib/<UUID>` 临时文件落盘（1MB 块拷贝，日志 `Copied so file from IPC: ...` / `service_getLibrary returned null for ..., abi: ...`）；
4. `System.load(临时文件绝对路径)`（**直接 load 路径**）→ 成功即删临时文件，`HashSet` 防重复加载。ABI 由 `Process.is64Bit()` + 平台判定（arm64-v8a/armeabi-v7a/x86_64）。
> 与 LSPFRIFA 的差异点：LSPFRIFA 静态链接进模块 APK 直接 `System.loadLibrary("gumjs_bridge")`；算法助手把 .so 内容经 Binder 推送，意味着**引擎升级不需要重新打包目标 APK/模块**（服务端/宿主持有新版 .so 即可热更），且目标进程不留 .so 残留。

### 2.3 配置（无 assets 配置文件，运行时对象注入）
- assets/ 仅有 eula.html / help.zh.html / log.html / webui/index.html / xposed_init；**无** `frida-gadget.config.json`、**无** script.js。
- 配置体 = `globalThis.__algorithmAideBootstrap{bridgeSource, userScriptName, userScriptSource}`，由 native 注入全局对象。bootstrap 顺序：`installFrida17Compatibility()`（frida 17 兼容修补）→ `installLazyJavaBridge(patchJavaBridge(bridgeSource))`（**Java bridge 源码由宿主侧 JNI 提供**，打补丁后懒加载）→ `Script.evaluate(userScriptName, userScriptSource)`。
- 所以「配置」=「frida 17 兼容层 + Java bridge + 用户脚本」三件套的运行时装配，无静态文件。

### 2.4 目标进程与注入时机
- 目标 = 任一被 LSPosed 勾选的应用进程（默认 scope 资源 `xposed_scope` = **`android`（系统框架）+ `com.reqable.android`**；用户可自选）。FAQ 明确支持系统框架勾选（「系统服务未启动」状态即由此而来），并提及 VirtualXposed（dex 引用 `content://me.weishu.exposed.CP/`）/blackbox/VMOS 兼容。
- 注入时机 = 目标应用生命周期早期（hookAllMethods+beforeHookedMethod 触发）；**具体 hook 的类/方法名【未确认】**（字符串加密）。
- 【未确认】`NativeHook.init(String)` 参数内容；宿主主进程是否也加载运行时。

### 2.5 脚本送达与日志回程（IPC 配送，非 assets/远程）
- **配送（宿主→目标）**：维度 = (包名, 脚本名)；`IAlgorithmService.app_getScript(pkg, name)→String`（目标进程拉取源码）；宿主侧 `AppConfigManager`（`fridaDir`/`scriptList`/`app_importScript`/`app_deleteScript`/`app_setScriptAlias`）+ 数据类 `FridaScriptData/FridaScriptAppData`（Parcelable）+ `FridaScriptData` 的 `getFileName()`；宿主 db `algorithmAidePro.db`（`FridaLogDataDao`）。
- **回程（目标→宿主）**：`openFridaWritePipe(pkg, process)→ParcelFileDescriptor`（宿主 `:algorithm_ipc` 创建 pipe，**写端**给目标进程；uid 校验日志 `Rejected Frida writer: uid=...` + 包名正则 `[A-Za-z0-9_.:-]+`）→ `NativeHook.setFridaLogPipe(fd)` → native 运行时把 frida 日志写 pipe → 宿主读线程 `Lm3/c.run()`（DataInputStream 循环，失败提示 `Frida pipe failed/closed: package=`）→ `FridaLogFrameCodec` 帧解码 → `FridaLogData` → 按包名独立 db → UI（LogListViewActivity/LogReadActivity，`frida_log_count` = "%1$d条frida日志"）。
- **热更新**：`ConfigAction` 广播（`Frida script reload broadcast received:`）→ `ConfigReader.updateFromBroadcast`（对应 manifest `xposedsharedprefs=true`，ConfigReader 实现 SharedPreferences）→ 重新拉取/重载。

---

## 3. 与 LSPFRIFA 对比（机制差异 / 优缺点）

### 3.1 逐维度对比表

| 维度 | 算法助手Pro | LSPFRIFA 现状（源码核对） | 评价 |
|---|---|---|---|
| 注入载体 | Xposed 模块，**de.robv 旧 API**（min 54，兼容 LSPosed/黑盒/VMOS/虚拟化） | Xposed 模块，**libxposed api-102 / XposedModule**（java_init.list 注册） | LSPFRIFA 更现代；算法助手兼容面更广（旧框架/虚拟环境） |
| 注入时机 | hookAllMethods + beforeHookedMethod（类/方法加密，时机=应用生命周期早期） | libxposed `hook(ActivityThread.callApplicationOnCreate)`（时机=Application 就绪后） | 同构；LSPFRIFA 时机更明确可控 |
| 引擎 | 定制 frida agent runtime（**QuickJS 后端全套**：entrypoint/core/error-handler/message-dispatcher/console/hexdump/worker + `frida:rpc` + **/frida/bridges/java.js**）+ **Java bridge 显式运行时装填**（`__algorithmAideBootstrap.bridgeSource`） | frida-gumjs devkit（`gum_script_backend_obtain_qjs()`，**gum 层 API 为主**） | **重大差异**：算法助手脚本具备 `Java.perform/Java.use/Java.choose` 级 Java hook 能力（agent runtime + java bridge + frida:rpc）；LSPFRIFA 仅 gum 层（Interceptor/Memory/Module），**JS 侧无 Java bridge** |
| 引擎交付 | Binder 流式下发 .so → `System.load`（**引擎可热更、不落盘**） | 模块 APK 静态链接 `System.loadLibrary`（**引擎随模块版本**） | 算法助手引擎升级链路更灵活 |
| native 能力面 | 6 JNI（init/loadScript/setFridaLogPipe/isReady/unload/hookNetwork）+ 导出 hookSignal + **内嵌 Dobby**（inline hook 框架） | 4 JNI（init/loadScript/unload/callback）+ 无第三方 native hook 库 | 算法助手覆盖 signal/network 自研 hook；LSPFRIFA 纯 gum |
| 脚本管理 | **(包名, 脚本名) 多脚本**：`scriptList/app_importScript/app_deleteScript/app_setScriptAlias` + 宿主 db + fridaDir + UI（FridaEditActivity 编辑器，「新建 Frida 脚本」） | **单脚本/目标**：`ScriptStore`(SharedPreferences) + `IpcManager.pushScript` + 项目选择器/详情 UI | 算法助手脚本库更成体系（多脚本+别名+编辑器+导入导出） |
| 日志回程 | **pipe**（openFridaWritePipe + PFD + FridaLogFrameCodec 帧协议 + 按包名 db + uid/包名校验 + fridaStats） | **Binder**（ILogReceiver.onLog 逐行跨进程推送宿主 UI）+ Logcat 本地备份 | 算法助手：高吞吐帧协议+持久化+鉴权+统计；LSPFRIFA：实现简单、近实时（逐行） |
| 进程隔离 | IPC 逻辑在独立 `:algorithm_ipc` 进程（服务崩不影响 UI） | Provider/Binder 在宿主主进程 | 算法助手隔离性更好；LSPFRIFA 实现更简单 |
| 目标管控 | `getAppsWithSwitch/app_setSwitch/app_isSwitch/service_startApp/forceStopApp/appIsRunning`（每目标开关 + 启停管控） | `ScriptStore.enableTarget/disableTarget/isTargetEnabled` + 系统关键进程黑名单 | 算法助手管控面更全（含启动/停止应用） |
| 错误反馈 | 官方 FAQ **4 级**：模块未激活(红)→系统服务未启动(橙)→版本错误(橙)→日志列表为空（含对抗/hook 检测提示） | Logcat + 宿主 UI 日志流；（无分级诊断） | 算法助手有**可执行的分级排障手册**（信息架构值得学，非照搬） |
| UI/生态 | Miuix 系 + `webui/index.html`（远程 junge666.cn）+ **MCP 服务**（McpServerService） | Miuix Compose UI；无 web/MCP | LSPFRIFA 尚无远程/自动化接口面（MCP 是后续可选方向） |

### 3.2 优缺点小结
- **算法助手 Pro 强项**：① 完整 agent runtime + Java bridge（JS 全能力，能 hook Java 层）；② .so Binder 流式下发（引擎热更新）；③ pipe 帧协议日志（吞吐/持久化/鉴权/统计）；④ 多脚本库 + 模块/进程/目标三层管控；⑤ 4 级排障反馈 + 虚拟框架兼容。
- **算法助手 Pro 弱项**：① 旧 Xposed API（min54）与现代框架（libxposed 102）脱节，长期维护性差；② 字符串加密/混淆重（XpInit/ConfigReader 全串密文），调试与二次开发成本高；③ 单 dex 全量内嵌 agent runtime（arm64 6.1MB），体积/内存开销大；④ 依赖宿主 `:algorithm_ipc` 常驻（FloatingKeepAliveService + 前台服务保活），达成注入前置条件多。
- **LSPFRIFA 强项**：① libxposed 102 现代规范；② GumJS 零重启热加载（`gum_script_load_sync` 即换即生效，无需重启目标进程——算法助手的 Script.evaluate 是否免重启未确认）；③ 架构轻、代码短、无混淆，可维护性高；④ 注入时机明确（callApplicationOnCreate）。
- **LSPFRIFA 弱项**：① 脚本无 Java bridge / rpc（JS 能力面窄）；② 单脚本无多脚本库；③ 日志无持久化/鉴权/统计；④ 无引擎热更；⑤ 错误反馈无分级。

---

## 4. 可借鉴点（口径：不照搬，学组织/能力/反馈模式）

1. **组织模式**：脚本按 **(目标包名, 脚本名)** 组织 + 宿主侧 db/目录 + 别名 → 映射为「每目标应用一项目，项目内多脚本」；`app_importScript/app_deleteScript/app_setScriptAlias/scriptList` 接口族 → 对照 `ScriptStore/ScriptConfigProvider` 的扩展清单。**不学**：其字符串加密/混淆、旧 API。
2. **能力模式**：① **Java bridge 运行时装配**（`__algorithmAideBootstrap.bridgeSource` + `installLazyJavaBridge` + frida17 兼容层）→ 若 LSPFRIFA 要支持 `Java.perform`，这是已验证的组织方式（bootstrap 先装 bridge 再 evaluate 用户脚本）；② **pipe 帧协议日志回程**（写端给目标、读线程解码、帧编解码器、按包名持久化、uid 鉴权）；③ **Binder 流式下发引擎**（PFD 传输 .so + UUID 临时文件 + load 后删除）→ 引擎升级不依赖应用重装。
3. **反馈模式**：4 级排障（模块未激活→系统服务未启动→版本错误→日志为空）+ 每级给具体行动（勾选系统框架/重启/对抗提示）→ 比「日志列表全量展示」更贴近用户排障；LSPFRIFA 可映射为「连接状态→引擎状态→脚本状态→日志」四段状态机（已有部分基础：ping/registerLogReceiver/离线条目）。

---

## 5. 冲突 / 未确认项标注

### 5.1 冲突项
**无实质冲突**。integrator 独立验证（zip 清单、native symbols/strings、JNI 注册表、xposed_scope 资源、LibraryLoader smali、链路 xref、Dobby 路径、QuickJS 类名）与 t1 报告的每一项结论均互洽。仅两处表述差异（非冲突）：
- t1 称「内嵌 Frida 运行时（gumjs+QuickJS）」→ 本次验证**按 agent runtime 全套**精化（含 worker/rpc/bridges/java.js，见 §1.2）。
- t1 判定「与 LSPFRIFA 同源做法」→ 补充限定：**同为「官方 frida 构建树→静态链接→JNI 暴露」路线，但算法助手含完整 agent runtime（Java bridge/rpc），LSPFRIFA 仅 gum 层——不同深度**（§6-补充2）。

### 5.2 未确认清单（含 t1 + 本次新增）
| # | 事项 | 级别 | 备注 |
|---|---|---|---|
| 1 | handleLoadPackage 具体 hook 的类/方法名 | 未确认 | 字符串加密（全密文），静态分析无效 |
| 2 | `NativeHook.init(String)` 参数语义（会话配置 JSON? 脚本标识?） | 未确认 | 需要动态 hook 或脱壳运期解密 |
| 3 | 上游 fork 分支（gadget fork vs core agent fork） | 中置信推断 | `.frida` section 残串→gadget 系；无 section→未走 gadget 路径 |
| 4 | 宿主主进程是否也加载运行时 | 未确认 | 未见 `Lg3/e` 出 xposed 路径的证据 |
| 5 | `app_getScript → loadScript` 直接调用点 | 未确认 | 接口已证实，单调用点未逐指回溯（混淆） |
| 6 | 与 LSPFRIFA 代码级同源性 | 未确认 | 仅模式级可比 |
| 7 | **`hookNetwork()` 用途与 Dobby 集成方式**（拦截网络 API？配合 Reqable scope？） | 未确认 | 导出符号+源码路径+dex native 三角已证存在，语义未解析 |
| 8 | `algorithmAidePro.js`（.rodata @0x49e19）角色（默认 userScriptName? 打包资源?） | 未确认 | 字符串存在，引用未定位 |

---

## 6. Integrator 独立验证对 t1 的补充（本次新增证据）

| # | 补充 | 证据 | 意义 |
|---|---|---|---|
| 1 | **内嵌 Dobby**（t1 未提） | .rodata 6 条 `Dobby/source/*.cc/.h` 路径（MemoryAllocator/InterceptRouting/platform-posix/InstructionRelocationARM64/FunctionInlineHook/assembler-arm64.h） | .so = frida runtime + Dobby 双框架；hookSignal/hookNetwork 有自研实现基座 |
| 2 | **完整 agent runtime 而非仅 gumjs**（t1 说「gumjs+QuickJS」） | `message-dispatcher.js/console.js/hexdump.js/worker.js/_frida_worker_runtime.js//frida/bridges/java.js/frida:rpc` + `GumQuickJS*` 类名 + `entrypoint-quickjs.js` | 能力面：脚本可 `Java.perform`（bridge 经 bootstrap 注入），**高于** LSPFRIFA 的 gum 层 |
| 3 | **Java bridge 显式运行时装填**（t1 只描述了 bootstrap 结构） | bootstrap JS：`installFrida17Compatibility(); installLazyJavaBridge(patchJavaBridge(bootstrap.bridgeSource)); Script.evaluate(...)` + 错误串 | 模式可复用：bridge 源由 JNI 推送、先装兼容层再装 bridge 再跑用户脚本 |
| 4 | **JNI 注册为 RegisterNatives 动态注册**（t1 说「memcpy 注册表」） | rodata `com/junge/algorithmAidePro/hook/NativeHook` + JNI_OnLoad 反汇编 + 导出 hookNetwork/hookSignal 与 dex native 一一对应 | 完整闭环：dex native 声明 → RegisterNatives → 导出实现 |
| 5 | x86_64 也有 libalgorithmAidePro.so（7.2MB，无 crash/umeng 的精简版） | zip 条目 | 模拟器可用，且是「引擎包可独立分发」的又一佐证 |

---

## 7. 与 LSPFRIFA 的落地映射（给 LSPFRIFA 的可执行建议）

### 7.1 【锚点】LSPFRIFA 现状（本项目源码逐文件核对）
- `LSPFRIFAModule`（libxposed 102）：onPackageLoaded → 过滤系统关键进程 → hook `ActivityThread.callApplicationOnCreate` → onApplicationCreated → `isTargetEnabled`（Provider 查询）→ `System.loadLibrary("gumjs_bridge")` + `GumJsBridge.init()` → `TargetIpcServer(targetPackage)` → Provider `register_ipc` 注册 Binder → `loadInitialScript`（Provider get_script）。
- `TargetIpcServer`（Binder 实体）：loadScript/unloadScript/registerLogReceiver/ping；init 时注册 `GumJsBridge.OnScriptMessage`：Logcat 备份 + `ILogReceiver.onLog` 跨进程推送。
- `GumJsBridge.kt` + `gumjs_bridge.cpp`：`gum_init_embedded` + `gum_script_backend_obtain_qjs()`（QuickJS）+ `gum_script_backend_create_sync` + `set_message_handler` + `load_sync`（**零重启热加载**）+ GMainContext 泵。
- `ScriptStore`（SharedPreferences）+ `ScriptConfigProvider`（call 方法族）+ `IpcManager`（连接池/日志监听/推送/启停）+ Miuix Compose UI（Dashboard/ProjectDetail/ProjectScreen/SelectProject/Settings）。

### 7.2 差距→建议（按投入/收益排序，保持 GumJS 架构，不引入多语言/不换引擎）

**P0（能力补齐，直接对标差距）**
1. **Java bridge / agent runtime 装配**：算法助手已验证「`installFrida17Compatibility → installLazyJavaBridge(bridgeSource) → Script.evaluate(userScript)`」装配顺序。LSPFRIFA 若需 `Java.perform/Java.use`（对「注入调试工具」是刚需），建议仿照此模式：native 侧注入 `__bootstrap` 全局对象（含 java bridge 源码串），bootstrap 先装兼容层再 evaluate 用户脚本；或在 devkit 层增加 `frida-agent runtime bundle` 嵌入（体积换能力，需评估 APK 体积）。
2. **日志回程升级为 pipe 帧协议**：现为 Binder 逐行 `ILogReceiver.onLog`（可另留作近实时通道）。建议：`openFridaWritePipe`-式 PFD 管道 + `FridaLogFrameCodec`-式长度前缀帧（readString/writeError 已在其 dex 中验证可行）+ 目标侧 `setFridaLogPipe(fd)` + 宿主读线程 + **按目标包名持久化**（现 ScriptStore 只存脚本不存日志）+ uid/包名校验（安全：任意 uid 伪造日志）。
3. **多脚本组织**：`ScriptStore` 从「包名→单脚本」升级为「包名→脚本列表（名称/别名/源码/启停）」；`ScriptConfigProvider` 增加 `listScripts/importScript/deleteScript/setScriptAlias` call 方法；UI 项目详情页加「脚本列表 + 新建脚本」（对齐其 FridaEditActivity + fragment_frida_script 交互，Miuix 风格实现）。
4. **排障反馈 4 级状态机**：UI 顶部状态条：模块未激活（框架未启用）→ 目标进程未连接（ping 失败）→ 引擎加载失败（init 返回 false）→ 脚本状态（已加载/错误）；每级给行动建议（对齐其 FAQ 模式）。这可直接复用现有 `ping()/registerLogReceiver()` 通道，成本低。

**P1（工程改进）**
5. **.so 流式下发（引擎热更）**：评估把 `gumjs_bridge.so` 从模块内置改为宿主→目标 PFD 流式传输 + `System.load`（其 `service_getLibrary` 模式已验证：UUID 临时文件 + 删后即焚）。收益：引擎升级不重装、目标不留 so；**不影响**零重启热加载——.so 加载仍是一次性的，脚本热加载保留现有 `gum_script_load_sync`（比其 Script.evaluate 更优，建议保留并明示为差异化优势）。
6. **独立 IPC 进程**：把 Provider/Binder/日志读取放入 `:ipc` 独立进程（对齐 `:algorithm_ipc`），UI 崩溃/卡顿不影响注入通道；代价是跨进程 state（ScriptStore 需迁移到 db/文件）。

**P2（生态可选，非任务范围）**
7. MCP 服务面（其 `McpServerService`+`ServerFeature` 模式）——若未来要让外部 AI 工具/桌面端编排注入，可参考；本次不实现。
8. 虚拟框架兼容（content://me.weishu.exposed.CP/ 等）——仅在需要「非 root/免 LSPosed」投放时评估，当前不引入。

### 7.3 明确不学（口径重申）
- **不引入**多脚本引擎（Rhino/BSH/LuaJava——那是 LSPilot 路线，非本目标）、**不换掉 GumJS**、**不学**其字符串加密/元器混淆（增加维护成本，无安全收益）、**不回退** libxposed 102 API（min54 是历史包袱，LSPFRIFA 保持现代 API）。

---

## 附录 A｜关键证据字符串索引（integrator 复核版）

| 字符串/符号 | 位置 | 含义 |
|---|---|---|
| `com.junge.algorithmAidePro.hook.XpInit` | assets/xposed_init | Xposed 入口 |
| `de.robv.android.xposed.IXposedHookLoadPackage` | XpInit sig | 旧 API 载体 |
| `xposedminversion=54 / xposedscope=android,com.reqable.android` | manifest / 0x7f030003 | 作用域（系统框架+Reqable 默认） |
| `GumQuickJSProbeListener/GumQuickJSEventSink/GumQuickJSCallListener` | .rodata 0x4478d/0x4ba1b/0x68adb | QuickJS 后端类 |
| `entrypoint-quickjs.js/core.js/error-handler-quickjs.js/message-dispatcher.js/console.js/hexdump.js/worker.js/_frida_worker_runtime.js` | .rodata 0x4b958 起 | agent runtime bundle |
| `/frida/bridges/java.js` + `frida:rpc` | .rodata 0xb5571/0xb9960 | Java bridge + RPC 能力 |
| `Missing AlgorithmAidePro Frida bootstrap data` | .rodata 0xb3846 | bootstrap 校验错误 |
| `installFrida17Compatibility()/installLazyJavaBridge(...)/Script.evaluate(bootstrap.userScriptName, bootstrap.userScriptSource)` | .rodata 0xb3897 起 | 装配顺序（frida17 兼容→java bridge→用户脚本） |
| `0.9.27-frida`（tcc）/`/__w/frida/frida/deps/src/_sdk.out/android-arm64/...` | .rodata 0x4809d/0x603d5 | 官方 frida CI 构建树 |
| `--only-section=.frida` / `  .frida 0x%zx: {` | 0x44911 / 0x6402e | gadget fork 源码残留（未使用） |
| `Dobby/source/.../MemoryAllocator.cc 等 6 条` | 0x52387 起 | 内嵌 Dobby hook 框架（**integrator 新增**） |
| `com/junge/algorithmAidePro/hook/NativeHook` | .rodata 0x432cc | RegisterNatives 目标类（**integrator 新增**） |
| `Unable to create GumJS script: %s / Unable to start GumJS main loop` | 0x4b901 / 0x65c21 | GumJS 运行错误串 |
| `setFridaLogPipe/isFridaLogPipeReady` | .rodata 0x4b8ed/0x6be18 | pipe 日志 JNI |
| `libalgorithmAidePro.so`（dex `Lg3/e`）+ `service_getLibrary returned null for ..., abi:` | dex 字符串 | Binder 送 .so |
| `Frida pipe failed/closed: package=`、`Rejected Frida writer: uid=` | dex（Lm3/c 等） | 日志管道错误/鉴权 |
| `Frida script reload broadcast received:` | dex Lx2/b | 热更新广播 |
| `algorithmAidePro.js` | .rodata 0x49e19 | 角色未确认（见 §5.2#8） |
| `exported JNI_OnLoad/hookNetwork/hookSignal/print_stacktrace` | .dynsym | native 自研能力面 |

*本报告由 t1 拆解报告 + integrator 独立交叉验证 + LSPFRIFA 源码锚点三方整合；无冲突，4 处遗漏已补齐（§6），8 项未确认已列明（§5.2），落地建议按 P0/P1/P2 排序且保持「不照搬」口径。*
