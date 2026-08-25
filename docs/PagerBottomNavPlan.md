# LSPFRIFA 底栏 HorizontalPager 化改造方案

> 依据：LSPilot 1.0.9 反编译还原结论（底栏自绘 + 页面分页滑动）；约束：不换底栏视觉实现、不引入 miuix-blur、minSdk 26 不变。
> 基线版本：Miuix 0.9.4-rc01（top.yukonga.miuix.kmp）、Compose Multiplatform `org.jetbrains.compose.*:1.12.0-rc01`（`gradle/libs.versions.toml`；app/build.gradle.kts 注释中的"1.11.1"为陈旧注释）、AGP 9.3.1 / Kotlin 2.4.10、minSdk 26 / targetSdk 34 / compileSdk 37。

---

## 1. 现状梳理（已通读源码）

### 1.1 页面结构与切换（MainActivity.kt）

- `AppRoot`：单一 `NavHost`，三条路由 `main` / `select_project` / `project_detail/{pkg}?name={name}`；`main` → `MainScaffold`。
- `MainScaffold`（当前 156–209 行）：
  - `var selectedTab by remember { mutableIntStateOf(0) }`（第 156 行）——**当前 tab 的唯一状态源**。
  - `navigationItems`：`NavigationItem("首页", MiuixIcons.Home)` / `("项目", ListView)` / `("设置", Settings)`。
  - 外层 **Miuix** `Scaffold(bottomBar = { FloatingMiuixNavigationBar(items, selected = selectedTab, onClick = { selectedTab = it }) })`；content 内 `Box(Modifier.fillMaxSize().padding(padding))` + `when (selectedTab) { 0 → DashboardScreenV093(...), 1 → ProjectScreenV2(...), else → SettingsScreenV093(...) }`。
- **跨页联动点**：Dashboard 的 `onOpenSelector = { selectedTab = 1 }`（首页"项目分析"卡点击 → 切到项目页，第 190 行）。

### 1.2 底栏（ui/component/MiuixComponents.kt，本次不改）

- `FloatingMiuixNavigationBar(items, selected, onClick, modifier)`：`Box(fillMaxWidth + navigationBarsPadding)` > `Row(SpaceEvenly, padding(vertical 8dp, horizontal 16dp))` > 每项 `FloatingNavItem`。
- `FloatingNavItem`：`Column(.clip(RoundedCornerShape(22.dp)).background(pillColor).clickable().padding(horizontal 18dp, vertical 7dp))`；Icon 24dp + Spacer 3dp + Text 11sp（选中 Medium/非选中 Normal）；选中药丸 `secondaryContainer`、前景 `primary`，`animateColorAsState(tween(300))`。
- 结论：底栏组件签名 `(items, selected: Int, onClick: (Int) -> Unit)` 与 Pager 化完全兼容，**零改动**。

### 1.3 三个页面入口（签名均兼容，不改）

| 页面 | 签名 | 内容外壳 |
|---|---|---|
| `DashboardScreenV093` | `(isXposedActive, apiVersion, frameworkName, frameworkVersion, activeProjectCount, activeProcessCount, onOpenSelector, ...默认值)` | 内部 `Scaffold(TopAppBar("LSPFRIFA"))` + LazyColumn；含 `showDocs` Dialog 状态 |
| `ProjectScreenV2` | `(projects, onToggle, onNavigateToSelect, onOpenProject, modifier = Modifier)` | 内部 `Scaffold(TopAppBar("项目"))` + LazyColumn/FAB |
| `SettingsScreenV093` | `(frameworkName, frameworkVersion, apiVersion, active)` | 内部 `Scaffold(TopAppBar("设置"))` + LazyColumn |

### 1.4 三键导航白条根因（对应 setNavigationBarContrastEnforced）

`MainActivity.onCreate` 已用 `enableEdgeToEdge(..., navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))`——它只设置导航栏颜色/图标样式，**不设置** `Window.setNavigationBarContrastEnforced(false)`。Android 10–14 系统默认对导航栏做对比度 scrim，三键导航（浅色背景+透明导航栏）时叠加白色半透明条，即"白条"。`contrastEnforced = false` 是其标准补救。

---

## 2. 改造方案（改动集中在 MainActivity.kt）

### 2.1 `onCreate`：加 `setNavigationBarContrastEnforced(false)`

紧跟 `enableEdgeToEdge(...)` 之后（`super.onCreate` 前后均可，setContent 之前）：

```kotlin
// 三键导航：系统对透明导航栏默认叠加对比度 scrim（Android 10–14），
// 导致底栏下方白条；显式关闭（API 35 + targetSdk 35 才被系统强制忽略，本项目 targetSdk 34 不受影响）。
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    window.navigationBarContrastEnforced = false
}
```

- 新增 import：`android.os.Build`（MainActivity 目前无此 import）。
- minSdk 26：API ≤ 28 无此属性，必须 `SDK_INT >= Q` 守卫（26–28 本身无对比度 scrim，行为不受影响）。

### 2.2 `MainScaffold`：内容区 `when(selectedTab)` → `HorizontalPager`

```kotlin
val pagerState = rememberPagerState(pageCount = { 3 })   // 注意：lambda 形式（见 §4 不确定点 1）
val scope = rememberCoroutineScope()
var selectedTab by remember { mutableIntStateOf(0) }

// 双向联动（滑动方向）：页面推进 → 底栏高亮跟随
LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage }.collect { page ->
        selectedTab = page
    }
}

Scaffold(
    bottomBar = {
        FloatingMiuixNavigationBar(
            items = navigationItems,
            selected = selectedTab,
            onClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },  // 点击 → 动画翻页
        )
    },
) { padding ->
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().padding(padding),
        beyondViewportPageCount = 2,   // 3 页全量保活：见 §3 风险 3
    ) { page ->
        when (page) {
            0 -> DashboardScreenV093(
                /* 原参数不变 */,
                onOpenSelector = { scope.launch { pagerState.animateScrollToPage(1) } },  // 首页"项目分析"卡 → 翻到项目页
            )
            1 -> ProjectScreenV2(/* 原参数不变 */)
            else -> SettingsScreenV093(/* 原参数不变 */)
        }
    }
}
```

新增 import（MainActivity.kt）：
- `androidx.compose.foundation.pager.HorizontalPager`
- `androidx.compose.foundation.pager.rememberPagerState`
- `androidx.compose.runtime.rememberCoroutineScope`
- `androidx.compose.runtime.snapshotFlow`
- `kotlinx.coroutines.launch`
- `android.os.Build`（2.1 用）

### 2.3 设计取舍（关键）

1. **`selectedTab` 不再是直接写入源**：点击底栏**只** `animateScrollToPage`，由 `snapshotFlow { currentPage }` 回写 `selectedTab`。原因：若点击时直接写 `selectedTab = index`，动画过程中 `currentPage` 会依次经过中间页（0→1→2 时经过 1），把 pill 打回来造成闪烁；只由 pager 状态驱动可保证 highlight 与真实页面一致且单调不闪烁。
2. **高亮时机**：`currentPage` 在拖动越过页面中线时更新 → 滑动过程中高亮实时跟随（Miuix 设置类应用同款手感）。若产品上要"松手后才高亮"，把 `pagerState.currentPage` 换成 `pagerState.settledPage`（foundation 1.7+，本基线下存在）。
3. **保活策略 `beyondViewportPageCount = 2`**：默认 0 时离屏页会被销毁——ProjectScreenV2 的 LazyColumn 滚动位置、Dashboard 的 `showDocs` Dialog 状态都会丢失（现状切 tab 时其实也丢，因为 `when` 直接销毁；Pager 化后两页会同时组合）。3 个页面均为轻量 LazyColumn，全量组合开销可忽略，换来"切回记住滚动位置 + 弹窗状态不丢"。备选：`= 1` 只保相邻页（跨页跳转仍丢最远页状态）；或把各页 LazyListState 上提到 MainScaffold 传入（侵入三个屏幕签名，不推荐）。
4. **动画曲线**：首版用默认 fling（`PagerDefaults.flingBehavior` 默认 spring）。目标应用曲线数值由 rev-eng 任务产出后，再通过 `HorizontalPager(flingBehavior = ...)` 自定义（见 §4 不确定点 3）。`animateScrollToPage(page)` 无 animationSpec 参数，**曲线还原的唯一入口是 flingBehavior**。

---

## 3. 改动文件清单与 diff 摘要

| # | 文件 | diff 摘要 |
|---|---|---|
| 1 | `app/src/main/kotlin/com/bail/lspfrifa/MainActivity.kt` | ① onCreate 加 `window.navigationBarContrastEnforced = false`（含 SDK 守卫）；② MainScaffold：新增 `pagerState`/`scope`/`snapshotFlow` 同步，`when(selectedTab)` → `HorizontalPager`；③ bottomBar `onClick = { scope.launch { pagerState.animateScrollToPage(it) } }`；④ Dashboard `onOpenSelector = { scope.launch { pagerState.animateScrollToPage(1) } }`；⑤ 新增 6 个 import |
| 2 | `app/src/main/kotlin/com/bail/lspfrifa/ui/component/MiuixComponents.kt` | **不改**（底栏视觉/签名不动） |
| 3 | `DashboardScreenV093.kt` / `ProjectScreenV2.kt` / `SettingsScreenV093.kt` | **不改**（签名与实现均兼容；仅调用点传参方式不变） |

预计净改动：MainActivity.kt 约 +35 / −8 行。

---

## 4. 不确定 API 标注（由本项目版本推断，编译为准）

| 不确定点 | 结论/置信度 |
|---|---|
| 1. `rememberPagerState(pageCount = { 3 })` 参数形式 | androidx foundation 1.7 / CMP 1.7 起 pageCount 为 **lambda `() -> Int`**（Int 形式已弃用）。1.12.0-rc01 必为 lambda 形式。**置信度：高** |
| 2. `androidx.compose.foundation.pager.*` 在 JetBrains CMP foundation 中可用 | CMP 的 Android 构件即 androidx foundation 同源类（pager 自 foundation 1.4 稳定，自 CMP 1.5 起提供）。**置信度：高** |
| 3. `PagerDefaults.flingBehavior(state, pagerSnapDistance, flingDecay, snapAnimationSpec, snapPosition)` 签名 | `snapPosition` 参数 1.7+ 提供；1.12 中默认 `SnapPosition.Center`。**置信度：中高**；曲线还原阶段编译验证 |
| 4. `PagerState.settledPage` | foundation 1.7+，**置信度：高**（仅备选方案用） |
| 5. `beyondViewportPageCount` 构造参数 | 自 foundation 1.5 稳定存在，**置信度：高** |
| 6. `window.navigationBarContrastEnforced` | `Window` 属性，API 29+（**置信度：高**；minSdk 26 需守卫；MIUI/HyperOS 等 ROM 是否尊重该 flag 需真机三键导航实测——**此为唯一需真机验证项**） |
| 7. `snapshotFlow { pagerState.currentPage }` | 稳定 API，**置信度：高** |

---

## 5. 风险点

1. **横向手势冲突**：三个页面内部无横向滚动容器（全为 LazyColumn/Column/Card），HorizontalPager 手势独占无冲突；未来页面内加横向控件需评估 `userScrollEnabled`。
2. **嵌套 Scaffold**：外层 Miuix Scaffold（无 topBar）+ 内层页面 Scaffold（自带 TopAppBar）——与现状完全一致；Pager 页面若未撑满视口（理论可能），在 page 内容外包 `Box(Modifier.fillMaxSize())` 兜底。
3. **页面保活（beyondViewportPageCount=2）**：三页常驻组合，内存/性能开销可忽略；副作用是切换时偶发同时组合（跨页动画期间两页同时参与绘制，属预期）。
4. **行为变化（属改进）**：原来切 tab 会销毁重建页面（列表滚动位置丢失、Dialog 关闭），Pager 化后保留——需在验收时确认这是期望行为（目标应用 Miuix 设置类分页即保留）。
5. **回退点**：`animateScrollToPage` 抛 `CancellationException` 属正常并发取消（scope.launch 内每次点击取消上次动画），无需捕获；不会崩。
6. **返回键/导航栈**：底部 tab 切换不在 NavHost 栈内，返回键行为不变；只有 `select_project`/`project_detail` 路由在栈内。
7. **首帧**：`rememberPagerState(initialPage = 0)` 与现状默认 tab 0 一致；不做「记住上次 tab」（如需要另起任务，初稿不加）。

---

## 6. 建议实施顺序

1. 改 MainActivity §2.2（Pager 化），编译验证 pager API（§4 不确定点 1/2 现场确认）。
2. 加 §2.1（contrastEnforced），真机三键导航验证白条消失（唯一真机验证项）。
3. 等 rev-eng 产出底栏数值 + 动画曲线 → 用 `flingBehavior` 还原曲线（不确定点 3）。
4. reviewer 评审后合入。
