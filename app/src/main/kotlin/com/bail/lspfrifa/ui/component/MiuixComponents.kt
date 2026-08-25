package com.bail.lspfrifa.ui.component


import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolInputView
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面/Scaffold 底色（Miuix 语义 token，浅深自适应）：
 * - 浅色 = background（官方浅色 background=#FFFFFF；Miuix Scaffold 默认 surface=#F7F7F7
 *   灰白，与目标应用白底不符且底部露出大片灰（截图"灰块"根因），故浅色取白）；
 * - 深色 = surface（官方深色 surface=Black；而深色 background=#242424 与卡片
 *   surfaceContainer=#242424 同色、无层次，故深色用 surface，恢复"黑底+深灰卡"层次）。
 */
@Composable
fun MiuixPageBackground(): Color {
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme() // System / MonetSystem / null
    }
    return if (dark) MiuixTheme.colorScheme.surface else MiuixTheme.colorScheme.background
}

/**
 * 悬浮底栏（自绘）——t2 玻璃拟态版：
 * 容器 = 超椭圆（SquircleShape 22dp）玻璃胶囊（blur + 半透明 surfaceContainer），
 * 结构保持 t1/t2 决策：无任何"多余块"覆盖，仅每项 icon+text + **单一 pill 滑动指示器**
 * （secondaryContainer + 300ms 位置动画，无颜色动画=无"双灰"交叉 fade）。
 *
 * glassSurface 已内置 API<33 降级（不透纯色胶囊，与旧实色表现一致）。
 * 指示器容器同步改用 squircleClip（超椭圆；API<33 自动降级 RoundedCornerShape）。
 *
 * 调用方（MainActivity）负责：
 *   val backdrop = rememberLayerBackdrop()
 *   内容容器 .layerBackdrop(backdrop)（不含本底栏，避免自模糊回环）。
 */
@Composable
fun FloatingMiuixNavigationBar(
    items: List<NavigationItem>,
    selected: Int,
    onClick: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp), // 底部偏移 12dp + 水平 16dp 悬浮边距
    ) {
        // 单一 pill 的 x 偏移（相对胶囊容器左上角）；与 Row(SpaceEvenly, 16dp 水平 padding) 槽位公式对齐
        val pillOffsetX by animateDpAsState(
            targetValue = singlePillOffsetX(items.size, selected, maxWidth),
            animationSpec = tween(300),
            label = "navSinglePillOffsetX",
        )
        // 玻璃胶囊容器（超椭圆 Shape；模糊区域剪裁与内容底色同形）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop = backdrop,
                    shape = SquircleShape(UiTokens.BarRadius),
                    tint = MiuixTheme.colorScheme.surfaceContainer,
                ),
        ) {
            // 指示器层（置于 Row 之下，实体整体滑动，无交叉 fade）
            Box(
                modifier = Modifier
                    .offset(x = pillOffsetX, y = 8.dp) // 8dp = Row vertical padding
                    .size(64.dp, 56.dp)
                    .squircleClip(UiTokens.BarRadius)
                    .background(MiuixTheme.colorScheme.secondaryContainer),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    FloatingNavItem(
                        item = item,
                        isSelected = selected == index,
                        onClick = { onClick(index) },
                    )
                }
            }
        }
    }
}

/** 单一 pill 在 [index] 槽位上的 x 偏移（Dp），与 Row 的 SpaceEvenly + 76dp 槽位布局精确对齐。 */
private fun singlePillOffsetX(itemCount: Int, index: Int, containerWidth: Dp): Dp {
    if (itemCount <= 0) return 0.dp
    // 越界时 clamp（与旧实现"无选中项"行为兼容，不抛异常）
    val safeIndex = index.coerceIn(0, itemCount - 1)
    val itemWidth = 76.dp            // item 槽位宽（t1 X11.h@BottomBarMiuix.kt:85）
    val rowContentWidth = containerWidth - 32.dp // Row horizontal padding 16dp × 2
    // SpaceEvenly：剩余空间均分到 (itemCount + 1) 个间隙（含两端）
    val gap = (rowContentWidth - itemWidth * itemCount) / (itemCount + 1)
    val itemStartX = 16.dp + gap + (itemWidth + gap) * safeIndex
    // 胶囊 64dp 在 76dp 槽位内水平居中
    return itemStartX + (itemWidth - 64.dp) / 2
}

@Composable
private fun FloatingNavItem(
    item: NavigationItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // t2：标签背景静止透明（指示器上移到单一 pill 层），仅 icon/text 做色彩动画，
    // 选中 primary 高亮 vs 未选中 onSurface（t1 配色不变，仅去掉 pill 背景颜色动画）。
    val tint by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        animationSpec = tween(300),
        label = "navTint",
    )
    // item 容器 76dp 宽（t1 X11.h@BottomBarMiuix.kt:85），胶囊 64×56（t1 FloatingBottomBar.kt:181），
    // 胶囊内边距 4dp（t1 Lpbf.o/v42=4f）；圆角 22dp 与 300ms 过渡均保持原值不变。
    Column(
        modifier = Modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .size(64.dp, 56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Transparent)
                .clickable(onClick = onClick)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 官方 Miuix Switch 薄封装（0.9.4-rc01 的 Switch + SwitchDefaults）。
 * 保留函数名与签名使调用点零改动；行为/视觉 = 官方组件。
 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    )
}

/** 高清 Drawable 图标展示器。t2：剪裁改超椭圆（HyperOS 品牌应用图标即 squircle；API<33 自动降级圆角）。 */
@Composable
fun AppIconImage(drawable: Drawable?, modifier: Modifier = Modifier) {
    if (drawable == null) {
        Box(modifier = modifier.squircleClip(UiTokens.CardRadius).background(Color(0xFFE0E0E0)))
        return
    }
    val bitmap = remember(drawable) {
        runCatching { drawable.toBitmap(120, 120).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.squircleClip(UiTokens.CardRadius),
        )
    } else {
        Box(modifier = modifier.squircleClip(UiTokens.CardRadius).background(Color(0xFFE0E0E0)))
    }
}

// ==================== t3：脚本代码编辑器（sora-editor 0.23.6 + TextMate 高亮） ====================

/**
 * Miuix 代码编辑器：sora-editor 0.23.6 CodeEditor（View 体系）的 Compose 桥接封装，
 * 用于详情页脚本编辑（替换原 Miuix TextField）。行号/等宽字体/自动换行/undo/当前行高亮/深浅色。
 *
 * TextMate JS 语法高亮（可选增强，失败自动降级为基础配色，不阻塞编辑）——
 * 初始化链路（全部真实签名见 TextMateGrammars 注释）：
 * FileProviderRegistry+AssetsFileResolver → GrammarRegistry.loadGrammars("textmate/languages.json")
 * → ThemeRegistry.loadTheme(light/dark)+setTheme（TextMateAnalyzer 构造要求 currentTheme 非空）
 * → TextMateLanguage.create("source.js", true) → editor.setEditorLanguage/setColorScheme。
 * 时序：初始化在 IO 线程串行执行（0.23.6 的 loadGrammars 为同步方法——Xed fork 的
 * CompletableDeferred 异步 API 在 0.23.6 不存在），首帧不阻塞；编辑先行 setText（先 setText 后
 * setEditorLanguage 安全：源码以现有 text 重建分析），高亮就绪后 update 异步应用。
 * 主题切换经 ThemeRegistry.setTheme → scheme/analyzer 监听器自动重绘。
 *
 * t5：底部符号栏 = sora SymbolInputView（0.23.6 实核类名，非 SymbolInputBar）：
 * SymbolInputView(Context) / bindEditor(CodeEditor) / addSymbols(String[] display, String[] insertText)
 * （"\t" 特殊处理=缩进/下一 tab stop；其余 editor.insertText(text, 1)）/ setTextColor(int) /
 * removeSymbols()。默认背景/文字色读 AAR 资源（浅色系），此处以 Miuix 语义 token 覆写
 * （surfaceContainer 底 + onSurfaceSecondary 文字，深浅自适应）；编辑触发 ContentChangeEvent → 同步。
 *
 * 许可证合规（NOTICE）：
 * - sora-editor（editor/language-textmate）= LGPL-2.1（POM + 源码头实读）；动态链接**未修改的**
 *   AAR 使用合规；**不修改库源码后闭源**——如功能定制走子类/包装层（本封装即属此类）；
 * - org.eclipse.tm4e.core（随 language-textmate 打包）= EPL-2.0（SPDX 头实读）；
 * - assets 资源全部取自 microsoft/vscode 仓库（MIT）：JavaScript.tmLanguage.json 227KB、
 *   javascript-language-configuration.json（原文件为 JSONC，已剥离注释为严格 JSON）、
 *   dark_plus.json/light_plus.json 各 ~4.7KB；全程未引用 Xed-Editor（GPL）任何文件；
 * - 传递依赖 joni/jcodings（Ruby 生态 EPL-2.0 兼容许可）、gson、snakeyaml-engine；
 * - desugaring：language-textmate 契约要求 API<33 使用 core-library desugaring——已开
 *   isCoreLibraryDesugaringEnabled + desugar_jdk_libs 2.1.5（Google Maven 最新稳定，minSdk26 必须）；
 * - proguard：release minify 需 keep org.eclipse.tm4e.**（debug 无 minify 无需）。
 */
@Composable
fun MiuixCodeEditor(
    script: String,
    onScriptChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTmError: ((String?) -> Unit)? = null,
    onEditorReady: ((io.github.rosemoe.sora.widget.CodeEditor) -> Unit)? = null,
) {
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    // 基础配色（TextMate 就绪/失败前的降级配色；深=Darcula，浅=默认白底）
    val baseScheme = remember(dark) { if (dark) SchemeDarcula() else EditorColorScheme() }
    // AndroidView.factory 只执行一次，直接捕获 state 会成快照；用 rememberUpdatedState 读最新值
    val latestScript by rememberUpdatedState(script)
    val latestOnChange by rememberUpdatedState(onScriptChange)
    val tm = remember { TextMateHolder() }
    val context = LocalContext.current
    // TextMate 初始化一次性放 IO 线程（不阻塞首帧；幂等，见 TextMateGrammars）；
    // tmReady 触发重组后由 update 应用语言/配色（先 setText 后 setEditorLanguage 安全）
    var tmReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        tmReady = withContext(Dispatchers.IO) {
            val ok = runCatching {
                TextMateGrammars.init(context, tm, dark)
            }.onFailure { t ->
                // t7：高亮失败可观测（logcat + UI 提示），不再静默降级
                android.util.Log.w(TAG_MIUIX_EDITOR, "TextMate init failed: ${t.message}", t)
                onTmError?.invoke(t.message ?: t.javaClass.simpleName)
            }.isSuccess
            // t5：成功路径清除旧错误（onTmError 仅在失败时携带消息；成功以 null 清除——
            // 修复"高亮已生效（tmEnabled）却仍显示红色降级提示"）
            if (ok) onTmError?.invoke(null)
            ok
        }
    }
    // t5：底部符号栏配色（取 Miuix 语义 token；factory 非 composable 作用域，不能读 Local，故预取）
    val symbolBarBg = MiuixTheme.colorScheme.surfaceContainer
    val symbolBarTint = MiuixTheme.colorScheme.onSurfaceSecondary
    AndroidView(
        factory = { context ->
            val editor = CodeEditor(context).apply {
                setTextSize(13f)                 // sp，与原 TextField 13sp 一致
                setTypefaceText(Typeface.MONOSPACE)
                setDisplayLnPanel(true)          // 行号
                setWordwrap(true)
                setUndoEnabled(true)
                setHighlightCurrentLine(true)
                setColorScheme(baseScheme)       // 基础配色先行（高亮就绪前）
                setText(script)                  // 初值（后续变更走 update）
                // 编辑变更 → Compose 状态；与 update 的 setText 值相同即跳过，双向无环
                subscribeAlways(ContentChangeEvent::class.java) {
                    val newText = text.toString()
                    if (newText != latestScript) latestOnChange(newText)
                }
            }
            tm.editor = editor
            onEditorReady?.invoke(editor)
            // t5：底部符号栏（sora SymbolInputView 0.23.6 实核：bindEditor/addSymbols/setTextColor）
            val symbolBar = SymbolInputView(context).apply {
                setBackgroundColor(symbolBarBg.toArgb())
                setTextColor(symbolBarTint.toArgb())
                addSymbols(
                    arrayOf("(", ")", "[", "]", "{", "}", "<", ">", "\t", "  "),
                    arrayOf("(", ")", "[", "]", "{", "}", "<", ">", "\t", "  "),
                )
                bindEditor(editor)
            }
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(editor, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                addView(symbolBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        },
        update = { _ ->
            val editor = tm.editor
            val current = editor.text.toString()
            if (current != script) editor.setText(script)
            if (tmReady && tm.enabled) {
                // 高亮异步就绪：应用语言（内部以现有 text 重建分析）+ TextMate 配色
                if (editor.editorLanguage !== tm.language) editor.setEditorLanguage(tm.language)
                if (editor.colorScheme !== tm.scheme) editor.setColorScheme(tm.scheme)
                // 深浅色切换：ThemeRegistry.setTheme → scheme/analyzer 监听器自动重绘
                val target = if (dark) tm.darkModel else tm.lightModel
                if (tm.lastTheme !== target) {
                    tm.lastTheme = target
                    ThemeRegistry.getInstance().setTheme(target)
                }
            } else if (editor.colorScheme !== baseScheme) {
                editor.setColorScheme(baseScheme)
            }
        },
        onRelease = { tm.editor.release() },
        modifier = modifier,
    )
}

/** TextMate 初始化结果持有点（普通对象，避免写入 snapshot state 触发重组）。 */
private class TextMateHolder {
    var enabled = false
    lateinit var editor: CodeEditor
    lateinit var language: TextMateLanguage
    lateinit var scheme: TextMateColorScheme
    lateinit var darkModel: ThemeModel
    lateinit var lightModel: ThemeModel
    var lastTheme: ThemeModel? = null
    /** 主题模型/语言一次性初始化标志（lateinit isInitialized 无法跨实例引用，用普通标志替代） */
    var initialized = false
}

/**
 * TextMate 全局注册表一次性初始化（进程级单例、幂等）。任何异常向上抛，由 MiuixCodeEditor 降级。
 *
 * 真实 API（sources jar 实核，与用户参考代码的差异见任务 output 差异表）：
 * - FileProviderRegistry.getInstance() / addFileProvider(FileResolver)；AssetsFileResolver(AssetManager)；
 * - GrammarRegistry.getInstance().loadGrammars(String jsonPath) —— 0.23.6 为同步方法（返回
 *   List<IGrammar>），无 CompletableFuture/CompletableDeferred（Xed fork 的差异点）；
 *   languages.json 字段 = name/scopeName/grammar（路径经 FileProviderRegistry 解析），
 *   languageConfiguration/embeddedLanguages 可选（null 安全，源码 L99 判空）；
 * - ThemeRegistry.getInstance() / loadTheme(ThemeModel) / setTheme(ThemeModel)；
 *   ThemeModel(IThemeSource)；IThemeSource.fromInputStream(InputStream, String?, Charset?)；
 *   ⚠ TextMateAnalyzer 构造读取 themeRegistry.getCurrentThemeModel().getTheme()（源码 L87）——
 *   必须先在 ThemeRegistry setTheme（否则 NPE）；
 * - TextMateLanguage.create(String scope, boolean autoComplete)（唯一 scope 重载；不存在
 *   create(IThemeSource, boolean) 重载）；找不到 scope 抛 IllegalArgumentException；
 * - TextMateColorScheme(ThemeRegistry, ThemeModel) throws Exception + setTheme(ThemeModel)
 *   （触发 applyDefault + 注册 ThemeChangeListener，主题切换自动重绘）；
 * - setTheme 后创建 TextMateLanguage 再 setEditorLanguage/setColorScheme/setText 顺序。
 */
private object TextMateGrammars {
    private const val LANGUAGES_JSON = "textmate/languages.json"
    private const val DARK_THEME_PATH = "textmate/themes/dark_plus.json"
    private const val LIGHT_THEME_PATH = "textmate/themes/light_plus.json"

    @Volatile
    private var registered = false

    @Synchronized
    fun init(context: Context, holder: TextMateHolder, dark: Boolean) {
        if (!registered) {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
            GrammarRegistry.getInstance().loadGrammars(LANGUAGES_JSON)
            registered = true
        }
        val themeRegistry = ThemeRegistry.getInstance()
        if (!holder.initialized) {
            holder.lightModel = ThemeModel(
                IThemeSource.fromInputStream(
                    context.assets.open(LIGHT_THEME_PATH), LIGHT_THEME_PATH, Charsets.UTF_8,
                ),
            )
            holder.darkModel = ThemeModel(
                IThemeSource.fromInputStream(
                    context.assets.open(DARK_THEME_PATH), DARK_THEME_PATH, Charsets.UTF_8,
                ),
            ).apply { setDark(true) }
            themeRegistry.loadTheme(holder.lightModel)
            themeRegistry.loadTheme(holder.darkModel)
            holder.initialized = true
        }
        val target = if (dark) holder.darkModel else holder.lightModel
        // 必须先 setTheme（currentTheme 非空），TextMateAnalyzer 构造在其后依赖
        if (themeRegistry.currentThemeModel === null || themeRegistry.currentThemeModel !== target) {
            themeRegistry.setTheme(target)
        }
        holder.language = TextMateLanguage.create("source.js", true)
        holder.scheme = TextMateColorScheme(themeRegistry, target).also { it.setTheme(target) }
        holder.lastTheme = target
        holder.enabled = true
    }
}
private const val TAG_MIUIX_EDITOR = "LSPFRIFA-CodeEditor"
