# UI 重设计实施报告（t2，2026-08-25）

> 依据：design-researcher t1 调研报告 + 参考截图范式（docs/UI-Reference-Comparison.md）+ 用户"底栏/卡片/整体排版"三大痛点。
> 范围：纯视觉层。Monet/信息架构/注入链逻辑零改动。

## ① 设计规格摘要（改了什么 + 理由）

| 决策 | 变更 | 理由 |
|---|---|---|
| **玻璃拟态·底栏** | 悬浮底栏改为**超椭圆胶囊玻璃容器**（miuix-blur textureBlur：20dp 高斯模糊 + 噪声抖动防色带 + surfaceContainer 半透明叠色），全宽透明条 → 16dp 边距悬浮胶囊（22dp 圆角保持） | 用户"底栏"痛点；HyperOS Liquid Glass 范式；仅小区域（性能约束） |
| **玻璃拟态·TopAppBar** | 详情/日志/编辑页 TopAppBar 包 GlassTopAppBar（blur + surface 半透明叠色，状态栏区域一并玻璃化） | 参考截图顶栏质感；仅二级页三处 |
| **降级策略** | miuix-blur `drawBackdrop` 内置 `isRuntimeShaderSupported` 门控（**API<33 自动无操作**）；glassSurface 在无 shader 时降级**不透纯色**（= 旧实色表现，无视觉回归） | 任务规格"低于门槛降级纯色" |
| **超椭圆** | 卡片/插件卡/底栏胶囊/应用图标全部 squircle；Miuix Card 0.9.4-rc01 **内部已是 squircleSurface**（实核 Card.kt L177），故卡片仅需调圆角值；应用图标 12dp squircleClip；底栏胶囊 SquircleShape(22dp)；pill 指示器 squircleClip(22) | HyperOS 品牌（logo 即超椭圆点阵） |
| **圆角规范** | 卡片 22/20/18dp → **统一 12dp**（HyperOS widget 12.67dp FHD 就近取整）；底栏胶囊 22dp 保持；图标 12dp；标签 12dp 保持 | 任务规格 ~12-14dp |
| **排版栅格** | 页内水平留白 12dp；卡片间隙 12dp（原 8dp 处统一）；卡内 padding 14×12dp（原 20×18/17/15 等不齐）；段落间距 12dp | 任务规格 8dp 级栅格 + 12-14dp 卡内 |
| **排版层级** | 统一 token：标题 17sp（原 18/19sp 散乱）、副标 13sp（原 12/13/14sp）、说明 12sp（caption）；大标题由 Miuix TopAppBar largeTitle 承担；数值指标（22/23sp）保留强调 | 任务规格 24-26/17/13/12 层级 |
| **图标形态** | AppIconImage 12dp RoundedCorner → 12dp squircleClip（HyperOS 应用图标形态） | 品牌一致性 |
| **深色** | 新增颜色全部为 Miuix 语义 token（surfaceContainer/surface/次要容器），glassSurface 深色 alpha 0.70 / 浅色 0.75；blur/squircle 双模同一管线 | 双模自动成立 |
| **M3 组件** | **本批未使用**（评估结论见 §③） | 见下 |

## ② 实施 diff（文件清单）

**依赖（唯一新增 = miuix-blur，零新传递依赖风险）**
- `gradle/libs.versions.toml`：+`miuix-blur`（top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01）
- `app/build.gradle.kts`：+`implementation(libs.miuix.blur)`
  - POM 实查：miuix-blur-android → miuix-shader-android + foundation 1.12.0-rc01 + kotlin-stdlib 2.4.10（全对齐）
  - miuix-shader **已随 miuix-ui→miuix-squircle 在 classpath**（squircle POM 实查）→ 实际新增仅 blur AAR 本体

**新增**
- `app/src/main/kotlin/com/bail/lspfrifa/ui/component/UiTokens.kt` —— 设计 token（间距/圆角/字号）+ `SquircleShape`（Path.addSquircleRect 公开 API 构建的 Shape）
- `app/src/main/kotlin/com/bail/lspfrifa/ui/component/GlassSurface.kt` —— `Modifier.glassSurface()`（blur+降级）+ `GlassTopAppBar`

**修改**
- `MainActivity.kt` —— MainScaffold 增 capture 层：`rememberLayerBackdrop()` + 内容 Box `.layerBackdrop(backdrop)`（**不含底栏自身**，防模糊回环）；`FloatingMiuixNavigationBar` 传 backdrop
- `ui/component/MiuixComponents.kt` —— 底栏重写为玻璃胶囊（pill 滑动指示器逻辑/槽位公式不变）；AppIconImage squircle
- `ui/screen/DashboardScreenV093.kt` —— 卡片 22→12dp、卡内 20×18→14×12、字号 19/18→17、副标 12/14→13、信息行间距 18→12、Dialog 卡 22→12/内 20→16
- `ui/screen/ProjectScreenV2.kt` —— 卡片 18→12dp、列表间距 8→12dp、项目名 16→17sp、空态标题 18→17sp
- `ui/screen/ProjectDetailScreenV093.kt` —— **GlassTopAppBar**（透明 TopAppBar + 捕获层 Box，含页面底色防采样透明）；卡片 22/20/20→12dp、头卡内 18×16→14×12、头标 19→17sp
- `ui/screen/LogScreenV1.kt` —— **GlassTopAppBar**；指标/日志卡 18/20→12dp、卡内 15→14×12
- `ui/screen/ScriptEditorScreenV1.kt` —— **GlassTopAppBar**；编辑器卡 20→12dp
- `ui/screen/SettingsScreenV093.kt` —— 两组卡 20→12dp
- `ui/screen/SelectProjectScreenV2.kt` —— 卡片 18→12dp、列表间距 8→12dp、应用名 16→17sp

**未动**：Monet 逻辑、t6 信息架构、注入链、ScriptStore/LogStore、编辑器 TextMate 链路、各页 TopAppBar 大标题内容。

## ③ 三查/双清单（API 实核证据）

### miuix-blur（0.9.4-rc01 sources 实核；Maven Central 2026-08-25 实查）
| API | 实际源码签名 | 调用点 |
|---|---|---|
| `Modifier.textureBlur` | `fun Modifier.textureBlur(backdrop: Backdrop, shape: Shape, blurRadius: Float = BlurDefaults.BlurRadius /*dp*/, noiseCoefficient: Float = BlurDefaults.NoiseCoefficient, colors: BlurColors = BlurColors(), highlight: Highlight? = null, contentBlendMode: BlendMode = SrcOver, enabled: Boolean = true)`（TextureEffect.kt L29） | GlassSurface.kt `textureBlur(backdrop=, shape=, blurRadius=)` ✔ |
| `Modifier.layerBackdrop` / `rememberLayerBackdrop` | `fun Modifier.layerBackdrop(backdrop: LayerBackdrop)`（LayerBackdropModifier.kt L22）；`@Composable fun rememberLayerBackdrop(graphicsLayer: GraphicsLayer = rememberGraphicsLayer(), onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw): LayerBackdrop`（LayerBackdrop.kt L41） | MainActivity / 三屏 `rememberLayerBackdrop()` + `.layerBackdrop(backdrop)` ✔ |
| 门槛 | `isRuntimeShaderSupported() = Build.VERSION.SDK_INT >= TIRAMISU`（miuix-shader RuntimeShader.android.kt L18 实核）；`drawBackdrop` 内 `effectiveEnabled = enabled && isRuntimeShaderSupported()`（DrawBackdropModifier.kt L118）——**API<33 自动降级** | glassSurface 分支调用 ✔ |
| 单位 | blur radius = **dp**（"Internally converted to pixels using display density"，TextureEffect 文档）；`BlurDefaults.BlurRadius = 20f`、`MaxBlurRadius = 150f` | UiTokens.GlassBlurRadius = 20f ✔ |

### miuix-squircle（0.9.4-rc01 sources 实核）
| API | 实际源码签名 | 调用点 |
|---|---|---|
| Card 内部 | `BasicCard` 使用 `.squircleSurface(color = colors.color, cornerRadius = cornerRadius)`（Card.kt L177）——**卡片超椭圆已是 Miuix 默认**，无需改造 | 全部 Card ✔ |
| `Modifier.squircleClip` | `@Composable fun Modifier.squircleClip(cornerRadius: Dp, extension: Float = SquircleDefaults.Extension)`（SquircleBackground.kt L96）；无 shader 时 fallback `RoundedCornerShape` clip（L120） | 底栏 pill / AppIconImage ✔ |
| `Path.addSquircleRect` | `fun Path.addSquircleRect(width: Float, height: Float, cornerRadius: Float, extension: Float = SquircleDefaults.Extension, squircleEnabled: Boolean = true)`（SquirclePath.kt） | UiTokens.SquircleShape.createOutline ✔ |
| `SquircleDefaults` | `Extension = 1.1f`；钳制 [1.0, 2.0] | SquircleShape 默认参数 ✔ |

### material3（1.12.0-alpha03 sources 实核——**已直接依赖**，非新增）
| API | 存在性 | 结论 |
|---|---|---|
| `SegmentedButton` / `SingleChoiceSegmentedButtonRow` / `MultiChoiceSegmentedButtonRow` | SegmentedButton.kt L130/210/255/285/328/364 ✔ | 可用但**未使用**（见下） |
| `ExtendedFloatingActionButton` | FloatingActionButton.kt L593/L863 ✔ | 可用但**未使用** |
| `MaterialTheme` 包装 | ColorScheme.kt 存在；项目未包（grep 零命中）——M3 组件将吃默认 lightColorScheme（灰紫） | **评估结论：成本高、无真实缺口** |

### M3 组件启用评估（任务规格第 4 条）
1. **视觉混合度**：M3 组件颜色来自 `LocalColorScheme`；当前仅在 MainActivity 用 `dynamicLightColorScheme` 取 seed（Monet），**全 App 无 `MaterialTheme` 包装**。若启 M3 组件需包一层 `MaterialTheme(colorScheme = 映射 Miuix scheme 或 dynamicColorScheme)`（约 20 行）+ 逐组件的 shape/border 对齐 Miuix（SupBasic 形态差异：M3 SegmentedButton 为描边分段式，与 Miuix 圆底胶囊语汇不同）。
2. **真实缺口**：参考截图 #2 的分段 tab（插件/分析）意图已被 **t6 信息架构** 替代（详情页条目 + 独立日志页）；详情页"运行/编辑"主操作已有两入口行（脚本编辑/日志），ExtendedFAB 属重复入口；Miuix FloatingActionButton 已在项目页承担"添加"主操作。**本批无真实缺口**。
3. 用户原则（UI-Reference-Comparison.md）：只对齐信息结构与交互意图，不移植 M3 组件样式。
4. **结论：本批不用 M3 组件（保留 Miuix）**。如后续确需分段控件（如日志级别过滤），列为独立任务：`MaterialTheme(colorScheme = ...)` + M3 SegmentedButton（或 Miuix 自绘），需 captain 拍板。

## ④ 缺口/风险标注

**缺口（如实）**
1. **玻璃仅静态页有内容可模糊**：详情/日志/编辑页内容不滚动到 TopAppBar 之下（Column 静态布局），玻璃采样=页面底色+噪声，视觉为"轻雾面板"而非滚动穿透模糊；滚动穿透需把 `padding(padding).top` 移入 LazyColumn contentPadding 改造（三页均非 LazyColumn 主体，收益低，未做）。底栏玻璃则**真采样** Pager 内容（滚动/切页透视）✔。
2. **M3 = 未启用**（结论见上，非缺陷）。
3. **大区域 blur 未做**（性能约束，任务规格禁止）。
4. 详情页"申请作用域/连接状态"行字体 12sp 保留（语义=元信息）。

**风险**
1. **无法本地编译验证**（DSH 环境无 JDK/kotlinc —— 已核 `which java kotlinc` 均无）；所有签名以 sources jar 逐参数对照（上表），但需 RV2IDE 编译一次确认。重点编译点：GlassSurface.kt（textureBlur 具名参数）、UiTokens.kt（Shape 接口协议）、MainLayout 嵌套 Box。
2. **性能**：底栏 blur 每帧录制全屏 GraphicsLayer + 采样；Pager 三页保活 + blur 叠加，中低端机需真机验证。若掉帧：降 `beyondViewportPageCount` 或 blur 半径（已留 token）。
3. **blur AAR minSdk**：miuix-blur POM 未声明额外 minSdk（foundation 1.12 门槛 ≤26 项目基线）；接口层 API<33 全降级，无崩溃路径（RuntimeShader 引用走内部 gate——`createRuntimeShaderEffect` 仅在 `isRuntimeShaderSupported()` 真分支调用，BlurEffect.kt 内同 gate）。
4. **回滚路径**：全部为新增/数值改动，无结构性依赖反转；撤销 = 恢复 build.gradle.kts 一行 + 回退各文件视觉值。
5. 图标 squircle：非小米设备上应用图标本身方形时，12dp squircle 剪裁保留（与原 12dp 圆角剪裁行为一致，仅轮廓曲线不同）。
