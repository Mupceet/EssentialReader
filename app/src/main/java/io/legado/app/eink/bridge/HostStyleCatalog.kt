package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/**
 * 宿主排版参数目录：当前 ReadBookConfig 引擎约束的完整声明，
 * 值域/默认值与宿主排版设置 UI 同源。
 *
 * affectsLayout 判定：页眉/页脚字号/字体/上下边距/显隐/分隔线经
 * extent（LegacyReaderPageDecorationFactory 按字体度量推导分页预留
 * 高度，分隔线厚度计入带内）影响正文分页；左右边距仅条带内部布局。
 * 正文斜体标 affectsLayout = true 是快照固化语义（skew 随 spec 分页期
 * 固化，变更需重建页快照；几何本身不受 skew 影响）。
 *
 * 页眉/页脚分隔线 0.8.2 起恢复声明与绘制（2026-11-02 停绘决策撤销）：
 * 模块两渲染面（阅读页 tip 行与屏保）按快照样式画线，几何此前已按
 * 宿主配置预留——恢复仅放开 snapshotStyle 的恒 false。页脚字体
 * （FOOTER_FONT）值通道先行、目录行待后续独立配置落地时一并声明。
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
            // 正文斜体（0.8.2 起支持：skew 经 spec 跨桥；affectsLayout 语义见类注释）
            toggle(Ids.BODY_ITALIC, default = false),
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
            // 标题分段（0.8.1 参数总账补全）：引擎排版级特性，eink 侧标题
            // 元素自动呈现；delimiter 为文本参数无目录控件类型，仅快照通道
            ReaderStyleParam.Choice(
                id = Ids.TITLE_SEGMENT_MODE,
                available = true,
                affectsLayout = true,
                options = listOf(
                    ReaderStyleParam.Choice.Option(0, "关闭"),
                    ReaderStyleParam.Choice.Option(1, "按位置"),
                    ReaderStyleParam.Choice.Option(2, "按符号"),
                    ReaderStyleParam.Choice.Option(3, "按正则"),
                ),
                default = 0,
            ),
            // 分段位置 min=1 与宿主 UI（TypographyTabs 1..20）同源：引擎对
            // 0 走不分割分支，0 档语义困惑（模式开着却不分割）不提供
            stepped(Ids.TITLE_SEGMENT_DISTANCE, 1f, 20f, 4f),
            // 缩放下限 0.5 为 eink 侧刻意收紧（宿主 UI 0..2）：低于 0.5 的
            // 副标题趋近不可见，非同源项已知差异
            stepped(Ids.TITLE_SEGMENT_SCALE, 0.5f, 2f, 1f),
            // 副标题间距（0.8.3 勘误入账）：与宿主「副标题间距」滑条同源
            //（-30..30，引擎按标题字高 × 值/10 计入分段行距）
            stepped(Ids.TITLE_SEGMENT_SPACING, -30f, 30f, 0f),
            // 整页文字下划线族（0.8.1 参数总账补全）：引擎以页级 Rule 元素
            // 产出、经页快照 ruleLines 跨桥，eink 直接渲染；dashed 当前被
            // 排版层对 E-Ink 会话硬门控为实线（值通道先行声明）
            toggle(Ids.BODY_UNDERLINE, default = false),
            stepped(Ids.BODY_UNDERLINE_WIDTH, 0f, 20f, 1f),
            stepped(Ids.BODY_UNDERLINE_OFFSET, 0f, 20f, 10f),
            toggle(Ids.BODY_UNDERLINE_EXTEND, default = false),
            toggle(Ids.BODY_UNDERLINE_DASHED, default = false),
            stepped(Ids.BODY_UNDERLINE_DASH_ON, 0f, 20f, 6f),
            stepped(Ids.BODY_UNDERLINE_DASH_OFF, 0f, 20f, 6f),
            ReaderStyleParam.Font(Ids.HEADER_FONT, available = true, affectsLayout = true),
            ReaderStyleParam.Choice(
                id = Ids.HEADER_MODE,
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
            // 分隔线 0.8.2 起恢复声明与绘制（停绘决策撤销，见类注释）
            toggle(Ids.HEADER_DIVIDER, default = false),
            toggle(Ids.FOOTER_DIVIDER, default = true),
            toggle(Ids.FOOTER_VISIBILITY, default = true),
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
