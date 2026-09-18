package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/**
 * 宿主排版参数协商目录：按本宿主 ReadBookConfig/ReadTipConfig 引擎真实
 * 能力逐参数声明（值域/默认值与宿主默认一致），模块据此呈现设置行。
 *
 * 本宿主能力边界（不可用参数直接不进目录，模块隐藏对应设置项）：
 *  - 标题/页眉无独立字体与字重（引擎单字体 + textBold 全局粗细）；
 *  - 标题行距、页眉/页脚字号无配置键；
 *  - 标题字号为「正文 + 增量」模型（引擎 titlePaint = textSize+titleSize），
 *    契约 TITLE_SIZE 的绝对值在 applyStyle/currentStyle 映射期换算；
 *  - 正文字重仅 0 常规/1 粗/2 细三预设（引擎 getPaints）——Presets 档
 *    声明（无自定义滑条；若有自定义值进入 applyStyle 就近量化兜底）。
 *
 * 契约改进已落地（模块 0.6.0）：设置弹层行按目录守卫——本宿主未声明
 * HEADER_SIZE/FOOTER_SIZE（页眉页脚字号行隐藏）与 TITLE_WEIGHT（标题
 * 字重行隐藏；引擎单键耦合无独立标题字重键，直接声明不支持）。
 * 页眉/页脚字号渲染为锁死档（预留带推导 ≈ 12sp，对齐完整模式）。
 *
 * affectsLayout 判定：本宿主 visibleHeight = viewHeight - 上下边距（含
 * E-Ink 装饰预留），页眉/页脚显隐与分割线不改变分页几何（绘制于边距
 * 带内），标 affectsLayout = false；字号/字体/字重/标题参数与四边距均
 * 影响排版。
 */
internal object HostStyleCatalog {

    fun create(): ReaderStyleCatalog = ReaderStyleCatalog(
        listOf(
            stepped(Ids.BODY_SIZE, 8f, 40f, 20f),
            stepped(Ids.BODY_LETTER_SPACING, 0f, 0.5f, 0.1f),
            stepped(Ids.BODY_INDENT, 0f, 4f, 2f),
            stepped(Ids.BODY_LINE_SPACING, 0f, 30f, 12f),
            stepped(Ids.BODY_PARAGRAPH_SPACING, 0f, 10f, 2f),
            ReaderStyleParam.Font(Ids.BODY_FONT, available = true, affectsLayout = true),
            // 正文字重：引擎仅三预设（textBold 0 常规/1 粗/2 细）——预设档
            // 声明，无自定义滑条（allowCustom = false 隐藏「自定义」按钮）
            ReaderStyleParam.Presets(
                id = Ids.BODY_WEIGHT,
                available = true,
                affectsLayout = true,
                default = 0,
            ),
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
            stepped(Ids.TITLE_TOP_SPACING, 0f, 48f, 0f),
            stepped(Ids.TITLE_BOTTOM_SPACING, 0f, 48f, 0f),
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
            ReaderStyleParam.Toggle(
                id = Ids.FOOTER_VISIBILITY,
                available = true,
                affectsLayout = false,
                default = true,
            ),
            ReaderStyleParam.Toggle(
                id = Ids.HEADER_DIVIDER,
                available = true,
                affectsLayout = false,
                default = false,
            ),
            ReaderStyleParam.Toggle(
                id = Ids.FOOTER_DIVIDER,
                available = true,
                affectsLayout = false,
                default = true,
            ),
            stepped(Ids.BODY_PADDING_TOP, 0f, 48f, 6f),
            stepped(Ids.BODY_PADDING_BOTTOM, 0f, 48f, 6f),
            stepped(Ids.BODY_PADDING_LEFT, 0f, 64f, 16f),
            stepped(Ids.BODY_PADDING_RIGHT, 0f, 64f, 16f),
            stepped(Ids.HEADER_PADDING_TOP, 0f, 48f, 0f),
            stepped(Ids.HEADER_PADDING_BOTTOM, 0f, 48f, 0f),
            paintOnly(Ids.HEADER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(Ids.HEADER_PADDING_RIGHT, 0f, 48f, 16f),
            stepped(Ids.FOOTER_PADDING_TOP, 0f, 48f, 6f),
            stepped(Ids.FOOTER_PADDING_BOTTOM, 0f, 48f, 6f),
            paintOnly(Ids.FOOTER_PADDING_LEFT, 0f, 48f, 16f),
            paintOnly(Ids.FOOTER_PADDING_RIGHT, 0f, 48f, 16f),
        )
    )

    private fun stepped(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = true, min, max, default)

    private fun paintOnly(id: String, min: Float, max: Float, default: Float) =
        ReaderStyleParam.Stepped(id, available = true, affectsLayout = false, min, max, default)
}
