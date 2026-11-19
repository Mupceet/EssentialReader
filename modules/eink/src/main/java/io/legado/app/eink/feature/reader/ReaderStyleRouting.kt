package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderStyleCatalog
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import io.legado.app.eink.contract.ReaderTextStyle

/**
 * 排版参数变更 → 是否需要重新分页：对比新旧快照，任一「已变化且目录
 * 标记 affectsLayout」的参数即重排。目录中不存在的参数保守视为需要
 * 重排。字段→id 映射与目录同源，新增参数两处同步。
 */
internal fun styleChangeNeedsRelayout(
    catalog: ReaderStyleCatalog,
    old: ReaderTextStyle,
    new: ReaderTextStyle,
): Boolean {
    val diffs = listOf(
        Ids.BODY_SIZE to (old.textSize != new.textSize),
        Ids.BODY_LETTER_SPACING to (old.letterSpacing != new.letterSpacing),
        Ids.BODY_INDENT to (old.indentChars != new.indentChars),
        Ids.BODY_LINE_SPACING to (old.lineSpacing != new.lineSpacing),
        Ids.BODY_PARAGRAPH_SPACING to (old.paragraphSpacing != new.paragraphSpacing),
        Ids.BODY_FONT to (old.bodyFont != new.bodyFont),
        Ids.BODY_WEIGHT to (old.bodyWeight != new.bodyWeight),
        Ids.BODY_PADDING_TOP to (old.paddingTop != new.paddingTop),
        Ids.BODY_PADDING_BOTTOM to (old.paddingBottom != new.paddingBottom),
        Ids.BODY_PADDING_LEFT to (old.paddingLeft != new.paddingLeft),
        Ids.BODY_PADDING_RIGHT to (old.paddingRight != new.paddingRight),
        Ids.TITLE_FONT to (old.titleFont != new.titleFont),
        Ids.TITLE_WEIGHT to (old.titleWeight != new.titleWeight),
        Ids.TITLE_MODE to (old.titleMode != new.titleMode),
        Ids.TITLE_SIZE to (old.titleSize != new.titleSize),
        Ids.TITLE_TOP_SPACING to (old.titleTopSpacing != new.titleTopSpacing),
        Ids.TITLE_BOTTOM_SPACING to (old.titleBottomSpacing != new.titleBottomSpacing),
        Ids.TITLE_LINE_SPACING to (old.titleLineSpacing != new.titleLineSpacing),
        Ids.TITLE_SEGMENT_MODE to (old.titleSegmentMode != new.titleSegmentMode),
        Ids.TITLE_SEGMENT_DISTANCE to (old.titleSegmentDistance != new.titleSegmentDistance),
        Ids.TITLE_SEGMENT_DELIMITER to (old.titleSegmentDelimiter != new.titleSegmentDelimiter),
        Ids.TITLE_SEGMENT_SCALE to (old.titleSegmentScale != new.titleSegmentScale),
        // 0.8.3 勘误入账：副标题间距计入分段行距（标题字高 × 值/10），重排路径
        Ids.TITLE_SEGMENT_SPACING to (old.titleSegmentSpacing != new.titleSegmentSpacing),
        Ids.BODY_UNDERLINE to (old.bodyUnderline != new.bodyUnderline),
        Ids.BODY_UNDERLINE_WIDTH to (old.bodyUnderlineWidth != new.bodyUnderlineWidth),
        Ids.BODY_UNDERLINE_OFFSET to (old.bodyUnderlineOffset != new.bodyUnderlineOffset),
        Ids.BODY_UNDERLINE_EXTEND to (old.bodyUnderlineExtend != new.bodyUnderlineExtend),
        Ids.BODY_UNDERLINE_DASHED to (old.bodyUnderlineDashed != new.bodyUnderlineDashed),
        Ids.BODY_UNDERLINE_DASH_ON to (old.bodyUnderlineDashOn != new.bodyUnderlineDashOn),
        Ids.BODY_UNDERLINE_DASH_OFF to (old.bodyUnderlineDashOff != new.bodyUnderlineDashOff),
        // 0.8.2：斜体变更需重建页快照（spec 分页期固化）；页脚字体同（快照
        // 经 headerFooterTypefaces 消费）——均标 affectsLayout 走重排路径
        Ids.BODY_ITALIC to (old.bodyItalic != new.bodyItalic),
        Ids.FOOTER_FONT to (old.footerFont != new.footerFont),
        Ids.HEADER_FONT to (old.headerFont != new.headerFont),
        Ids.HEADER_MODE to (old.headerMode != new.headerMode),
        Ids.HEADER_SIZE to (old.headerSize != new.headerSize),
        Ids.FOOTER_SIZE to (old.footerSize != new.footerSize),
        Ids.HEADER_DIVIDER to (old.headerDivider != new.headerDivider),
        Ids.HEADER_PADDING_TOP to (old.headerPaddingTop != new.headerPaddingTop),
        Ids.HEADER_PADDING_BOTTOM to (old.headerPaddingBottom != new.headerPaddingBottom),
        Ids.HEADER_PADDING_LEFT to (old.headerPaddingLeft != new.headerPaddingLeft),
        Ids.HEADER_PADDING_RIGHT to (old.headerPaddingRight != new.headerPaddingRight),
        Ids.FOOTER_VISIBILITY to (old.footerVisible != new.footerVisible),
        Ids.FOOTER_DIVIDER to (old.footerDivider != new.footerDivider),
        Ids.FOOTER_PADDING_TOP to (old.footerPaddingTop != new.footerPaddingTop),
        Ids.FOOTER_PADDING_BOTTOM to (old.footerPaddingBottom != new.footerPaddingBottom),
        Ids.FOOTER_PADDING_LEFT to (old.footerPaddingLeft != new.footerPaddingLeft),
        Ids.FOOTER_PADDING_RIGHT to (old.footerPaddingRight != new.footerPaddingRight),
    )
    return diffs.any { (id, differs) -> differs && (catalog.find(id)?.affectsLayout ?: true) }
}
