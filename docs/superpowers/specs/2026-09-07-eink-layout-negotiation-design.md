# eink 排版参数扩展与宿主协商目录设计

- 日期：2026-09-07
- 分支：md3/port/eink
- 状态：已与用户逐节确认（含两轮修订）
- 关联：`docs/superpowers/specs/2026-08-31-eink-reader-snapshot-render-design.md`（快照桥先行设计）

## 0. 背景与问题

2026-09 上游同步（`1a24157bb`）后，MD3 宿主排版核心重写为 `feature/reader/core`，布局参数体系完整：`ReadBookConfig.Config`（每样式一份 JSON，40+ 布局字段）+ `ReadSettings`（DataStore 全局项）。eink 模块目前仅覆盖其中 17 个字段（正文五标量 + 正文/页眉/页脚四边距）。

三个问题：

1. **未覆盖参数**：标题全套（位置/字号/字体/字重/留白/行距）、字体与字重、页眉页脚几何（显隐/字号/字体/分割线）等。
2. **契约裂缝**：`header/footerPaddingTop/Bottom`（以及页眉页脚字号/字体/分割线/显隐）影响宿主分页预留高度（extent），但 eink 按纯绘制处理（`applyStyleOnly` 不触发重排），且 `ReaderChapterPager.cacheKey` 不含这些键——改后条带高度即时变化，正文坐标仍是旧预留测量的，也不会补排。
3. **协商空白**：eink 与宿主之间无任何能力协商机制，模块嵌入不同版本宿主时无法感知参数可用性与值域。

本设计一次性解决三件事：参数协商契约、参数覆盖扩展（含裂缝根因修复）、排版设置 UI 重组。

## 1. 设计原则（用户确立）

1. **纯效果参数主动忽略**：只影响效果不影响布局的参数（阴影、斜体、颜色、着重线等）不进 eink 界面，保持设置简单。
2. **命名按效果抽象**：eink 参数命名按用户可感知效果，不照搬宿主键名（宿主 `lineSpacingExtra` 存值=倍率×10，eink 表现为「行距 1.2 倍」是既有先例）。

## 2. 决策记录

| 议题 | 决策 |
|---|---|
| 协商形态 | 参数描述符目录：宿主逐参数声明 id/可用性/值域/默认值/是否影响布局；eink 手写呈现层按 id 映射；未知参数隐藏（不做通用行兜底） |
| 页眉页脚 | 几何参数进目录同步宿主（保证 extent 与分页预留一致）；内容固定（页眉=时间+电量，页脚=章节名+页码+进度），不开放槽位配置 |
| 覆盖范围 | 现有 17 参数 + 字体选择（正文/标题/页眉）+ 字重（正文/标题）+ 标题全套 + 页眉页脚几何。不进：双栏、标题分段、对齐两开关、斜体、阴影、颜色、着重线、高亮规则、内容配置 |
| UI 形态 | 面板高频直调（5 滑条不动）+ 一行三文本按钮（字体配置/信息配置/边距调整）+ 三个居中透明弹层；非必要不滚动 |
| 字体默认 | 标题/页眉字体默认「跟随正文」；apply 时桥展开为多键同写同一路径 |
| 字重 | 正文/标题 100..900 可变字重滑条，取代「其它」面板「正文加粗」开关 |

## 3. 协商契约：ReaderStyleCatalog

契约层（`modules/eink/src/main/java/io/legado/app/eink/contract/`）新增：

```kotlin
// ReaderEngine 新增成员（接口默认实现 = 能力降级）
fun styleCatalog(): ReaderStyleCatalog? = null   // null = 旧宿主 → eink 用 FallbackCatalog
fun availableFonts(): List<ReaderFontOption> = emptyList()

class ReaderStyleCatalog(val params: List<ReaderStyleParam>)

sealed interface ReaderStyleParam {
    val id: String                // 稳定语义 id（eink 命名空间），非宿主键名
    val available: Boolean        // 宿主不支持 → eink 隐藏对应 UI 行
    val affectsLayout: Boolean    // 变更后是否必须重新分页（eink 据此路由）
}
data class SteppedParam(          // 档位滑条；量纲随 id 约定（sp/dp/em/倍/行/字）
    override val id: String, override val available: Boolean,
    override val affectsLayout: Boolean,
    val min: Float, val max: Float, val default: Float,
)
data class ChoiceParam(           // 选项类（如下拉/分段）
    override val id: String, override val available: Boolean,
    override val affectsLayout: Boolean,
    val options: List<Option>,    // Option(value: String, label: String)
    val default: String,
)
data class ToggleParam(           // 开关
    override val id: String, override val available: Boolean,
    override val affectsLayout: Boolean,
    val default: Boolean,
)
data class FontParam(             // 字体选择；选项动态来自 availableFonts()，不入目录
    override val id: String, override val available: Boolean,
    override val affectsLayout: Boolean,
)
```

职责边界：**目录只管约束**（可用性、值域、默认值、布局影响），**eink 管呈现**（步进粒度、标签、分组、控件选择，按 id 手写映射）。当前值仍走 `currentStyle()`，写入仍走 `applyStyle()`，三口分离不变。

**类型化并集 + 目录，不引入 `Map<String, Value>`**：`ReaderTextStyle` 新增约 15 个可空字段（null = 不跨桥写、保持宿主值）；编译期安全；未知宿主参数按设计不可见。

**值域规则**：滑条值域以目录为准（eink 移除自有上下限常量 `MIN_/MAX_TEXT_SIZE` 等，正文边距等随目录放宽）；步进粒度与标签格式由 eink 呈现层定（字距 0.05 档、行距 0.1 倍档等）。

### 3.1 降级：FallbackCatalog

旧宿主（`styleCatalog()` 返回 null）→ 模块内置 FallbackCatalog：现有 17 参数全集 + eink 既有值域常量，即今天的完整行为；新增参数行全部隐藏。这是仓库「接口默认方法 = 能力降级」模式（`headerDecorationExtentPx = 0f` 先例）的再次应用。

### 3.2 字体数据

字体列表是动态数据（扫描结果），不走目录，走端口 `availableFonts()`。eink 侧字体取值类型：

```kotlin
sealed interface ReaderFontSelection {
    data object Sans            // 系统无衬线
    data object Serif           // 系统衬线
    data object Mono            // 系统等宽
    data class File(val path: String)
    data object FollowBody      // 跟随正文（仅标题/页眉合法）
}
```

## 4. 参数目录清单

### 4.1 排版面板直调（正文组，现状 5 滑条）

| eink id / 名称 | 控件 | 目录值域（默认） | 宿主落点 | affectsLayout |
|---|---|---|---|---|
| body.size 字号 | 滑条 | 5..50sp（20） | textSize | 是 |
| body.letter-spacing 字距 | 滑条 | −0.5..0.5em（0.1） | letterSpacing | 是 |
| body.indent 缩进 | 滑条 | 0..4字（2） | paragraphIndent | 是 |
| body.line-spacing 行距 | 滑条 | 0..2.0倍（1.2） | lineSpacingExtra | 是 |
| body.paragraph-spacing 段距 | 滑条 | 0..2.0行（0.2） | paragraphSpacing | 是 |

### 4.2 字体配置弹层（单列表统一字体）

| eink id / 名称 | 控件 | 选项/值域（默认） | 宿主落点 | affectsLayout |
|---|---|---|---|---|
| body.font 正文字体 | 列表选择 | 系统预设×3 + 字体文件（系统默认） | textFont / systemTypefaces | 是 |
| body.weight 字重 | 四选+滑条 | 0 常规/1 粗体/2 细体/100..900（500） | textBold | 是 |
| title.font 标题字体 | 列表选择 | +「跟随正文」（跟随正文） | titleFont | 是 |
| title.weight 标题字重 | 四选+滑条 | 0 常规/1 粗体/2 细体/100..900（500） | titleBold | 是 |
| header.font 页眉字体 | 列表选择 | +「跟随正文」（跟随正文） | headerFont | 是 |

页眉无字重键（宿主未提供），故无滑条；页脚不暴露字体（经宿主 `applyHeaderStyle` 默认开传递性跟随页眉）。

title.font/header.font 参数仍在协商目录中，但 eink UI 不再独立暴露（统一字体原则下恒为「跟随正文」）。

### 4.3 信息配置弹层（tabs 标题｜页眉｜页脚）

| eink id / 名称 | 控件 | 值域/选项（默认） | 宿主落点 | affectsLayout |
|---|---|---|---|---|
| title.mode 标题位置 | 三选 | 左/中/隐藏（左） | titleMode | 是 |
| title.size 标题字号 | 滑条 | 8..60sp（20） | titleSize | 是 |
| title.top-spacing 标题上留白 | 滑条 | 0..200dp（0） | titleTopSpacing | 是 |
| title.bottom-spacing 标题下留白 | 滑条 | 0..200dp（0） | titleBottomSpacing | 是 |
| title.line-spacing 标题行距 | 滑条 | 0..2.0倍（1.2） | titleLineSpacingExtra | 是 |
| header.visibility 页眉模式 | 三选 | 随状态栏/显示/隐藏（随状态栏） | headerMode | 是 |
| header.size 页眉字号 | 滑条 | 0..100sp（12） | headerFontSize | 是 |
| header.divider 页眉分割线 | 开关 | （关） | showHeaderLine | 是 |
| footer.visibility 页脚显隐 | 开关 | （开） | footerMode | 是 |
| footer.divider 页脚分割线 | 开关 | （开） | showFooterLine | 是 |

### 4.4 边距调整弹层（现状三 tab，零改动）

| eink id | 值域（默认） | 宿主落点 | affectsLayout |
|---|---|---|---|
| body.padding-top/bottom | 0..200dp（6/6） | paddingTop/Bottom | 是 |
| body.padding-left/right | 0..200dp（16/16） | paddingLeft/Right | 是 |
| header.padding-top/bottom | 0..300dp（0/0） | headerPaddingTop/Bottom | **是**（计入 extent） |
| header.padding-left/right | 0..300dp（16/16） | headerPaddingLeft/Right | **否**（仅条带内部） |
| footer.padding-top/bottom | 0..300dp（6/6） | footerPaddingTop/Bottom | 是 |
| footer.padding-left/right | 0..300dp（16/16） | footerPaddingLeft/Right | 否 |

### 4.5 排除项（明确不进）

斜体、阴影、颜色体系、着重线套件、高亮规则、双栏（doubleHorizontalPage）、标题分段（titleSeg 四件套）、两端对齐/末行分散开关（textFullJustify/textBottomJustify，保持宿主默认开）、页眉页脚内容槽与自定义模板、页脚独立字体字号。「其它」面板「正文加粗」开关移除，由字重滑条取代。

## 5. 值流动与契约修复

### 5.1 部分写入

`applyStyle` 只写目录声明 available 的键；`ReaderTextStyle` 新字段可空，null 键跳过（不触碰宿主值）。存量字段维持整快照写（现状）。

### 5.2 extent 裂缝修复（根因）

- **宿主侧**：`ReaderChapterPager.cacheKey` 补全 extent 影响项：header/footer 字号、字体路径、上下 padding、分割线开关、显隐模式（含 applyHeaderStyle）。
- **eink 侧**：VM 对比新旧 style，变更键中任一 `affectsLayout=true` → 走 200ms 防抖 relayout；仅 `affectsLayout=false` 键（页眉页脚左右边距）变更 → `applyStyleOnly` 即时生效不重排。

### 5.3 标题解钉

移除 `ReaderEngineImpl` 的 TitleSize≡TextSize 钉平（`ReaderEngineImpl.kt:380-384`）。存量配置 titleSize 已被钉写为当前 textSize，解钉无缝；此后 title.size 独立调节。

### 5.4 页眉页脚渲染

**背景机制**：宿主分页时正文可用高度 = 视口 − 正文上下边距 − headerExtent − footerExtent；extent 由宿主按「字号 + 字体度量 + 上下边距 + 分割线」计算，eink 经 `header/footerDecorationExtentPx` 端口只读。eink 在预留条带内本地绘制内容（时间+电量 / 章节名+页码进度）。

**字号确定**：

- **页眉 = 所见即所得**：按配置字号直接渲染（保留像素锚定 `Dp.toPx().toSp()`，不受应用内字体缩放影响），行高 = extent − 上下边距，垂直居中。现行「14/20 行高比反推」（`f4e0383b8`/`491e4a04d`）是宿主字号不可见时代的近似，**退役为兜底**：配置字号的行高放不进可用条带时回落推导，保证不裁字。
- **页脚 = 继续按 extent 推导**：行高 = footerExtent − 上下边距 − 2dp 进度条，字号 = 行高 × 14/20。原因：宿主 `applyHeaderStyle` 允许页脚独立配置字体字号（完整模式用户可能设过），页脚真实有效字号 eink 不可知；但 footerExtent 永远按有效字号的度量计算，从 extent 推导在「跟随页眉/独立配置」两种状态下都正确，默认状态下推导结果 ≈ 页眉字号。

**字体**：页眉/页脚文字按「设置→跟随正文→系统默认」链渲染——经 `headerFooterTypefaces()` 端口由宿主解析并加载 Typeface，模块叠加到 tip 样式的 fontFamily（null 保持模块平台默认）。

**时序**（页眉字号 12→14sp）：抬手 → `applyStyle` 写 headerFontSize=14（affectsLayout=是）→ 桥 mutation 落宿主配置 → `onStyleChanged` → cacheKey（已补全）变化 → 重新分页（200ms 防抖合并）→ 新页就绪：extent 变大、正文行数减少、页眉按 14sp 渲染 → 旧页渲染至新页就绪直接替换，保留阅读位置。

### 5.5 字体跟随语义

「跟随正文」（FollowBody）是 eink 哨兵状态，不落宿主键；`applyStyle` 时桥展开写入：正文选文件 F → `textFont/titleFont/headerFont` 同写 F（即对宿主而言全部配置为同样的字体）。用户在 eink 单独为标题/页眉选了字体后，该处固定、不再跟随。

边界：正文选**系统预设**时无文件路径可写，跟随者写空路径（宿主语义 = 系统默认字体）；实施计划阶段验证 headerFont 键是否接受系统族名，接受则升级为完整跟随。

## 6. UI 设计

### 6.1 排版面板（6 行，稳进 45% 封顶，无需滚动）

```
┌──────────────────────────────┐
│ 字号    [====20====]   − +   │
│ 字距    [==0.10==]     − +   │
│ 缩进    [=2字=]        − +   │
│ 行距    [==1.2倍==]    − +   │
│ 段距    [=0.2行=]      − +   │
│ [字体配置] [信息配置] [边距调整] │  ← 一行三枚文本按钮（新 DS 组件）
└──────────────────────────────┘
```

三入口行为同概念轴切分：**字形选择 / 版面信息 / 间距**。三枚文本按钮复用既有按压反馈配色，触摸目标不小于现状按钮。

### 6.2 三个弹层（居中透明 + 隐藏操作条，边距弹框既有模式）

- **字体配置**：三列网格统一选字体（系统默认/衬线/等宽 + 字体文件），
  正文字重与标题字重各为「细体/常规/粗体/自定义」四选，标签与四枚按钮
  同排一行、文字样式一致（labelLarge），进入自定义按当前档位映射等效值
  （0→400/1→900/2→300，与宿主 resolveWeight 同口径），仅自定义显示
  100..900 滑条；底部全宽字体文件夹按钮（恒为「选择字体文件夹」，选择
  后字体进入上方网格）——统一字体原则，正文/标题/页眉/页脚字体完全
  一致，无页签。
- **信息配置**：tabs 标题｜页眉｜页脚，内容见 4.3。
- **边距调整**：现状三 tab 四边滑条，零改动。

弹层期间顶部/底部被调对象（标题在页面顶部、页眉页脚在上下边缘）不被遮挡，实时预览；返回键逐级回退（弹层 → 面板 → 操作条 → 退出）。

### 6.3 交互（沿用现状）

- 滑条逐档生效 + 200ms 防抖合并 + 重排保留阅读位置。
- 所有滑条按目录默认值显示「默认」标识（`markerStep`，fontScale 设置页先例推广到排版全参数）。
- 值域来自目录、步进与标签来自 eink 呈现层。

## 7. 兼容与迁移

- 旧宿主：`styleCatalog()` 默认 null → FallbackCatalog = 今天的完整行为；字体/字重/标题/页眉页脚行全部隐藏。
- eink 与完整模式共享同一份 ReadBookConfig（eink 改字体/字号在完整模式同样生效，既有共享语义，不改）。
- 存量迁移：titleSize 钉平值无缝衔接；textBold/titleBold 读回保留宿主原始档位（0/1/2 预设不再归一化，完整模式下拉语义一致），写入 0/1/2 直传、其余钳制 100..900；「正文加粗」开关移除后其效果由字重参数承接。

## 8. 测试

- **桥目录映射单测**：宿主配置 → 描述符逐参数断言（值域/默认/available/affectsLayout）。
- **VM 路由单测**：affectsLayout 键变更 → relayout 调度；仅左右边距变更 → applyStyleOnly；null 键跳过。
- **cacheKey 回归**：改页眉字号/上下边距/分割线/显隐必须触发重排（修复前不触发）。
- **字体跟随展开单测**：FollowBody → 三键同写；正文系统预设 → 跟随者写空路径。
- 既有 ReaderTextStyle/分页相关测试保持绿（仓内已知 5 个既有单测失败为本设计范围外基线）。

## 9. 非目标

- 不动宿主完整模式的排版 UI。
- 不做参数双向订阅（eink→宿主单向 `applyStyle`；完整模式侧改动仍靠引擎回调 `syncWithWindow` 兜底对账）。
- 不做未知参数通用行兜底（未知即隐藏）。
- 不做页眉页脚内容配置与自定义模板。

## 10. 涉及范围（文件级）

- `modules/eink/.../contract/`：`ReaderEngine.kt`（+styleCatalog/+availableFonts 默认实现）、`ReaderTextStyle.kt`（+约 15 可空字段 + ReaderFontSelection）、新增 `ReaderStyleCatalog.kt`（目录类型 + FallbackCatalog）、`ReaderFontOption`。
- `modules/eink/.../feature/reader/`：`ReaderMenus.kt`（面板三入口行 + 字体配置/信息配置弹层）、`ReaderViewModel.kt`（目录消费、affectsLayout 路由、默认标识）、`ReaderScreen.kt`（页眉按配置字号渲染）。
- `modules/eink/.../designsystem/`：三入口行组件、字体选择列表弹层。
- `app/.../eink/bridge/`：`ReaderEngineImpl.kt`（目录实现、字体列表、跟随展开、解钉、部分写入）、`ReaderChapterPager.kt`（cacheKey 补全）。
- `modules/eink/src/test/` 与 `app/src/test/`：§8 单测。

## 修订记录

- 2026-09-07 初版：四题澄清（协商形态/页眉页脚/覆盖/UI 形态）+ 两轮修订（字重纳入、字体默认跟随正文、三入口行替代四入口）。
- 2026-09-07 实施修订：§3 目录代码块中 ChoiceParam 的 Option(value: String)/default: String 落地为 Option(value: Int)/default: Int（标题位置与宿主 titleMode 同构，避免字符串往返）；§5.2 裂缝修复实施时追加「分页缓存键直接钉住派生 extent 值（同完整模式键）」作为参数片段之外的结构性闭合（覆盖 hideStatusBar 等非 Config 输入）；§5.4 页眉渲染的门控判据落地为「按构造放得下」（宿主 extent 即按同字号度量预留），兜底仅 null/非正字号。
- 2026-09-07 终审修订：标题/页眉字体选项移除系统预设行（宿主 titleFont/headerFont 键仅收文件路径，空串回落正文/系统默认，预设不可表达且选中不粘）；§5.2 实施对齐：页眉/页脚左右边距为纯绘制参数，移出分页缓存键（避免无谓整章重排）。
- 2026-09-08 修订：页眉显隐升级为三态（随状态栏/显示/隐藏，宿主 HeaderMode 同构）——解除「一经显式设置回不到随状态栏」的单向棘轮，隐藏状态栏开关恢复自动驱动页眉显隐；契约字段 headerVisible:Boolean? 更名 headerMode:Int?。
- 2026-09-08 修订：页眉/页脚字体在 eink 端按「设置→跟随正文→系统默认」链渲染（新端口 headerFooterTypefaces，宿主解析并加载 Typeface）——解除此前"字形仅在完整模式生效"的限制。
- 2026-09-08 修订：字体配置弹层去页签——统一字体原则（正文/标题/页眉/页脚字体完全一致），单列表选字体 + 正文/标题两个字重；VM setReaderFont 一次性写三目标（标题/页眉归位跟随正文），独立 per-target 字体 setter 移除。
- 2026-09-08 修订：字重协议扩为 0/1/2 预设 + 100..900 自定义（宿主同构，读回保留原始档位不再归一化——完整模式下拉语义一致）；字体弹层改三列网格 + 底部全宽文件夹按钮（选择/更新双态）+ 字重四选按钮（细体/常规/粗体/自定义，仅自定义显示滑条）；「系统无衬线」更名「系统默认」、「字重」更名「正文字重」；新端口 fontFolderUri。
- 2026-09-08 修订：字体弹层打磨——字重行标签与四选按钮同排单行、文字样式统一；进入自定义按当前档位映射等效值（不写死 500）；字体网格加顶部呼吸边距；文件夹按钮统一「选择字体文件夹」（fontFolderUri 端口随之移除）。
