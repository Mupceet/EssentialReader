package io.legado.app.eink.bridge

import io.legado.app.domain.gateway.ReadStyleBooleanKey
import io.legado.app.domain.gateway.ReadStyleFloatKey
import io.legado.app.domain.gateway.ReadStyleIntKey
import io.legado.app.domain.gateway.ReadStyleMutation
import io.legado.app.domain.gateway.ReadStyleStringKey
import io.legado.app.eink.contract.ReaderFontSelection
import io.legado.app.eink.contract.ReaderTextStyle

/** 段首缩进展开字符（宿主引擎常量，与完整模式一致）。 */
internal const val INDENT_CHAR = "　"

/**
 * ReaderTextStyle → 宿主配置 mutation 列表（纯函数，applyStyle 消费）。
 *
 * 部分写入：可空扩展字段为 null 时跳过对应键（不触碰宿主值）。
 * 字体跟随：FollowBody 展开为正文当前有效路径——本次快照已带正文字体
 * 用之，否则用宿主当前值；正文为系统预设时无路径可写，回落空串
 * （宿主语义 = 系统默认字体；标题键空串原生回落正文字体）。
 * 正文字体带 FollowBody 属非法态，按系统预设处理（写空路径）。
 */
internal fun buildStyleMutations(
    style: ReaderTextStyle,
    currentBodyFontPath: String,
): List<ReadStyleMutation> = buildList {
    fun int(key: ReadStyleIntKey, value: Int) = add(ReadStyleMutation.IntValue(key, value))
    fun str(key: ReadStyleStringKey, value: String) = add(ReadStyleMutation.StringValue(key, value))

    int(ReadStyleIntKey.TextSize, style.textSize)
    add(ReadStyleMutation.FloatValue(ReadStyleFloatKey.LetterSpacing, style.letterSpacing))
    str(
        ReadStyleStringKey.ParagraphIndent,
        if (style.indentChars <= 0) "" else INDENT_CHAR.repeat(style.indentChars),
    )
    int(ReadStyleIntKey.LineSpacing, style.lineSpacing)
    int(ReadStyleIntKey.ParagraphSpacing, style.paragraphSpacing)
    int(ReadStyleIntKey.PaddingTop, style.paddingTop)
    int(ReadStyleIntKey.PaddingBottom, style.paddingBottom)
    int(ReadStyleIntKey.PaddingLeft, style.paddingLeft)
    int(ReadStyleIntKey.PaddingRight, style.paddingRight)
    int(ReadStyleIntKey.HeaderPaddingTop, style.headerPaddingTop)
    int(ReadStyleIntKey.HeaderPaddingBottom, style.headerPaddingBottom)
    int(ReadStyleIntKey.HeaderPaddingLeft, style.headerPaddingLeft)
    int(ReadStyleIntKey.HeaderPaddingRight, style.headerPaddingRight)
    int(ReadStyleIntKey.FooterPaddingTop, style.footerPaddingTop)
    int(ReadStyleIntKey.FooterPaddingBottom, style.footerPaddingBottom)
    int(ReadStyleIntKey.FooterPaddingLeft, style.footerPaddingLeft)
    int(ReadStyleIntKey.FooterPaddingRight, style.footerPaddingRight)

    style.titleSize?.let { int(ReadStyleIntKey.TitleSize, it) }
    style.titleMode?.let { int(ReadStyleIntKey.TitleMode, it.coerceIn(0, 2)) }
    style.titleTopSpacing?.let { int(ReadStyleIntKey.TitleTopSpacing, it) }
    style.titleBottomSpacing?.let { int(ReadStyleIntKey.TitleBottomSpacing, it) }
    style.titleLineSpacing?.let { int(ReadStyleIntKey.TitleLineSpacingExtra, it) }
    style.bodyWeight?.let { int(ReadStyleIntKey.TextBold, it.coerceIn(100, 900)) }
    style.titleWeight?.let { int(ReadStyleIntKey.TitleBold, it.coerceIn(100, 900)) }

    val effectiveBodyPath = when (val body = style.bodyFont) {
        null -> currentBodyFontPath
        is ReaderFontSelection.File -> body.path
        else -> ""
    }
    style.bodyFont?.let { str(ReadStyleStringKey.TextFont, effectiveBodyPath) }
    style.titleFont?.let { str(ReadStyleStringKey.TitleFont, fontPathForHost(it, effectiveBodyPath)) }
    style.headerFont?.let { str(ReadStyleStringKey.HeaderFont, fontPathForHost(it, effectiveBodyPath)) }

    style.headerSize?.let { int(ReadStyleIntKey.HeaderFontSize, it) }
    style.headerDivider?.let {
        add(ReadStyleMutation.BooleanValue(ReadStyleBooleanKey.ShowHeaderLine, it))
    }
    style.footerDivider?.let {
        add(ReadStyleMutation.BooleanValue(ReadStyleBooleanKey.ShowFooterLine, it))
    }
    style.headerVisible?.let { int(ReadStyleIntKey.HeaderMode, if (it) 1 else 2) }
    style.footerVisible?.let { int(ReadStyleIntKey.FooterMode, if (it) 0 else 1) }
}

/** FollowBody 展开为正文有效路径；系统预设 → 空串（宿主 = 系统字体）。 */
private fun fontPathForHost(
    selection: ReaderFontSelection,
    effectiveBodyPath: String,
): String = when (selection) {
    is ReaderFontSelection.File -> selection.path
    ReaderFontSelection.FollowBody -> effectiveBodyPath
    ReaderFontSelection.Sans, ReaderFontSelection.Serif, ReaderFontSelection.Mono -> ""
}

/** 宿主遗留字重值（0 正常 / 1 粗 / 2 细 / 100..900）归一化为 100..900，与引擎 resolveWeight 同口径。 */
internal fun normalizeHostWeight(value: Int): Int = when (value) {
    1 -> 900
    2 -> 300
    in 100..900 -> value
    else -> 400
}
