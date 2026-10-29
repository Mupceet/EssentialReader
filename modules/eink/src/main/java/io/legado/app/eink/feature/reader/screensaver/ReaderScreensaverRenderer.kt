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
 * Renders the current reader page at the device's real display size.
 *
 * The live Compose page is drawn through the same [ReaderPageSnapshotPainter]
 * and scaled from the reader viewport into the wallpaper canvas. Header/footer
 * rendering mirrors the reader's configured pixel-anchored font sizing and is
 * drawn in the host-reserved extents before the canvas scale is restored. The
 * system illustration is applied last at full target size.
 */
internal class ReaderScreensaverRenderer {

    /**
     * Style 2: cover is the base layer and the system illustration is stretched
     * over the full cover. This mirrors iReader's `WallpaperBookManager`
     * composition instead of sharing the current page renderer.
     */
    fun renderCover(
        cover: Bitmap,
        illustration: Bitmap?,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = illustrationPaint()
        val destination = RectF(0f, 0f, targetWidthPx.toFloat(), targetHeightPx.toFloat())
        canvas.drawBitmap(cover, null, destination, paint)
        if (illustration != null && !illustration.isRecycled) {
            canvas.drawBitmap(illustration, null, destination, paint)
        }
        return bitmap
    }

    fun render(
        request: ReaderScreensaverRequest,
        illustration: Bitmap?,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint().apply { color = request.colors.backgroundArgb }
        canvas.drawRect(
            0f,
            0f,
            targetWidthPx.toFloat(),
            targetHeightPx.toFloat(),
            backgroundPaint,
        )

        val sourceWidth = request.viewportWidthPx.coerceAtLeast(1)
        val sourceHeight = request.viewportHeightPx.coerceAtLeast(1)
        val viewport = resolveReaderScreensaverViewport(
            contentWidthPx = sourceWidth,
            contentHeightPx = sourceHeight,
            systemBarTopInsetPx = request.systemBarTopInsetPx,
            systemBarBottomInsetPx = request.systemBarBottomInsetPx,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
        )

        canvas.save()
        canvas.scale(viewport.scaleX, viewport.scaleY)
        if (!request.headerVisible && request.systemBarTopInsetPx > 0f) {
            drawBookName(
                canvas = canvas,
                request = request,
                top = 0f,
                bottom = request.systemBarTopInsetPx,
            )
        }
        canvas.translate(0f, request.systemBarTopInsetPx)
        val bodyBottomPx = if (request.footerVisible && request.footerExtentPx > 0f) {
            sourceHeight - request.footerExtentPx
        } else {
            sourceHeight.toFloat()
        }
        canvas.save()
        canvas.clipRect(
            0f,
            request.headerExtentPx,
            sourceWidth.toFloat(),
            bodyBottomPx,
        )
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
        canvas.restore()
        if (request.headerVisible) {
            val density = request.density
            drawBookName(
                canvas = canvas,
                request = request,
                top = request.style.headerPaddingTop * density,
                bottom = request.headerExtentPx -
                    request.style.headerPaddingBottom * density,
            )
        }
        drawFooter(canvas, request)
        canvas.restore()

        if (illustration != null && !illustration.isRecycled) {
            canvas.drawBitmap(
                illustration,
                null,
                RectF(0f, 0f, targetWidthPx.toFloat(), targetHeightPx.toFloat()),
                illustrationPaint(),
            )
        }
        return bitmap
    }

    private fun illustrationPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private fun drawBookName(
        canvas: Canvas,
        request: ReaderScreensaverRequest,
        top: Float,
        bottom: Float,
    ) {
        if (bottom <= top) return
        val density = request.density
        val left = request.style.headerPaddingLeft * density
        val right = request.viewportWidthPx -
            request.style.headerPaddingRight * density
        val availableHeight = (bottom - top).coerceAtLeast(1f)
        val paint = TextPaint(
            tipPaint(
                typeface = request.headerTypeface,
                configuredSizeSp = request.style.headerSize,
                availablePx = availableHeight,
                density = density,
                colorArgb = request.colors.tipArgb,
            ),
        )
        val baseline = centeredBaseline(top, availableHeight, paint)
        val title = TextUtils.ellipsize(
            request.bookName,
            paint,
            (right - left).coerceAtLeast(0f),
            TextUtils.TruncateAt.END,
        )
        canvas.drawText(title, 0, title.length, left, baseline, paint)
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
}
