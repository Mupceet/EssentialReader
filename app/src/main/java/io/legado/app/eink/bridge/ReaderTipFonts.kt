package io.legado.app.eink.bridge

import android.graphics.Typeface
import io.legado.app.feature.reader.platform.ReaderAndroidPaintFactory

/** 页眉/页脚有效字体解析结果：字体文件 / 系统预设 / null（系统默认）。 */
internal sealed interface TipFont {
    data class File(val path: String) : TipFont
    data class Preset(val index: Int) : TipFont
}

/**
 * 页眉有效字体解析（纯函数）：headerFont 设置→用之；未设置→正文字体
 * 文件；正文也未设置但选了衬线/等宽预设→该预设；否则 null。
 */
internal fun resolveHeaderTipFont(headerFont: String, textFont: String, systemTypefaces: Int): TipFont? =
    headerFont.takeIf { it.isNotBlank() }?.let(TipFont::File)
        ?: textFont.takeIf { it.isNotBlank() }?.let(TipFont::File)
        ?: if (systemTypefaces == 1 || systemTypefaces == 2) TipFont.Preset(systemTypefaces) else null

/** 页脚有效字体：applyHeaderStyle（默认开）沿用页眉结果；关闭时按同一规则独立解析。 */
internal fun resolveFooterTipFont(
    footerFont: String,
    applyHeaderStyle: Boolean,
    headerResolved: TipFont?,
    textFont: String,
    systemTypefaces: Int,
): TipFont? =
    if (applyHeaderStyle) {
        headerResolved
    } else {
        resolveHeaderTipFont(footerFont, textFont, systemTypefaces)
    }

/** 系统预设 → 平台字体（1 衬线 / 2 等宽；其余 null）。 */
internal fun presetTypeface(index: Int): Typeface? = when (index) {
    1 -> Typeface.SERIF
    2 -> Typeface.MONOSPACE
    else -> null
}

/**
 * 字体文件路径 → Typeface：复用宿主正文排版同款加载与进程级缓存
 * （[ReaderAndroidPaintFactory.loadTypeface]，content:// 与纯路径同
 * 口径，宿主 extent 度量即出自同款调用）。加载失败宿主回落
 * sans-serif，与模块平台默认的渲染一致；空路径返回 null。
 */
internal fun loadTipTypeface(path: String): Typeface? {
    if (path.isBlank()) return null
    return ReaderAndroidPaintFactory.loadTypeface(path, weight = 400, italic = false)
}
