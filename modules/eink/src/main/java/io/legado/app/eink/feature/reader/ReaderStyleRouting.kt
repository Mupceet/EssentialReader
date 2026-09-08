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
        Ids.HEADER_FONT to (old.headerFont != new.headerFont),
        Ids.HEADER_VISIBILITY to (old.headerMode != new.headerMode),
        Ids.HEADER_SIZE to (old.headerSize != new.headerSize),
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
