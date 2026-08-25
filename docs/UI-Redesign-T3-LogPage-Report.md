# 日志页重设计报告（t3，2026-08-25）

> 目标：用户"日志页太丑，找最优解"——参考截图 4/5：干净日志页 = 紧凑顶栏 + 返回 + 标题"日志" + 内容清晰扫读 + 空态。
> 功能零改动：历史回放 / 实时监听 / 清除 / prettyMessage / LogStore 交互全部保留。仅 `LogScreenV1.kt` 一个文件改动。

## ① 设计决策摘要

| 决策 | 变更 | 理由 |
|---|---|---|
| **紧凑玻璃顶栏** | TopAppBar（大标题 52+dp 扩张）→ **Miuix `SmallTopAppBar`**（CollapsedHeight 52dp：返回 + 标题"日志" 17sp + `subtitle`=应用名小字 + 右侧清除 `Delete` 图标 action）包在 `GlassTopAppBar` 玻璃内 | 参考截图紧凑顶栏；subtitle 参数即"应用名小字"，零自定义代码 |
| ⚠ 规格偏差（如实） | task 提到 "TopAppBar title=projectName+largeTitle=""" 用法 —— **源码实核不可行**：`TopAppBarLayout` 的 small 标题可见性仅由 `scrollBehavior`（collapsedFraction）驱动，无 scrollBehavior 时 `smallTitleAlpha` 恒 0（TopAppBar.kt L652/L668 实核）；`largeTitle=""` 时大标题区域无文本 → 该写法渲染为空栏。故改用 stock 紧凑组件 `SmallTopAppBar`（同文件 L173 实核存在） | 不写"存在但用错"的代码 |
| **指标精简** | 两卡 metrics（"脚本/日志"）→ **单行小字 "日志 N 条"**（12sp caption）；"脚本 1" 硬编码假值移除 | 参考截图无指标；假值移除是质量修正 |
| **扫读式列表** | 行 = **8dp 级别色点徽标（行首）** + 时间戳弱化列（76dp、11sp 次要）+ 消息列（prettyMessage、等宽 12sp、主 onSurface、weight 1 自动换行）；多行 payload 续行缩进 12dp 并**继承级别色**；行距 2dp 紧凑；保留长按复制（SelectionContainer） | t4 定稿：AS Logcat 惯例（级别→消息主线），色点非整行着色（保留可读性） |
| **级别色 map** | V=onSurfaceVariantSummary（⚠ 实核调整：Miuix Colors 无 onSurfaceVariant，用语义等价摘要色）/D=secondary/I=primary/W=tertiaryContainer（⚠ 无 tertiary，用第三色族 container）/E=error/F=error+消息加粗；**全 token 无硬编码** | t4 定稿（AS 惯例 → Miuix token） |
| **自动滚动** | `pinnedToBottom` 跟随态：新日志 `animateScrollToItem(末项)`；**用户拖动开始立即暂停**（`interactionSource.collectIsDraggedAsState`）；每次滚动停止时按位置重定针（底部区→恢复；离开→暂停）；**「↓ 回到底部」**胶囊（secondaryContainer token）离底时弹出，点击恢复追尾 | 体验最佳 + 不打断用户读旧日志；scroll lock 按钮为必须项 |
| **清除** | 卡内"清除"文本按钮 → 顶栏 action `MiuixIcons.Delete` 图标（onSurfaceVariantSummary 色）；**真清除**：显示层清理 + `LogStore.clear` 删除持久文件（`files/logs/<pkg>/`），并记录清空水位丢弃写队列中清除前入队行（重进不回潮）。附带保留策略：单包当日文件 >5000 行时裁剪至尾部一半（临时文件+原子 rename） | t8 修正（t4 复核后拍板）：原"清显示不清文件"导致清除后重进回潮，改为真清除 |
| **空态** | 两态居中：`cleared ? "已清除" : "等待目标进程连接…"`（13sp 次要色；过滤无结果态本期无过滤→单态，列入缺口） | t4 定稿空态三态（本期单态） |
| **live 标识** | 指标行右侧「● 实时」：8dp primary 色点 + 12sp primary 小字；3s 心跳窗口（收上行日志后 3s 内显示） | t4 定稿 |
| **色彩（用户强制）** | **绿黑终端配色彻底废弃**（#171717/#74E391 任何形式均不保留，含降级/最小调整选项——该选项已取消）。日志视觉 = Miuix 语义 token 纯体系：消息主文本 **onSurface**、时间戳/次要 **onSurfaceVariantSummary**、背景 **MiuixPageBackground()**（无独立深色卡，深色模式自动由 token 变暗）；级别色差仅用 token 族，不允许硬编码 hex | 用户原话"绿色加黑色的日志页搭配太丑了，重新设计"；终端感配色属过时审美 |
| **时间戳** | LogStore 行前缀 `[yyyy-MM-dd HH:mm:ss.SSS]`（LogStore.kt L96 实核）→ 解析后仅显示 `HH:mm:ss.SSS`（单日视图日期段无信息量，列宽稳定）；**实时行无前缀**（IpcManager logReceiverStub 直发原始 message）→ 全宽显示，不伪造时间戳 | 如实呈现 |

## ② diff（单文件）

`app/src/main/kotlin/com/bail/lspfrifa/ui/screen/LogScreenV1.kt`（t3+t4 定稿，单文件）
- 顶栏：`TopAppBar` → `GlassTopAppBar { SmallTopAppBar(title="日志", subtitle=projectName, color=Transparent, navigationIcon=Back, actions={IconButton(Delete)}) }`
- metrics 两卡移除 → 单行 caption + 「● 实时」标识；卡内"实时日志/清除"行移除（清除入顶栏）
- 日志行 → LogLineRow（8dp 级别色点 + 时间戳弱化列 + 消息列，续行缩进 12dp 继承级别色）；`toLogLine()`/`parseLevel()`（前缀剥离 + level 映射 + prettyMessage）
- 自动滚动：`rememberLazyListState` + `collectIsDraggedAsState` + `snapshotFlow(isScrollInProgress)` 重定针 + `LaunchedEffect(logs.size)` 跟随 + 「回到底部」胶囊
- **深色卡 Card 移除**（列表直排页面背景）；#171717/#74E391/#8A8A8A 常量全部删除 → onSurface / onSurfaceVariantSummary / MiuixPageBackground() token 体系
- 清除=真清除（显示层 + LogStore.clear 删除持久文件 + 清空水位丢弃在途队列行，重进不回潮）；空态两态（等待连接/已清除）
- 保留策略：单包当日文件 >5000 行 → 裁剪至尾部最近一半（临时文件 + 原子 rename 替换）
- 未动：DisposableEffect 监听/历史回放/有界 500/prettyMessage 逻辑

## ③ 三查/双清单（实核证据）

### 新用 API（全部 sources 实核）
| API | 实核签名/位置 | 调用点 |
|---|---|---|
| `SmallTopAppBar` | `fun SmallTopAppBar(title: String, modifier, color=surface, titleColor, subtitle="", subtitleColor, navigationIcon, actions: RowScope.()->Unit, scrollBehavior, defaultWindowInsetsPadding=true, titlePadding, navigationIconPadding, actionIconPadding, bottomContent)`（miuix-ui-sources TopAppBar.kt L173） | 顶栏 ✔ 全具名参数匹配 |
| `SmallTopAppBar` subtitle 渲染 | 标题 title3(17sp) Medium 居中，subtitle body2 居中居下（L1000-1035 实核）；布局高 = max(52dp, subtitle 底部) | subtitle=projectName ✔ |
| `MiuixIcons.Delete` | `val MiuixIcons.Delete: ImageVector`（icons-sources extended/Delete.kt L15） | 顶栏 action ✔ |
| `collectIsDraggedAsState` | `@Composable fun InteractionSource.collectIsDraggedAsState(): State<Boolean>` —— **无 Experimental 注解**（foundation 1.12.0-rc01 DragInteraction.kt L78 实核，与 CMP 1.12.0-rc01 同线） | `listState.interactionSource.collectIsDraggedAsState()` ✔ |
| `LazyListState.interactionSource` | `val interactionSource: InteractionSource`（LazyListState.kt L261） | ✔ |
| `animateScrollToItem` | `suspend fun animateScrollToItem(index, scrollOffset=0)`（L589；越界自动钳制） | 新日志跟随 ✔ |
| `isScrollInProgress` / `layoutInfo` | LazyListState 标准成员 | 重定针 ✔ |
| LogStore 行前缀 | `line = "[${timeFormatter.format(...)}] $message"`，`yyyy-MM-dd HH:mm:ss.SSS`（LogStore.kt L59-60/L96） | LogLinePrefix 正则同格式 ✔ |

### 防坑清单
- ✅ 全用 `top.yukonga.miuix.kmp.basic.Text`（非 material Text）
- ✅ 次要色用 `MiuixTheme.colorScheme.onSurfaceVariantSummary`（非不存在的 onSurfaceVariant）
- ✅ 米 uix `IconButton` + MiuixIcons（非 material Icons）
- ✅ Regex `matchEntire` 全匹配（前缀 + 整行），组索引 groupValues[2]/[3] 与正则分组结构一致
- ✅ LazyColumn `items(logs)`（SnapshotStateList）无 key（日志可重复，不做伪 key）
- ✅ 无硬编码色散落（仅 3 个集中常量，且为既有终端配色迁移）

## ④ 风险
1. 未编译（DSH 无 JDK）——签名逐参数已对照；重点编译点：SmallTopAppBar 调用、collectIsDraggedAsState 导入路径。
2. 自动滚动与用户拖动竞争：已用 dragged 状态做"先暂停"，程序滚动由 isScrollInProgress 重定针闭环；真机手感需验证（若跟手问题：`scrollToItem` 替代 `animateScrollToItem`）。
3. subtitle（应用名）默认无 maxLines，超长名会在顶栏折行——项目名通常为应用标签，风险低；若需限制后续加 `bottomContent`/自绘小栏。
4. 时间戳列 84dp：12sp 等宽 11 字符 ≈ 79dp；字体度量差异时仍留 5dp 余量。
