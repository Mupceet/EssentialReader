package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids

/** 归一弹框聚合轴：按功能（Tab 切换）/ 按区域（分组全景 + 锚点直达）。 */
internal enum class StyleAxis { FUNCTION, REGION }

/** 功能轴 Tab（三入口直达目标）。 */
internal enum class StyleFunctionTab(val displayLabel: String) {
    FONTS("字体"),
    INFO("信息"),
    MARGIN("边距"),
}

/** 区域轴分组（spec §4.3 行清单归组）。 */
internal enum class StyleRegionGroup(val displayLabel: String) {
    BODY("正文"),
    TITLE("标题"),
    HEADER("页眉"),
    FOOTER("页脚"),
}

/**
 * 区域分组可见性：组内任一参数行可用即可见（锚点与组同步隐藏，spec §4.3）。
 * 行→参数 id 对应 spec §4.3 现状行清单。
 */
internal fun regionGroupVisible(catalog: ReaderStyleCatalog, group: StyleRegionGroup): Boolean {
    val ids = when (group) {
        // 五滑条 + 正文四边距
        StyleRegionGroup.BODY -> listOf(
            Ids.BODY_SIZE, Ids.BODY_LETTER_SPACING, Ids.BODY_INDENT,
            Ids.BODY_LINE_SPACING, Ids.BODY_PARAGRAPH_SPACING,
            Ids.BODY_PADDING_TOP, Ids.BODY_PADDING_BOTTOM,
            Ids.BODY_PADDING_LEFT, Ids.BODY_PADDING_RIGHT,
        )
        // 标题位置 + 标题字号
        StyleRegionGroup.TITLE -> listOf(Ids.TITLE_MODE, Ids.TITLE_SIZE)
        // 页眉显示 + 页眉页脚字号 + 页眉四边距
        StyleRegionGroup.HEADER -> listOf(
            Ids.HEADER_MODE, Ids.HEADER_SIZE,
            Ids.HEADER_PADDING_TOP, Ids.HEADER_PADDING_BOTTOM,
            Ids.HEADER_PADDING_LEFT, Ids.HEADER_PADDING_RIGHT,
        )
        // 页脚显示 + 页眉页脚字号 + 页脚四边距
        StyleRegionGroup.FOOTER -> listOf(
            Ids.FOOTER_VISIBILITY, Ids.FOOTER_SIZE,
            Ids.FOOTER_PADDING_TOP, Ids.FOOTER_PADDING_BOTTOM,
            Ids.FOOTER_PADDING_LEFT, Ids.FOOTER_PADDING_RIGHT,
        )
    }
    return ids.any { catalog.available(it) }
}
