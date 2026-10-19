package io.legado.app.eink.feature.reader

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.feature.reader.selection.decorationSpanX

/**
 * Snapshot geometry and colors needed to draw a reader page.
 *
 * Coordinates remain in the reader viewport pixel space. The off-screen
 * screensaver renderer scales the canvas before invoking the painter.
 */
internal data class ReaderPageRenderStyle(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val textColorArgb: Int,
    val highlightColorArgb: Int,
    val badgeColorArgb: Int,
    val imageAntiAlias: Boolean,
    val headerExtentPx: Float,
)

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

    fun draw(
        canvas: Canvas,
        snapshot: ReaderPageSnapshot,
        style: ReaderPageRenderStyle,
    ) {
        titlePaint.applySpec(snapshot.titleSpec, style.textColorArgb)
        contentPaint.applySpec(snapshot.contentSpec, style.textColorArgb)

        // Highlight bands stay below glyphs.
        highlightPaint.color = style.highlightColorArgb
        for (line in snapshot.lines) {
            for (run in line.decorations) {
                if (!run.highlight) continue
                val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                canvas.drawRect(
                    span.first,
                    line.top,
                    span.second,
                    line.bottom,
                    highlightPaint,
                )
            }
        }

        if (snapshot.bookmarkBadge) {
            val badgeWidth = 16f * style.density
            val badgeHeight = 64f * style.density
            val right = style.widthPx - 6f * style.density
            val top = style.headerExtentPx
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
            val paint = if (line.isTitle) titlePaint else contentPaint
            for (index in line.chunks.indices) {
                canvas.drawText(line.chunks[index], line.x[index], line.baseY, paint)
            }
        }

        imagePaint.isAntiAlias = style.imageAntiAlias
        for (slot in snapshot.images) {
            drawImageSlot(canvas, slot, imagePaint)
        }

        for (line in snapshot.lines) {
            for (run in line.decorations) {
                if (run.underlineMode == 0) continue
                val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                val geometry = run.underline
                val strokeWidth =
                    geometry?.widthPx ?: EInkUnderlineDefaults.widthPx(style.density)
                val y = line.bottom +
                    (geometry?.offsetPx ?: EInkUnderlineDefaults.offsetPx(style.density))
                when (run.underlineMode) {
                    2 -> drawDashedLine(
                        canvas = canvas,
                        left = span.first,
                        right = span.second,
                        y = y,
                        strokeWidth = strokeWidth,
                        colorArgb = style.textColorArgb,
                        dashOnPx = geometry?.dashOnPx
                            ?: EInkUnderlineDefaults.dashOnPx(style.density),
                        dashOffPx = geometry?.dashOffPx
                            ?: EInkUnderlineDefaults.dashOffPx(style.density),
                    )

                    3 -> drawWaveLine(
                        canvas = canvas,
                        left = span.first,
                        right = span.second,
                        y = y,
                        strokeWidth = strokeWidth,
                        colorArgb = style.textColorArgb,
                        waveLengthPx = geometry?.waveLengthPx
                            ?: EInkUnderlineDefaults.waveLengthPx(style.density),
                        waveAmplitudePx = geometry?.waveAmplitudePx
                            ?: EInkUnderlineDefaults.waveAmplitudePx(style.density),
                    )

                    4 -> {
                        drawSolidLine(
                            canvas,
                            span.first,
                            span.second,
                            y,
                            strokeWidth,
                            style.textColorArgb,
                        )
                        drawSolidLine(
                            canvas,
                            span.first,
                            span.second,
                            y + (geometry?.doubleLineGapPx
                                ?: EInkUnderlineDefaults.doubleLineGapPx(style.density)) +
                                strokeWidth,
                            strokeWidth,
                            style.textColorArgb,
                        )
                    }

                    else -> drawSolidLine(
                        canvas,
                        span.first,
                        span.second,
                        y,
                        strokeWidth,
                        style.textColorArgb,
                    )
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
