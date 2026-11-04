# 脚本模块化 + 注入时机提前 —— 冻结设计（决策已定，实施前不再争论）

> 本卷为**冻结决策卷**。按本项目实核纪律（三查 / 先冻结再实施），实施前所有键名、接口、顺序以本卷为准；
> 与本卷冲突的旧设想（含对话中的临时倾向）一律作废。
> 依据：源码实读（`xposed/*.kt`、`cpp/gumjs_bridge.cpp`、`ipc/*.kt`、`provider/ScriptConfigProvider.kt`、
> `MainActivity.kt`、`ui/screen/*`）+ libxposed 源码实读（`/storage/emulated/0/Download/libxposed_sources/`）。

---

## 0. 第一性原理（决策的唯一依据）

1. **注入 = 三件互不依赖的事被错误地串成了一串**：
   - ① 引擎准备（`loadLibrary` + `GumJsBridge.init`）——只依赖进程存在
   - ② hook 就绪（HookRouter + 脚本加载 + 注册）——依赖引擎 + **targetClassLoader**
   - ③ 宿主协同（`register_ipc` / 日志上 UI / 热更新）——依赖 **Context**
   - ④ 启用判定——只依赖 remote prefs（**无依赖**）
   现在 ①②③④ 全被挂在 `Instrumentation.callApplicationOnCreate` 之后，而其中只有 ③ 真正需要那么晚。
   → **结论：拆分这四段，各自放到最早可行的位置。**

2. **"更早"不能替代"能挂未加载的类"**。LSPlant 需要 ArtMethod 已存在；脚本写任意类必然 MISS。
   → 这是**独立能力缺口**，用"类加载感知 + 待挂队列"解决，不靠提前时机。

3. **目标 App 启动延迟是不可付出的成本**。本项目 F2 修复已把整链移出主线程；任何"提前"若回到主线程即倒退。
   → 提前必须与后台化绑定；且同步代价必须是**有界且条件式**的。

4. **失败模式必须自愈**。单脚本架构下任一模块顶层语法错误 → 整包加载失败 → 目标 App 每次启动都受影响。
   → 需要自动熔断（见 D12），否则用户会陷入"目标应用不可用且不知如何恢复"。

5. **少状态 = 少 bug**。能不新增代码路径就不新增（如"引擎提前加载"不单列，合并进时机改造）。

---

## 1. 冻结决策清单

### D1 — 时间线：做 C 与 A；B 合并进 A；E 不做
| 代号 | 内容 | 处置 |
|---|---|---|
| A | 脚本加载 + hook 挂载前移到 `onPackageReady`（后台线程） | **做**，第 3 工作流 |
| B | 引擎加载前移到 `onPackageLoaded` | **不做为独立项**——合入 A（两个回调间隔毫秒级，单列只增状态） |
| C | 启用判定/脚本来源只用 remote prefs，去掉 Provider 硬依赖 | **做，第 1 工作流**（当前最痛的失败源） |
| D | 类加载感知 + 待挂队列（能力解锁） | **做**，第 4 工作流 |
| E | `onModuleLoaded` 极早介入 | **不做**（无 app CL、判断脆弱、无新增能力） |

**为什么 C 排第一**：失败模式已被真实事件验证（`Unknown authority` 导致握手失败、团队花 t16/t17 修补），
且当前注入链**强依赖宿主进程存活**——宿主未启动/被强停即注入失败。这是"正在流血"的问题，优先于新能力。

**为什么 D 排最后**：价值最高但风险最高（`loadClass` 是极端热点 + 多 CL + 递归），
且当前有可用绕行（类加载后重跑脚本），无事故记录。高风险项放在低风险项之后。

### D2 — 同步 vs 异步：重活永远后台；允许有界条件式同步
- 引擎加载、Provider 握手、脚本解析 → **永远后台线程**（沿用 `isDaemon=true` 模式）。
- **例外（新增）**：在 `Instrumentation.callApplicationOnCreate` 的 intercept 内，`chain.proceed()` **之前**，
  执行一次**有界、条件式**的早期 hook flush：仅当"待挂队列非空"时，同步尝试挂载**已加载类**的 hook。
  队列为空 → 立即返回，成本≈0。
- **不承诺**"脚本一定早于 `Application.onCreate`"。对外口径改为：
  **"hook 就绪与目标生命周期解耦；提前为 best-effort；早期类 hook 通过 flush 保证（已加载者）"**。
- 理由：二选一（要么全同步拖慢启动、要么全异步丢确定性）是伪二分；真正的解是"把同步部分压到条件式且有界"。

### D3 — 类加载感知的实现形态：三级再武装（默认开 ①②，③ 可开关）
1. **锚点 flush（零成本，必做）**：在既有锚点（`callApplicationOnCreate` proceed 前）flush 待挂队列。
2. **Kotlin 侧调度（必做）**：待挂队列非空时，用 Java 侧线程每 200ms 重试；队列空即停止。
   ℹ️ **【2026-10-05 更正】timer 可用**——原写“devkit QuickJS 未实现 `setInterval/setTimeout`”**系误诊**（实核：devkit 完整实现，见 gumquickcore.c/core.js）。
   本调度仍留在 Kotlin/Java 侧是**设计选择**（不依赖脚本执行状态、不受脚本异常影响、无脚本时也能跑），而非能力限制。
3. **`ClassLoader.loadClass` 拦截（激进模式，设置项开关）**：前缀白名单 + 待挂队列为空时立即 return +
   **proceed 之后再 post 补挂**（绝不在 intercept 内同步装新 hook，防递归）。

### D4 — 脚本来源：remote prefs 为唯一必需通道（三查后修正）
**三查结论**（`XposedInterface.java:546-564`）：
- `String[] listRemoteFiles()` / `ParcelFileDescriptor openRemoteFile(String name)` —— **均为只读**。
- `XposedService`（宿主侧）只有 `listRemoteFiles` / `openRemoteFile` / `deleteRemoteFile`，
  **没有任何远程文件写入 API**（已穷举 classes.jar 全部类，见 §4 三查清单 #2）。
→ **宿主无法向目标进程写文件。大脚本没有文件通道可用。**

优先级（修正后）：
1. `remote prefs`（模块端只读，不依赖宿主进程）—— **唯一必需通道**
2. `openRemoteFile`（只读；用于读模块 APK 内嵌资源，**不用于下发脚本**）
3. Provider（兜底；仅在 remote prefs 完全不可用时，沿用 F1 鉴权）

### D5 — 脚本大小策略（三查后重写：限制是 group 级，不是脚本级）

**三查结论**（RemotePreferences / RemotePreferences$Editor 反编译）：
- 读：requestRemotePreferences(group) 返回**整个 group** 的 Bundle → getSerializable("map")
- 写：updateRemotePreferences(group, bundle)；buildCommitBundle() 用 putSerializable 打包
  mPut（整个 HashMap）+ mDelete（HashSet）
→ **每次读写都搬运整个 group 的 map。** 因此：

> **真正的上限不是「单脚本大小」，而是「单个 group 的总字节数」（约等于 Binder 事务上限 1MB，含序列化开销）。**

现状 lspfrifa_config 一个 group 里塞了 enabled + script.<pkg>×N + hint_inject：
N 个目标各 100KB → 一次写 = 1MB → **写入被静默丢弃**（Failed to commit changes to framework）。
这是当前架构里**尚未被发现的隐患**，与脚本大小阈值同等重要。

修正策略（配合新增 D15）：

| 阈值 | 处置 |
|---|---|
| 单脚本 ≤ 200KB | 走该包独立 group（D15），安全 |
| 200KB ~ 400KB | 允许但警告（接近上限，以实测为准） |
| 大于 400KB | **拒绝导入并明确报错**（当前架构无下发手段） |
| 硬上限 8MB | 超出直接拒绝 |

**实测协议（必须做，不用推理代替）**：分别导入 200KB / 500KB / 900KB / 1.2MB 四个合成脚本各注入一次，
记录 load_persisted_script 是否出现、宿主侧是否报 Failed to commit changes to framework。依结果回填。

### D6 — 导入通道：文件 + 剪贴板 + 分享 + 脚本库；不做 URL、不做 zip
- **做**：SAF 文件选择（零权限）、剪贴板（零权限）、`ACTION_SEND`/`ACTION_VIEW` 分享进入（零权限）、从脚本库复用。
- **不做 URL 下载**：需新增 `INTERNET` 权限；注入框架宿主增权扩大攻击面，而"浏览器下载→分享/文件导入"已覆盖该需求。
  存储 schema 的 `origin` 字段预留 `"url:"` 形式，将来要加零改动。

### D7 — 导入行为：覆盖 + 自动备份 + 可撤销 + 内容去重
导入 = 入库 + 应用到当前目标（覆盖）。覆盖前自动备份旧脚本；提供"撤销导入"回滚。
库内按 `sha256` 去重（同内容不重复入库，仅更新 name/origin）。

### D8 — 多模块与导入合并为同一工作流
两者共用同一套存储与同一套界面（脚本库即模块库），分开做等于做两遍。

### D9 — 模块粒度：每个目标包 = N 个模块文件
理由：模块级 enable/禁、级日志前缀、级去重、级分享都需要"文件级身份"；
且单文件内写多个模块的用法可被它兼容（后者是前者的真子集）。

### D10 — 同方法冲突语义：observe 扇出；replace 单赢 + 冲突告警
- **observe 语义**：按注册顺序**全部执行**（链式扇出，符合 frida 体验）。
- **replace 语义**：**只有一个赢**——按优先级取最高者执行，其余 observe 类仍全部执行；
  两个模块同时 replace 同一方法 → 记 `[lsp-hook] CONFLICT` 日志并在宿主 UI 标黄。
- 优先级**复用 libxposed 原生机制**（`HookBuilder.setPriority`，`PRIORITY_DEFAULT=50`），不另造优先级表。
  ⚠️ `setPriority` 精确签名待三查（当前仅读到 javadoc 片段）。

### D11 — 隔离：单引擎，不追求抢占隔离
- 单 `GumScript` 单 `HookRouter.handles`，模块以注册表区分（不新增 native 实例）。
  理由：frida 多 script 共用同一 JS 线程，多引擎**拿不到**想要的抢占隔离，成本却线性叠加（内存/加载耗时/JNI/AIDL/rpc id 空间）。
- 真实痛点的替代缓解：
  - **模块级错误归因**：每模块 IIFE 包裹 + 宿主侧预校验（语法/大小/编码）+ 日志与异常信息带 `moduleId`。
  - 已知不可解：模块内死循环会僵死整个 JS 线程（文档化，不假装解决）。

### D12 — 熔断安全阀（防砖）
连续 **3 次**脚本加载失败（`g_load_error_pending` 路径观测）→ 自动禁用该目标的模块加载，
宿主 UI 显示"因加载失败已暂停注入"并提供一键恢复。
理由：单脚本架构下坏脚本会让**每次**目标启动都受影响，必须能自愈。

### D13 — 刻意不改的东西（本批）
`AIDL`（继续用 `loadScript(String, Boolean)`）、`cpp/gumjs_bridge.cpp` 的结构（仅允许 pending 条目加 `moduleId` 字段）、
`provider/ScriptConfigProvider.kt` 的鉴权三件套（沿用）、`HookRouter` 的幂等键语义（`cls#method#sig#tag`，键值内嵌 moduleId）。

### D14 — 早期日志缓冲
引擎就绪但 `logReceiver` 未注册期间的日志（`onPackageReady`~Application 阶段）写入进程内 ring buffer（上限 500 行），
握手成功后一次性补发宿主 UI，避免"早期注入过程无日志可查"。

---

### D15 — remote prefs group 分离（三查后新增，解决 D5 隐患）

```
NOW（隐患）:
  lspfrifa_config                      ← 全部脚本挤在一个 group
    enabled      : StringSet(pkg)
    script.<pkg> : String
    hint_inject  : Boolean

AFTER（分离）:
  lspfrifa_config                      ← 只放小数据，永不放大脚本
    enabled           : StringSet(pkg)
    hint_inject       : Boolean
    scriptgroup.<pkg> : String         ← 指针：该包的专属 group 名
  lspfrifa_s_<pkgSafe>                 ← 每包一个，只含该包数据
    code      : String                 ← 脚本正文（大）
    modules   : StringSet(moduleId)
```

- pkgSafe = pkg.replace('.', '_').replace('-', '_')（包名字符集安全，无需 hash）
- **兼容**：模块侧先读 scriptgroup.<pkg> 指针；无指针则回退读旧的 script.<pkg>；再失败才走 Provider。
- **收益**：写任一目标的脚本只搬运该目标一个 group；enabled 集合的读写永不携带脚本正文。
- **代价**：读路径多一次 Binder 往返；写路径 2 次（指针仅首次写入）。

### D16 — 三查新增结论：onPackageReady 无需 Context 即可完成注入

官方示例 libxposed_example_ModuleMainKt.kt:50-78 实核确认：在 onPackageReady 内可直接调用
getRemotePreferences("test")、openRemoteFile("test.txt")、Class.forName(..., param.classLoader)、
hook(...)、setPriority(PRIORITY_HIGHEST)。
→ **工作流 3（提前注入）的可行性由官方示例直接背书，无需 Context。**
我此前标注的 R2 风险（早阶段无 Context）仅影响 Toast / 日志上 UI / register_ipc 三项，不影响引擎与 hook 挂载。

## 2. 冻结的存储键约定

```
# ===== 模块库（宿主侧，新增 data/ScriptLibraryStore.kt，SharedPreferences "lspfrifa_library"）=====
lib.index                  : StringSet(moduleId)
lib.<moduleId>.name        : String            # 显示名（默认取文件名）
lib.<moduleId>.code        : String            # 源码
lib.<moduleId>.origin      : String            # "file:<uri>" | "share:<pkg>" | "clip" | "manual" | "url:<u>(预留)"
lib.<moduleId>.addedAt     : Long
lib.<moduleId>.sha256      : String            # 去重键

# ===== 目标绑定（宿主侧本地 SharedPreferences，兼容兼容） =====
modules.<pkg>              : StringSet(moduleId)
module.<pkg>.<moduleId>.enabled : Boolean
script.<pkg>.bak.<ts>      : String            # 导入前自动备份（撤销用）
script.<pkg>.failCount     : Int               # D12 熔断计数

# ===== framework remote prefs（group 分离，D15）=====
# group "lspfrifa_config"（只放小数据）
enabled                    : StringSet(pkg)    # 既有，不动
hint_inject                : Boolean           # 既有，不动
scriptgroup.<pkg>          : String            # 新增：指针 → 专属 group 名
# group "lspfrifa_s_<pkgSafe>"（每包一个，只装该包）
code                       : String            # 脚本正文（模块拼装产物）
modules                    : StringSet(moduleId)   # 该包启用的模块集

# ===== 兼容读取顺序（模块侧） =====
1) scriptgroup.<pkg> 指针 → lspfrifa_s_<pkgSafe>.code
2) 旧键 script.<pkg>（无指针时回退，升级前数据）
3) Provider get_script（两通道都不可用）
```

注入侧拼装顺序（2026-11 第一阶段更新）：`LSP_SHIM_BASE` → `LSP_SHIM_COMPAT` → `JavaBridgeBundle`（上游 frida-java-bridge 7.0.13 真 bundle + LSPlant 适配层）→ 用户脚本。

> ⚠️ 此处已于「frida-java 分支第一阶段」变更：原 `LSP_SHIM_JAVA`（约 340 行兼容子集）已删除，
> `globalThis.Java` 直接是上游 Runtime 实例。跨语言契约见 `tools/javabridge/BRIDGE-CONTRACT.md`；
> 变更明细与证据见仓库根 `DONE.md`。

> ⚠️ 写入纪律：`enabled` / `hint_inject` 位置不变（向后兼容）；所有写操作先判断 group 序列化尺寸，超 700KB 拒绝并报错。

## 3. 交付顺序（4 个工作流）

| # | 工作流 | 关键动作 | 风险 | 依赖 |
|---|---|---|---|---|
| 1 | **C — 解耦 Provider** | remote prefs 读启用/脚本/模块集；Provider 降兜底；早期日志 ring buffer（D14） | 低 | 无 |
| 2 | **导入 + 模块化** | `ScriptLibraryStore` + `ScriptImport` + 编辑器/详情页 UI + 库管理页；模块粒度 D9、冲突 D10、熔断 D12 | 低-中 | 1（键约定） |
| 3 | **A — 时机提前** | `onPackageReady` 实现 + 后台线程 + 后台引擎加载 + 有界同步 flush（D2） | 中 | 1 |
| 4 | **D — 类加载感知** | 三级再武装（D3）；激进模式设置开关 | 中-高 | 3 |

**纪律**：每个工作流先跑 `tools/check-kt.sh`（双绿：0 规则 + 0 未导入符号），再交用户编译。

---

## 4. 三查结果（已全部完成 — 2026-08-26）

| # | 项 | 结果 | 依据 |
|---|---|---|---|
| 1 | `onPackageReady` + `PackageReadyParam.getClassLoader()` | **已实核，存在且可用** | `XposedModuleInterface.java:84-99, 221` |
| 2 | 模块共享目录 API | **已实核，但仅为只读**：`String[] listRemoteFiles()` + `ParcelFileDescriptor openRemoteFile(String)`；宿主侧 `XposedService` 仅 `listRemoteFiles/openRemoteFile/deleteRemoteFile`，**无写入 API**（穷举 classes.jar 全部类） | `XposedInterface.java:546-564`；`libxposed-service-102.0.0.aar / classes.jar` |
| 3 | `HookBuilder.setPriority` | **已实核**：`HookBuilder setPriority(int priority)` | `XposedInterface.java:362`；示例 `ModuleMainKt.kt:110` |
| 4 | MiuixIcons 导入类图标 | **仍未核**（Miuix sources jar 不在本机） | 需用户侧提供或改用已用过的图标（`MiuixIcons.Edit`/`ListView` 已验证可用） |
| 5 | 脚本大小实际上限 | **推理已升级**：限制是 group 级非脚本级（见 D5）；**数值仍需实测** | `RemotePreferences/Editor` 反编译 |
| 6 | `XposedModule` 是否暴露 Context | **已实核：不暴露**（`XposedModule` 仅继承 `XposedInterfaceWrapper`，无 Context 方法）→ 但官方示例证明**注入本身不需 Context**（D16） | `XposedModule.java:8`；`ModuleMainKt.kt:50-78` |
| 7 | remote prefs 读写是否走 Binder | **已实核：是**（读 `requestRemotePreferences` / 写 `updateRemotePreferences`，每次搬运整个 group） | `RemotePreferences.java`、`RemotePreferences$Editor.java` |
| 8 | frida 多 script 是否共用 JS 线程（D11 依据） | **未实测**（依据 frida 源码模型，非本机实测） | 若要坐实需 1 次多 script PoC |

**新增第二条硬约束（重要）**：项目纪律的"API 未核对不得编码"已适用于上表；本批 6/8 项已核完，
剩余未核项（#4 图标、#5 数值、#8 线程模型）各自的处置：
- #4 → 用已验证的 `MiuixIcons.Edit` 先实现，图标美化归 UI 二轮
- #5 → 按 D5 实测协议实测后回填
- #8 → 不影响工作流 1/2/3；仅影响 D11 的书面依据强度（已在 §5 标注）

## 5. 已知限制（不可解，如实记录）

1. 模块内死循环 → 僵死 JS 线程 → 全部模块的 replace 退化为 observe（单线程事件循环，无抢占）。
2. 对象参数/返回值仍为 `__obj` 占位（B1/B2 既定边界）。
3. 部分 hook 时机硬性不可达：类加载前不存在 ArtMethod；D 档把窗口缩到"类加载瞬间"，不等于零延迟。
4. ~~devkit QuickJS 无 timer~~ **【2026-10-05 更正：误诊】** → timer 完整可用（`setTimeout/setInterval/setImmediate`）；
   Kotlin 侧调度是设计选择。**真实缺口**：`queueMicrotask` 未定义 → 已在 shim 补。
5. **远程文件通道只写不了**：宿主无法向目标进程推送文件，所以脚本分发完全依赖 remote prefs（受 group 1MB 上限约束，见 D5/D15）。
6. **remote prefs 无事务语义**：`Editor.apply()` 失败仅打日志（`Failed to commit changes to framework`），调用方无从得知——写入必须靠读回验证。

---


### 第三轮交付（D12 熔断闭环 + 库管理页）

#### D12 熔断（本轮从死代码补成闭环）
设计要点：**目标进程无法自写状态**（remote prefs 在被 hook 的 app 里只读），故：
计数由宿主维护、**闸写进 remote prefs**（目标进程唯一能读到的持久状态）、
register_ipc 会拉起宿主进程因此宿主通常在场。

| 环节 | 实现 |
|---|---|
| 目标侧读闸 | `LSPFRIFAModule.runInitChain` 入口先查 `circuitOpen.<pkg>`，闸断则 `event=circuit_open_skip` 直接 return（不再尝试注入） |
| 目标侧上报 | 三处脚本加载失败分支（remote_group / legacy / provider）调 `reportLoadFailure()` → Provider `report_load_failure` |
| 宿主侧计数 | `IpcManager.reportLoadFailure` → `bumpFailCount`，达 3 次即 `openCircuit` |
| 用户合闸 | 详情页熔断卡「恢复注入」→ `IpcManager.closeCircuit`（清计数 + 删闸） |
| 鉴权 | `report_load_failure` 走 F1 的 **caller-arg uid 匹配**（沿用既有机制，未新开洞） |

#### 库管理页
新增 `ui/screen/ScriptLibraryScreen.kt`（216 行）：列表 / 应用 / 删除 / 空态。
入口在项目详情页「脚本库」行；带 targetPackage 传入，因此可**直接应用到当前目标**
（复用与导入一致的安全顺序：备份旧脚本 → 写新脚本 → 四态反馈）。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `ipc/ScriptStore.kt` | 新增熔断闸 API：`CIRCUIT_THRESHOLD` / `openCircuit` / `closeCircuit` / `isCircuitOpen` |
| `ipc/IpcManager.kt` | 新增 `reportLoadFailure` / `isCircuitOpen` / `closeCircuit` |
| `provider/ScriptConfigProvider.kt` | 新增 `report_load_failure` 方法 |
| `xposed/LSPFRIFAModule.kt` | `runInitChain` 闸门；`reportLoadFailure`；三处失败分支上报 |
| `ui/screen/ProjectDetailScreenV093.kt` | 熔断状态轮询 + 熔断卡（含「恢复注入」）+ 脚本库入口行 |
| `ui/screen/ScriptLibraryScreen.kt` | 新增 |
| `MainActivity.kt` | 新增 `script_library/{pkg}?name={name}` 路由 |

#### 验证
- `tools/check-kt.sh` **双绿**
- 花括号净值：MainActivity 93/93、ScriptLibraryScreen 39/39、ProjectDetail 63/63 —— 全部平衡
- 路由共 7 条（main / select_project / project_detail / script_editor / logs / script_library / import_target），嵌套逐行核对无误

### 仍未交付
- ~~工作流 3~~ ✅；~~工作流 4（类加载感知）~~ ✅ `f069e7f`（代码层面；编译与真机未验证）
- 模块化拼装（`modules.<pkg>` 已建键但未接入注入链）——用户已明确暂缓，`setModules`/`enabledModules` 暂为未调用
- **D5 尺寸阀值真机实测**（200KB / 500KB / 900KB / 1.2MB）——需用户侧
- 熔断链路的真机验证（本轮为静态实现，未跑过真机）


### 第四轮交付（工作流 3：onPackageReady 提前注入）

#### 实现思路（第一性原理）
注入链四段里，**只有“宿主协同”需要 Context**：
| 段 | 需要的依赖 | 早期阶段能否做 |
|---|---|---|
| ① 引擎准备 | 进程存在 | ✅ |
| ② hook 就绪 | app classloader | ✅（param.classLoader） |
| ③ 宿主协同（握手/日志上 UI/Toast） | Context | ❌ 留 Application 阶段 |
| ④ 启用判定 | remote prefs | ✅（Provider 需要 Context，跳过） |
因此提前阶段 = ①②④，剩下的 ③ 交回 Application 阶段。

#### 关键实现点
| 点 | 处理 | 为何 |
|---|---|---|
| classloader 源 | 用 `param.classLoader`，**不用** `getDefaultClassLoader()` | ACF 可能换 CL，用错 → 全部 hook 静默 MISS |
| 主线程纪律 | 整个早期流程丢守护线程 `lspfrifa-early` | F2 教训：不得阻塞框架回调所在的目标主线程 |
| 路由接管 | Application 阶段若有 earlyRouter 则 `attachRuntime(app) {...}` 复用，不重建 | 重建会丢失已挂 hook 手柄 → 泄漏 + 关不掉 |
| 重载防护 | 仅 `earlyScriptLoaded`（**真载成功**）才跳过 App 阶段的 loadInitialScript | 重载会 unload+recreate，清掉 Interceptor 制造失效窗口 |
| 回滚开关 | `EARLY_INJECT_ENABLED` 编译期常量 | 早期挂载与目标启动并行存在时序竞争，需一个不用重新设计的退回路径 |

#### 交付中发现并修复的自身 bug（重要）
初版用**一个**集合 `earlyInited` 同时表达“尝试过”与“成功过”，导致三种失败路径全部退化为“永不注入”：
1. 引擎加载抛错 → App 阶段跳过载脚本
2. 早期读不到脚本（`early_no_script`）→ App 阶段的 Provider 回退被绕过
3. 早期 `enabled != true`（无 remote 配置）→ 三态判定 + 延迟重试全被绕过

**修复**：拆为 `earlyAttempted`（防重入）与 `earlyScriptLoaded`（真载成功才置位，专供跳过判断），
`loadScriptEarly` 改为返回 Boolean。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `xposed/LSPFRIFAModule.kt` | 新增 `onPackageReady` / `runEarlyInject` / `loadScriptEarly`；两个集合；`earlyRouter` 字段；`runInitChain` 接管与跳过逻辑；顺手删除误加的 `ContentValues` import，修复被挤掉的 `TargetCheck` KDoc |
| `xposed/HookRouter.kt` | `appContext`/`hostLog` 改为 `private var`；新增 `attachRuntime(context, log)`（注意 `this.` 防参数遮蔽） |

#### 验证
- `tools/check-kt.sh` 双绿
- `LSPFRIFAModule.kt` 花括号 138/138；无旧集合残留
- 新增日志（供真机验证）：`early_router_ready` / `early_script_loaded` / `early_no_script` / `early_check_skip` / `early_circuit_open_skip` / `early_router_taken_over` / `skip_reload_early_loaded`

### 仍未交付
- 模块化拼装（已暂缓）
- 所有真机验证（含本轮提前注入、D5 尺寸阀值、熔断链路）

### 第五轮交付（工作流 4：类加载感知 / 三级再武装）

commit `f069e7f`（5 文件 +347/-1）。解决了设计之初就登记的硬限制：
**LSPlant 要求 ArtMethod 已存在，类未加载时 hook 请求只能 MISS**。

#### 三级实现
| 级 | 触发 | 成本 | 状态 |
|---|---|---|---|
| ① 锚点 flush | `onPackageReady` 路由就绪后；`callApplicationOnCreate` 拦截内、proceed 之前 | 队列空时≈0 | 代码到，未真机验证 |
| ② Kotlin 轮询 | 队列非空起守护线程，200ms；队列空即退出；上限 600 tick（≈2min） | 仅队列非空时 | 代码到，未真机验证 |
| ③ loadClass 监听 | hook `ClassLoader.loadClass(String,boolean)`，proceed **之后**处理 | 稳态=一次 isEmpty() | 激进模式，**默认关**，设置项可开 |

#### 五条安全纪律（逐条对应实现，均来自本轮自查）
1. **轮询线程必须全包异常**：安卓任意线程未捕获异常会杀掉**整个目标进程**。
   → `try/catch(Throwable)/finally`，绝不外抛（`POLL_ERR` 日志）。
2. **绝不允许双重出队**：`ConcurrentHashMap` 迭代器连续两次 `remove()`（中间无 `next()`）抛 ISE，
   而其中一条路径暴露在目标主线程上 → `flushPending` 重构为两阶段（先解析，成功才出队）。
3. **loadClass 处理必须在 proceed 之后**，并经 `mainHandler.post` 投递：
   安装 hook 本身会加载类，与当前加载共用 per-loader 锁 —— 同步做等于自己抢自己的锁。
4. **`armOnClass` 必须 @Synchronized**：消息路径与 flush 路径可并发，
   同时通过 `containsKey` 检查会对同一方法挂两次，后一个 handle 覆盖前一个 → 泄漏且卸不掉。
5. **锚点预算保守**：`FLUSH_MAX_ITEMS` 16→8。每项可能触发 LSPlant 安装（含 deopt），
   主线程预算需保守；剩余交给②（非主线程，代价几乎为零）。

#### 实现中的额外修正
- `unhookByTag` 改用“先筛键再逐个 remove”，不依赖 `entrySet().removeIf` 实现细节。
- `unhookAll` 同步 `clearPending()` —— 防上一轮脚本的 hook 迟到生效。
- 类加载监听手柄**单独持有**（不进 `handles`）：它不是脚本注册的 hook，
  不应随脚本重载被 `unhookAll` 卸掉；必须强引用持住防 GC。

#### 新增文件
- `data/HookModeStore.kt`（54 行）：设置项存储，与 `InjectHintStore` 同构，
  写 `lspfrifa_config` 组下发（目标进程只读）。键 `loadclass_watch`，**默认关**。

#### 真机核验点（新增日志关键字）
`MISS_CLASS_QUEUED` / `ARMED_LATE src=anchor|poll|loadclass` / `POLL_GIVEUP` /
`POLL_ERR` / `LATE_ARM_ERR` / `PENDING_CLEARED` /
`CLASSLOADER_WATCH_ON|OFF|FAIL` / `loadclass_watch_requested`

#### ⚠️ 本轮的诚实声明
- **编译未验证**：最后一次成功构建在 07:32（W3 之前）；W4 代码（含新文件 HookModeStore）尚未编译过。
- **真机未验证**：三级逻辑一次都没在设备上跑过。
- `uninstallClassLoaderWatcher()` 已定义但**当前无调用方**（预留给“开关关闭后即时卸载”，
  但按 D13 不改 AIDL，运行期关闭只能下次注入生效，故暂未接）。

### 四个工作流全部完成（代码层面）
| 工作流 | 内容 | commit |
|---|---|---|
| W1 | D4 解耦 Provider + D15 remote prefs 分组 | `ab28c4d` 含 |
| W2 | D6 导入四通道 + D7 备份撤销 + D8/D9 脚本库 + D12 熔断 | `ab28c4d` 含 |
| W3 | onPackageReady 提前注入 | `ab28c4d` 含 |
| W4 | D3 类加载感知（三级再武装） | `f069e7f` |
| 编译警告修复 | err 解析语义 + componentType 类型安全 | `1cdddea` |

**模块化拼装仍暂缓**（用户明确）；`setModules`/`enabledModules` 仍未调用。

### 第六轮修复（提前注入消息链三处缺口 — 2026-09-26）

> 本轮不对应新工作流，而是 W3 引入的**回归缺陷**修复。三处缺口症状相同：
> **「脚本已加载，但 hook 永远不生效，宿主 UI 也看不到相关日志」**。

#### 缺陷 1（致命）：早期阶段从未注册消息回调
- **现状事实**：`GumJsBridge._messageCallback` 默认 `null`，全项目唯一赋值点是
  `TargetIpcServer.init`；而 `runEarlyInject` **不创建** `TargetIpcServer`（它需要 Context）。
- **后果链**：早期脚本 `send()` → cpp `on_message` → `dispatchMessage` → 回调 null → **静默丢弃**；
  App 阶段又因 `earlyScriptLoaded` 已置位而跳过 `loadInitialScript` → **hook 永不注册**。
- **修复**：`runEarlyInject` 在 `loadScriptEarly` **之前**先 `registerMessageCallback`，
  路由到 `earlyRouter.tryHandle`；未消费的入 EarlyLogBuffer。
- **为何之前静态检查未发现**：这不是语法/符号问题，而是**初始化顺序的语义依赖**，
  规则式检查无法覆盖。教训：新增生命周期阶段时，必须逐项核对“旧阶段中建立的关键依赖在哪建立”。

#### 缺陷 2：`TargetIpcServer` 构造 → `setHookRouter` 之间的丢消息窗口
- 该窗口内 `hookRouter == null`，消息不被路由。
- **为何旧版无害**：旧版脚本在 `setHookRouter` **之后**才加载（init 链尾部），窗口内根本没有消息。
- **为何现在真丢**：提前注入下脚本**早已在跑**，窗口内会真实到达 `lsp.hook` 消息。
- **修复**：路由改为**经构造器注入**（新增 `initialRouter` 参数，字段初始值即路由）→ 窗口消失。
  随之在 `runInitChain` 中把路由构造提前，并用 `ipcRef` 破 `hostLog` ↔ `ipcServer` 的前向引用环
  （环未破前的日志回落 `record()`，进 logcat + ring buffer，不丢）。

#### 缺陷 3：早期/构造后未消费消息未入缓冲
- 非 hook 消息（脚本 `console.log` / 自定义 `send`）在通道建立前只进 logcat，
  不进 `EarlyLogBuffer` → 宿主 UI 永久看不到（D14 缓冲的意义正在于此）。
- **修复**：两处回调的未消费分支均 `EarlyLogBuffer.add(message)`
  （flush 后 `add` 为空操作，无重复风险）。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `LSPFRIFAModule.kt` | `runEarlyInject` 注册回调；`runInitChain` 路由先行 + `ipcRef` 破环 + 构造器注入 |
| `TargetIpcServer.kt` | 新增 `initialRouter` 参数；两处回调补 `EarlyLogBuffer.add` |

#### 验收口径（写入清单 §C13/§C14/§H8）
- **§C13**：早期 `ARMED` 出现在 `event=application_created` 之前（而非“已加载但无 ARMED”）。
- **§C14**：早期 `EARLY-PROBE` 日志最终在宿主面板出现。

### 第七轮修复（脚本输出积压 + Java.available 失效 — 2026-10-05）

> 由真机日志定位。症状：脚本启动后长时间静默，手动热更新后积压日志一次性涌出。
> 铁证：`engine poll left 4760` 与 `left 4720` 时间戳仅隔 **1ms**，
> 而按脚本配置（50ms×40tick）二者必须相隔 **2s** —— 积压后冲出的特征。

#### 缺陷 1（主修）：目标进程内无任何线程持续泵送默认 GMainContext
- **事实链**：脚本出站消息经 `gum_quick_script_emit` 以 idle source 挂到
  【脚本创建线程的 thread-default context】；Java 线程无 thread-default → 回退到
  **进程默认 GMainContext**（frida-gum `gumquickscript.c:1127` + glib `gmain.c:943`）。
  而目标进程内**没有任何线程持续泵送该 context**——仅有的派发点是
  ① `nativeLoadScript` 尾部一次性排空 ② `nativeCallJs` 等 reply 时的泵（仅 hook 命中）。
- **关键澄清**：脚本定时器/逻辑**照常运行**（定时器挂 `js_context`，由 gum-js-loop
  调度器泵送）——所以“功能其实执行了，但输出全部积压”。这解释了
  “为什么点一下才完全执行”的错觉。
- **修复**：新增常驻线程 `lspfrifa-pump` 对默认 context 跑 `g_main_loop_run`
  （等价于 frida-server 主线程跑主循环的职责）。进程内幂等。
- **并发配套**：`nativeLoadScript` 的 F4 错误检测加 ~10ms 宽限
  （防泵线程领走 error 消息但尚未派发时被误判“加载成功”）。

#### 缺陷 2：`Java.available` 探针恒为 falsy
- **事实链**：合并用 `Object.assign(Object.create(null), bundleJava, ourJava)`；
  `Object.assign` **只复制可枚举自有属性**，而 bundle 的 `available` 是定义在
  `Runtime` **原型**上的 getter → 不被复制 → `merged.available === undefined`。
- **后果**：脚本里 `if (!Java.available) return;` 类早退探针（行业惯用写法）恒成立
  → 整条 Java 路径被**静默跳过**（实测日志 “Java unavailable, cannot schedule GL ...”，
  即使脚本只依赖 use/perform —— 本 shim 完全支持的子集）。
- **修复**：shim 显式 `ourJava.available = true`；同时把 `registerClass` 补入
  “显式可读错误” stub 列表（避免 available=true 后它从 undefined 变静默失败）。
- **边界**：available=true **不代表** bundle 深度 API（choose/registerClass/openClassFile）
  可用——它们仍为可读错误。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `app/src/main/cpp/gumjs_bridge.cpp` | +泵线程块（~50 行）、initEngine 启动、F4 宽限 |
| `GumJsBridge.kt` | `ourJava.available = true` + stub 列表加 `registerClass` |

#### 验证
- **clang++ -fsyntax-only**（NDK 28 sysroot + devkit，aarch64-linux-android26）：
  当前版 0 错；HEAD 对照 0 错；**故意破坏版正确报错**（检查有效性已验证）
- JS shim：`node --check` 通过；`tools/check-kt.sh` 双绿

#### ⚠️ 诚实声明
- 静态验证已尽力（含 clang 真语法检查 + 反向验证），**未经真机复测**。
- 预期：启动后日志实时流动，不再需要点“运行”；`Java unavailable` 日志消失。

#### ⚠️ 诚实声明
- 本轮为**静态修复**：`tools/check-kt.sh` 双绿，**未经编译**（含新签名，编译时优先怀疑
  `TargetIpcServer` 构造调用点）；真机未验证。
- `setHookRouter()` 现已无调用方（保留为公共 API，但主链已不再依赖它）。

#### 追加加固（第二轮，commit `bf8bb6d`）
1. **cpp 回调线程全局兜底**：两个回调均运行在 cpp `gum-js-loop` 线程，
   该线程未捕获异常会杀**整个目标进程** → 现所有分支（含 record / EarlyLogBuffer / hostLog）
   均包在 `runCatching` 内。
2. **`handleToastMessage` context 缺失语义**：原 `appContext ?: return true` 会把
   `lsp.toast` 当作“已消费”静默吞掉（提前阶段必然无 Context）→ 现改为 `return false`，
   让消息走未消费分支入 EarlyLogBuffer 补发（日志：`[lsp-toast] deferred (no context yet)`）。

### 第八轮更正与补缺（timer 误诊平反 + queueMicrotask — 2026-10-05）

> 由用户提问触发复查（“不是说引擎不含 timer 吗”）。复查结果：**该结论系误诊**，
> 并顺带找到一个**真实缺口**（queueMicrotask）。

#### 更正：timer 从未缺失
- **实核证据**：
  - `gumquickcore.c`：`JS_CFUNC_DEF("_setTimeout"/"_setInterval", ...)` →
    `gum_quick_core_schedule_callback` → `g_idle_source_new`/`g_timeout_source_new`
    → `g_source_attach(..., gum_script_scheduler_get_js_context(...))`（js_context 挂载）。
  - `gumscriptscheduler.c`：`js_loop = g_main_loop_new(js_context, TRUE)` + `g_thread_new("gum-js-loop")`
    → **js_context 由常驻调度器线程泵送**（与本次修的默认 context 不同！）。
  - `core.js`：`setTimeout/setInterval/setImmediate/clearTimeout/clearInterval` 均为全局。
- **真机实证**：用户脚本（重度使用 `setInterval`）的日志中，`linker patched`、`engine poll`、
  `PF2_SEED` 等均由定时器驱动，且种子文件（1460 行）持续写入 → **timer 一直在工作**。
- **误诊成因**：脚本出站消息（send）缺常驻泵（已于 `e659ce9` 修复）→ 心跳“看起来不触发”；
  叠加历史探针恰好被 `queueMicrotask` ReferenceError 中断（见下）→ 错误归因到 timer。

#### 真实缺口：queueMicrotask 未定义（已补）
- QuickJS 内核与 frida runtime **均未定义** `queueMicrotask`（grep 零命中）。
- 后果：脚本顶层调用 → ReferenceError → **整个脚本终止**（含后续 timer 注册）——
  与历史“timer 不触发”的症状完全一致（**这才是真因之一**）。
- 修复：shim 内补齐（`Promise.resolve().then` 标准微任务语义）。

#### 改动文件
| 文件 | 改动 |
|---|---|
| `GumJsBridge.kt` | shim 补 `queueMicrotask` |
| `HookRouter.kt` | 轮询注释更正（timer 误诊 → 设计选择） |
| `Handoff / Design / Checklist` | 三处 timer 误诊记载全部更正 |

### 第九轮：引擎全面审查（2026-10-05）

> 按层审查：native（cpp）→ JNI 边界 → Kotlin 桥接 → 与 HookRouter 交互。
> 共发现并修复 **3 类 14 处**缺陷；同时确认了若干“看似可疑但实际安全”的设计。
> 前置背景：常驻消息泵（`e659ce9`）把消息派发从“偶发”变为“持续”，
> 使既有竞态从低概率放大为常态——本批与其配套修复。

#### A. 并发竞态（UAF 级）
| # | 缺陷 | 修复 |
|---|---|---|
| 1 | `g_script` 无锁解引用（callJs/postOriginalReply）vs 并发 unload+unref → **use-after-free** | 新增 `g_script_lock` + `lsp_acquire_script()`（锁内取强引用，用毕 unref）；加载/卸载改“锁内摘除→锁外卸载”；新脚本**最后一步**发布 |
| 2 | `g_callback` 交换竞态（SetCallback 的 Delete/NewGlobalRef 之间被泵线程读取）→ UAF | 新增 `g_callback_lock` + 锁内 `NewLocalRef` 取局部引用；附带缓存 jmethodID |

#### B. UTF-8 边界（静默损坏）
| # | 缺陷 | 修复 |
|---|---|---|
| 3 | 输出侧：`NewStringUTF` 对非 modified-UTF8（emoji）UB | `lsp_new_jstring()`：UTF-8→UTF-16→NewString；非法输入 `g_utf8_make_valid` 清洗 |
| 4 | 输入侧：`GetStringUTFChars` 产出 modified UTF-8（CESU-8）→ 非法字节进 JSON/引擎 | `lsp_get_utf8()`：`GetStringChars`→`g_utf16_to_utf8`；覆盖脚本正文/name、id/payload、retJson |

#### C. 调度与健壮性
| # | 缺陷 | 修复 |
|---|---|---|
| 5 | `flushPending` 预算按“成功数”计 → CNFE 不增 done → 锚点路径（主线程）扫全队列 | 加 `scanned` 双重约束 |
| 6 | loadclass 分支 flush 用 `Int.MAX_VALUE` 且跑主线程 | 改为 `FLUSH_MAX_ITEMS`（剩余由轮询接手） |
| 7 | `on_message`：F4 错误捕获被 `g_callback==nullptr` 提前 return 跳过 | 错误捕获与 rpc 消费**前移**到一切检查之前 |
| 8 | Kotlin 回调抛异常未清理 → 悬挂 JNI 异常 | `ExceptionCheck/Describe/Clear` |
| 9 | `message==nullptr` 未防（`NewStringUTF(NULL)` UB） | 已加防御 |

#### 已确认安全（看似可疑，实非缺陷）
- **pump 线程 vs callJs 自泵**：GLib context 同一时刻只能一个 owner；pump 持有时
  其他线程的非阻塞 iterate 返回 FALSE（gmain.c 实证）→ 不会双重 dispatch；
  callJs 的等待由 pump 线程的 cond signal 唤醒（比自泵更可靠）。
- **post 到已卸载脚本**：frida 的 `do_post` 检查 `incoming_message_sink`，为 NULL 时
  丢弃消息并释放（gumquickcore.c 实证）→ 强引用模式不会 UAF。
- **pending 条目生命周期**：超时后 owner 线程唯一移除+释放；迟到 reply 未命中静默消费。

#### 改动文件
- `app/src/main/cpp/gumjs_bridge.cpp`（+~200/-60）
- `HookRouter.kt`（2 处预算修复）

#### 验证
- clang++ -fsyntax-only（NDK 28 sysroot）：0 错；反向验证（故意破坏）正确报错
- check-kt.sh 双绿；花括号净值全平衡

### 第十轮：API 面收敛（参考官方“契约”，不参考“实现”——2026-10-05）
> **背景（事实核查实测）**：官方 Runtime 的 API 全部定义在**类体（=原型）**上，而我们的装配
> `Object.assign(Object.create(null), bundleJava, ourJava)` 只复制**自有字段** → 合并后官方 API
> 实际全部丢失，此前仅 `use/perform/available` 因被显式覆盖而幸免（`performNow` 等早已缺失，
> 因为脚本未用而未暴露）。
>
> **第一性原理**：脚本的迁移成本由**契约**（脚本可见的名字/语义/错误行为）决定，不由**实现**（hook 引擎）决定。
> → 契约层必须参考；实现层不可参考（本模块 devkit 实测 `nm` 零命中 frida java bridge 原生符号，
> `ArtController/MethodMangler` 等一行都不会执行；且其 ART 偏移方案跨版本脆弱，正是选 LSPlant 的原因）。

#### 裁决表（官方 Runtime 成员 → 本模块处置）
> **历史记录（2026-11 更新）**：下表是"第十轮"兼容子集时代的裁决，`LSP_SHIM_JAVA` 与其 19 个
> stub / 4 个 trap 已在 frida-java 分支第一阶段整体删除——现在直接运行上游 7.0.13 的真实实现，
> 本表仅保留为演进记录，不再是当前行为。证据见仓库根 `DONE.md` §1。

| 官方成员/字段 | 处置 | 依据 |
|---|---|---|
| `perform` | 已实现 | 本架构=同步直执（无 VM attach 概念） |
| `performNow` | **本轮补实现** | 官方=立即执行；与本架构 perform 等价 |
| `use` | 已实现 | ClassWrapper → HookRouter 注册 |
| `available` | 已实现（常量 true） | 目标进程必有 JVM；深度 API 由 stub 报错而非 available 静默跳过 |
| `isMainThread` | **本轮补实现**（常量 false） | JS 恒在 gum-js-loop 线程执行（hook 回调经 frida:rpc 投递），保守方向 |
| `androidVersion` | **本轮补实现**（装配时注入 Build.VERSION.RELEASE） | 官方为 getter；token 字面量替换（LSP_ANDROID_VERSION_TOKEN） |
| `choose/register/registerClass/openClassFile/array/type` | 可读错误 stub | 需真 VM 对象模型 |
| `cast/retain/backtrace/enumerate*/deoptimize*/synchronized/scheduleOnMainThread` | **本轮补 stub**（原为静默 undefined） | 同上；统一可读报错，绝不静默 |
| `ClassFactory/classFactory/vm/api`（自有字段） | **本轮补“陷阱”中和** | 内部状态非 API；防链式访问 `Cannot read property of null` 迷惑性 TypeError |

#### 明确不参考（排除项）
- `ArtController`/`MethodMangler`/`class-model.js` 的 CModule 编译（ART 内部偏移方案，跨版本脆弱）。
- `api_default`/`VM`/`Env` 原生层（devkit 不含其原生符号，实测 `nm` 零命中 → 初始化链不可能跑起来）。

#### 改动文件 / 验证
- `GumJsBridge.kt`：+`performNow`/`isMainThread`/`androidVersion`（token 注入）/ +19 项 stub 名单 / +4 项陷阱字段。
- 验证：`node --check` + 反向验证（故意破坏能报错）；真实合并模拟（模拟 bundle Runtime + 新 shim）
  → 7 项 stub 全部可读报错、4 项陷阱链式访问均得同一指引、`Object.keys(Java)` 完整枚举不抛。

### 第十一轮：体积与性能优化（P0 —— 2026-10-06）
> 触发：用户反馈"应用卡顿 + 体积庞大"。全部结论基于**实测数据**（APK zip 拆解 + readelf 节区 + 源码热点定位）。

#### A. 体积：87 MB 的构成（实测）
| 类别 | 压缩后 | 占比 | 根因 |
|---|---|---|---|
| `lib/arm64-v8a/libgumjs_bridge.so` | 37.95 MB | 44% | 静态链接 libfrida-gumjs.a；未做导出裁剪 |
| `lib/armeabi-v7a/libgumjs_bridge.so` | 27.19 MB | 32% | 32 位冗余分支 |
| dex × 14 | 17.67 MB | 21% | **无 buildTypes 块 → R8 完全未开** |
| resources.arsc | 1.46 MB | 2% | 未开资源压缩 |
| 其它 | 1.6 MB | 1% | — |

`.so` 节区实测（arm64）：`.text` 12.32 MB / `.rodata` 7.34 MB / **`.dynstr` 5.42 MB + `.dynsym` 1.45 MB = 6.87 MB 纯符号**；
动态导出符号 **63066 个**（多为 `frida__ZN...` C++ mangled 名，运行时用不到）。

#### B. 已实施：P0-1 砍 32 位（用户裁定）
- `abiFilters` → 仅 `arm64-v8a`。
- **依据**：验证环境小米 23013RK75C / A15 为 arm64；armeabi-v7a 的 `.so` 占全包 32%，零收益纯负担。
- devkit 的 `armeabi-v7a/` 目录**保留未删**（138 MB），需要时把 ABI 加回一行即可。
- 预期收益：**-27.19 MB**（APK 从 87 MB → ~60 MB）。
- **未做（用户裁定"先不管"）**：导出符号裁剪（`--exclude-libs,ALL` + version script）可再省 ~7 MB/ABI，
  但用户保留"以后可能有用"，故不动。

#### C. 已实施：P0-2 日志页三机制修复（卡顿主因）
第一性原理：主线程每帧预算 16.6 ms，超预算即掉帧。三个独立机制：

| # | 原实现 | 问题 | 修复 |
|---|---|---|---|
| ① | 监听回调内直接 `logs.add(...)` | **每条日志一次 state 写 → 一次重组**；高频脚本（engine poll 50ms）下每秒数十次 | 新增 `pending` 普通 ArrayList 缓冲 + `LaunchedEffect` 每 **200ms** 批量 flush → 重组频率上限 ~5 次/秒 |
| ② | `while (logs.size > MAX) logs.removeAt(0)` | **n 次 O(n) 搬移 + n 次重组** | 改为一次性裁剪（`toList().takeLast(n)` + `clear` + `addAll`） |
| ③ | 3s 轮询 `readHistory()` | **每 3 秒逐行读完整个日志文件**（上限 5000 行） | 新增 `LogStore.latestFileSignature()`（name:length:mtime）；签名未变 ⇒ 跳过解析，降为 O(1) stat |

附带修复（自查发现）：
- 心跳时间戳 `lastLiveAt` 原由**回调线程直接写 state** → 改 `AtomicLong` + 主线程 flush 时同步（消除跨线程 state 写）。
- 历史读取期间缓冲的实时行会**插到历史之前** → 在历史装载处先合并 `pending`（保序）。
- `cleared` 后 flush 会回灌 → 加 `cleared` 防护（与 3s 轮询语义一致）。
- 追尾滚动 `animateScrollToItem` → `scrollToItem`（高频追加下动画被反复取消/重启，永远追不上）。

#### D. 已实施：P0-3 滚动期 blur 降级
- `glassSurface` / `GlassTopAppBar` 新增 `blurEnabled: Boolean = true` 参数（默认值 = 零回归）。
- 日志页滚动中（`listState.isScrollInProgress`）临时走纯色降级，松手即恢复。
- 依据：`textureBlur` 每帧重捕获内容层纹理做 RuntimeShader 高斯模糊，是当前最贵的 Compose 效果。
- 调用点安全性已核查：`GlassTopAppBar` 的 `content` 为尾随 lambda、`blurEnabled` 为命名参数 → 无位置错配。

#### E. 明确不做（本轮）
- 导出符号裁剪（用户保留）。
- ~~R8/minifyEnabled~~ **【2026-10-06 第十二轮已完成】** —— 原记录："**无 `buildTypes` 块**是 17.67 MB dex 的根因，但开 R8 需处理 libxposed / LSPosed 入口类的保留规则（`module.prop`、`assets/META-INF/xposed/*`、反射入口），属独立风险项 —— 需单独一轮验证，本轮不混入。"
  → 该独立轮已完成，见下方**第十二轮**（三类 keep 全部实核后落规则）。

#### G. 编译阻塞：`ninja still dirty after 100 tries`（实测根因 + 处置）
**症状**：`P0-1` 改动后编译失败，日志刷 100 次 `[0/N] Re-running CMake...` 后报
`ninja: error: manifest 'build.ninja' still dirty after 100 tries, perhaps system time is not set`。

**排查（先排除误判）**：
- 时钟正常（无未来时间文件：扫描 `app/.cxx` 得"未来时间文件数 = 0"）。
- 一度误判为"路径错位"（我在 Ubuntu sandbox 看不到 `/data/user/0/com.tom.rv2ide/...`，36/40 输入显示缺失）
  —— **该判断错误**：ninja 实际运行在设备上，该路径是 RV2IDE 私有目录，设备侧存在。sandbox 视角不可作证据。

**真根因（数据钉死）**：
- `build.ninja` mtime = **Aug 25 11:23**；`CMakeLists.txt` mtime = **Oct 6 04:12**（+41 天）。
- `build.ninja` 的 `RERUN_CMAKE` 边共 40 个输入，其中**唯一**"比 build.ninja 新"的就是 `CMakeLists.txt`。
- 机制：CMake 生成 `build.ninja` 是 **copy-if-different**。P0-1 对该文件只改了**注释** →
  生成内容与旧文件**逐字节相同** → CMake 不写文件 → mtime 不更新 → ninja 永远判定 dirty。

**处置**：`rm -rf app/.cxx app/build/intermediates/{cxx,merged_native_libs,stripped_native_libs} app/build/outputs/apk`
（`app/build` 548M → 87M；同时清掉旧 armeabi-v7a 残留产物，保证 P0-1 的 ABI 收敛真正生效）。
**防复发**：`CMakeLists.txt` 顶部已加注释说明（改注释后如遇该错，删 `app/.cxx` 即可）。

#### F. 验证
- `check-kt.sh` 双绿；花括号净值：LogScreenV1 107/107、LogStore 89/89、GlassSurface 8/8。
- 全部调用点人工核查（`GlassTopAppBar` × 3、`glassSurface` × 2）。
- **未编译**（与 `0a97d77` 同批，等 §0 门槛）。

### 第十二轮：R8 启用 + 仓库门面（2026-10-06）

#### A. 触发与前置事实
- 触发：第十一轮把 dex（17.67 MB / 占压缩包 30%）列为「独立风险项，不混入本轮」，
  本轮专门处理它。同批完成仓库门面（LICENSE / Issue 模板）。
- **前置**：用户侧已确认 `0a97d77`→`992b4ee` 全链**编译通过**。本轮改动为**纯构建配置 + 新增文件**，
  不触碰任何 `.kt` 源码 → 编译风险集中在 proguard 规则正确性。

#### B. 第一性原理：dex 里到底装了什么
R8 的三项能力对体积的贡献**不等价**：

| 能力 | 机制 | 体积收益 |
|---|---|---|
| **shrink** | 可达性分析，删除无引用的类/方法/字段 | **主力**（库代码大量未被直接引用） |
| optimize | 内联、类合并、常量传播、死代码消除 | 中等 |
| obfuscate | 标识符重命名为短名 | 次要（只缩短常量池字符串） |

结论：**受益主要来自 shrink 而非混淆**——即"开 R8 会把用不到的东西删掉"，而非"改名字省空间"。

#### C. 实核：R8 必须 keep 的三类（全部有据可查）

**这是本轮唯一有真实风险的环节，逐条实测后写入规则：**

| # | 类别 | 实核依据 | 不 keep 的后果 |
|---|---|---|---|
| 1 | **libxposed 模块入口** | APK 内 `META-INF/xposed/java_init.list` 的**内容是硬编码字符串**：`com.bail.lspfrifa.xposed.LSPFRIFAModule`<br>（`app/src/main/resources/META-INF/xposed/java_init.list`） | 框架反射找不到入口 → **模块完全失效**（连日志都没有） |
| 2 | **JNI native 方法** | `app/src/main/cpp/*.cpp` 实现 6 个符号：<br>`Java_com_bail_lspfrifa_xposed_GumJsBridge_{nativeInitEngine,nativeSetCallback,nativeLoadScript,nativeUnloadScript,nativeCallJs,nativePostOriginalReply}`<br>（对应 `GumJsBridge.kt` 的 6 个 `external fun`） | JNI 静态注册按「类全名+方法名」拼符号 → `UnsatisfiedLinkError` → 引擎无法初始化 |
| 3 | **AIDL 跨进程契约** | `app/src/main/aidl/com/bail/lspfrifa/ipc/{ILogReceiver,IScriptExecutor}.aidl`<br>目标进程与宿主进程**各自持有同一份接口** | 两侧混淆结果不同 → `TransactionTooLarge`/`UNKNOWN_TRANSACTION` 等诡异跨进程错误 |

附带保留（低成本兜底，非必需）：Android 组件四类、Kotlin 注解属性、枚举 `values/valueOf`、
`Parcelable.CREATOR`、sora-editor/tm4e/gson/snakeyaml（反射加载语法与主题）、
`HookRouter.uninstallClassLoaderWatcher`（预留未调用 API，R8 会当死代码删掉）。

#### D. 落地改动

**`app/build.gradle.kts`** —— 新增 `buildTypes` 块（此前**完全没有**该块）：
```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
    }
    debug { isMinifyEnabled = false }   // 真机验证期需要可读栈 + 快速增量
}
```

**`app/proguard-rules.pro`** —— 从 AGP 模板（23 行注释）替换为 164 行实核规则，
分 10 组，每条规则标注实核依据（文件:行号）。

#### E. 明确不做（**用户裁定，不要动**）
- **导出符号裁剪（用户保留，原话："那个导出符号先不管，万一以后有用呢"）** ——
  `--exclude-libs,ALL` + version script 可再省 ~7 MB/ABI，但用户明确保留。
  本轮实测确认**未触碰**：
  ```
  符号表总条目 63458 · 动态导出(FUNC GLOBAL) 30469 · frida__ZN 23095
  .dynsym 1.45 MB · .dynstr 5.42 MB · .rodata 7.34 MB · .text 12.32 MB  → 与 P0 基线一致
  CMakeLists.txt 中 exclude-libs/version-script/strip/visibility 命中数 = 0（git diff 亦为空）
  ```
  **R8 的作用域仅限 dex，物理上够不到 `.so`** —— 两者是不同层面，互不影响。

#### F. 验证
- proguard 规则：全文花括号净值 24/24；逐行深度检查负深度=0；6 条关键规则存在性逐条命中。
- **双向验证**：植入 `-keep class com.bad.test {`（缺闭括号）→ 25/24 能发现；
  恢复后 24/24 → 不误报。
- 8 个 keep 类名与源码路径**逐条比对一致**（LSPFRIFAModule / GumJsBridge / LSPFRIFAApplication /
  MainActivity / ScriptConfigProvider / ScriptStore / EarlyLogBuffer / HookRouter）。
- `check-kt.sh` 双绿（本轮未动 Kotlin，属回归确认）。
- **未编译**：R8 规则的正确性最终要由 `assembleRelease` 验证（见 Checklist §L）。

#### G. 仓库门面（同批完成，无代码风险）
- **`LICENSE`**：GPL-3.0 全文 674 行（标准行数）。
  取材过程中两个源**均不完整**，已如实记录：
  | 源 | 问题 |
  |---|---|
  | `gnu.org/licenses/gpl-3.0.txt` | 网络被阻（SSL_ERROR_SYSCALL） |
  | choosealicense 版（**首取**） | 带 YAML frontmatter（37 行），剥除后**正文仅 553 行**，缺 `Copyright (C) 2007 Free Software Foundation, Inc.` |
  | SPDX 版 | **仅 232 行**，同样缺该行 |
  | **最终采用** | choosealicense 原始版剥 frontmatter 后 **674 行**，9 项关键锚点全命中 |
- **`.github/ISSUE_TEMPLATE/`**：`bug_report.yml` + `feature_request.yml`。
  bug 模板强制附日志（对应 Checklist §F 的回传格式）；feature 模板显式列出**已暂缓方向**（模块化拼装 /
  符号裁剪 / 深度 VM API）减少无效 issue。

#### H. 已知未验证项
1. **R8 规则未经真实构建**：`assembleRelease` 是唯一验收手段。
   若出现 `Missing class` 告警需补 `-dontwarn`；若运行时崩溃，优先怀疑
   入口类/JNI/AIDL 三类的 keep 不足（可临时 `-keep class com.bail.lspfrifa.** { *; }` 二分定位）。
2. **release 变体未签名**：产出 `app-release-unsigned.apk`，需手动签名；
   或临时把 `isMinifyEnabled = true` 打在 debug 上验证（会牺牲增量编译速度）。
3. **依赖库自带 consumer 规则未实核**：sandbox 无 Gradle 缓存，
   `sora-editor`/`tm4e`/`miuix` 的 consumer proguard 内容无法查证——
   本轮按"保守 superset"策略多留了规则（过度 keep 只影响体积收益，不影响正确性）。

## 6. 验证清单（每工作流交付后由用户侧执行）

- **工作流 1**：宿主冷启动前先启动目标 → 脚本应仍注入成功（`load_persisted_script src=remote_prefs`）；
  `event=binder_handshake_deferred` 不应阻断注入。
- **工作流 2**：文件/剪贴板/分享三通道各导入一次；重复导入同内容不产生重复条目；撤销导入可回滚；
  两模块同方法 replace 时出现 `CONFLICT` 日志；连续 3 次坏脚本触发熔断且可一键恢复。
- **工作流 3**：`ARMED` 日志时间早于 `event=application_created`（或至少同阶段）；
  hook `Application.onCreate` 时命中率显著提升；目标冷启动无可见变慢。
- **工作流 4**：脚本直接 hook 一个未加载的应用类 → 不再 `MISS`，而在该类加载后自动 `ARMED`；
  激进模式关闭时行为退回工作流 3。

## 7. 实施记录（工作流 1 已交付 — 2026-08-26）

### 新增文件
| 文件 | 作用 |
|---|---|
| `data/ScriptLibraryStore.kt` | D8/D9 模块库（SharedPreferences `lspfrifa_library`；sha256 去重；增删改查） |
| `data/ScriptImport.kt` | D6/D7 导入解析（SAF Uri / 文本；二进制嗅探 / BOM / 括号配平；不阻断式警告） |
| `xposed/EarlyLogBuffer.kt` | D14 早期日志 ring buffer（500 行，flush 幂等） |

### 改动文件
| 文件 | 改动 |
|---|---|
| `ipc/ScriptStore.kt` | **重写**：D15 分组（`lspfrifa_config` 只放小数据 + `lspfrifa_s_<pkgSafe>` 专属正文）；`saveScript` 返回 `SaveResult` 四态；`commit()` 同步校验；备份/撤销；熔断计数；模块集 |
| `ipc/IpcManager.kt` | `pushScript`/`saveScript` 透传 `SaveResult`；新增 backup/undoImport/failCount/isRemoteAvailable |
| `provider/ScriptConfigProvider.kt` | `save_script` 返回 `result` 字段（不再只回 boolean） |
| `xposed/LSPFRIFAModule.kt` | D15 读取链（指针 → 专属 group → 旧键 → Provider）；`record()` 双写早期日志 |
| `xposed/TargetIpcServer.kt` | 握手成功时 `EarlyLogBuffer.flush`；`hostLog` 双写 buffer |
| `ui/screen/ScriptEditorScreenV1.kt` | 顶栏导入入口（MiuixIcons.Edit）；来源选择弹窗（文件/剪贴板/撤销）；预览确认弹窗；`pushScript` 适配四态反馈 |
| `LSPFRIFAApplication.kt` | `ScriptLibraryStore.init(this)` |

### 检查结果
`bash tools/check-kt.sh` → **两条全绿**（0 规则命中 + 0 未导入符号）。
唯一告警：`ScriptImport.kt` 花括号 30/29 —— **已知假阳性**（字符字面量 `LBRACE`/`RBRACE` 各计 1，工具不排除 char 字面量；项目坑位库已记录此规则缺陷）。

### 交付期间的自我纠正（如实记录）
1. 首次用 `apply_file` 行内注入弹窗时，因 `lastIndexOf('}')` 定位错误，把弹窗块插进了 `ImportPreviewDialog` 内部 → 改用 `git show HEAD:<file>` 取回干净基线（279 行）整体重建，不再做增量补丁。
2. 两次被 `tools/check-kt.sh` 拦住：`ArrayDeque` 未 import（真问题）；花括号（假阳性）。
3. 三查推翻了初版设计两处（见 D4/D5 修订）：文件通道只读（无写入 API）、remote prefs 限制是 group 级。


### 第二轮交付（同日，D6 分享入口闭环）

| 文件 | 类型 | 作用 |
|---|---|---|
| `data/PendingImport.kt` | 新增 | 分享进入的待处理单例。**在 Intent 到达当下就读完字节**（R8 风险的落地对策：Uri 授权会过期，不能只存 Uri） |
| `ui/screen/ImportTargetScreen.kt` | 新增 | 分享后“导到哪里”决策页：仅入库 / 应用到某个已添加目标（分享无目标上下文，必须问用户） |
| `AndroidManifest.xml` | 改动 | MainActivity 加 ACTION_SEND + ACTION_VIEW intent-filter（零新增权限） |
| `MainActivity.kt` | 改动 | `onCreate`/`onNewIntent` 捕获分享；新增 `import_target` 导航路由 |

### 验证结果（本轮结束）
- `tools/check-kt.sh` → **两条全绿**
- 逐文件花括号净值：EarlyLogBuffer 5/5、ScriptLibraryStore 15/15、ScriptImport 排除字面量后 **28/28**、PendingImport 11/11、ImportTargetScreen 27/27、ScriptStore 28/28、MainActivity 85/85 → **全部平衡**
- `ScriptImport.kt` 的 30/29 告警已定性：工具未排除字符字面量（`'{'`/`'}'`），**已知假阳性**，建议后续给 `check-kt.sh` 的括号规则加字符字面量排除

### 本轮 AI 自己的错误（如实记录，供后续警惕）
1. `apply_file` 参数名写错（`new_text` 应为 `new`）多次
2. 行内 JS 模板字符串与 Kotlin 的 `${...}` 插值冲突 → 改用数组拼接 + 文件落盘执行
3. **最严重**：用 `lastIndexOf('}')` 定位插入点，把弹窗块插进了另一个对话框内部，破坏文件结构 → 改用 `git show HEAD:<file>` 取干净基线整体重建，**教训：结构性插入不能用“最后一个括号”定位**
4. MainActivity 新路由插进了 logs composable 内部（同样原因），靠花括号净值 + 手工缩进核对才发现

> **给后续会话的硬建议**：本项目 Kotlin 编辑严禁“找最后一个 } 插入”；应先取 git 基线，再用唯一字符串锚点 + 完整块重建。本次已因此浪费两轮修复。

### 尚未交付（后续工作流）
- ~~工作流 2 余项~~：库管理页 ✅、D12 熔断 ✅（本轮完成）；模块化拼装 ⏸️（用户暂缓）；D10 冲突语义 ⏸️（依赖模块化）
- 工作流 3：`onPackageReady` 提前注入
- 工作流 4：类加载感知
- ~~分享入口（`ACTION_SEND` intent-filter）~~ ✅ 本第二轮已闭环（PendingImport + ImportTargetScreen + Manifest + 路由）
- **实测回填**：D5 的四个尺寸阈值（200KB/500KB/900KB/1.2MB）需用户侧真机验证
