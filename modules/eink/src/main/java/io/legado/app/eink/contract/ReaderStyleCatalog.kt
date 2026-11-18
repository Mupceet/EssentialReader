package io.legado.app.eink.contract

/**
 * 排版参数协商目录：宿主逐参数声明「可用性 + 值域 + 默认值 + 是否影响
 * 分页」，模块按 id 手写呈现（标签/步进/分组/控件）。
 *
 * 职责边界：目录只管约束；当前值仍经 ReaderEngine.currentStyle() 读、
 * applyStyle() 写，三口分离。宿主不支持 styleCatalog() 时模块回落
 * [FallbackReaderStyleCatalog]（= 模块历代内置行为）。
 */
sealed interface ReaderStyleParam {
    /** 稳定语义 id（模块命名空间，非宿主配置键名；跨版本不变）。 */
    val id: String

    /** 宿主不支持该参数时模块隐藏对应设置项。 */
    val available: Boolean

    /** 变更后是否必须重新分页（模块据此决定是否走重排路径）。 */
    val affectsLayout: Boolean

    /** 档位滑条参数；量纲随 id 约定（sp/dp/em/倍/行/字/字重）。 */
    data class Stepped(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val min: Float,
        val max: Float,
        val default: Float,
    ) : ReaderStyleParam

    /** 选项参数（如标题位置；value 用宿主同构的 Int）。 */
    data class Choice(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val options: List<Option>,
        /** 默认值；为选项 [Choice.Option.value]（非下标）。 */
        val default: Int,
    ) : ReaderStyleParam {
        data class Option(val value: Int, val label: String)
    }

    /** 开关参数。 */
    data class Toggle(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        val default: Boolean,
    ) : ReaderStyleParam

    /** 字体选择参数；选项动态来自 ReaderEngine.availableFonts()，不入目录。 */
    data class Font(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
    ) : ReaderStyleParam

    /**
     * 固定值参数：宿主锁定该参数（仅单一值可用、其余档位不支持——如引擎
     * 单键耦合下标题字重恒粗体的宿主）。UI 呈现置灰锁定态（值可见、不可
     * 调）；宿主也可选择不声明本参数使设置行整体隐藏。
     *
     * 消费范围：仅数值参数（[Stepped] 同族 id，[value] 量纲随 id 约定）；
     * 枚举/开关参数的「锁定」用 [Choice] 单选项或不声明表达，不使用本类型。
     */
    data class Locked(
        override val id: String,
        /** 锁定值（量纲随 id 约定）。 */
        val value: Float,
    ) : ReaderStyleParam {
        override val available: Boolean get() = true
        override val affectsLayout: Boolean get() = false
    }

    /**
     * 预设档参数：宿主仅支持协议约定的固定档位集，无连续自定义——UI
     * 呈现预设按钮组、隐藏自定义滑条入口（对比 [Stepped] 声明的自定义
     * 滑条区间）。
     *
     * 协议域：当前仅字重两 id（[ReaderStyleParamIds.BODY_WEIGHT] /
     * [ReaderStyleParamIds.TITLE_WEIGHT]）定义预设协议——档位 0 常规 /
     * 1 粗 / 2 细（同 [ReaderTextStyle.bodyWeight] 注释），[default] 取
     * 档位值。其余 id 声明 Presets 属未定义呈现，适配方不得依赖；枚举
     * 档参数用 [Choice] 表达。
     */
    data class Presets(
        override val id: String,
        override val available: Boolean,
        override val affectsLayout: Boolean,
        /** 默认档（协议约定域内的值）。 */
        val default: Int,
    ) : ReaderStyleParam
}

class ReaderStyleCatalog(val params: List<ReaderStyleParam>) {
    fun find(id: String): ReaderStyleParam? = params.firstOrNull { it.id == id }
}

/**
 * 稳定语义 id：模块命名空间；宿主实现把它映射到自身配置键。
 *
 * ## 参数总账（0.8.1 起声明完备）
 *
 * 本注册表覆盖宿主排版引擎的**全部输入参数**，分三组声明：
 *
 * **① 目录协商参数**——有 id + 宿主目录行 + [ReaderTextStyle] 快照字段
 * （currentStyle 读 / applyStyle 写），模块按目录呈现：
 * 正文五标量/字体/字重/四边距、标题字体/字重/位置/字号/上下留白/行距/
 * 分段（模式/位置/缩放）、页眉字体/模式/字号、页脚字号/显隐、页眉页脚
 * 四边距，及**整页文字下划线族**（总开关/线粗/间距/延伸/虚线开关/虚线
 * 墨长/虚线空长——引擎以页级 Rule 元素产出，经页快照 ruleLines 跨桥，
 * eink 直接渲染）。
 *
 * **② 仅快照通道参数**——有 id + 快照字段、无目录行（呈现待模块 UI 排期）：
 * [TITLE_SEGMENT_DELIMITER]（文本参数，目录控件类型无 String 档）。
 *
 * **③ 刻意排除参数**——宿主引擎/配置存在、eink 契约面**有意不设通道**，
 * 逐项声明处置（新参数入账前先对照本清单选边）：
 *  - `body.italic`（正文斜体）、`shadow 族`（阴影开关/半径/偏移/颜色）：
 *    E-Ink 不渲染（[ReaderPaintSpec] 刻意排除项；高亮规则的合成斜体经
 *    逐段覆盖例外跨桥）；
 *  - 颜色族（正文/标题/强调/页眉页脚/分隔线/下划线颜色，背景色/背景图/
 *    背景透明度）：eink 黑白主题立场（正文色模块自涂）；**装饰性**颜色
 *    （下划线/高亮/规则色）由排版产物以 BT.709 灰度自动携带，不经参数面；
 *  - [HEADER_DIVIDER] / [FOOTER_DIVIDER]：id 保留占位，eink 停绘分隔线
 *    （布局仍按宿主配置预留厚度，分页几何两模式一致）；
 *  - `footer.font` / `footer-follow-header`（宿主 applyHeaderStyle）：
 *    eink 统一字体原则——页脚字体跟随页眉、不单独设置；
 *  - `title.size-relative`：eink 以「titleSize == textSize 即随正文」表达；
 *  - `title.line-spacing-sub`：宿主遗留键，排版核心测量未消费；
 *  - 翻页动画族 / 状态栏图标 / 菜单外观：非排版参数，不入账。
 *
 * ## 高亮规则样式词汇表（per-rule，非全局设置参数）
 *
 * 替换/高亮规则实体自带的样式参数经**排版产物**承载（不经本目录）：
 * 线种/线宽/偏移/虚线节距/波浪参数/双线间距 → [ReaderUnderlineGeometry]；
 * 文字色/背景色/线色 → 灰度跨桥（[ReaderDecorationRun]）；字体/字号偏移/
 * 字重/斜体 → [ReaderChunkPaintOverride]。**不跨桥**（既有取舍）：边框族
 * （颜色/粗细/圆角）、背景图族（bgImage 及九宫格参数）、SVG 花色线
 * （underlineMode 5 降级实线）——引擎把背景框扩张计入元素 bounds，故
 * 缺席仅影响绘制、不影响两模式坐标一致性。
 */
object ReaderStyleParamIds {
    const val BODY_SIZE = "body.size"
    const val BODY_LETTER_SPACING = "body.letter-spacing"
    const val BODY_INDENT = "body.indent"
    const val BODY_LINE_SPACING = "body.line-spacing"
    const val BODY_PARAGRAPH_SPACING = "body.paragraph-spacing"
    const val BODY_FONT = "body.font"
    const val BODY_WEIGHT = "body.weight"
    const val BODY_PADDING_TOP = "body.padding-top"
    const val BODY_PADDING_BOTTOM = "body.padding-bottom"
    const val BODY_PADDING_LEFT = "body.padding-left"
    const val BODY_PADDING_RIGHT = "body.padding-right"

    /** 整页文字下划线总开关（引擎逐行产出 Rule 元素，经 ruleLines 跨桥）。 */
    const val BODY_UNDERLINE = "body.underline"

    /** 下划线线粗（px）。 */
    const val BODY_UNDERLINE_WIDTH = "body.underline-width"

    /** 下划线与文字的间距基准（宿主 offset = 值 − 10 的 dp；10 = 无偏移）。 */
    const val BODY_UNDERLINE_OFFSET = "body.underline-offset"

    /** 下划线延伸至栏宽。 */
    const val BODY_UNDERLINE_EXTEND = "body.underline-extend"

    /** 虚线下划线开关（宿主排版层当前对 E-Ink 会话恒输出实线——既有硬门控，值通道先行声明）。 */
    const val BODY_UNDERLINE_DASHED = "body.underline-dashed"

    /** 虚线墨长（px，宿主 dottedBase）。 */
    const val BODY_UNDERLINE_DASH_ON = "body.underline-dash-on"

    /** 虚线空长（px，宿主 dottedRatio——宿主键名 ratio 但实为空长绝对值）。 */
    const val BODY_UNDERLINE_DASH_OFF = "body.underline-dash-off"
    const val TITLE_FONT = "title.font"
    const val TITLE_WEIGHT = "title.weight"
    const val TITLE_MODE = "title.mode"
    const val TITLE_SIZE = "title.size"
    const val TITLE_TOP_SPACING = "title.top-spacing"
    const val TITLE_BOTTOM_SPACING = "title.bottom-spacing"
    const val TITLE_LINE_SPACING = "title.line-spacing"

    /** 标题分段模式（0 关闭 / 1 按位置 / 2 按符号 / 3 按正则，宿主同构）。 */
    const val TITLE_SEGMENT_MODE = "title.segment-mode"

    /** 分段判断位置（第几个字符开始分段）。 */
    const val TITLE_SEGMENT_DISTANCE = "title.segment-distance"

    /** 分段判断符/正则（文本参数：仅快照通道，目录无 String 控件类型、不声明协商行）。 */
    const val TITLE_SEGMENT_DELIMITER = "title.segment-delimiter"

    /** 副标题字号缩放（第二段与第一段的字体大小比例）。 */
    const val TITLE_SEGMENT_SCALE = "title.segment-scale"
    const val HEADER_FONT = "header.font"
    /** 0.8.0 起由 header.visibility 更名——对齐快照字段 headerMode（仅常量名与 id 串变更，语义不变）。 */
    const val HEADER_MODE = "header.mode"
    const val HEADER_SIZE = "header.size"
    const val FOOTER_SIZE = "footer.size"
    const val HEADER_DIVIDER = "header.divider"
    const val HEADER_PADDING_TOP = "header.padding-top"
    const val HEADER_PADDING_BOTTOM = "header.padding-bottom"
    const val HEADER_PADDING_LEFT = "header.padding-left"
    const val HEADER_PADDING_RIGHT = "header.padding-right"
    const val FOOTER_VISIBILITY = "footer.visibility"
    const val FOOTER_DIVIDER = "footer.divider"
    const val FOOTER_PADDING_TOP = "footer.padding-top"
    const val FOOTER_PADDING_BOTTOM = "footer.padding-bottom"
    const val FOOTER_PADDING_LEFT = "footer.padding-left"
    const val FOOTER_PADDING_RIGHT = "footer.padding-right"
}

/**
 * 内置回落目录：宿主未提供 styleCatalog()（旧宿主）时的模块基线——
 * 仅历代已支持的 17 个参数（正文五标量 + 三组四边距），值域沿用模块
 * 既有常量。新参数（字体/字重/标题/页眉页脚信息）不在其中，即旧宿主
 * 下对应设置项全部隐藏。
 *
 * 页眉/页脚上下边距标 affectsLayout = true：宿主把它们计入分页预留
 * 高度（extent），与左右边距（仅条带内部）不同。
 */
object FallbackReaderStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(ReaderStyleParamIds.BODY_SIZE, 8f, 40f, 20f),
            stepped(ReaderStyleParamIds.BODY_LETTER_SPACING, 0f, 0.5f, 0.1f),
            stepped(ReaderStyleParamIds.BODY_INDENT, 0f, 4f, 2f),
            stepped(ReaderStyleParamIds.BODY_LINE_SPACING, 0f, 30f, 12f),
            stepped(ReaderStyleParamIds.BODY_PARAGRAPH_SPACING, 0f, 10f, 2f),
            stepped(ReaderStyleParamIds.BODY_PADDING_TOP, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.BODY_PADDING_BOTTOM, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.BODY_PADDING_LEFT, 0f, 64f, 16f),
            stepped(ReaderStyleParamIds.BODY_PADDING_RIGHT, 0f, 64f, 16f),
            stepped(ReaderStyleParamIds.HEADER_PADDING_TOP, 0f, 48f, 0f),
            stepped(ReaderStyleParamIds.HEADER_PADDING_BOTTOM, 0f, 48f, 0f),
            paintOnly(ReaderStyleParamIds.HEADER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(ReaderStyleParamIds.HEADER_PADDING_RIGHT, 0f, 48f, 16f),
            stepped(ReaderStyleParamIds.FOOTER_PADDING_TOP, 0f, 48f, 6f),
            stepped(ReaderStyleParamIds.FOOTER_PADDING_BOTTOM, 0f, 48f, 6f),
            paintOnly(ReaderStyleParamIds.FOOTER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(ReaderStyleParamIds.FOOTER_PADDING_RIGHT, 0f, 48f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)
}
