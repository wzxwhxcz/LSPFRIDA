package com.bail.lspfrifa.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Recording
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bail.lspfrifa.R
import com.bail.lspfrifa.data.HookModeStore
import com.bail.lspfrifa.data.InjectHintStore
import com.bail.lspfrifa.data.ThemeModeStore
import com.bail.lspfrifa.ipc.IpcManager
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens

/** SettingPagerMiuix / SettingsMiuix 的结构复刻：设置页本身是分组偏好项，不是运行日志页。 */
@Composable
fun SettingsScreenV093(
    frameworkName: String,
    frameworkVersion: String,
    apiVersion: Int,
    active: Boolean,
) {
    val frameworkSummary = if (active) {
        listOf(frameworkName, frameworkVersion)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { stringResource(R.string.settings_framework_connected) }
    } else {
        stringResource(R.string.settings_framework_disconnected)
    }
    // t4：主题模式单一事实源 ThemeModeStore.mode；选中项由 RadioButtonPreference 标题主色高亮。
    val themeMode by ThemeModeStore.mode.collectAsState()
    // R2a：主题种子色（预设色板；null=默认）
    val keyColorHex by ThemeModeStore.keyColor.collectAsState()
    // R1.3：热重载结果回显（summary 行）
    var hotReloadSummary by remember { mutableStateOf<String?>(null) }
    val hotReloadPending = stringResource(R.string.settings_hot_reload_pending)

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                largeTitle = stringResource(R.string.settings_title),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 128.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    ThemeModeOption(
                        stringResource(R.string.settings_theme_follow_system),
                        stringResource(R.string.settings_theme_follow_system_summary),
                        ColorSchemeMode.System,
                        themeMode,
                    )
                    ThemeModeOption(
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_light_summary),
                        ColorSchemeMode.Light,
                        themeMode,
                    )
                    ThemeModeOption(
                        stringResource(R.string.settings_theme_dark),
                        stringResource(R.string.settings_theme_dark_summary),
                        ColorSchemeMode.Dark,
                        themeMode,
                    )
                    // t6：Monet 动态色开关（ThemeModeStore.wallpaperMonet 实核存在 L44/L72；
                    // 动态优先于下方手选色板，summary 注明）
                    val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
                    SwitchPreference(
                        checked = wallpaperMonet,
                        onCheckedChange = { ThemeModeStore.setWallpaperMonet(it) },
                        title = stringResource(R.string.settings_follow_wallpaper),
                        summary = if (wallpaperMonet) {
                            stringResource(R.string.settings_follow_wallpaper_summary_on)
                        } else {
                            stringResource(R.string.settings_follow_wallpaper_summary_off)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // t10：注入成功提示（默认开启——目标进程注入成功后弹 Toast 含包名；
                    // 设置存宿主 InjectHintStore，下发随 AIDL loadScript hint 参数）
                    var hintInject by remember { mutableStateOf(InjectHintStore.isEnabled()) }
                    SwitchPreference(
                        checked = hintInject,
                        onCheckedChange = {
                            hintInject = it
                            InjectHintStore.setEnabled(it)
                        },
                        title = stringResource(R.string.settings_inject_hint),
                        summary = stringResource(R.string.settings_inject_hint_summary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // D3③：类加载感知（激进模式）
                    // 代价/收益已在 HookModeStore 注释里说明：进入目标 App 最热路径，
                    // 换取“类一加载就挂上”（比轮询快约 200ms）。故默认关。
                    var loadClassWatch by remember { mutableStateOf(HookModeStore.isLoadClassWatchEnabled()) }
                    SwitchPreference(
                        checked = loadClassWatch,
                        onCheckedChange = {
                            loadClassWatch = it
                            HookModeStore.setLoadClassWatchEnabled(it)
                        },
                        title = stringResource(R.string.settings_class_load_watch),
                        summary = if (loadClassWatch) {
                            stringResource(R.string.settings_class_load_watch_summary_on)
                        } else {
                            stringResource(R.string.settings_class_load_watch_summary_off)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // R2a：主题种子色（预设色板；点击选中/再点取消恢复默认）
                    Text(
                        stringResource(R.string.settings_theme_color),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // 默认项（Miuix 内置)
                        ThemeColorDot(
                            hex = null,
                            selected = keyColorHex == null,
                            onClick = { ThemeModeStore.setKeyColor(null) },
                        )
                        PRESET_KEY_COLORS.forEach { hex ->
                            ThemeColorDot(
                                hex = hex,
                                selected = keyColorHex == hex,
                                onClick = { ThemeModeStore.setKeyColor(if (keyColorHex == hex) null else hex) },
                            )
                        }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    // t4 修正（用户）：统一条目规格（icon 圆底 + 标题 + 副标题；chevron› 为 M3 视觉
                    // 元素已撤销——只对齐信息结构与交互意图；图标全部实核存在（见缺口清单）
                    BasicComponent(
                        title = stringResource(R.string.settings_framework_status),
                        summary = frameworkSummary,
                        enabled = true,
                        startAction = { IconBadge(MiuixIcons.Info) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.settings_module_api),
                        summary = "libxposed 102 · API $apiVersion",
                        enabled = true,
                        startAction = { IconBadge(MiuixIcons.Layers) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.settings_hot_reload),
                        summary = hotReloadSummary ?: stringResource(R.string.settings_hot_reload_summary),
                        enabled = active,
                        startAction = { IconBadge(MiuixIcons.Refresh) },
                        // R1.3：对 STALE 目标逐个热重载；回调已在主线程，直接写 UI 状态
                        onClick = {
                            hotReloadSummary = hotReloadPending
                            IpcManager.hotReloadStaleTargets { _, _, msg ->
                                hotReloadSummary = msg
                            }
                        },
                    )
                    BasicComponent(
                        title = stringResource(R.string.settings_scope_mode),
                        summary = stringResource(R.string.settings_scope_mode_summary),
                        enabled = true,
                        startAction = { IconBadge(MiuixIcons.SearchDevice) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.settings_frida_runtime),
                        summary = "GumJS · QuickJS · Binder IPC",
                        enabled = true,
                        startAction = { IconBadge(MiuixIcons.Recording) },
                    )
                }
            }
            item {
                BasicComponent(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.settings_about),
                    summary = "LSPFRIFA 1.0 · Miuix 0.9.4",
                    titleColor = BasicComponentDefaults.titleColor(),
                    summaryColor = BasicComponentDefaults.summaryColor(),
                    enabled = true,
                    startAction = { IconBadge(MiuixIcons.Help) },
                )
            }
        }
    }
}

/**
 * t4：主题模式单选行（RadioButtonPreference 受控组件：selected 由 ThemeModeStore.mode 驱动，
 * 点击回写 ThemeModeStore.set → StateFlow 更新 → MainActivity 全局重组）。
 * 选中时标题/摘要自动取主题 primary 色（RadioButtonPreferenceDefaults）。
 */
@Composable
private fun ThemeModeOption(
    title: String,
    summary: String,
    option: ColorSchemeMode,
    current: ColorSchemeMode,
) {
    RadioButtonPreference(
        title = title,
        summary = summary,
        selected = current == option,
        onClick = { ThemeModeStore.set(option) },
    )
}
/** R2a：预设主题种子色（未含默认紫——默认项单独显示）。 */
private val PRESET_KEY_COLORS = listOf(
    "2962FF", // HyperOS 蓝
    "00A679", // 绿
    "B35C00", // 橙
    "A40041", // 红
    "00838F", // 青
    "7C4DFF", // 紫罗兰
)

/** R2a：主题色圆点（hex=null 为默认项）；选中描边 primary；默认项中心小点标记。 */
@Composable
private fun ThemeColorDot(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val dotColor = if (hex != null) {
        Color(runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0xFF6750A4.toInt()))
    } else {
        Color(0xFF6750A4)
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (hex == null) MiuixTheme.colorScheme.surfaceVariant else dotColor)
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, CircleShape)
                else Modifier.border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (hex == null) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
        }
    }
}

/**
 * t4：设置条目规格——icon 前置（圆形底色容器）。
 * 容器 = surfaceVariant 圆 + primary 图标（Miuix 语义 token，浅/深自适应）。
 */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
