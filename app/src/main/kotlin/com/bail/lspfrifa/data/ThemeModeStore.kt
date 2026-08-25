package com.bail.lspfrifa.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题设置（模式/种子色/调色板风格）持久化存储。
 * 由 LSPFRIFAApplication.onCreate 初始化；变更即时写入 StateFlow，
 * MainActivity 在 setContent 中 collectAsState 驱动 ThemeController 重建 → MiuixTheme 全局即时生效。
 */
object ThemeModeStore {

    private const val PREFS_CFG = "lspfrifa_cfg"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_KEY_COLOR = "theme_key_color"
    private const val KEY_PALETTE = "theme_palette"
    private const val KEY_WALLPAPER = "theme_wallpaper_monet"
    private const val MODE_SYSTEM = "system"
    private const val MODE_LIGHT = "light"
    private const val MODE_DARK = "dark"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(ColorSchemeMode.System)
    /** 当前主题模式（只读 StateFlow；修改只有 [set]）。 */
    val mode: StateFlow<ColorSchemeMode> = _mode.asStateFlow()

    private val _keyColor = MutableStateFlow<String?>(null)
    /** 种子色（#RRGGBB 大写 Hex；null=默认(Miuix 内置)。只读 StateFlow）。 */
    val keyColor: StateFlow<String?> = _keyColor.asStateFlow()

    private val _paletteStyle = MutableStateFlow(ThemePaletteStyle.Content)
    /** 调色板风格（Miuix ThemeController paletteStyle 透传）。只读 StateFlow）。 */
    val paletteStyle: StateFlow<ThemePaletteStyle> = _paletteStyle.asStateFlow()

    private val _wallpaperMonet = MutableStateFlow(false)
    /** Monet 跟随壁纸（M3 dynamicColorScheme 取系统壁纸主色作为种子）。只读 StateFlow）。 */
    val wallpaperMonet: StateFlow<Boolean> = _wallpaperMonet.asStateFlow()

    /** 必须在首次访问 [mode]/[set] 前调用（LSPFRIFAApplication.onCreate）。 */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = decode(p.getString(KEY_MODE, null))
        _keyColor.value = p.getString(KEY_KEY_COLOR, null)
        _paletteStyle.value = decodePalette(p.getString(KEY_PALETTE, null))
        _wallpaperMonet.value = p.getBoolean(KEY_WALLPAPER, false)
    }

    fun current(): ColorSchemeMode = _mode.value
    fun currentKeyColor(): String? = _keyColor.value

    /** 持久化并即时更新全局状态（设置页 → MainActivity 生效链路）。 */
    fun set(mode: ColorSchemeMode) {
        prefs?.edit()?.putString(KEY_MODE, encode(mode))?.apply()
        _mode.value = mode
    }

    /** R2a：设置/清除种子色；null=恢复 Miuix 默认。正值即时生效（ThemeController remember(keyColor) 重建）。 */
    fun setKeyColor(hex: String?) {
        prefs?.edit()?.apply { if (hex == null) remove(KEY_KEY_COLOR) else putString(KEY_KEY_COLOR, hex) }?.apply()
        _keyColor.value = hex
    }

    /** Monet 跟随壁纸开关（M3 动态种子色优先于手选色板）。 */
    fun setWallpaperMonet(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_WALLPAPER, enabled)?.apply()
        _wallpaperMonet.value = enabled
    }

    // 只持久化三态；Monet* 等类型统一按 System 编码，解码未知值回落 System。
    private fun encode(mode: ColorSchemeMode): String = when (mode) {
        ColorSchemeMode.Light -> MODE_LIGHT
        ColorSchemeMode.Dark -> MODE_DARK
        else -> MODE_SYSTEM
    }

    private fun decode(value: String?): ColorSchemeMode = when (value) {
        MODE_LIGHT -> ColorSchemeMode.Light
        MODE_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }

    private fun decodePalette(value: String?): ThemePaletteStyle = value
        ?.let { v -> ThemePaletteStyle.entries.firstOrNull { it.name == v } }
        ?: ThemePaletteStyle.Content
}
