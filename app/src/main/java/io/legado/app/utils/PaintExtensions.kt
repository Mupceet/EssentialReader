package io.legado.app.utils

import android.os.Build
import android.text.TextPaint

/**
 * 文本行高（不含字体的 leading/行间隙）。
 *
 * 阅读排版与封面竖排文字都用这个值做行间距；若包含 leading，
 * 部分字体（如方正新楷体 leading≈1em）会把 leading 全部堆到字形上方，
 * 导致行距 0 时顶部仍有一大块空隙。
 */
val TextPaint.textHeight: Float
    get() = fontMetrics.run { descent - ascent }

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
