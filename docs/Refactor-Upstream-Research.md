# 重构上游研究报告 — libxposed/service · Navigation3 · materialkolor

状态：**v1.4（读阶段完成）**；来源=3 个深读 agent（service/Navigation3 回填，materialkolor agent 中断后由 Captain 用 README 全文+POM 实证+本地源码闭环）+ Captain 一手 sources 实读（service/api 102、Miuix ThemeController）。日期：2026-08-24。
用途：LSPFRIFA「UI 重构 + LSP 通讯重构」的技术选型依据。**读阶段完成，重构为下一阶段（未开始）**。

---

## 1. libxposed/service 102.0.0（⭐ 一手：Maven sources jar 实读）

坐标：`io.github.libxposed:service:102.0.0`（与 api 102 配对；宿主 App 侧 `implementation`，模块侧无）
类清单（sources jar 6 类）：`XposedServiceHelper / XposedService / RemotePreferences / HookedTarget / HotReloadResult / XposedProvider`

### 1.1 XposedServiceHelper（唯一入口）
```java
public static void registerListener(OnServiceListener listener)  // 只调一次；bind 早于 listener 时先缓存 mCache 再派发
public interface OnServiceListener {
    void onServiceBind(@NonNull XposedService service);  // 多框架可多次 bind
    void onServiceDied(@NonNull XposedService service);
}
```
我们现状已用它（LSPFRIFAApplication → FrameworkState【验证 ✅】）。

### 1.2 XposedService（完整方法清单）
| 能力 | 方法 | 对重构的用途 |
|---|---|---|
| 框架信息 | `getApiVersion/getFrameworkName/getFrameworkVersion/getFrameworkVersionCode/getFrameworkProperties` | 设置页"模块 API/框架版本"（现走 FrameworkState 部分数据源可补齐） |
| **动态作用域** | `getScope() / requestScope(pkgs, OnScopeEventListener) / removeScope(pkgs)` | ⭐ **替换"用户手动去 LSPosed Manager 勾选"流程**：详情页选目标 → requestScope → 用户授权页 → approved/failed 回调 |
| **运行目标** | `getRunningTargets(): List<HookedTarget>` | ⭐ **在线状态数据源**：框架视角"已注入且存活的目标"，可替代自研 activeTargets 注册表 + 详情 3s 轮询的**信息源**（分层语义：框架层在线 vs 引擎层 alive（ping）） |
| 热重载 | `hotReloadModule(target, data, HotReloadCallback)` | 模块热重载（模块 app 手动重载/脚本热更按需） |
| **远程存储** | `getRemotePreferences(group) / deleteRemotePreferences(group)`、`listRemoteFiles() / openRemoteFile(name, ...) / deleteRemoteFile(name)` | ⭐ 跨进程共享存储（框架托管）：脚本内容/启用开关可迁往远程 prefs → **ScriptConfigProvider 的脚本读写面可退役** |
| 常量 | API_101/API_102、PROP_CAP_SYSTEM/REMOTE/RT_API_PROTECTION | — |

### 1.3 关键结论（LSP 通讯重构目标态）【v1.3：证据闭环（api sources 实读 + agent A 行号级回填）】

**分歧消解（决定性证据）**：模块（hooked 目标进程）侧 API `XposedInterface.getRemotePreferences/listRemoteFiles/openRemoteFile` 的 javadoc（api-src XposedInterface.java L537-559）明确 **"read-only in hooked apps"**、`openRemoteFile` **"read-only mode"**；UOE **仅限 "framework is embedded"**（模块入口嵌入框架进程，如 system server 内嵌系统模块）。→ **普通目标进程模块可只读远程 prefs/files**；注入链可摆脱"依赖宿主进程存活"。

**重构目标态（最终）**：
- ✅ **退役**：ScriptConfigProvider 的 `get_script`/`is_target_enabled`（模块侧改读框架远程 prefs：宿主 `XposedService.getRemotePreferences` 写（框架数据库，模块 App 数据持久于框架），模块 `XposedInterface.getRemotePreferences` 只读）；宿主侧 UI 配置读写亦可远程 prefs；在线状态/框架信息 → `getRunningTargets()`（HookedTarget.State：UP_TO_DATE/STALE/RELOADING/FAILED）+ `getFrameworkName/Version/Properties`；作用域 → `getScope/requestScope`（授权 UI 由框架/LSPosed Manager 承载，库内无 deeplink——agent A 纠错）；热重载 → `hotReloadModule`+onHotReloading/onHotReloaded（前提：恰一个 Java 入口类）
- ✅ **保留**：目标进程内 Binder `IScriptExecutor`（实时脚本执行/结果）+ `ILogReceiver`（实时日志流）——service 只共享"状态"，无实时交互；`register_ipc`（Provider 唯一必须保留的目标侧动词 + F1 caller-uid 鉴权）
- ✅ **最终 Provider 形态**：仅 `register_ipc`（+鉴权）；`save/remove/enable/disable` 动词与 ScriptStore 本地后端随之调整（宿主 UI in-process → 远程 prefs 后端）
- ⚠️ **安全新增**：service 的 `XposedProvider`（authority=`${applicationId}.XposedService`，exported=true）**绑定过程无 caller-uid 校验**（agent A 指出）——任何 App 可 `call("SendBinder")` 伪造服务；若重构后依赖它，须自行校验 binder 属主（框架/系统 uid）再信任。自建 ScriptConfigProvider（authority 不同）不受影响
- **坑**（agent A）：`openRemoteFile/deleteRemoteFile` name 禁含分隔符与 `.`/`..`；remote prefs `apply()` 在单线程 executor 异步提交；`hotReloadModule` data 禁放模块自定义 Parcelable/Serializable；HookedTarget/HotReloadResult 为 API 102 方法（需 LSPosed 2.1+，本项目 2.1.1 ✅）

---

## 2. AndroidX Navigation3（官方 releases 实读 + CMP 状态）【v1.1：agent B 详读回填】

### 2.1 版本时间线（agent B 实读发布页；分歧已标注）
| 版本 | 日期 | 要点 |
|---|---|---|
| 1.0.0-alpha01 | 2024-11-27（agent B）/ 检索摘要见 2025-05-20 ⚠️双源分歧，以发布页为准 | 首发；SnapshotStateList 栈 + 类型化 NavKey |
| alpha08 | 2025-08-27 | **Compose Multiplatform 全平台**（JetBrains artifact：`org.jetbrains.androidx.navigation3:navigation3-ui`） |
| alpha09 | 2025-09-10 | NavKey/@Serializable、RememberNavBackStack、DecoratedNavEntry 系列 |
| alpha11 | 2025-10-08 | 交互式预测性返回 |
| 1.0.0-beta01 | 2025-10-22 | Scene/SceneStrategy/rememberSceneState（仍实验） |
| **1.0.0 稳定** | **2025-11-19**（googleblog 官宣） | NavBackStack/NavDisplay/entry/NavKey 核心转稳定 |
| 1.1.0-alpha05/beta01 | 2026-02-25 / 2026-03-11 | NavDisplay 扩展、SceneDecoratorStrategy、DialogKey |
| **当前** | **稳定 1.1.6（2026-08-12）**；alpha 1.2.0-alpha07 | — |

### 2.2 稳定度结论（agent B）
- **nav3 核心 API 已稳定（非整体实验）**；`@Experimental` 仍挂在高级 API（Scene/SceneStrategy/rememberSceneState/DialogKey 等，1.1.0-alpha05 仍在新增）
- `NavBackStack` 已 generic over NavKey，支持自定义 key 类型
- 官方架构指南正确 URL：`developer.android.com/guide/navigation/navigation-3`（`/develop/ui/compose/navigation3` 已 404）

### 2.3 API 模型
- 类型安全 `NavKey`；back stack = `SnapshotStateList<NavKey>` / `rememberNavBackStack`；add/removeLastOrNull 驱动；`NavDisplay` 观察栈渲染顶/多 pane；`NavEntry` 包裹目的地；可组合谓词+预测性返回（navigationevent）+自适应（adaptive-navigation3）
- 与 Nav2 差异：无 NavController/NavHost，栈即状态；compose-first、类型安全；navigation-compose 未弃用，nav3 为 Compose 新项目推荐方向（置信：高）

### 2.4 与 miuix-nav 的取舍（本项目关键决策）【agent B：推荐 miuix-nav，置信度高】
| 方案 | 优势 | 劣势 | 推荐度 |
|---|---|---|---|
| **miuix-nav 0.9.4-rc01** | 与 Miuix 0.9.4 同版本同生态；FloatingNavigationBar/TopAppBar/主题**零桥接**；官方示例齐全 | flat 栈、无 scene/结果通道；导航能力够用 | ⭐⭐ **推荐**（当前页面≤6 场景）；页面>4 或二级页出现时以 flat 栈迁移，Pager 降为一级 tab 容器 |
| androidx navigation3（org.jetbrains.androidx.navigation3） | 官方方向、1.1.6 稳定、scene 自适应/多面板 | 与 Miuix 混用=双栈自维护+版本可解析性**未验证**（风险中高）；收益低 | 备选（多面板/平板适配再上） |
| 现状（Pager+tab） | 零依赖、稳定 | 无返回栈/二级页语义 | 过渡（页面≤4 时接受） |

**选择页结果回传注意**：miuix-nav 无结果通道 → 用状态容器/回调 + 手动清栈（agent B 提示）。
**未验证**：nav3 稳定版对 Scene 实验注解的精确 scope；miuix-nav 内部实现与发布节奏；org.jetbrains.androidx.navigation3 与 Miuix 0.9.4 依赖 CMP 版本真机可解析性。

---

## 3. materialkolor（⭐ 本地 Miuix 源码实读 + Maven 实查）

### 3.1 Miuix 的集成流水线（ThemeController.kt 实读）
```
colorsFromSeed(seed, colorSpec, paletteStyle, dark):
  Hct.fromInt(seed.toArgb())
  → Scheme*（TonalSpot/Neutral/Vibrant/Expressive/Rainbow/FruitSalad/
     Monochrome/Fidelity/Content 全家族）—— materialkolor scheme 工厂
  → MonetRoles（26 个 role：primary/…/onSurfaceVariant）
  → mapMd3RolesToMiuixColorsCommon(roles, dark)
默认种子色 Color(0xFF6750A4)；Spec2025 仅支持 styles 子集（否则运行时降级 Spec2021）
```
→ **本项目主题重构不需要直接依赖 materialkolor**：`ThemeController(keyColor, colorSpec, paletteStyle, isDark)` 是 Miuix 完整透传接口。扩展点 = ThemeModeStore 增存 keyColor/paletteStyle + 设置页取色 UI。

### 3.2 materialkolor 项目本体（Maven 实查 + POM 实证）
- 版本：**5.0.0 最新**（`com.materialkolor:material-kolor:5.0.0`，Kotlin 2.4.0 / poko；另有 material-color-utilities:5.0.0 纯色板无 Compose）。MIT。README 全文已读（GitHub raw，9.8KB）
- **我们工程的实际版本（POM 实锤）**：`miuix-ui-android-0.9.4-rc01.pom` → `com.materialkolor:material-color-utilities-android:5.0.0`（runtime scope）——**Miuix 0.9.4-rc01 传递的最新版 5.0.0**，Miuix ThemeController 消费其 `com.materialkolor.dynamiccolor/hct/scheme` 层
- 双 artifact：`material-kolor`（Compose Material3 集成：`dynamicColorScheme(seedColor, isDark)`、`PaletteStyle`、`ColorSpec.SpecVersion.SPEC_2025`、`DynamicMaterialTheme`/`MaterialExpressiveTheme`、HarmonizeColors/提亮/色温、**`ImageBitmap.themeColors()/themeColor()` 图片取种色管线**）；`material-color-utilities`（Miuix 用的那层）
- 依赖 Material Color Utilities（Google `material-foundation/material-color-utilities` 的 Kotlin 移植）

### 3.3 主题重构可行路径
1. 【低风险】保持 ThemeController 透传：ThemeModeStore 增加 `keyColor`（Hex 串）+ `paletteStyle` 持久化；设置页增加"主题色（种子色）"入口（预设色板/取图-后者需 material-kolor 集成，先行预设色板）
2. 【中风险】Spec2025：直接支持（Miuix 已透传），仅部分 paletteStyle 生效——UI 上可展示提示
3. 【参考】materialkolor 的 image→scheme 管线（若后续上"壁纸取色"）

---

## 5. 实施记录

### R1.1（✅ 已实现，未编译验证）— 配置双通道：框架远程 prefs 优先 + Provider 回退
**目标**：目标进程读取脚本/开关不再依赖宿主进程存活（远程 prefs 由框架托管，模块只读）。
**文件**：`LSPFRIFAApplication.kt`（FrameworkState.current() 暴露当前 service）、`ScriptStore.kt`（双通道读写：远程优先/本地常驻/双写）、`LSPFRIFAModule.kt`（checkTargetEnabled/loadInitialScript 远程优先→Provider 回退；键常量双端一致 `lspfrifa_config` / `enabled`(StringSet) / `script.<pkg>`）。
**兼容性**：Provider 全部动词未删（回退路径保留）；升级场景（旧数据仅本地）→ 模块读远程 null → Provider→ScriptStore 本地返回 ✅；下次宿主写入双写远程 → 后续模块走远程 ✅ 渐进迁移。
**验证要点（真机）**：① 模块日志出现 `event=target_check_remote`/`load_persisted_script src=remote_prefs` ② 杀宿主进程后强停重开目标 app→ 注入链仍走远程（不出现 provider_no_bundle 重试的 30 秒等待）③ 开关同步 UI 正常。
**待办（后续轮）**：~~R1.2（getRunningTargets 在线状态/requestScope 作用域 UI）~~ 已完成（见下）；R1.3（hotReloadModule）；R2（miuix-nav + 主题扩展）。

### R1.2（✅ 已实现，未编译验证）— 框架级在线状态 + 动态作用域
**目标**：① Dashboard 在线进程数在宿主进程被杀后仍真实反映（框架级 `getRunningTargets` 补盲）② 详情页一键向框架申请作用域（替代去 LSPosed Manager 手动勾选）。
**文件**：`IpcManager.kt`（`onlineCount()` = max(引擎注册表, 框架 UP_TO_DATE/RELOADING 目标数)；`requestScope(pkg, onResult)` 封装，回调 post 主线程）、`MainActivity.kt:65`（onResume 取数改 `onlineCount()`）、`ProjectDetailScreenV093.kt`（连接状态行新增"申请作用域"可点击入口 + 回调入日志流；补齐 `Modifier.width` import）。
**关键依赖**：`HookedTarget.State` 枚举（UP_TO_DATE=跑着当前模块代码/STALE/RELOADING/FAILED，sources 实读）；`OnScopeEventListener` 为 default 方法接口（非 SAM，须 object 表达式）。
**验证要点**：① 杀宿主后打开目标 app → Dashboard 在线进程数非 0（框架级生效）② 详情页"申请作用域" → 框架授权页 → 日志 `作用域申请成功: 已授权: ...`。

### R1.3（✅ 已实现，未编译验证）— 模块热重载（hotReloadModule）
**目标**：设置页一键对运行中目标（STALE=跑旧代码）热重载模块，免重开目标进程。
**文件**：`IpcManager.kt`（`hotReloadTarget(target, onResult(HotReloadResult))`——真实签名 `onHotReloadResult(HookedTarget, HotReloadResult)`（record: Status SUCCEEDED/FAILED/UNSUPPORTED/IN_PROGRESS/PROCESS_DIED + message，sources 实读；data 固定空 Bundle）；`hotReloadStaleTargets(onResult(done,total,msg))` 逐个 STALE 目标重载）、`SettingsScreenV093.kt`（框架状态卡新增"热重载模块" BasicComponent，onClick 触发、summary 行回显结果；补 remember/mutableStateOf/setValue/IpcManager imports）。
**验证要点**：① 修改模块代码重装模块 APK → 目标进程处于 STALE → 设置页点"热重载模块" → 目标日志出现 `[hot] <process> -> SUCCEEDED` 且目标进程存活 ② 无 STALE 时提示"无待热重载目标" ③ 未激活时提示未连接。

**LSP 通讯重构（R1.1+R1.2+R1.3）全部完成——编译批次 1**：共 7 文件（LSPFRIFAApplication/ScriptStore/LSPFRIFAModule/IpcManager/MainActivity/ProjectDetailScreenV093/SettingsScreenV093）。验证顺序：编译 → ①配置双通道（target_check_remote/remote_prefs 日志+杀宿主后目标仍注入）②在线状态（杀宿主后 Dashboard 非 0）③作用域申请 ④热重载 ⑤主题三态回归（上批）。

**R2（待办）**：miuix-nav 导航重构 + 主题扩展（keyColor/paletteStyle）。

---

## 7. 批次 1（R1.1-R1.3）签名自查记录（2026-08-24 交付前全量核对）

**方法**：每条库侧 API 均以 `~/miuix-src/` 下 sources jar（service/api 102.0.0/Miuix 0.9.4-rc01）实读比对。**结论：0 处签名偏差**（HotReloadCallback 已在开发中由真源码纠正；其余全部核验通过）。

| API（R1.x 使用处） | 真源码证据 | 代码用法 | 判定 |
|---|---|---|---|
| `XposedService.getRemotePreferences(group)` (R1.1 宿主) | XposedService.java L379 | `FrameworkState.current()?.getRemotePreferences(GROUP)` | ✅ |
| `XposedInterfaceWrapper.getRemotePreferences` (R1.1 模块) | api-src XposedInterfaceWrapper.java L167（final 转发）+ XposedInterface.java L537 javadoc(read-only in hooked apps) | `getRemotePreferences(REMOTE_GROUP)` 直调 | ✅ |
| `RemotePreferences.getStringSet/putStringSet` (R1.1 enabled 键) | RemotePreferences.java L71/L138：**编辑路径直接存 Set 无转换**、读侧 cast `(Set<String>)`；map 经 Serializable 通道往返（HashMap+HashSet 可序列化） | StringSet 存储 | ✅ **本轮新核**（类型一致，无 ClassCastException 风险） |
| `XposedService.getRunningTargets(): List<HookedTarget>` (R1.2) | XposedService.java L270 | `?.getRunningTargets()` | ✅ |
| `HookedTarget.State` (R1.2) | HookedTarget.java L19-40：UP_TO_DATE/STALE/RELOADING/FAILED | `it.state == ...` | ✅ |
| `HookedTarget.getState/getProcessName` (R1.2/1.3) | HookedTarget.java L78/L86 | 属性语法 `state`/`processName` | ✅ |
| `XposedService.requestScope(List, OnScopeEventListener)` (R1.2) | XposedService.java L234（oneway 异步） | `requestScope(listOf(pkg), object : ...)` | ✅ |
| `OnScopeEventListener` (R1.2) | XposedService.java L68-105：**default 方法接口（非 SAM）**、`onScopeRequestApproved(List<String>)`/`onScopeRequestFailed(String)` | object 表达式（正确非 lambda） | ✅ |
| `XposedService.hotReloadModule(HookedTarget, Bundle, HotReloadCallback)` (R1.3) | XposedService.java L319；data 禁放模块自定义 Parcelable（L307-310） | 空 Bundle | ✅ |
| `HotReloadCallback.onHotReloadResult(HookedTarget, HotReloadResult)` (R1.3) | XposedService.java L108+：**真实签名=(target, result) 非 (code,msg)** | 已按其实现 | ✅（开发期已纠正） |
| `HotReloadResult` record (R1.3) | HotReloadResult.java L17：`(Status, String? message)`；Status 枚举 L21-55 | 构造/属性访问 | ✅ |
| `BasicComponent(onClick)` (R1.3 UI) | Component.kt L59-74（onClick: (() -> Unit)?） | 设置页用 onClick | ✅ |

**`onlineCount()` 重复计数分析（用户质疑项）**：
- 实现 = `maxOf(engine, framework)`（**取最大值，非求和**）——重复计数/虚高只会发生在"求和或并集 size"的实现中，max 天然免疫。
- 集合关系论证：进程能注册引擎 Binder ⇒ 跑着当前模块代码 ⇒ 框架必然 UP_TO_DATE ⇒ **引擎 ⊆ 框架（稳态）**；max=framework（数值=真实在线）。
- 边界场景（如实）：① 框架数据异常→0 ⇒ max=engine（不虚高）② 框架时序滞后（注册未反映）⇒ max=engine=真实值 ③ 框架将进程标 STALE 但引擎仍活着 ⇒ max=engine（引擎级真实存活数）。**所有场景 max 均不虚高、不低估**。
- 语义瑕疵（非缺陷）：不区分两来源构成（数值正确、构成未披露）；Uid+Pid 去重并集为过度设计，不采纳。

**已知未验证/框架侧灰度**：XposedInterface.getRemotePreferences 的**模块侧实现细节**（框架内部，快照/监听行为）——经推理冷启动语义=新进程新快照=最新值，热路径不受影响（仍走 Binder）；留真机验证兜底（若 target_check_remote 未出现=远程通道未生效，自动回退 Provider，功能不坏）。

---

## 6. 重构路线建议（依文档 v1.3 结论定稿）

### Phase R1 — LSP 通讯重构（影响面：IpcManager/ScriptStore/LogStore/Provider/模块侧）
1. ScriptStore 的「脚本内容 + 启用开关」→ `XposedService.getRemotePreferences`（宿主写、读回）；模块侧读取通道按 1.3 待验证结果分叉：
   - 若模块侧可读远程 prefs → Provider 删去 get_script/is_target_enabled，仅留 register_ipc（注册表查 running targets）
   - 若不可读 → Provider 保留 3 目标侧动词（F1 鉴权面不变），仅存储后端换远程 prefs
2. 在线状态：Dashboard/详情改用 `getRunningTargets()`（框架级）+ 保留 ping（引擎级）双层；自研 activeTargets 注册表退役或降级
3. 作用域：`requestScope` 流程（选择目标 → 申请 → 授权回调 → 生效提示），配合现有"未选作用域"三态诊断改造

### Phase R2 — UI 重构（影响面：MainActivity/Screens）
1. 导航：引入 **miuix-nav**（NavKey+rememberNavBackStack+NavDisplay），页面注册：Dashboard/Project/ProjectDetail/Settings/SelectProject；Pager 保留为 Dashboard 内部容器
2. 主题：ThemeModeStore 扩展（keyColor/paletteStyle/Spec2025）+ 设置页「主题色」入口（预设色板 v1）
3. 顺带消化审计 P1/P2（日志性能/状态同源/hook DSL 等）

### 待 agent 报告回填
- [x] Navigation3：agent B 已回填（1.0.0 稳定=2025-11-19、当前 1.1.6、推荐 miuix-nav）
- [x] libxposed service：agent A 行号级回填 + Captain api-sources 分歧消解（模块侧可只读远程 prefs；UOE 仅限 embedded；XposedProvider 无 caller 校验需自补）
- [x] materialkolor：README 全文（GitHub raw）+ Maven 版本 + **POM 实锤传递版本 5.0.0** + Miuix ThemeController 集成流水线——Captain 闭环（agent C 深读 6 轮后中断，无新增信息缺口）

---

## 8. R2 导航重构设计（miuix-nav 0.9.4-rc01 API 一手研读落点，2026-08-24）

**sig 研读证据**：`~/miuix-src/nav-sources.jar`（miuix-nav-android 0.9.4-rc01 sources，public API 实读）。

**核心 API 与设计决策**：
| API | 签名（实读） | 决策 |
|---|---|---|
| `NavKey` | marker interface（纯标签无行为） | 页面 Route 以 sealed interface 实现 |
| `NavBackStack` | = `SnapshotStateList<NavKey>`；`navBackStackOf(vararg)` 内存栈（**免 @Serializable**） | ✅ **采用内存栈**（页面≤6、无需进程死恢复；绕开 kotlinx-serialization 插件引入，构建面最小） |
| `rememberNavBackStack<T>` | @Composable，**要求 @Serializable**（非序列化键抛 SerializationException） | 不采用（见上） |
| `NavController(backStack)` | `push/pop(≥1)/replace/popUntil` + `rememberNavController<T>` | 用 `rememberNavController`（确认其为内存 remember，实施时复核） |
| `NavDisplay(backStack, modifier, onBack, transition, effects, content: NavEntryBuilder.() -> Unit)` | **简洁公开重载**（L948-965）；onBack 默认 `backStack.removeLastOrNull()`；content DSL=NavEntryBuilder（entry<T>{} 等实施时读 NavEntryBuilder 签名） | ✅ 唯一渲染入口 |
| 转场 | `transition: NavTransition = NavTransitions.MiuixDefault` | 用默认 |

**R2 目标形态**（导航层）：
- Route 层级：`sealed interface Route : NavKey { Home(内含 Pager 三页)/Settings/ProjectDetail(pkg)/SelectProject }`
- MainActivity：Root = `MiuixTheme { NavDisplay(backStack=rememberNavController(Route.Home).backStack) { entry<Route.*> { screen } } }`；底栏（FloatingNavigationBar）只在 `Route.Home` 栈顶显示（Home 内部保留 Pager）
- 详情/设置/选择页 = push 进栈；返回 = NavDisplay onBack（默认 removeLastOrNull）
- Pager 保留为 Home 的 tab 容器（状态提升至 Home 层）
- 依赖新增：`top.yukonga.miuix.kmp:miuix-nav-android:0.9.4-rc01`（gradle libs.versions.toml 增一行）
- 主题扩展（R2 后半）：ThemeModeStore + `keyColor`（Hex 存储/详情色板）/paletteStyle（Miuix ThemeController 已透传，实读确认）

**执行顺序确认（用户指令）**：① R2 重构主线优先（本设计文档即为 R2 启动准备，实施紧随批次 1 编译回归后）② Xed-Editor 编辑器升级为独立并行项（GPLv3 已否决直接集成；research agent 转 Rosemoe CodeEditor 选型中，报告后由 ui-engineer-2 独立实施，不阻塞主线）。

## 9. R2 修正与实施记录（2026-08-24 实况修正）

**前提修正（重要）**：实读 MainActivity 发现项目**已在用 Navigation 2**（androidx.navigation.compose NavHost：main/select_project/project_detail/{pkg}?name，运行正常）——第 8 章"从零迁移 miuix-nav"的前提（无导航栈）**不成立**。
**决策（第一性原理）**：Navigation 2 已满足需求且工作正常 → **miuix-nav 迁移暂缓**（技术偏好替换，无用户可感知价值，不臆造收益）；R2 聚焦真实价值项：
- **R2-1 底部胶囊导航动画修复**（团队 ui-engineer-2，MiuixComponents.kt）："双灰"根因=每个标签各自 animateColorAsState（旧标签淡出+新标签淡入）；修复=单一 pill 滑动指示器（参考官方 NavigationBar indicator 思路）。
- **R2-2 主题扩展（✅ 已实现，未编译验证）**：`ThemeModeStore` 增加 keyColor（#RRGGBB Hex）+ paletteStyle（默认 Content）持久化/StateFlow；`MainActivity` ThemeController 透传（remember(mode, keyColor, paletteStyle) 重建）；`SettingsScreenV093` 主题卡新增"主题色"预设色板（默认项+6 色，点击选中/再点取消恢复默认，ThemeColorDot 组件）。验证要点：选色即时全局变色、杀进程保持、默认项恢复 Miuix 默认紫。
- **R2-3（进行中）**：编辑器升级（Xed-Editor GPLv3 否决 → Rosemoe CodeEditor 选型，t1 进行中）。

### R2 实施状态更新（2026-08-24 晚）
- **R2-1 胶囊动画修复（✅ 完成，未编译验证）**：ui-engineer-2 单一 pill 滑动指示器（MiuixComponents.kt 仅此文件；animateDpAsState 位移+secondaryContainer 恒色+icon/text 颜色插值；singlePillOffsetX 公式与 Row SpaceEvenly/76dp 槽位/16dp padding 数学同源，胶囊 64dp 居中+6dp；调用方零改动）。
- **R2-2 主题扩展（✅ 完成，未编译验证 + 关键修复）**：ThemeModeStore keyColor/paletteStyle + MainActivity effectiveMode 合成（⚠️ Miuix ThemeController 源码实读修正：keyColor 仅 Monet* 模式生效，故有种子色时三态映射 MonetSystem/MonetLight/MonetDark，否则色选了不变）+ 设置页 6 色预设色板+默认项。
- **R2-3 编辑器升级（选型定稿，集成进行中）**：Xed-Editor = GPL v3 + 完整 App + 内核 sora fork（not for general use）→ **否决集成**；**主选 = io.github.Rosemoe.sora-editor:editor:0.23.6（LGPL v2.1 动态链接合规）+ language-textmate:0.23.6**（JS 高亮=上游 Rosemoe repo 的 textmate 包；禁止从 Xed repo 拷贝任何文件）；AndroidView{CodeEditor} 桥接；体积 +2.6MB。textmate 接入次序坑：先 initGrammarRegistry 再 setText。

## 10. 架构演进参考（用户愿景 2026-08-24 输入 + 现状对照）

| 用户愿景模块 | 现状/决策 | 判定 |
|---|---|---|
| C++ 胶水层（LSPlant↔GumJS 参数封送/原方法路由） | = P1（LSP.hook onEnter/onExit 回调 JS + 参数/返回值双向） | ✅ 方向一致，P1 待排期 |
| IPC 总线（LocalSocket 抽象命名空间 + 二进制分帧 [1B Level][4B Len][Payload]） | 现状 Binder（IScriptExecutor/ILogReceiver；1MB 事务限制理论存在，当前日志量未触顶） | ⏳ 演进项：高频日志（1w/s 级）场景升级 Socket+分帧 |
| 注入载体/作用域过滤 | ✅ 已实现（LSPosed 注入链 + 三态 + requestScope 动态作用域） | 完成 |
| JNI 线程/GC 守卫（AttachCurrentThreadAsDaemon + Push/PopLocalFrame） | 部分已做（on_message attach/detach）；**$borrowClassHandle 崩溃=未治的 GC/JNI 引用风险** | ⚠️ 强化项（P1.5：JS 回调包裹 LocalFrame + daemon attach 检查） |
| frida-java-bridge 职责边界（废弃 hook 仅反射辅助） | ✅ 与既定一致（官方通道 LSP.hook 接管 hook；bundle 保留供用户用=反射/类操作） | 一致 |
| UI 技术栈（用户建议 Compose+M3） | ⚠️ 差异：项目既定 **Miuix**（HyperOS 风格，全量投入）——不迁移 M3 | 维持 Miuix |
| 持久化（用户建议 Room+DataStore） | 现状 SharedPreferences/远程 prefs/LogStore 文件 | ⏳ 演进候选（脚本元数据/多脚本管理时再评估 Room） |
| 编辑器 UX（Symbol Bar 符号栏/一键模板/Snippets 库） | 编辑器集成（t3）后增强项 | 记录为 R2-3 后续 |

**本轮许可证核验（一手）**：sora-editor = **LGPL v2.1**（GitHub LICENSE + Maven POM 双实锤；用户记忆"Apache-2.0"有误——动态链接原库不改源码=商用合规，NOTICE 按 LGPL 措辞）；textmate 底层 tm4e = **EPL-2.0**（随包）；Xed-Editor = GPLv3+闭门 fork 否决。版本最新 = **0.23.6**（用户给的 0.23.4 非最新）。

## 11. P1/P2 设计基线（用户 2026-08-24 输入 5 项 + 现状对照裁决）

### ① 热重载与 Hook 清理注册表（P1 修订，✅ 采纳——真实缺口，且比设想的更小）
- 现状：LSP.hook 的 handle 注册在 **Kotlin HookRouter**（跨脚本存活）；GumJS unload 会清理其 Interceptor（无需 C++ 管理），但 **HookRouter 的手柄不会自动 unhook**——脚本热重载后旧 Java hook 仍在，重复发相同 LSP.hook 被幂等键跳过（新参数不更新）。
- 方案（Kotlin 即可，无需 C++ 胶水）：HookRouter 增加 `LSP.unhookAll()`（消息 t="lsp.unhook_all"）→ 遍历 handles 调 `HookHandle.unhook()`（libxposed 框架 API，技能表已确认）+ handles.clear()；GumJsBridge 重载流程=先 unhookAll 再 loadScript。
- 注：Interceptor.attach 无需手动 revert（unload script 时 Gum 自动清理）——仅 LSPlant 侧需要注册表。

### ② JS 异常穿透熔断（记录，⚠️ 部分前提不成立，不新增）
- 用户前提"JS 异常直接 SIGABRT"对 **GumJS 内嵌脚本不成立**：QuickJS 异常被 Gum 引擎封装（on_message error，F4 已利用）；**我们 LSP.hook 的 intercept 是 Kotlin**（异常被 LSPlant protective 捕获=宿主不崩）。
- 真正需加固的是 Native 错误路径（已做：F4 create NULL 防御/错误消息检测）。**结论：现有机制已覆盖，不新增熔断层**；若未来 intercept 回调回 JS（P1），再在调用点包裹 JS 异常检查并回传 error 消息。

### ③ isolated 进程 IPC 容灾（P2 演进项）
- 现状：注入链=用户勾选作用域（默认主进程）；UI 已有"所有进程"标签（未实现隔离通信）。
- 方案（当支持"所有进程"时启动）：isolated_app 检测（uid/context）→ 主进程 broker 中继 或 ashmem/memfd+环形队列；与 IPC Socket 演进（第 10 章）合并评估。

### ④ 反反调试（P2 高级项，涉及重编 devkit）
- Gum 线程名特征（gum-js-loop/gmain）——替换需自定义构建或运行期改名（pthread_setname_np，运行期可行）；
- memfd 分配 trampoline/Inline 隐藏——重型，待明确对抗需求再评估（LSPlant 自身 trampoline 已是检测线索，单独抹 Gum 意义有限）。

### ⑤ 运行时类检索器（P1.5 采纳——配合编辑器 UX，实现用现有栈）
- **方案（无需 C++ 胶水）**：JS 侧 `LSP.findClasses(keyword)` → 走 Java.enumerateLoadedClasses（bundle 保留给用户用=正好用上！）或 Kotlin 侧反射遍历 ClassLoader；`LSP.dumpMethods("pkg.Cls")` → HookRouter K 侧反射 declaredMethods（已具备 Class.forName+枚举逻辑）。
- 回传：现有 onLog 通道（日志流）→ 编辑器"点选式模板"UX（t3 编辑器完成后接入）。
- 优先级：**P1.5**（JNI/GC 守卫同级），排在 P1（unhookAll + 桥接）之后。

### P1/P2 修订计划（并入路线）
- **P1（近期）**：① LSP.unhookAll 卸载语义（Kotlin，HookRouter ~30 行）② sora-editor 编辑器（t3 进行中）③ 批次 2 回归（动画/主题色）
- **P1.5**：JNI/GC 守卫（LocalFrame+daemon attach——与 $borrowClassHandle 同源）+ 类检索器（findClasses/dumpMethods+模板 UX）
- **P2**：isolated 进程 broker / IPC Socket 演进 / 反反调试（按需求触发）

### R2-3 编辑器升级（✅ 完成，未编译验证）+ P1-①
- **sora-editor 0.23.6 集成**（3 文件：gradle/libs.versions.toml、app/build.gradle.kts、ProjectDetailScreenV093.kt 新增 ScriptCodeEditor）：AndroidView 桥接 CodeEditor（View 体系零 Compose 依赖）；**API 实核纠错**：行号开关=**setDisplayLnPanel**（无 setLineNumberEnabled——t1 报告有误，double-check 拦截）；深浅色=SchemeDarcula/EditorColorScheme()；双向同步防环（rememberUpdatedState+值不同才 setText）；onRelease release()；**JS 高亮后置**（上游无语言包资源；建议后续 vendor MIT 许可 VSCode JavaScript.tmLanguage + language-textmate 0.23.6 + initGrammarRegistry 顺序，登记来源）；**无 GPL 文件**（零 Xed 拷贝）；sora-editor=LGPL v2.1（KDoc 已注）。
- **P1-① LSP.unhookAll（✅ 完成，未编译验证）**：HookRouter 解析 lsp.unhook_all + unhookAll()（HookHandle.unhook 框架 API+清表+日志）；TargetIpcServer.loadScript 热更前置自动 unhookAll（LSPlant 手柄跨脚本存活，旧拦截残留问题根除；GumJS Interceptor 由 unload 自动清理）；shim 暴露 LSP.unhookAll()。约 60 行 Kotlin+JS，无需 C++ 胶水（与用户 5 项输入裁决一致）。

### 编译批次 2+3 合并清单（未编译验证，一次编译含全部）
| 域 | 文件 | 内容 |
|---|---|---|
| UI 动画 | MiuixComponents.kt | 胶囊 pill 滑动指示器（t2） |
| 主题 | ThemeModeStore/MainActivity/SettingsScreenV093 | keyColor/paletteStyle + 色板 + effectiveMode Monet 合成（R2a） |
| 编辑器 | libs.versions.toml/app gradle/ProjectDetailScreenV093 | sora-editor 0.23.6（t3） |
| P1 | HookRouter/TargetIpcServer/GumJsBridge | unhookAll 卸载语义 |
