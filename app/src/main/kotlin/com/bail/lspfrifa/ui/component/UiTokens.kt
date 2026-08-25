package com.bail.lspfrifa.ui.component

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect

/**
 * UI 重设计统一 token（t2）。
 *
 * 依据 design-researcher t1 调研 + 参考截图范式 + 用户"底栏/卡片/整体排版"三大痛点：
 * 统一 8dp 级间距栅格（页面 12dp / 卡片间隙 12dp / 卡内 14×12dp）、
 * 卡片圆角规范 ~12dp（HyperOS widget 规范 12.67dp(FHD) 就近取整）、
 * 底栏胶囊保持 22dp、排版层级（标题 17sp / 副标 13sp / 说明 12sp 为大标题 24sp 服务）。
 */
object UiTokens {

    /** 页面水平留白（8dp 栅格；参考截图 12dp 级留白） */
    val PagePadding = 12.dp

    /** 卡片之间的段落间距 */
    val CardSpacing = 12.dp

    /** 卡片统一圆角（HyperOS widget 12.67dp 规范 → 12dp；Miuix Card 内部即 squircleSurface） */
    val CardRadius = 12.dp

    /** 卡片内边距：水平 14dp（12-14 规范内）、垂直 12dp */
    val CardMarginH = 14.dp
    val CardMarginV = 12.dp

    /** 玻璃悬浮底栏胶囊圆角（保持原 22dp 不变） */
    val BarRadius = 22.dp

    /** 玻璃拟态模糊半径（dp；Liquid Glass 适中强度，避免过糊丢失内容识别） */
    val GlassBlurRadius = 20f

    // ===== 排版层级（大标题 24sp 由 TopAppBar largeTitle 承担，此处为内容层） =====

    /** 卡片/区块标题 */
    val TitleSize = 17.sp

    /** 副标/次级说明 */
    val SubtitleSize = 13.sp

    /** 元信息/说明（caption） */
    val CaptionSize = 12.sp
}

/**
 * 超椭圆（Superellipse/squircle）[Shape]：以公开 API [addSquircleRect] 构建轮廓，
 * 供 blur 区域剪切等必须传入 [Shape] 的场景使用（剪裁类用 [top.yukonga.miuix.kmp.squircle.squircleClip]）。
 *
 * 三查对照（ui-sources 实核，miuix-squircle-android 0.9.4-rc01）：
 * - `fun Path.addSquircleRect(width: Float, height: Float, cornerRadius: Float,
 *   extension: Float = SquircleDefaults.Extension, squircleEnabled: Boolean = true)`
 *   （SquirclePath.kt 公开 API；extension 钳制 [SquircleDefaults.ExtensionMin]..[ExtensionMax]）
 * - `object SquircleDefaults { val Extension = 1.1f }`
 */
class SquircleShape(
    private val cornerRadius: Dp,
    private val extension: Float = SquircleDefaults.Extension,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
        val path = Path()
        path.addSquircleRect(size.width, size.height, radiusPx, extension)
        return Outline.Generic(path)
    }
}
