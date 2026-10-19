package io.legado.app.eink.feature.reader.screensaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import io.legado.app.eink.feature.reader.ReaderPageRenderStyle
import io.legado.app.eink.feature.reader.ReaderPageSnapshotPainter
import io.legado.app.eink.feature.reader.clampTipFontSizeToAvailablePx
import io.legado.app.eink.feature.reader.fontLineRequirementRatio

/**
 * Renders the current reader page at the fixed iReader wallpaper size.
 *
 * The live Compose page is drawn through the same [ReaderPageSnapshotPainter].
 * Header/footer rendering mirrors the reader's configured pixel-anchored font
 * sizing and is drawn in the host-reserved extents before the canvas scale is
 * restored. The system illustration is applied last at full target size.
 */
internal class ReaderScreensaverRenderer {

    fun render(
        request: ReaderScreensaverRequest,
        illustration: Bitmap?,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint().apply { color = request.colors.backgroundArgb }
        canvas.drawRect(
            0f,
            0f,
            TARGET_WIDTH.toFloat(),
            TARGET_HEIGHT.toFloat(),
            backgroundPaint,
        )

        val sourceWidth = request.viewportWidthPx.coerceAtLeast(1)
        val sourceHeight = request.viewportHeightPx.coerceAtLeast(1)
        val scaleX = TARGET_WIDTH.toFloat() / sourceWidth
        val scaleY = TARGET_HEIGHT.toFloat() / sourceHeight

        canvas.save()
        canvas.scale(scaleX, scaleY)
        ReaderPageSnapshotPainter().draw(
            canvas = canvas,
            snapshot = request.page,
            style = ReaderPageRenderStyle(
                widthPx = sourceWidth,
                heightPx = sourceHeight,
                density = request.density,
                textColorArgb = request.colors.textArgb,
                highlightColorArgb = request.colors.highlightArgb,
                badgeColorArgb = request.colors.badgeArgb,
                imageAntiAlias = request.imageAntiAlias,
                headerExtentPx = request.headerExtentPx,
            ),
        )
        drawHeader(canvas, request)
        drawFooter(canvas, request)
        canvas.restore()

        if (illustration != null && !illustration.isRecycled) {
            canvas.drawBitmap(
                illustration,
                null,
                RectF(0f, 0f, TARGET_WIDTH.toFloat(), TARGET_HEIGHT.toFloat()),
                Paint().apply { isAntiAlias = true },
            )
        }
        return bitmap
    }

    private fun drawHeader(canvas: Canvas, request: ReaderScreensaverRequest) {
        if (!request.headerVisible || request.headerExtentPx <= 0f) return
        val density = request.density
        val extent = request.headerExtentPx
        val left = request.style.headerPaddingLeft * density
        val top = request.style.headerPaddingTop * density
        val right = request.viewportWidthPx -
            request.style.headerPaddingRight * density
        val bottom = extent - request.style.headerPaddingBottom * density
        val availableHeight = (bottom - top).coerceAtLeast(1f)
        val paint = tipPaint(
            typeface = request.headerTypeface,
            configuredSizeSp = request.style.headerSize,
            availablePx = availableHeight,
            density = density,
            colorArgb = request.colors.tipArgb,
        )
        val baseline = centeredBaseline(top, availableHeight, paint)
        canvas.drawText(request.headerTime, left, baseline, paint)
        val batteryText = "${request.batteryPercent}%"
        canvas.drawText(
            batteryText,
            right - paint.measureText(batteryText),
            baseline,
            paint,
        )
    }

    private fun drawFooter(canvas: Canvas, request: ReaderScreensaverRequest) {
        if (!request.footerVisible || request.footerExtentPx <= 0f) return
        val density = request.density
        val footerTop = request.viewportHeightPx - request.footerExtentPx
        val progressHeight = 2f * density
        if (request.autoPlay) {
            canvas.drawRect(
                0f,
                footerTop,
                request.viewportWidthPx.toFloat(),
                footerTop + progressHeight,
                Paint().apply { color = request.colors.textArgb },
            )
        }

        val rowTop = footerTop + progressHeight + request.style.footerPaddingTop * density
        val rowBottom = request.viewportHeightPx -
            request.style.footerPaddingBottom * density
        val availableHeight = (rowBottom - rowTop).coerceAtLeast(1f)
        val paint = TextPaint(
            tipPaint(
                typeface = request.footerTypeface,
                configuredSizeSp = request.style.footerSize,
                availablePx = availableHeight,
                density = density,
                colorArgb = request.colors.tipArgb,
            ),
        )
        val baseline = centeredBaseline(rowTop, availableHeight, paint)
        val left = request.style.footerPaddingLeft * density
        val right = request.viewportWidthPx - request.style.footerPaddingRight * density
        val pageText = request.pageAndTotal
        val pageWidth = paint.measureText(pageText)
        val gap = 12f * density
        val titleWidth = (right - left - pageWidth - gap).coerceAtLeast(0f)
        if (titleWidth > 0f) {
            val title = TextUtils.ellipsize(
                request.chapterTitle,
                paint,
                titleWidth,
                TextUtils.TruncateAt.END,
            )
            canvas.drawText(title, 0, title.length, left, baseline, paint)
        }
        canvas.drawText(pageText, right - pageWidth, baseline, paint)
    }

    private fun tipPaint(
        typeface: Typeface?,
        configuredSizeSp: Int?,
        availablePx: Float,
        density: Float,
        colorArgb: Int,
    ): TextPaint {
        val effectiveTypeface = typeface ?: Typeface.DEFAULT
        val lineRatio = effectiveTypeface.fontLineRequirementRatio()
        val requestedSize = if (configuredSizeSp != null && configuredSizeSp > 0) {
            configuredSizeSp * density
        } else {
            availablePx * 14f / 20f
        }
        val fontSize = clampTipFontSizeToAvailablePx(
            requestedFontSizePx = requestedSize,
            availablePx = availablePx,
            fontLineRequirementRatio = lineRatio,
        )
        return TextPaint().apply {
            isAntiAlias = true
            this.color = colorArgb
            textSize = fontSize
            this.typeface = effectiveTypeface
        }
    }

    private fun centeredBaseline(top: Float, availableHeight: Float, paint: Paint): Float {
        val metrics = paint.fontMetrics
        return top + (availableHeight - (metrics.descent - metrics.ascent)) / 2f -
            metrics.ascent
    }

    companion object {
        const val TARGET_WIDTH = 1860
        const val TARGET_HEIGHT = 2480
    }
}
