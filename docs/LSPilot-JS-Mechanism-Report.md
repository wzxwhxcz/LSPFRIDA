# 《LSPilot JS 脚本调用机制还原》最终整合报告

> 目标：me.yun.lspilot 1.0.9（versionCode 10，minSdk 33，targetSdk 37，Xposed 模块，xposedminversion 82）｜MT MCP 反编译 workspace rp2zaf5m（10 dex / 70,092 类）
> 材料：① t1 js-engine 全链路报告 ② t2 hook-api 全链路报告 ③ integrator 名称级验证基线（Rhino/bsh/LuaJava/无 JSR-223/结构）④ APK 官方插件文档 assets/plugin-doc.md（1107 行）⑤ 本机插件目录实测。
> 冲突标注：t1 / t2 / 名称级基线三方结论互洽，无冲突（细节互补：t1 补 ht9 方法级与入口优先级，t2 补 HookProvider 双实现与 HookParam 适配，基线补包名/native 库佐证）。

## 0. 结构骨架
- assets/xposed_init = me.yun.lspilot.loader.Xp82Entry；另有 loader/Xp102Entry（libxposed 102）；manifest：io.github.libxposed.service.XposedProvider(authorities me.yun.lspilot.XposedService)；com.rk.terminal.SessionService(:terminal, FGS specialUse) + com.rk.activities.terminal.Terminal(:terminal)
- lib/(arm64-v8a)：libloader.so(18KB)、libluajava.so(617KB)、libdexkit.so、libmemsearch.so、liblink2symlink.so、libproot.so、libtermux.so、libxed_cli.so、libandroidx.graphics.path.so——无任何 JS 引擎 .so
- assets 关键：plugin-doc.md、ai-system-prompt.md、kotlinc-resources.zip+android.jar（内嵌 Kotlin 编译器，编辑器/终端侧）、terminal/lsp/*.sh、textmate/*、xposed_init
- 注：org.jetbrains.kotlin.cli.common.repl.KotlinJsr223* 属内嵌 Kotlin 编译器 REPL（编辑器/终端功能），非插件引擎；无 javax.script（JSR-223）插件引擎。

## ① 三语言引擎结论
| 语言 | 引擎 | 入口文件 | 关键证据 | 置信度 |
|---|---|---|---|---|
| JavaScript | Mozilla Rhino（纯 Java，org.mozilla.javascript.* 全量内置 DEX） | main.js | ht9：j()=Context.enter→setOptimizationLevel(0) 解释模式→setLanguageVersion(0xb4=180,ES6)→new ImporterTopLevel(cx,false)；e()=readText(UTF-8)+Context.evaluateString(scope,text,fileName,1,null)；k()=Context.exit()；唯一引用类 plugin/js/api/LoadPathApi（compiler:Lht9/cx:LContext/scope:LScriptable）；版本串取自 MANIFEST.MF Implementation-Version；类集含 Interpreter/InterpreterData/JSCodeExec/JSCodeResume/lc.type.TypeInfoFactory/nvl/mvl→Rhino 1.7.x（≥1.7.14 特征） | 高 |
| Lua | LuaJava（org.keplerproject.luajava + libluajava.so 617KB） | main.lua | w6d.n()（代理对象回调/import/全局注册 Mem/pluginInfo）；错误串 "LuaState peer 为 0/import: expected class name/[Lua Hook Error]/No matching overload"（w6d） | 高 |
| BeanShell | 经典 BeanShell（根包 bsh：bsh.This/This$Keys/classpath.ClassManagerImpl；非 org.apache.bsh 3.x 包名） | main.java | e51 + d()（bsh/r 解释器）；BSH 内嵌 bsh.*；文档 plugin-doc.md 全篇以 BSH 为主语言（自定义预处理 KtStringTemplate/DefaultArgsDesugar） | 高 |
| （旁系） | 内嵌 Kotlin 编译器 REPL（KotlinJsr223*+kotlinc-resources.zip+android.jar） | — | 名称级；编辑器/终端侧 | 中 |

**冲突矫正（重要）**：任务标题「JS 脚本」不准确——官方文档明言主语言为 BSH；APK 同时存在 plugin/js|bsh|lua/api/LoadPathApi 三语言入口（各 8/8/12 方法）。主语言＝BSH（文档证据）；JS/Lua 为辅（多语言入口存在）；引擎＝纯 Java（Rhino/LuaJava/BSH，无 .so）。

## ② 插件扫描与加载链
1. 双入口：Xp102Entry.onPackageLoaded（libxposed 102）/ Xp82Entry.handleLoadPackage（legacy 82；自 hook ModuleStatusKt.isModuleActive→true）→ HookRouter.provider = ps7(libxposed 102 XposedInterface) / qs7(API82)
2. 初始化钩：ntc.c() 反射 hook ActivityThread.callApplicationOnCreate（mtc 回调）→ ntc.a(HookParam)（arg(0)=context）→ jtc.a(app)（ntc.b 初始化 itc 宿主状态；串 "Failed to initializeContext"/"Failed to injectApplication"/"callApplicationOnCreate"）→ context.getPackageName → nwf.e(hostPkg)
3. 路径解析：nwf.c(pkg) = Environment.getExternalStorageDirectory() + "/Android/media/" + pkg + "/LSPilot/Plugin" → mkdirs → nwf.h() 扫描
4. 扫描与校验（nwf.h/d）：listFiles 遍历子目录 → nwf.d 校验（存在 main.java|main.lua|main.js 且 info.prop）→ new Ldwf(dir)（info.prop: name/author/version/desc；入口优先级 main.java > main.lua > main.js）→ CopyOnWriteArrayList → nwf.f 检查 <pluginPath>/.enabled
5. 并发加载：线程池（availableProcessors）execute(new Lkwf) → nwf.a/i → nwf.g 按 Loyf 枚举(BSH/LUA/JS) ordinal 分发：new e51+d()（BSH）/ w6d.n()（Lua）/ ht9.e()（JS）；成功入 ConcurrentHashMap<pluginPath, runtime> 运行时注册表；异常 jwf.c 记日志（不炸宿主）
6. JS 执行（ht9.e）：每插件独立 Rhino Context + ImporterTopLevel scope（互不共享、并行线程）；入口 readText(UTF-8)+evaluateString(scope,text,fileName,1,null)；loadJs 相对 pluginPath 解析（串 "loadJs: failed to load "/": file not found "）；loadJar/loadDex/loadAar/loadApk 为 JS 侧空桩；卸载/停止：nwf.l → ht9.k()=Context.exit()
7. 文档生命周期：设置全局变量 → 注册 API 方法 → 执行入口；停止清理解释器命名空间；改动插件需重启宿主 App
8. 实测目录：/storage/emulated/0/Android/media/me.jsonet.jshook/LSPilot/Plugin/a/ = main.lua + info.prop + .enabled/.debug/.process_mode
   - 宿主包名疑点已解析：hostPkg=当前进程（被 Hook 宿主）包名（jtc.a→context.getPackageName→nwf.e），非 LSPilot 自身、非硬编码（APK 内无 jsonet/jshook 字符串）；本机 me.jsonet.jshook=被 Hook 宿主（JSHook，疑似同作者旧产品）；LSPilot 自身扫描 Android/media/me.yun.lspilot/LSPilot/Plugin
   - .process_mode 已定位：类 t50（插件目录助手）b()=读文本(Lh8g)/e()=写；w60（插件管理器）J() 迭代读 .enabled+.process_mode——语义=插件按进程模式配置；w60.M() 用 Properties 创建 info.prop（键 name/author/version/desc，独立验证文档 §2）；w60.t() 含 "js"/"lua" 类型+名称正则消毒([^a-zA-Z0-9_\-一-龥])+调 nwf.c
   - .enabled：nwf.f/k 读写（存在=启用）；.debug：文档「动态调试」悬浮球注入

## ③ 全局 API 注册与沙箱
JS（Rhino 命名空间，ht9.h/i 注入；t1+t2 一致）：
| 全局 | 指向 | 说明 |
|---|---|---|
| hook / dexkit / reflect | HookRouter / DexKitFinder / ReflectUtils | NativeJavaClass；三者全部 public static 方法注册为无前缀全局函数（包装类 rnd extends BaseFunction：Method.invoke+it9 参数转换，ht9.f/g） |
| 无前缀 hook 函数族 | hookBefore/hookAfter/hookReplace/hookMethodBefore/hookMethodAfter/hookAllMethodsBefore/hookAllMethodsAfter/hookAllConstructorsBefore/replaceHook/unhook | 与 plugin-doc.md §6 词表一致 |
| loader | LoadPathApi 实例 | loadJs/loadJar/loadDex/loadAar/loadApk（后四者空桩） |
| console | Lao3(log/info/warn/error) | |
| Mem | plugin/api/Mem | 内存搜索入口 |
| log/toast | MethodApi 包装（cxc/xgk） | log→<pluginPath>/log/<日期>.log+Logcat 双通道；toast 主线程 Handler |
| importClass | m98 | 宿主 ClassLoader 感知 |
| 预置变量 | pluginSdk=1/pluginName/pluginAuthor/pluginVersion/pluginPath | + hostVerName(itc.f)/hostVerCode(itc.e)/hostContext(itc.d)/hostLoader(itc.c) |
| 回调转换 | JS 函数经 Rhino InterfaceAdapter 自动转 JavaHookCallback(SAM) | |

BSH（文档+dex 证据）：预导入 HookParam/HookHandle/HookBuilder/JavaHookCallback/ClassFinder/MethodFinder/FieldFinder；全局 hook/反射/DexKit/log/toast/loadJava；宿主信息 hostLoader/hostContext/hostVerName/hostVerCode/plugin*；扩展语法=源码预处理（KtStringTemplate 字符串插值、DefaultArgsDesugar 默认参数；弱类型/分号可选/lambda 适配 SAM/三引号/增强 for/多 catch/try-with-resources）
Lua：Luajava import 函数 + 全局注册 Mem/pluginInfo 等（代理对象回调）
沙箱（ht9.b）：删除 javax/org/edu 前缀类访问 + 屏蔽 print/load/quit/readFile/readUrl（串 "javax,org,edu,print,load,quit,readFile,readUrl"）

API 词表（plugin-doc.md §6–8，与 dex 互证）：Hook 全局函数（hookBefore/After/Replace 可带 ID、hookMethodBefore/After、hookAllMethodsBefore/After、hookAllConstructorsBefore、replaceHook(id,cb)、unhook(id)）；HookParam（thisObject/args/result/throwable/hasThrowable/arg(i)/setArg(i,v)/skipWith(v)；skipWith 仅 before；before 设 result 原方法仍执行除非 skipWith）；反射全局函数（findClass/OrNull、findField/findFirstFieldByType/allFields、get/set*（Object/String/Int/Long/Boolean/Float/Double/Byte/Static*）、callMethod/callStaticMethod/…Exact、newInstance/…Exact、getEnumConstant/newArray、set/getAdditionalField- WeakHashMap 附加字段）；DexKit（ClassFinder/MethodFinder/FieldFinder，终止 single()/singleOrThrow()/list()，精确匹配）

## ④ Hook 调用链（脚本 → 目标进程回调；t1/t2 一致）
脚本 hook.hookBefore(member,fn)            // fn 经 InterfaceAdapter→JavaHookCallback
 → HookApiKt(11 方法, Function1 DSL)        // 最上层 Kotlin DSL
 → HookRouter(Java static facade,22 方法, JavaHookCallback, 错串"Method  not found in class ")
 → HookProvider(7 方法 SPI: hook/hookReplace/replaceHook/unhook/log)
 → 双实现：ps7=libxposed api-102（XposedInterface.hook(Executable).setId(id).intercept(ns7 Hooker)，id 存 ConcurrentHashMap）｜qs7=legacy（XposedBridge.hookMethod, qs7$b/$c 回调类）
 → 调用时 ns7.intercept(Chain) → ps7.a：p6d(HookParam 实现, 反射适配 Chain/MethodHookParam)
     before → skipWith 则直接 setResult 返回 → 否则反射 proceed(args) → after → 返回
 → 句柄=z2m(libxposed)/legacy；unhook via z2m/XposedInterface$HookHandle
HookBuilder DSL{id,before,after}；HookHandle=unhook()；生命周期：插件加载(②)→执行入口→注册 hook→目标方法被调→链上回调；取消/热替换以 ID 为中心（ConcurrentHashMap 注册表）。
桥接/跨进程：不存在名 TargetIpcServer 的类（任务提示「同类」实为 LSPFRIFA 自己的类名）；跨进程＝Mem.init(pid)→JNI libmemsearch.so（MemorySearchBridge 52 native 方法），无独立 IPC Server；XposedProvider 仅向框架提供模块服务；SessionService 仅终端。

## ⑤ Mem / DexKit / WebSearch / Terminal
- 内存：Mem(plugin/api,110 方法)→MemorySearchManager(loader/tool,81 方法；FreezeEntry/FuzzyCondition/MemType/ValueType/SearchStatus)→MemorySearchBridge(52 native, libmemsearch.so JNI)；JS 暴露 Mem.INSTANCE
- DexKit：DexKitFinder/DexKitManager + org.luckypray.dexkit 桥（libdexkit.so 381KB）；JS 暴露 dexkit；BSH 预导入 Finder；dexkit 包内 JavaLanguage(24)/SoraCompletionResultSet/SoraVirtualFile 为编辑器（JS/Lua）补全功能，非插件运行时
- WebSearch：WebSearchManager(11 方法+SearchResult)＝Bing 抓取，仅模块内部，未暴露给脚本
- Terminal：com.rk.terminal.SessionService(:terminal,FGS specialUse)+Terminal 活动+assets/terminal/*.sh（universal_runner/sandbox/setup）+libproot/libtermux/libxed_cli——完整终端+语言服务栈，与插件引擎无关（旁系）

## ⑥ 对 LSPFRIFA 的借鉴（用户口径：仅组织/能力/反馈模式；保持 Frida GumJS 架构，不引入多语言引擎、不换 GumJS）
> LSPFRIFA 现状：com.bail.lspfrifa，libxposed api+service 102.0.0，NDK 静态 frida-gumjs(arm64/arm)，xposed/LSPFRIFAModule→GumJsBridge→TargetIpcServer+ipc/ScriptStore，Miuix Compose UI。

1) 脚本加载组织：① 目录约定+info.prop 元信息(name/author/version/desc)+.enabled 启停标记+入口探测优先级(main.java>main.lua>main.js)+自动 log/ 目录→可统一 ScriptStore/ScriptConfigProvider 为「目录+元信息+启停+入口约定」；② 扫描→校验→线程池加载→运行时注册表(ConcurrentHashMap<path,runtime>)→卸载清理 的生命周期模型；③ 每目标宿主包名命名空间化（Android/media/<pkg>/LSPilot/Plugin/）→ 按目标应用/项目分目录；④ 加载顺序契约（全局→API→入口）可预期可复现；⑤ 改动需重启宿主（简单可靠；GumJS 侧可评估 attach/detach 热切换作为自身优势）
2) Hook API 能力面（能力对照表）：HookParam 上下文（thisObject/args/result/throwable/arg/setArg/skipWith）≈ GumJS InvocationContext；before/after/replace 语义 + ID 注册表驱动的 replaceHook/unhook 热替换（GumJS 用 script id 映射）；四级注册（Member/类名+方法名/全部同名重载/全部构造器）便利面；反射辅助全套（父类链自动上溯/缓存/accessible/WeakHashMap 附加字段）→ JavaBridge 封装补齐；DexKit 链式 Finder（single/singleOrThrow/list 统一终止）API 设计；内存模糊搜索/冻结（MemType/ValueType/FreezeEntry）对照 MemoryAccessMonitor/Memory.scan
3) 错误反馈方式：① 双通道日志（Logcat + <pluginPath>/log/<日期>.log）② jwf 异常兜底不炸宿主③ 沙箱显式屏蔽危险 API（javax/org/edu+print/load/quit/readFile/readUrl）→ LSPFRIFA 建议：每脚本独立错误日志（文件+UI 列表）、语法/运行时错误在项目详情页直接提示、沙箱违例给可读消息；.debug 调试模式+悬浮球注入为可选动态调试入口

## ⑦ 用户拍板结论项（醒目标注）
「目标应用的 BSH/Rhino/Lua 多语言脚本机制只是 LSPilot 自身的实现选择——LSPFRIFA 不照搬：保持现有 Frida GumJS 架构不变，仅学习三点：①脚本加载组织（插件目录/注册/生命周期）；②Hook API 能力面（其 API 清单作为我们能力对照表）；③错误反馈方式（脚本错误如何暴露给用户 UI/日志）。各节的"对 LSPFRIFA 建议"一律按此口径（只列可借鉴的组织/能力/反馈模式，不推荐引入多语言引擎/换掉 GumJS）。」


## ⑧ 对 LSPFRIFA 的落地映射（用户拍板追加）

### 8.1 【LSPFRIFA 现状锚点】（已核对项目源码，以下为事实）
- 宿主端模块：xposed/LSPFRIFAModule.kt（libxposed 102 XposedModule；框架经 META-INF/xposed/java_init.list 注册）；onPackageLoaded：过滤系统关键进程 + isTargetEnabled（ContentProvider content://com.bail.lspfrifa.config_provider/scripts 的 is_target_enabled）→ System.loadLibrary("gumjs_bridge") + GumJsBridge.init() → TargetIpcServer(targetPackage) → registerBinderToHost（Provider register_ipc 传 ipc_binder）→ loadInitialScript（Provider get_script 拉持久化脚本 → GumJsBridge.loadScript）
- xposed/TargetIpcServer.kt：目标进程内 Binder 实体（IScriptExecutor.Stub，能力=loadScript/unloadScript/registerLogReceiver/ping），经 ContentProvider 握手发布给宿主；init 时注册 GumJsBridge 消息回调：native on_message→Kotlin→①本地 Logcat 备份 ②ILogReceiver.onLog 跨进程推送宿主 Manager UI
- xposed/GumJsBridge.kt + cpp/gumjs_bridge.cpp：Frida GumJS 注入与主上下文泵（init/loadScript/unloadScript/registerMessageCallback→JNI 四函数；frida-gumjs devkit 静态链接 arm64/arm）
- 插件脚本走 GumJS（JS）而非 Rhino；UI=Miuix Compose；无内嵌多语言引擎（无 Rhino/BSH/LuaJava）；内存搜索无内建；无第三方插件目录机制（现状=注入脚本模式：宿主 UI 管理→IPC 下发）

### 8.2 【目标差距逐项】
| 维度 | LSPilot | LSPFRIFA 现状 | 性质 |
|---|---|---|---|
| JS 引擎 | Rhino（纯 Java，解释执行，ES6/180） | GumJS（原生 JS 引擎） | GumJS 更强，无需对标 |
| 语言面 | BSH 主 + JS/Lua 三入口 | 仅 JS（GumJS） | 无需对标 |
| 脚本组织 | Android/media/<pkg>/LSPilot/Plugin 目录 + main.* + info.prop + .enabled/.debug 生命周期 | 注入脚本模式（Provider get_script 单脚本） | 差距，可借鉴组织 |
| Hook 链 | HookRouter→HookProvider 双实现(ps7/qs7) + 前后置分派 + HookParam 短路协议（skipWith→setResult/否则 proceed） | GumJS Interceptor.attach(onEnter/onLeave) + JS 侧逻辑 | 语义映射（可借鉴协议） |
| 全局 API 注入 | 无前缀全局函数注册表（rnd BaseFunction）+ 预置变量 pluginSdk/pluginName/hostVer* | JNI 注入面（gumjs_bridge 有限绑定 + 消息回调） | 可借鉴清单 |
| 错误反馈 | console/log→<pluginPath>/log/<日期>.log+Logcat；toast 主线程；jwf.c 异常兜底 | Logcat 备份 + ILogReceiver 跨进程推送宿主 UI | 已有较好基础，可补文件日志+错误分类 |
| 跨进程内存搜索 | Mem.init(pid)→MemorySearchManager→native libmemsearch.so | 无内建 | 保持不动（不引入） |
| DexKit | org.luckypray.dexkit 桥（libdexkit.so） | 无 | 可选（frida 侧 enumerate+Java.perform 可替代） |
| WebSearch | Bing 抓取（仅模块内部） | 无 | 保持不动 |

### 8.3 【清单：可借鉴 / 保持不动 / 可选改造路径】
可借鉴（映射到现有骨架）：
- 插件/项目组织：插件目录+info.prop(name/author/version/desc)+.enabled 启停标记+入口约定 → 映射到项目选择器/项目 list（ScriptStore/ScriptConfigProvider）：每目标应用一个「项目」目录（如 .../lspfrifa/projects/<pkg>/），内含 manifest（名称/作者/版本/描述）+ 启用标记 + 主脚本；扫描→校验→加载→禁用清理的生命周期（线程池+注册表可简化：GumJS 单脚本 attach/detach 更轻量）
- Hook 链前后置/短路语义：HookParam 的 before/after/replace + skipWith 短路（不 proceed 直接返回）→ 映射 GumJS 调用：Interceptor.attach onEnter 中改 args/提前 return、onLeave 读 retval；ID 注册表热替换 → 用脚本名/句柄映射管理多个 Interceptor（detach+重 attach）
- 全局 API 注入表：整理我们 JS 注入键名（console/消息回调/日志函数等，保留 GumJS 原生能力 + native 绑定面），对标 LSPilot 的按名注册表 + 预置变量（可注入 targetPkg/hostVer* 等上下文）
- 错误反馈三段式：① console（native 已接 ILogReceiver 推送宿主 UI）② 补文件日志（宿主侧落盘 log/<日期>.log）③ 脚本错误分类提示（语法/运行时/越权）→ 宿主 UI 项目详情页展示

保持不动：Rhino/BSH/LuaJava 引擎（GumJS 已覆盖 JS 且更强）；多语言机制（不引入 BSH/Lua 入口）；跨进程内存搜索（不引入 libmemsearch）；WebSearch 内部工具。

可选改造路径：若将来要做第三方插件生态，可参考其目录/清单/启用协议（per-target 目录 + info.prop + .enabled/.debug 标记 + 入口探测优先级）设计我们的插件包格式（独立于内置项目）——但不引入其引擎：第三方包脚本仍由 GumJS 执行（仅 JS），或明确只支持 JS 包。

结尾重申：「LSPFRIFA 不照搬 LSPilot 的多语言脚本机制，保持现有 Frida GumJS 架构不变，仅学习其脚本加载组织、Hook API 能力面与错误反馈方式。」


---
## 附录 A｜关键字符串证据表（来源类）
| 字符串 | 来源类/方法 | 含义 |
|---|---|---|
| "Mozilla Rhino / Implementation-Version" | Rhino MANIFEST.MF (ImplementationVersion) | 引擎版本来源 |
| "main.js/main.lua/main.java/info.prop" | dwf、nwf.d | 入口/元信息探测 |
| "Android/media/ /LSPilot/Plugin" | nwf.c | 插件根路径（+pkg 拼接） |
| "hook/dexkit/reflect/loader/console/Mem/log/toast/importClass" | ht9.h | JS 全局注入名 |
| "pluginSdk..hostLoader" | ht9.i | 预置变量名 |
| "javax,org,edu,print,load,quit,readFile,readUrl" | ht9.b | 沙箱删除清单 |
| ".enabled" | nwf.f/k、t50.c/d | 启停标记 |
| ".process_mode" | t50.b/e、w60.J | 进程模式配置 |
| "Failed to initializeContext / Failed to injectApplication / callApplicationOnCreate" | ntc/jtc | 初始化错误串 |
| "Method  not found in class " | HookRouter | Hook 查找错误 |
| "loadJs: failed to load "/": file not found " | js LoadPathApi | JS 加载错误 |
| "LuaState peer 为 0 / import: expected class name / [Lua Hook Error] / No matching overload" | w6d | Lua 侧错误串 |
| "name/author/version/desc" | w60.M(Properties)、dwf | info.prop 键 |

## 附录 B｜疑点/偏差处置
| # | 事项 | 结论 |
|---|---|---|
| 1 | 标题「JS 脚本」vs 实际 BSH 主语言 | 矫正：BSH 主（文档+dex）、JS/Lua 辅（三 LoadPathApi） |
| 2 | 「TargetIpcServer 同类」未发现 | 以反编译为准：无此类；跨进程=Mem JNI libmemsearch.so |
| 3 | 文档仅覆盖 BSH 词表 | JS/Lua 词表以 dex 反编译为准（分列证据级别） |
| 4 | 插件目录宿主 me.jsonet.jshook | 已解析：=被 Hook 宿主（JSHook）；机制=hostPkg 当前进程包名，非硬编码 |
| 5 | WebSearchManager(Bing)未暴露脚本 | 事实标注：内部工具 |
| 6 | i54("https://github.com/mozilla/rhino") | 已排除：致谢页数据，非引擎引用 |
| 7 | KotlinJsr223* | 旁系：内嵌 Kotlin 编译器 REPL（编辑器/终端），非插件引擎 |
| 8 | Rhino 精确版本 | 1.7.x 系列（≥1.7.14 特征），版本串从 Implementation-Version 读取 |

## 附录 C｜证据级别
①名称级（dex 类/字段/方法名命中）②反编译行为链（类.方法级：t1/t2）③官方文档（plugin-doc.md）④运行时实测（外部目录）——本文结论均标注来源，无推测成分。

*本报告为 t1（js-engine）+ t2（hook-api）+ integrator 验证基线的最终整合件；三方无冲突，骨架按 captain 拍板口径，第⑦节为用户拍板结论，第⑧节为用户拍板追加的落地映射。*
