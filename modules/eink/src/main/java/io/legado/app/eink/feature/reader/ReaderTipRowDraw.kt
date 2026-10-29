package io.legado.app.eink.feature.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * 页眉/页脚条带的几何规则——逐式镜像宿主
 * `io.legado.app.feature.reader.core.model.ReaderTipRowLayout`：
 * 文字行盒按 includeFontPadding=false 口径（高 = descent − ascent，不含
 * leading），页眉基线自条带顶部锚定、页脚基线自底部锚定，分割线画在
 * 上下边距行之外（条带最底/最顶缘）。live 阅读页与屏保共用同一份，
 * 保证两端条带像素级一致。
 */
internal object ReaderTipRowGeometry {

    fun lineHeight(fontTopPx: Float, fontBottomPx: Float): Float =
        (fontBottomPx - fontTopPx).coerceAtLeast(0f)

    /** 条带预留高 = 上边距 + 行高 + 下边距 + 分割线厚度。 */
    fun extent(
        paddingTopPx: Float,
        fontTopPx: Float,
        fontBottomPx: Float,
        paddingBottomPx: Float,
        dividerExtentPx: Float = 0f,
    ): Float = paddingTopPx + lineHeight(fontTopPx, fontBottomPx) +
        paddingBottomPx + dividerExtentPx

    /** 页眉基线：文字顶贴上边距（= paddingTop − ascent）。 */
    fun headerBaseline(paddingTopPx: Float, fontTopPx: Float): Float =
        paddingTopPx - fontTopPx

    /** 页脚基线：文字底贴下边距（= 条带高 − paddingBottom − descent）。 */
    fun footerBaseline(bandHeightPx: Float, paddingBottomPx: Float, fontBottomPx: Float): Float =
        bandHeightPx - paddingBottomPx - fontBottomPx

    /** 分割线厚度（同宿主 0.5dp View）。 */
    const val DIVIDER_THICKNESS_DP = 0.5f
}

/**
 * 条带文字画笔：配置字号（sp×density 像素锚定）**直渲不钳制**——宿主
 * extent 本就按同一字号的 (descent − ascent)（includeFontPadding=false
 * 口径）精确预留，配置字号天然吻合；仅退化配置（边距吃光条带）时按
 * 同一口径收缩，保证字形完整且与宿主的隐式吻合规则一致。live 页眉
 * 页脚与屏保同用。
 */
internal fun tipRowPaint(
    typeface: Typeface?,
    configuredSizeSp: Int?,
    availablePx: Float,
    density: Float,
    colorArgb: Int,
): TextPaint {
    val effectiveTypeface = typeface ?: Typeface.DEFAULT
    val requestedSize = if (configuredSizeSp != null && configuredSizeSp > 0) {
        configuredSizeSp * density
    } else {
        availablePx * 14f / 20f
    }
    return TextPaint().apply {
        isAntiAlias = true
        this.color = colorArgb
        this.typeface = effectiveTypeface
        textSize = requestedSize
        val line = fontMetrics.descent - fontMetrics.ascent
        if (availablePx > 0f && line > availablePx && line > 0f) {
            textSize = requestedSize * availablePx / line
        }
    }
}

/**
 * 左/右双槽条带绘制——槽位规则镜像宿主 drawTipRow 的三槽约束（本模块
 * 内容项锁定，仅用左+右槽）：右槽右对齐、只在整行宽度处省略；左槽
 * available 压到右槽起始边（barrier）处省略。
 *
 * @param rowLeftPx  行左缘（条带内左边距，含内容边距左）
 * @param rowRightPx 行右缘（条带宽 − 右边距，含内容边距右）
 * @param baselineY  文字基线（条带内坐标）
 */
internal fun drawTipRowLeftRight(
    canvas: Canvas,
    paint: TextPaint,
    rowLeftPx: Float,
    rowRightPx: Float,
    baselineY: Float,
    leftText: String,
    rightText: String,
) {
    if (rightText.isNotEmpty()) {
        // 右槽右对齐（宿主 END 槽语义：textAlign=RIGHT 画在右缘），
        // 只在整行宽度处省略；barrier 取右槽**全宽**（未省略宽度，
        // 宿主同款——右槽过长时左槽可用宽压到 0 直接不画）
        val available = (rowRightPx - rowLeftPx).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(rightText, paint, available, TextUtils.TruncateAt.END)
        if (text.isNotEmpty()) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, 0, text.length, rowRightPx, baselineY, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        val rightWidth = paint.measureText(rightText)
        val leftAvailable = (rowRightPx - rightWidth - rowLeftPx).coerceAtLeast(0f)
        if (leftText.isNotEmpty() && leftAvailable > 0f) {
            val left = TextUtils.ellipsize(leftText, paint, leftAvailable, TextUtils.TruncateAt.END)
            canvas.drawText(left, 0, left.length, rowLeftPx, baselineY, paint)
        }
    } else if (leftText.isNotEmpty()) {
        val available = (rowRightPx - rowLeftPx).coerceAtLeast(0f)
        val left = TextUtils.ellipsize(leftText, paint, available, TextUtils.TruncateAt.END)
        canvas.drawText(left, 0, left.length, rowLeftPx, baselineY, paint)
    }
}

/**
 * 分割线：水平通栏画在 [leftPx, rightPx]，厚度 0.5dp——宿主同款
 * （宿主随内容边距内缩，此处边距已并入调用方入参）。
 */
internal fun drawTipDivider(
    canvas: Canvas,
    leftPx: Float,
    rightPx: Float,
    yPx: Float,
    thicknessPx: Float,
    colorArgb: Int,
) {
    if (rightPx <= leftPx) return
    val paint = Paint().apply { color = colorArgb }
    canvas.drawRect(leftPx, yPx, rightPx, yPx + thicknessPx, paint)
}
