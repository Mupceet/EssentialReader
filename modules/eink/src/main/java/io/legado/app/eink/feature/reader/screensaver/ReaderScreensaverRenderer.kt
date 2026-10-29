package io.legado.app.eink.feature.reader.screensaver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.legado.app.eink.feature.reader.ReaderPageRenderStyle
import io.legado.app.eink.feature.reader.ReaderPageSnapshotPainter
import io.legado.app.eink.feature.reader.ReaderTipRowGeometry
import io.legado.app.eink.feature.reader.drawTipDivider
import io.legado.app.eink.feature.reader.drawTipRowLeftRight
import io.legado.app.eink.feature.reader.tipRowPaint

/**
 * Renders the current reader page at the device's real display size.
 *
 * Coordinates are **window space**：the eink reader canvas is full-screen and
 * the snapshot already contains the content-padding offsets（同 live 阅读页，
 * 共用 [ReaderPageSnapshotPainter]）——快照按窗口坐标原样绘制，仅当窗口
 * 与设备面板尺寸不一致时整体缩放。页眉/页脚条带用与 live 同一份
 * [ReaderTipRowGeometry] 几何与配置参数（字号/字体/边距/分割线）绘制，
 * 内容项为锁屏专属锁定（页眉=书名、页脚=章节标题+页码）；状态栏带
 * （页眉隐且有带高时）填垂直居中的书名——带高来自系统栏，非条带几何。
 * The system illustration is applied last at full target size.
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

        // 快照坐标系 = 窗口坐标（画布全屏，避让已并入排版 contentPadding）
        val sourceWidth = request.viewportWidthPx.coerceAtLeast(1)
        val sourceHeight = request.viewportHeightPx.coerceAtLeast(1)
        val bands = resolveReaderScreensaverBands(
            sourceHeightPx = sourceHeight,
            systemBarTopInsetPx = request.systemBarTopInsetPx,
            systemBarBottomInsetPx = request.systemBarBottomInsetPx,
            headerExtentPx = request.headerExtentPx,
            footerExtentPx = request.footerExtentPx,
            footerVisible = request.footerVisible,
        )

        canvas.save()
        canvas.scale(
            targetWidthPx.toFloat() / sourceWidth,
            targetHeightPx.toFloat() / sourceHeight,
        )
        // 正文裁剪到内容带（窗口坐标），快照行已含 contentPadding 偏移
        canvas.save()
        canvas.clipRect(0f, bands.bodyTopPx, sourceWidth.toFloat(), bands.bodyBottomPx)
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
                contentTopPx = request.systemBarTopInsetPx +
                    request.style.paddingTop * request.density +
                    request.headerExtentPx,
            ),
        )
        canvas.restore()

        if (!request.headerVisible && request.systemBarTopInsetPx > 0f) {
            drawStatusBandBookName(canvas, request, sourceWidth)
        }
        if (request.headerVisible && request.headerExtentPx > 0f) {
            drawHeaderBand(canvas, request, sourceWidth)
        }
        drawFooterBand(canvas, request, sourceWidth)
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

    /**
     * 状态栏带书名（页眉隐 + 有带高）：带高来自系统栏而非页眉条带几何，
     * 垂直居中；水平仍按页眉左右边距（含内容边距）内缩。
     */
    private fun drawStatusBandBookName(
        canvas: Canvas,
        request: ReaderScreensaverRequest,
        sourceWidth: Int,
    ) {
        val density = request.density
        val left = request.contentPaddingLeftPx + request.style.headerPaddingLeft * density
        val right = sourceWidth - request.contentPaddingRightPx -
            request.style.headerPaddingRight * density
        val availableHeight = request.systemBarTopInsetPx
        val paint = tipRowPaint(
            typeface = request.headerTypeface,
            configuredSizeSp = request.style.headerSize,
            availablePx = availableHeight,
            density = density,
            colorArgb = request.colors.tipArgb,
        )
        val baseline = centeredBaseline(0f, availableHeight, paint)
        val available = (right - left).coerceAtLeast(0f)
        val title = android.text.TextUtils.ellipsize(
            request.bookName,
            paint,
            available,
            android.text.TextUtils.TruncateAt.END,
        )
        canvas.drawText(title, 0, title.length, left, baseline, paint)
    }

    /** 页眉带书名：与 live 页眉同一份几何/字号/字体/边距/分割线。 */
    private fun drawHeaderBand(
        canvas: Canvas,
        request: ReaderScreensaverRequest,
        sourceWidth: Int,
    ) {
        val density = request.density
        val style = request.style
        val extentPx = request.headerExtentPx
        val bandTop = request.systemBarTopInsetPx
        val dividerExtent =
            if (style.headerDivider == true) ReaderTipRowGeometry.DIVIDER_THICKNESS_DP * density else 0f
        val paddingTopPx = style.headerPaddingTop * density
        val paddingBottomPx = style.headerPaddingBottom * density
        val paint = tipRowPaint(
            typeface = request.headerTypeface,
            configuredSizeSp = style.headerSize,
            availablePx = (extentPx - paddingTopPx - paddingBottomPx - dividerExtent)
                .coerceAtLeast(1f),
            density = density,
            colorArgb = request.colors.tipArgb,
        )
        val metrics = paint.fontMetrics
        val rowLeft = request.contentPaddingLeftPx + style.headerPaddingLeft * density
        val rowRight = sourceWidth - request.contentPaddingRightPx -
            style.headerPaddingRight * density
        drawTipRowLeftRight(
            canvas = canvas,
            paint = paint,
            rowLeftPx = rowLeft,
            rowRightPx = rowRight,
            baselineY = bandTop +
                ReaderTipRowGeometry.headerBaseline(paddingTopPx, metrics.ascent),
            leftText = request.bookName,
            rightText = "",
        )
        if (style.headerDivider == true) {
            drawTipDivider(
                canvas = canvas,
                leftPx = rowLeft,
                rightPx = rowRight,
                yPx = bandTop + ReaderTipRowGeometry.extent(
                    paddingTopPx = paddingTopPx,
                    fontTopPx = metrics.ascent,
                    fontBottomPx = metrics.descent,
                    paddingBottomPx = paddingBottomPx,
                ),
                thicknessPx = dividerExtent,
                colorArgb = request.colors.tipArgb,
            )
        }
    }

    /** 页脚带：与 live 页脚同一份几何/参数（含自动翻页 2dp 条与分割线）。 */
    private fun drawFooterBand(
        canvas: Canvas,
        request: ReaderScreensaverRequest,
        sourceWidth: Int,
    ) {
        if (!request.footerVisible || request.footerExtentPx <= 0f) return
        val density = request.density
        val style = request.style
        val extentPx = request.footerExtentPx
        val footerTop = request.viewportHeightPx -
            request.systemBarBottomInsetPx - extentPx
        val progressHeight = 2f * density
        val dividerExtent =
            if (style.footerDivider == true) ReaderTipRowGeometry.DIVIDER_THICKNESS_DP * density else 0f
        val rowTop = footerTop + progressHeight
        if (request.autoPlay) {
            canvas.drawRect(
                0f,
                footerTop,
                sourceWidth.toFloat(),
                footerTop + progressHeight,
                Paint().apply { color = request.colors.textArgb },
            )
        }
        val paddingTopPx = style.footerPaddingTop * density
        val paddingBottomPx = style.footerPaddingBottom * density
        val paint = tipRowPaint(
            typeface = request.footerTypeface,
            configuredSizeSp = style.footerSize,
            availablePx = (extentPx - progressHeight - paddingTopPx - paddingBottomPx -
                dividerExtent).coerceAtLeast(1f),
            density = density,
            colorArgb = request.colors.tipArgb,
        )
        val metrics = paint.fontMetrics
        val rowLeft = request.contentPaddingLeftPx + style.footerPaddingLeft * density
        val rowRight = sourceWidth - request.contentPaddingRightPx -
            style.footerPaddingRight * density
        if (style.footerDivider == true) {
            drawTipDivider(
                canvas = canvas,
                leftPx = rowLeft,
                rightPx = rowRight,
                yPx = rowTop,
                thicknessPx = dividerExtent,
                colorArgb = request.colors.tipArgb,
            )
        }
        drawTipRowLeftRight(
            canvas = canvas,
            paint = paint,
            rowLeftPx = rowLeft,
            rowRightPx = rowRight,
            baselineY = rowTop + ReaderTipRowGeometry.footerBaseline(
                bandHeightPx = extentPx - progressHeight,
                paddingBottomPx = paddingBottomPx,
                fontBottomPx = metrics.descent,
            ),
            leftText = request.chapterTitle,
            rightText = request.pageAndTotal,
        )
    }

    private fun illustrationPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private fun centeredBaseline(top: Float, availableHeight: Float, paint: Paint): Float {
        val metrics = paint.fontMetrics
        return top + (availableHeight - (metrics.descent - metrics.ascent)) / 2f -
            metrics.ascent
    }
}
