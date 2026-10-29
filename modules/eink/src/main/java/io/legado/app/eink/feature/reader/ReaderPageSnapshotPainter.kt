package io.legado.app.eink.feature.reader

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.feature.reader.selection.decorationSpanX

/**
 * Snapshot geometry and colors needed to draw a reader page.
 *
 * Coordinates are window pixel space (the canvas is full-screen; system bars
 * overlay it and the snapshot already contains the content-padding offsets).
 * The off-screen screensaver renderer scales the canvas before invoking the
 * painter when the window differs from the device display.
 */
internal data class ReaderPageRenderStyle(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val textColorArgb: Int,
    val highlightColorArgb: Int,
    val badgeColorArgb: Int,
    val imageAntiAlias: Boolean,
    /** 正文首行顶（窗口坐标）：书签角标锚定点。 */
    val contentTopPx: Float,
)

/**
 * 下划线 mode → 绘制形态。完整模式（ReaderPageDecorationDrawCache）的
 * 语义映射在模块侧的等价物：6 删除线（行盒 52% 高度横线压过字形）、
 * 7 荧光（行盒下半高亮带）此前误入「行底实线」分支，本枚举锚定语义，
 * 模块单测锁定 mode→形态映射。
 */
internal enum class ReaderUnderlineVisual {
    SOLID, DASHED, WAVE, DOUBLE, STRIKE_THROUGH, HALF_BAND,
}

internal fun underlineVisualOf(mode: Int): ReaderUnderlineVisual = when (mode) {
    2 -> ReaderUnderlineVisual.DASHED
    3 -> ReaderUnderlineVisual.WAVE
    4 -> ReaderUnderlineVisual.DOUBLE
    6 -> ReaderUnderlineVisual.STRIKE_THROUGH
    7 -> ReaderUnderlineVisual.HALF_BAND
    // 1 实线；5 SVG 花色及未知值降级实线（明示不支持花色）
    else -> ReaderUnderlineVisual.SOLID
}

/**
 * Android-canvas implementation shared by the live Compose reader canvas and
 * the lock-screen renderer.
 *
 * Keeping the page drawing here makes both surfaces consume the same snapshot
 * geometry, decorations, image slots, and font paint rules. A painter instance
 * owns mutable [Paint] objects and must not be shared between draw calls that
 * can run concurrently.
 */
internal class ReaderPageSnapshotPainter {

    private val titlePaint = Paint()
    private val contentPaint = Paint()
    private val highlightPaint = Paint()
    private val imagePaint = Paint()
    private val linePaint = Paint().apply { isAntiAlias = true }

    /** 覆盖段派生画笔缓存（键含行规格身份与覆盖字段；防页内样式膨胀）。 */
    private val overridePaints = HashMap<OverridePaintKey, Paint>()

    private data class OverridePaintKey(
        val isTitle: Boolean,
        val textSizePx: Float,
        val typeface: Typeface?,
        val fontVariationSettings: String?,
        val colorArgb: Int,
        val skewX: Float,
        val fakeBold: Boolean,
    )

    fun draw(
        canvas: Canvas,
        snapshot: ReaderPageSnapshot,
        style: ReaderPageRenderStyle,
    ) {
        titlePaint.applySpec(snapshot.titleSpec, style.textColorArgb)
        contentPaint.applySpec(snapshot.contentSpec, style.textColorArgb)

        fun basePaintOf(line: ReaderPageLine): Paint =
            if (line.isTitle) titlePaint else contentPaint

        /** 段级画笔：无覆盖 = 行画笔；有覆盖 = 规格合并后的派生画笔（缓存）。 */
        fun chunkPaint(line: ReaderPageLine, index: Int): Paint {
            val override = line.chunkOverrides.getOrNull(index) ?: return basePaintOf(line)
            val base = if (line.isTitle) snapshot.titleSpec else snapshot.contentSpec
            val merged = ReaderPaintSpec(
                textSizePx = override.textSizePx ?: base.textSizePx,
                letterSpacing = base.letterSpacing,
                typeface = override.typeface ?: base.typeface,
                fontVariationSettings = override.fontVariationSettings ?: base.fontVariationSettings,
            )
            val color = override.colorArgb ?: style.textColorArgb
            val key = OverridePaintKey(
                isTitle = line.isTitle,
                textSizePx = merged.textSizePx,
                typeface = merged.typeface,
                fontVariationSettings = merged.fontVariationSettings,
                colorArgb = color,
                skewX = override.skewX ?: 0f,
                fakeBold = override.fakeBold,
            )
            return overridePaints.getOrPut(key) {
                Paint().apply {
                    applySpec(merged, color)
                    textSkewX = override.skewX ?: 0f
                    // 合成加粗：绘制期描边加宽、不改步进宽，选区/装饰测量不受影响
                    isFakeBoldText = override.fakeBold
                }
            }.also {
                if (overridePaints.size > 64) overridePaints.clear()
            }
        }

        fun measureChunk(line: ReaderPageLine): (Int, String) -> Float =
            { index, text -> chunkPaint(line, index).measureText(text) }

        // Highlight bands stay below glyphs. 荧光（mode 7）是行盒下半的
        // 高亮带，与背景高亮同画在字形之下；色取 run 灰度，缺省主题高亮色。
        for (line in snapshot.lines) {
            val measure = measureChunk(line)
            for (run in line.decorations) {
                val visual = underlineVisualOf(run.underlineMode)
                if (!run.highlight && visual != ReaderUnderlineVisual.HALF_BAND) continue
                val span = decorationSpanX(line, run, measure) ?: continue
                // 两者并存（bgColor + 荧光）时与完整模式一致：整行带与下半带叠画
                if (run.highlight) {
                    highlightPaint.color = run.highlightColorArgb ?: style.highlightColorArgb
                    canvas.drawRect(span.first, line.top, span.second, line.bottom, highlightPaint)
                }
                if (visual == ReaderUnderlineVisual.HALF_BAND) {
                    highlightPaint.color = run.underlineColorArgb ?: style.highlightColorArgb
                    val half = line.top + (line.bottom - line.top) * 0.5f
                    canvas.drawRect(span.first, half, span.second, line.bottom, highlightPaint)
                }
            }
        }

        if (snapshot.bookmarkBadge) {
            val badgeWidth = 16f * style.density
            val badgeHeight = 64f * style.density
            val right = style.widthPx - 6f * style.density
            val top = style.contentTopPx
            val notch = badgeHeight * 0.25f
            val ribbon = Path().apply {
                moveTo(right - badgeWidth, top)
                lineTo(right, top)
                lineTo(right, top + badgeHeight)
                lineTo(right - badgeWidth / 2f, top + badgeHeight - notch)
                lineTo(right - badgeWidth, top + badgeHeight)
                close()
            }
            highlightPaint.color = style.badgeColorArgb
            canvas.drawPath(ribbon, highlightPaint)
        }

        for (line in snapshot.lines) {
            for (index in line.chunks.indices) {
                canvas.drawText(line.chunks[index], line.x[index], line.baseY, chunkPaint(line, index))
            }
        }

        imagePaint.isAntiAlias = style.imageAntiAlias
        for (slot in snapshot.images) {
            drawImageSlot(canvas, slot, imagePaint)
        }

        // 下划线画在字形之上（删除线必须压过字形；其余线在行盒下沿之下，
        // 与字形无重叠，先后仅影响同色叠加，保持既有顺序）。荧光带已在
        // 字形之下处理，这里跳过。
        for (line in snapshot.lines) {
            val measure = measureChunk(line)
            for (run in line.decorations) {
                if (run.underlineMode == 0) continue
                val visual = underlineVisualOf(run.underlineMode)
                if (visual == ReaderUnderlineVisual.HALF_BAND) continue
                val span = decorationSpanX(line, run, measure) ?: continue
                val geometry = run.underline
                val strokeWidth =
                    geometry?.widthPx ?: EInkUnderlineDefaults.widthPx(style.density)
                val colorArgb = run.underlineColorArgb ?: style.textColorArgb
                val y = line.bottom +
                    (geometry?.offsetPx ?: EInkUnderlineDefaults.offsetPx(style.density))
                when (visual) {
                    ReaderUnderlineVisual.DASHED -> drawDashedLine(
                        canvas = canvas,
                        left = span.first,
                        right = span.second,
                        y = y,
                        strokeWidth = strokeWidth,
                        colorArgb = colorArgb,
                        dashOnPx = geometry?.dashOnPx
                            ?: EInkUnderlineDefaults.dashOnPx(style.density),
                        dashOffPx = geometry?.dashOffPx
                            ?: EInkUnderlineDefaults.dashOffPx(style.density),
                    )

                    ReaderUnderlineVisual.WAVE -> drawWaveLine(
                        canvas = canvas,
                        left = span.first,
                        right = span.second,
                        y = y,
                        strokeWidth = strokeWidth,
                        colorArgb = colorArgb,
                        waveLengthPx = geometry?.waveLengthPx
                            ?: EInkUnderlineDefaults.waveLengthPx(style.density),
                        waveAmplitudePx = geometry?.waveAmplitudePx
                            ?: EInkUnderlineDefaults.waveAmplitudePx(style.density),
                    )

                    ReaderUnderlineVisual.DOUBLE -> {
                        drawSolidLine(canvas, span.first, span.second, y, strokeWidth, colorArgb)
                        drawSolidLine(
                            canvas,
                            span.first,
                            span.second,
                            y + (geometry?.doubleLineGapPx
                                ?: EInkUnderlineDefaults.doubleLineGapPx(style.density)) +
                                strokeWidth,
                            strokeWidth,
                            colorArgb,
                        )
                    }

                    // 完整模式 ReaderUnderlineDrawCommand mode 6：行盒 52% 高度
                    ReaderUnderlineVisual.STRIKE_THROUGH -> drawSolidLine(
                        canvas,
                        span.first,
                        span.second,
                        line.top + (line.bottom - line.top) * 0.52f,
                        strokeWidth,
                        colorArgb,
                    )

                    ReaderUnderlineVisual.SOLID -> drawSolidLine(
                        canvas,
                        span.first,
                        span.second,
                        y,
                        strokeWidth,
                        colorArgb,
                    )

                    ReaderUnderlineVisual.HALF_BAND -> Unit // 已在字形之下处理
                }
            }
        }
    }

    private fun drawSolidLine(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
        strokeWidth: Float,
        colorArgb: Int,
    ) {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = strokeWidth
        linePaint.color = colorArgb
        linePaint.pathEffect = null
        canvas.drawLine(left, y, right, y, linePaint)
    }

    private fun drawDashedLine(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
        strokeWidth: Float,
        colorArgb: Int,
        dashOnPx: Float,
        dashOffPx: Float,
    ) {
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = strokeWidth
        linePaint.color = colorArgb
        linePaint.pathEffect = DashPathEffect(
            floatArrayOf(dashOnPx.coerceAtLeast(0.1f), dashOffPx.coerceAtLeast(0.1f)),
            0f,
        )
        canvas.drawLine(left, y, right, y, linePaint)
        linePaint.pathEffect = null
    }

    private fun drawWaveLine(
        canvas: Canvas,
        left: Float,
        right: Float,
        y: Float,
        strokeWidth: Float,
        colorArgb: Int,
        waveLengthPx: Float,
        waveAmplitudePx: Float,
    ) {
        val wavelength = waveLengthPx.coerceAtLeast(0.1f)
        val path = Path().apply {
            moveTo(left, y)
            var x = left
            var up = true
            while (x < right) {
                val next = (x + wavelength).coerceAtMost(right)
                val mid = (x + next) / 2f
                if (up) {
                    quadTo(mid, y - waveAmplitudePx, next, y)
                } else {
                    quadTo(mid, y + waveAmplitudePx, next, y)
                }
                up = !up
                x = next
            }
        }
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = strokeWidth
        linePaint.color = colorArgb
        linePaint.pathEffect = null
        canvas.drawPath(path, linePaint)
    }

    private fun drawImageSlot(canvas: Canvas, slot: ReaderImageSlot, paint: Paint) {
        val width = (slot.x1 - slot.x0).toInt()
        val height = slot.lineHeight.toInt()
        if (width <= 0 || height <= 0) return
        val bitmap = slot.loader(width, height) ?: return
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val rect = if (slot.fullLine) {
            RectF(slot.x0, slot.lineTop, slot.x1, slot.lineBottom)
        } else {
            val imageHeight = (slot.x1 - slot.x0) / bitmap.width * bitmap.height
            val verticalInset = (slot.lineHeight - imageHeight) / 2f
            RectF(
                slot.x0,
                slot.lineTop + verticalInset,
                slot.x1,
                slot.lineBottom - verticalInset,
            )
        }
        canvas.drawBitmap(bitmap, null, rect, paint)
    }
}

/**
 * Applies the measurement-coupled snapshot paint specification.
 *
 * The selection layer also uses this function so hit testing and live drawing
 * keep using the exact same font metrics as the shared page painter.
 */
internal fun Paint.applySpec(spec: ReaderPaintSpec, colorArgb: Int) {
    isAntiAlias = true
    color = colorArgb
    textSize = spec.textSizePx
    letterSpacing = spec.letterSpacing
    typeface = spec.typeface ?: Typeface.DEFAULT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        fontVariationSettings = spec.fontVariationSettings ?: ""
    }
}

internal object EInkUnderlineDefaults {
    fun widthPx(density: Float): Float = density
    fun offsetPx(density: Float): Float = 2f * density
    fun dashOnPx(density: Float): Float = 8f * density
    fun dashOffPx(density: Float): Float = 5f * density
    fun waveAmplitudePx(density: Float): Float = 3f * density
    fun waveLengthPx(density: Float): Float = 12f * density
    fun doubleLineGapPx(density: Float): Float = 3f * density
}
