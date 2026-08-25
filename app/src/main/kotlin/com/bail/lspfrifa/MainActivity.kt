package com.bail.lspfrifa

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bail.lspfrifa.data.AddedProject
import com.bail.lspfrifa.data.AppsUiState
import com.bail.lspfrifa.data.AppsViewModel
import com.bail.lspfrifa.data.ProjectViewModel
import com.bail.lspfrifa.data.ThemeModeStore
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ui.component.FloatingMiuixNavigationBar
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.screen.DashboardScreenV093
import com.bail.lspfrifa.ui.screen.LogScreenV1
import com.bail.lspfrifa.ui.screen.ProjectDetailScreenV093
import com.bail.lspfrifa.ui.screen.ProjectScreenV2
import com.bail.lspfrifa.ui.screen.ScriptEditorScreenV1
import com.bail.lspfrifa.ui.screen.SelectProjectScreenV2
import com.bail.lspfrifa.ui.screen.SettingsScreenV093
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

class MainActivity : ComponentActivity() {

    // P1：在线进程数作为 Compose 状态维护；onResume 时从 IpcManager 刷新。
    // activeTargets 是宿主进程内存注册表，进程重启/目标进程变化后需要主动刷新 UI，
    // 否则首页"在线进程"停留在旧值。
    private val onlineProcessCount = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        // R1.2：框架级（getRunningTargets）+ 引擎级（注册表）取较大者，宿主不在时仍反映注入状态
        onlineProcessCount.intValue = IpcManager.onlineCount()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸模式：必须在 super.onCreate() 或 setContent 之前调用。
        // 显式禁用三键导航的系统白 scrim（navigationBar），让底部手势区透出应用背景，
        // 避免底栏下方白条；statusBar 用 auto（透明+按主题图标）。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                detectDarkMode = { true }, // 透明导航栏，不做暗色scrim
            ),
        )
        // 三键导航白条修复：enableEdgeToEdge 只设置导航栏颜色/样式，不关闭系统的对比度 scrim
        // （Android 10–14 默认对导航栏叠加对比度 scrim），此处显式关闭（仅 API 29+ 存在该属性）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        setContent {
            // t4/R2a：主题模式+种子色+调色板风格由 ThemeModeStore 状态流驱动；任一变化 →
            // collectAsState 触发重组 → remember(mode, keyColor, paletteStyle) 重建 ThemeController
            // → MiuixTheme 全局即时生效（AppRoot 在 MiuixTheme 内）。
            val mode by ThemeModeStore.mode.collectAsState()
            val keyColorHex by ThemeModeStore.keyColor.collectAsState()
            val paletteStyle by ThemeModeStore.paletteStyle.collectAsState()
            val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
            val keyColor = remember(keyColorHex) {
                keyColorHex?.let {
                    runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                }
            }
            // Monet 跟随壁纸（用户明确引入 M3 的目的）：M3 dynamicColorScheme 读取系统壁纸动态色
            // 的主色作为种子；开启时优先于手选色板。⚠ dynamicLightColorScheme 是 @Composable——
            // 不能在 runCatching/remember 等普通 lambda 中调用（已踩）；API<31 无动态壁纸色→null。
            val context = androidx.compose.ui.platform.LocalContext.current
            val dynamicSeed: androidx.compose.ui.graphics.Color? =
                if (wallpaperMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    androidx.compose.material3.dynamicLightColorScheme(context).primary
                } else null
            val effectiveSeed = dynamicSeed ?: keyColor
            // R2a 关键（Miuix ThemeController 源码实读）：lightColors/darkColors 只在 System/Light/Dark
            // 模式下使用，keyColor **仅 Monet* 模式生效**——无种子色用原三态；有种子色时合成对应
            // Monet 模式（跟随系统深浅/强制浅/强制深），否则"选了色但模式不变=色不变"。
            val effectiveMode = if (effectiveSeed != null) {
                when (mode) {
                    ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
                    ColorSchemeMode.Dark -> ColorSchemeMode.MonetDark
                    else -> ColorSchemeMode.MonetSystem
                }
            } else {
                mode
            }
            val controller = remember(effectiveMode, effectiveSeed, paletteStyle) {
                ThemeController(
                    colorSchemeMode = effectiveMode,
                    keyColor = effectiveSeed,
                    colorSpec = ThemeColorSpec.Spec2021,
                    paletteStyle = paletteStyle,
                )
            }
            MiuixTheme(controller = controller) { AppRoot() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 白条回归防护（reviewer 从 androidx.activity 源码确认）：enableEdgeToEdge 在 API29+
        // 内部会按样式重设 isNavigationBarContrastEnforced，配置变化（旋转/暗色切换等）后
        // 可能恢复为 true；此处重新关闭，保证三键导航下不出现白条。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Composable
    private fun AppRoot(
        appsViewModel: AppsViewModel = viewModel(),
        projectViewModel: ProjectViewModel = viewModel(),
    ) {
        val navController = rememberNavController()
        val framework by FrameworkState.status.collectAsState()

        // 根容器不铺背景：背景由各页 Scaffold 的默认 surface 统一负责；
        // 此前叠加 background 与 surface 不同色，被透出时形成色差灰带
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            NavHost(navController = navController, startDestination = "main") {
            composable("main") {
                MainScaffold(
                    frameworkActive = framework.active,
                    apiVersion = framework.apiVersion,
                    frameworkName = framework.frameworkName,
                    frameworkVersion = framework.frameworkVersion,
                    appsViewModel = appsViewModel,
                    projectViewModel = projectViewModel,
                    onNavigateToSelect = { navController.navigate("select_project") },
                    onOpenProject = { project ->
                        // 项目详情：路由传参（packageName + appName）
                        val pkg = project.packageName
                        val name = project.appName
                        navController.navigate("project_detail/${android.net.Uri.encode(pkg)}?name=${android.net.Uri.encode(name)}")
                    },
                )
            }
            composable("select_project") {
                LaunchedEffect(Unit) { appsViewModel.load() }
                val appsState by appsViewModel.uiState.collectAsState()
                val projects by projectViewModel.addedProjects.collectAsState()
                SelectProjectScreenV2(
                    appsState = appsState,
                    addedPackages = projects.map { it.packageName }.toSet(),
                    onRetry = { appsViewModel.load(force = true) },
                    onAddProject = { app ->
                        projectViewModel.addProject(app)
                        // 同步标记为注入目标，保持与旧控制流一致
                        IpcManager.enableTarget(app.packageName)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "project_detail/{pkg}?name={name}",
                arguments = listOf(
                    androidx.navigation.navArgument("pkg") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val pkg = entry.arguments?.getString("pkg").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                ProjectDetailScreenV093(
                    projectName = name.ifBlank { pkg },
                    packageName = pkg,
                    onBack = { navController.popBackStack() },
                    // t6 契约：插件视图两入口 → 独立编辑页/日志页
                    onOpenEditor = {
                        navController.navigate("script_editor/${android.net.Uri.encode(pkg)}?name=${android.net.Uri.encode(name)}")
                    },
                    onOpenLogs = {
                        navController.navigate("logs/${android.net.Uri.encode(pkg)}?name=${android.net.Uri.encode(name)}")
                    },
                )
            }
            composable(
                route = "script_editor/{pkg}?name={name}",
                arguments = listOf(
                    androidx.navigation.navArgument("pkg") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val pkg = entry.arguments?.getString("pkg").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                ScriptEditorScreenV1(
                    projectName = name.ifBlank { pkg },
                    packageName = pkg,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "logs/{pkg}?name={name}",
                arguments = listOf(
                    androidx.navigation.navArgument("pkg") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("name") {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val pkg = entry.arguments?.getString("pkg").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                LogScreenV1(
                    projectName = name.ifBlank { pkg },
                    packageName = pkg,
                    onBack = { navController.popBackStack() },
                )
            }
        }
        }
    }

    /** 主壳：底部导航 + 三页（首页 / 项目 / 设置）。 */
    @Composable
    private fun MainScaffold(
        frameworkActive: Boolean,
        apiVersion: Int,
        frameworkName: String,
        frameworkVersion: String,
        appsViewModel: AppsViewModel,
        projectViewModel: ProjectViewModel,
        onNavigateToSelect: () -> Unit,
        onOpenProject: (AddedProject) -> Unit,
    ) {
        var selectedTab by remember { mutableIntStateOf(0) }
        val projects by projectViewModel.addedProjects.collectAsState()
        val navigationItems = remember {
            listOf(
                NavigationItem("首页", MiuixIcons.Home),
                NavigationItem("项目", MiuixIcons.ListView),
                NavigationItem("设置", MiuixIcons.Settings)
            )
        }

        // 底部三页容器：内容区由 when(selectedTab) 改为 HorizontalPager（页面间横向滑动切换）。
        // 底栏组件与每个 tab item 的样式/颜色/动画参数完全复用，未做任何改动。
        val pagerState = rememberPagerState(pageCount = { 3 })
        val scope = rememberCoroutineScope()

        // 滑动 → 选中项跟随：selectedTab 只由 pager 状态回写；点击侧不直写，
        // 避免动画过程中 currentPage 经过中间页时把底栏高亮打回造成闪烁。
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { page ->
                selectedTab = page
            }
        }

        // t2：底栏玻璃拟态捕获层（含全部页面内容但**不含底栏自身**，避免模糊回环）。
        // 悬浮底栏作为兄弟节点绘制在其上，drawBackdrop 按坐标采样我层内容做模糊。
        val backdrop = rememberLayerBackdrop()

        // 外层 Scaffold 容器色 = background（官方浅色 #FFFFFF 白；Miuix 默认 surface 为 #F7F7F7
        // 灰白，目标应用为白底，故显式取 background）。与各内容页 Scaffold 一致，
        // 消除"内容页底部 ↔ 底栏上方"的灰面（截图灰块根因）。
        // 布局：页面全屏 + 底栏【悬浮覆盖】（与目标应用一致：BottomBar 悬浮于内容之上，
        // 不占 Scaffold 槽位——槽位会推高内容视口并留出大片空白，即截图"胶囊上方多余块"
        // 与"内容被切"的根因）。insets 全部交给各页面 Scaffold / 底栏自身处理。
        Scaffold(
            containerColor = MiuixPageBackground(),
            contentWindowInsets = WindowInsets(0),
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixPageBackground()), // 治"黑一下"：Pager 滑动/切换帧之间透出的窗口背景为黑，补白底
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop), // 捕获层：Pager 三页内容（不含底栏）
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 3, // t1：目标应用实参=3，三页全保活（防切页状态丢失/重组合卡顿）
                        // t1：pageSpacing=0.dp（框架默认即 0，无需显式传）；flingBehavior 走默认（目标应用=默认）
                    ) { page ->
                        when (page) {
                        0 -> DashboardScreenV093(
                            isXposedActive = frameworkActive,
                            apiVersion = apiVersion,
                            frameworkName = frameworkName,
                            frameworkVersion = frameworkVersion,
                            activeProjectCount = projects.size,
                            activeProcessCount = onlineProcessCount.intValue,
                            onOpenSelector = { scope.launch { pagerState.animateScrollToPage(1) } }
                        )

                        1 -> ProjectScreenV2(
                            projects = projects,
                            onToggle = { pkg, enabled -> projectViewModel.setEnabled(pkg, enabled) },
                            onNavigateToSelect = onNavigateToSelect,
                            onOpenProject = onOpenProject,
                        )

                        else -> SettingsScreenV093(
                            frameworkName = frameworkName,
                            frameworkVersion = frameworkVersion,
                            apiVersion = apiVersion,
                            active = frameworkActive
                        )
                    }
                    }
                }

                // 悬浮底栏（覆盖式）：Align BottomCenter，自身含 navigationBarsPadding + 12dp 偏移；
                // 内容滚动底部由各页面 contentPadding 预留（128dp），保证最后一项显示在胶囊上方。
                FloatingMiuixNavigationBar(
                    items = navigationItems,
                    selected = selectedTab,
                    onClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
