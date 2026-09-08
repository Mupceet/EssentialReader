package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/**
 * 宿主排版参数目录：当前 ReadBookConfig 引擎约束的完整声明，
 * 值域/默认值与宿主排版设置 UI 同源。
 *
 * affectsLayout 判定：页眉/页脚字号/字体/上下边距/分割线/显隐经
 * extent（LegacyReaderPageDecorationFactory 按字体度量推导分页预留
 * 高度）影响正文分页；左右边距仅条带内部布局。
 */
internal object HostStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(Ids.BODY_SIZE, 5f, 50f, 20f),
            stepped(Ids.BODY_LETTER_SPACING, -0.5f, 0.5f, 0.1f),
            stepped(Ids.BODY_INDENT, 0f, 4f, 2f),
            stepped(Ids.BODY_LINE_SPACING, 0f, 20f, 12f),
            stepped(Ids.BODY_PARAGRAPH_SPACING, 0f, 20f, 2f),
            ReaderStyleParam.Font(Ids.BODY_FONT, available = true, affectsLayout = true),
            // 字重协议域另含 0 常规/1 粗/2 细预设（模块 UI 按钮直写，宿主
            // 同构）；此处目录声明自定义滑条区间与默认值
            stepped(Ids.BODY_WEIGHT, 100f, 900f, 500f),
            ReaderStyleParam.Font(Ids.TITLE_FONT, available = true, affectsLayout = true),
            stepped(Ids.TITLE_WEIGHT, 100f, 900f, 500f),
            ReaderStyleParam.Choice(
                id = Ids.TITLE_MODE,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "居左"),
                    ReaderStyleParam.Choice.Option(1, "居中"),
                    ReaderStyleParam.Choice.Option(2, "隐藏"),
                ),
                default = 0,
            ),
            stepped(Ids.TITLE_SIZE, 8f, 60f, 20f),
            stepped(Ids.TITLE_TOP_SPACING, 0f, 200f, 0f),
            stepped(Ids.TITLE_BOTTOM_SPACING, 0f, 200f, 0f),
            stepped(Ids.TITLE_LINE_SPACING, 0f, 20f, 12f),
            ReaderStyleParam.Font(Ids.HEADER_FONT, available = true, affectsLayout = true),
            ReaderStyleParam.Choice(
                id = Ids.HEADER_VISIBILITY,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "随状态栏"),
                    ReaderStyleParam.Choice.Option(1, "显示"),
                    ReaderStyleParam.Choice.Option(2, "隐藏"),
                ),
                default = 0,
            ),
            stepped(Ids.HEADER_SIZE, 0f, 100f, 12f),
            stepped(Ids.FOOTER_SIZE, 0f, 100f, 12f),
            toggle(Ids.HEADER_DIVIDER, default = false),
            toggle(Ids.FOOTER_VISIBILITY, default = true),
            toggle(Ids.FOOTER_DIVIDER, default = true),
            stepped(Ids.BODY_PADDING_TOP, 0f, 200f, 6f),
            stepped(Ids.BODY_PADDING_BOTTOM, 0f, 200f, 6f),
            stepped(Ids.BODY_PADDING_LEFT, 0f, 200f, 16f),
            stepped(Ids.BODY_PADDING_RIGHT, 0f, 200f, 16f),
            stepped(Ids.HEADER_PADDING_TOP, 0f, 300f, 0f),
            stepped(Ids.HEADER_PADDING_BOTTOM, 0f, 300f, 0f),
            paintOnly(Ids.HEADER_PADDING_LEFT, 0f, 300f, 16f),
            paintOnly(Ids.HEADER_PADDING_RIGHT, 0f, 300f, 16f),
            stepped(Ids.FOOTER_PADDING_TOP, 0f, 300f, 6f),
            stepped(Ids.FOOTER_PADDING_BOTTOM, 0f, 300f, 6f),
            paintOnly(Ids.FOOTER_PADDING_LEFT, 0f, 300f, 16f),
            paintOnly(Ids.FOOTER_PADDING_RIGHT, 0f, 300f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)

    private fun toggle(id: String, default: Boolean) =
        ReaderStyleParam.Toggle(id, available = true, affectsLayout = true, default)
}
