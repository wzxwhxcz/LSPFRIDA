# UI 参考对比与对齐清单（2026-08-24，对照用户 5 张参考截图）

## 逐图对比（参考 vs 我们现状）

| # | 参考截图 | 关键特征 | 我们现状 | 差距 |
|---|---|---|---|---|
| 1 | 设置页 | 大标题 TopAppBar；分组大圆角卡片（**icon 前置 + 标题/副标题 + chevron ›**）；空态干净 | 设置页：主题卡/框架状态卡（部分无 icon/chevron，条目风格不统一） | **卡片条目结构**：需 icon 前置 + › 尾缀 + 标题/副标排版 |
| 2 | 详情页 | 顶部（返回 + 应用名 + 包名）；**分段 tab（插件/分析）**——圆角分段控件（选中白/半透明）；插件项卡片（**icon + 名称 + 版本/作者 + 蓝色 Switch**） | 详情页：TabRow（Miuix）+ 插件面板（GumJS 开关 + 脚本编辑） | 分段 tab 样式 + 插件项卡片化（icon/版本/作者） |
| 3 | **代码编辑器** | **Lua 语法高亮 + 行号 + 底部符号工具条**（log→ 图标 + ( ) [ ] { } < > 等符号键 + 辅助行）— 移动端编辑体验标杆 | sora-editor 基础集成（无高亮无符号栏） | **需要：语法高亮（JS grammar）+ 底部符号栏（SymbolInputBar 类）** |
| 4/5 | 日志页 | 独立"日志"页：文件列表/日志条目（返回 + 标题 + 空态"暂无日志文件"，条目行 icon+名称） | 详情页"分析"tab 内嵌日志流 | 日志**拆分独立页**（列表+空态范式） |

## 对齐优先级（建议）
1. **P0 编辑器体验**（截图 3 直接命中最痛点）：JS 语法高亮（MIT VSCode grammar + language-textmate 0.23.6 + desugaring + initGrammarRegistry 顺序）+ **底部符号栏**（实核 sora SymbolInputBar 类；无则自实现符号行，参考截图布局：log→ 图标 + （）[]{}<>）
2. **P1 设置页卡片化**（截图 1）：统一卡片条目=icon 前置 + 标题/副标 + › 尾缀（MiuixIcons Chevron/Arrow 系 + BasicComponent startAction/endActions）
3. **P1 详情页对齐**（截图 2）：分段 tab（圆角分段控件）+ 插件项卡片化（icon/名称/版本/作者/Switch）
4. **P2 日志页拆分**（截图 4/5）：独立日志列表页（空态/条目），详情页分析 tab 保留实时流（或按原版拆文件列表）

## 约束
- 全部用现有 Miuix 组件与 token（不引入新库/新依赖除非符号栏需求）；API 实核后落地（本项目纪律）
- 图标从 miuix-icons（已依赖）+ MiuixIcons 扩展集选取（先实核存在性）

## ⚠️ 原则澄清（用户 2026-08-24 强调）
1. **参考截图目标应用 = Material 3 语境**（FAB/Tab 分段控件/卡片 chevron/日志文件页均为 M3 组件语汇）；本项目 = **Miuix（HyperOS）栈**——**只对齐"信息结构与交互意图"，不移植 M3 组件样式**。
2. **不臆造功能**：Miuix 没有的组件（分段控件/特定图标/供应商配置等）——如实标注"能力缺口/不做"或最小自实现（仅当用户确认）；所有新 API/图标以 ui-sources.jar 实核为准，无对应物即放弃该项。
3. 参考可借鉴项（意图级）：设置=分组条目卡片、详情页=插件开关列表、tab=二选一、编辑器=高亮+符号栏（后者为独立已定项）、日志=列表/空态范式。

## 实核纪律（三查，2026-08-24 用户制度升级）
任一 Miuix/M3/sora 组件或图标写入代码前必须三查：
1. **存在性**：在当前版本 sources jar 中存在（记录 jar 路径+文件+行号）
2. **签名一致性**：实际源码签名与调用点逐参数对照一致（本项目三次失败案例存档：
   - `XposedInterface.Chain.getArgs()` 返回 `List<Object>` 非 `Object[]`（HookRouter 编译错误）
   - `RadioButtonPreference.titleColor/summaryColor` 在 0.9.4-rc01 已删除（迁移到 `colors = RadioButtonPreferenceDefaults.radioButtonPreferenceColors(...)`）
   - sora 0.23.6 行号开关实为 `setDisplayLnPanel`（无 `setLineNumberEnabled`）
3. **调用点对照**：实际写出的调用（参数名/顺序/类型）与签名逐项匹配；无"存在但用错"。
【交付清单】每项沿用必须附：实际源码签名 + 调用点代码，两者对照（不只路径行号）。
【M3 混用】用户确认可 M3+Miuix：工程未直接依赖 material3（compose=org.jetbrains.compose 1.12.0-rc01 系）→ 需 M3 组件时列入缺口清单并建议"组件+需加依赖（版本对齐）"，由 captain 拍板统一加，成员不擅自加依赖。
