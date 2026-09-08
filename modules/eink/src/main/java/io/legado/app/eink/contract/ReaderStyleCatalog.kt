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
}

class ReaderStyleCatalog(val params: List<ReaderStyleParam>) {
    fun find(id: String): ReaderStyleParam? = params.firstOrNull { it.id == id }
}

/** 稳定语义 id：模块命名空间；宿主实现把它映射到自身配置键。 */
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
    const val TITLE_FONT = "title.font"
    const val TITLE_WEIGHT = "title.weight"
    const val TITLE_MODE = "title.mode"
    const val TITLE_SIZE = "title.size"
    const val TITLE_TOP_SPACING = "title.top-spacing"
    const val TITLE_BOTTOM_SPACING = "title.bottom-spacing"
    const val TITLE_LINE_SPACING = "title.line-spacing"
    const val HEADER_FONT = "header.font"
    const val HEADER_VISIBILITY = "header.visibility"
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
