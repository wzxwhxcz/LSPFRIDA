package com.bail.lspfrifa.ui.screen

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.bail.lspfrifa.R
import com.bail.lspfrifa.ui.component.MiuixPageBackground
import com.bail.lspfrifa.ui.component.UiTokens

/**
 * LSPFRIFA 首页（Miuix 布局）的源码结构复刻。
 * 已确认：12dp 内容间距；首行左侧状态卡、右侧上下两张统计卡；
 * 随后是设备信息与项目介绍卡。
 */
@Composable
fun DashboardScreenV093(
    isXposedActive: Boolean = false,
    apiVersion: Int = 0,
    frameworkName: String = "",
    frameworkVersion: String = "",
    activeProjectCount: Int = 0,
    activeProcessCount: Int = 0,
    onOpenSelector: () -> Unit = {},
) {
    val statusTitle = if (isXposedActive) {
        stringResource(R.string.dash_status_activated)
    } else {
        stringResource(R.string.dash_status_rootless)
    }
    val statusSummary = if (isXposedActive) {
        if (apiVersion > 0) stringResource(R.string.dash_active_xp, apiVersion) else stringResource(R.string.dash_xposed_detected)
    } else stringResource(R.string.dash_xposed_not_detected)
    // 状态绿色：项目既有"已激活"成功色惯例（非 Miuix 语义 token；skill 明确禁止伪造 success/alert token）
    val statusColor = if (isXposedActive) Color(0xFF35C759) else MiuixTheme.colorScheme.primary
    val frameworkText = listOf(frameworkName, frameworkVersion)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    var showDocs by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = "LSPFRIFA", largeTitle = "LSPFRIFA") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusCard(
                        title = statusTitle,
                        summary = statusSummary,
                        apiVersion = if (apiVersion > 0) apiVersion.toString() else "?",
                        active = isXposedActive,
                        statusColor = statusColor,
                        modifier = Modifier.weight(1.22f).height(168.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f).height(168.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HomeMetricCard(
                            title = stringResource(R.string.dash_projects),
                            value = activeProjectCount.toString(),
                            secondary = activeProcessCount.toString(),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onClick = onOpenSelector,
                        )
                        HomeMetricCard(
                            title = stringResource(R.string.dash_active_processes),
                            value = activeProcessCount.toString(),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = UiTokens.CardRadius,
                    insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.CardSpacing)) {
                        HomeInfoText(stringResource(R.string.dash_software_version), "1.0 (1)")
                        HomeInfoText(stringResource(R.string.dash_api_version), if (apiVersion > 0) apiVersion.toString() else "?")
                        HomeInfoText(stringResource(R.string.dash_device_model), "${Build.MANUFACTURER} ${Build.MODEL}".ifBlank { Build.DEVICE })
                        HomeInfoText(stringResource(R.string.dash_android_version), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = UiTokens.CardRadius,
                    insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
                ) {
                    Text(stringResource(R.string.dash_project_intro), fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.dash_intro_body),
                        fontSize = UiTokens.SubtitleSize,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (frameworkText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(frameworkText, fontSize = UiTokens.CaptionSize, color = MiuixTheme.colorScheme.primary)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = UiTokens.CardRadius,
                    insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
                    showIndication = true,
                    onClick = { showDocs = true },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dash_docs_title), fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.dash_docs_summary),
                                fontSize = UiTokens.SubtitleSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Text("›", fontSize = 30.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }

    if (showDocs) {
        Dialog(onDismissRequest = { showDocs = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = UiTokens.CardRadius,
                insideMargin = PaddingValues(16.dp),
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.dash_docs_title), fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dash_docs_steps),
                        fontSize = UiTokens.SubtitleSize,
                        lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showDocs = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.dash_got_it)) }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    summary: String,
    apiVersion: String,
    active: Boolean,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    // 未激活：error 语义（errorContainer 浅粉底 + error 红字 + 大号叹号水印，对齐目标应用首页）
    // 激活：项目既有绿色成功惯例（0xFF35C759，非 Miuix token，skill 禁止伪造 success token）
    val isError = !active
    val cardColor = if (isError) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.surfaceContainer
    val cardContent = if (isError) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurface
    Card(
        modifier = modifier,
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(14.dp),
        colors = CardDefaults.defaultColors(
            color = cardColor,
            contentColor = cardContent,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (active) MiuixIcons.Ok else MiuixIcons.Info,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Column {
                    Text(title, fontSize = UiTokens.TitleSize, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        summary,
                        fontSize = UiTokens.SubtitleSize,
                        lineHeight = 16.sp,
                        color = if (isError) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "API $apiVersion",
                        fontSize = UiTokens.SubtitleSize,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Icon(
                imageVector = if (active) MiuixIcons.Ok else MiuixIcons.Info,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(110.dp)
                    .alpha(0.18f),
            )
        }
    }
}

@Composable
private fun HomeMetricCard(
    title: String,
    value: String,
    secondary: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val cardModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(
        modifier = cardModifier,
        cornerRadius = UiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = UiTokens.CardMarginH, vertical = UiTokens.CardMarginV),
        showIndication = onClick != null,
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = UiTokens.SubtitleSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (secondary != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("/ $secondary", fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun HomeInfoText(label: String, value: String) {
    // 两行式：label 上（小字灰）、value 下（大字黑），对齐目标应用首页设备信息卡
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = UiTokens.SubtitleSize, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}