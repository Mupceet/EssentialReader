package io.legado.app.utils

import android.os.Build
import android.text.TextPaint

/**
 * 文本行高。
 *
 * 正常情况下包含字体的 leading（行间隙）；但部分字体文件（如方正新楷体）
 * 会把 leading 声明为异常大的值（约等于 1em），导致行高翻倍且空隙全堆到
 * 字形上方。因此当 leading 超过字体高度（descent-ascent）的 15% 时，
 * 视为字体文件异常，不计入行高。
 */
val TextPaint.textHeight: Float
    get() = fontMetrics.run {
        val height = descent - ascent
        val validLeading = if (leading > height * 0.15f) 0f else leading
        height + validLeading
    }

fun TextPaint.getTextWidthsCompat(text: String, widths: FloatArray) {
    getTextWidths(text, widths)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val letterSpacing = letterSpacing * textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        for (i in widths.indices) {
            if (widths[i] > 0) {
                widths[i] += letterSpacingHalf
                break
            }
        }
        for (i in text.lastIndex downTo 0) {
            if (widths[i] > 0) {
                widths[i] += letterSpacingHalf
                break
            }
        }
    }
}
