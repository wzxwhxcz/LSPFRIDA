package com.bail.lspfrifa.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃拟态表面（Miuix Liquid Glass 模式）：
 * `Modifier.layerBackdrop(backdrop)` 捕获内容 → [textureBlur]（RuntimeShader 高斯模糊 +
 * 自适应降采样 + 噪声抖动防色带）→ 半透明表面色 [tint] 叠在模糊之上。
 *
 * 三查对照（miuix-blur-android 0.9.4-rc01 实核，sources jar + POM 实查）：
 * - `fun Modifier.textureBlur(backdrop: Backdrop, shape: Shape, blurRadius: Float = BlurDefaults.BlurRadius,
 *   noiseCoefficient: Float = BlurDefaults.NoiseCoefficient, colors: BlurColors = BlurColors(),
 *   highlight: Highlight? = null, contentBlendMode: BlendMode = BlendMode.SrcOver, enabled: Boolean = true)`
 *   —— blurRadius 单位 dp（"Internally converted to pixels using display density"）
 * - `fun Modifier.layerBackdrop(backdrop: LayerBackdrop)` / `@Composable fun rememberLayerBackdrop(...): LayerBackdrop`
 * - 门槛：blur 管线全部基于 RuntimeShader；`drawBackdrop` 内部 `effectiveEnabled = enabled && isRuntimeShaderSupported()`
 *   —— **API<33（Android <13）自动无操作**；`isRuntimeShaderSupported() = Build.VERSION.SDK_INT >= TIRAMISU`
 *   （miuix-shader-android RuntimeShader.android.kt L18 实核）
 *
 * 降级策略（低于门槛/不支持）：
 * - [tint] 按不透纯色直接绘制（与 Miuix TopAppBar 默认 surface 实色一致，无视觉回归）；
 * - [textureBlur] 本身被门控为无操作，内容正常绘制。
 *
 * 性能：只用于小区域（底栏胶囊、TopAppBar 条），禁止叠加大区域（任务规格约束）。
 */
@Composable
fun Modifier.glassSurface(
    backdrop: LayerBackdrop,
    shape: Shape,
    tint: Color,
    blurRadius: Float = UiTokens.GlassBlurRadius,
): Modifier {
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    return if (isRuntimeShaderSupported()) {
        textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            // t9：显式关闭顶部高光（Highlight）——浅色/动态内容下高光渐变会显现为
            // 左侧灰色"一块模糊"伪影（用户两轮截图：日志页顶栏左下一坨灰，内容变化后出现）
            highlight = null,
        ).background(
            color = tint.copy(alpha = if (dark) 0.70f else 0.75f),
            shape = shape,
        )
    } else {
        // API<33 降级纯色（不透）：等同于旧实色表面
        background(color = tint, shape = shape)
    }
}

/**
 * 玻璃拟态 TopAppBar 容器（详情/日志/编辑页）。
 *
 * 用法：Scaffold(topBar = { GlassTopAppBar(backdrop) { TopAppBar(..., color = Color.Transparent) } })，
 * 内容侧以 Box(Modifier.fillMaxSize().background(MiuixPageBackground()).layerBackdrop(backdrop)) 捕获。
 * 注意 TopAppBar 默认 defaultWindowInsetsPadding=true —— 状态栏区域同样被玻璃覆盖（Liquid Glass 意图）。
 */
@Composable
fun GlassTopAppBar(
    backdrop: LayerBackdrop,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop = backdrop,
                shape = RectangleShape,
                tint = MiuixTheme.colorScheme.surface,
            ),
    ) {
        content()
    }
}
