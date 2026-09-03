package io.legado.app.eink.feature.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderTextStyle
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 阅读页绘制层（模块自持）。
 *
 * 绘制宿主映射来的 [ReaderPageSnapshot]：行 chunk 按预计算 x 坐标画字，
 * 图片槽位按铺满/等比居中画位图。排版本身由引擎（宿主 ChapterProvider）
 * 完成，这里不做二次排版。
 *
 * 渲染参数来自模块自身设置：字号/字距取 [style]（与写入引擎排版的同一
 * 来源），字体取端口正文字体（与引擎排版测量同源，否则字形宽度与列坐
 * 标错位）。标题 = 正文字号 + 加粗 + 正文体 —— 宿主排版配置中的标题类
 * 设置（标题字号/标题字重）被忽略，完整模式的斜体/阴影等显示效果也
 * 不跟随（E-Ink 渲染自治的既定取舍）。
 *
 * 字色随日/夜间主题每次绘制前钉上（首帧即正确，主题切换重组自动重绘）。
 * API35+ 的逐字字距半格补偿在绘制期按本画笔画笔度量计算（View 版画布
 * 同款），快照 x 保持引擎原始列起点。
 *
 * [pageVersion] 用于强制重绘（引擎可能原地更新同一排版实例后仅推版本号）。
 *
 * 文字画笔恒抗锯齿（对齐引擎 upStyle 硬编码 isAntiAlias = true）；图片
 * 画笔抗锯齿取全局设置 useAntiAlias。
 */
@Composable
internal fun ReaderPageSnapshotCanvas(
    page: ReaderPageSnapshot?,
    pageVersion: Int,
    style: ReaderTextStyle,
    modifier: Modifier = Modifier,
) {
    val themeTextColorArgb = EInkTheme.colorScheme.onBackground.toArgb()
    val imageAntiAlias = EInkEngineRegistry.globalSettings.useAntiAlias
    val contentTextTypeface = EInkEngineRegistry.readerEngine.contentTextTypeface
    val textSizePx = with(LocalDensity.current) { style.textSize.sp.toPx() }
    val contentPaint = remember(textSizePx, style.letterSpacing, contentTextTypeface) {
        Paint().apply {
            isAntiAlias = true
            textSize = textSizePx
            letterSpacing = style.letterSpacing
            typeface = contentTextTypeface ?: Typeface.DEFAULT
        }
    }
    val titlePaint = remember(textSizePx, style.letterSpacing, contentTextTypeface) {
        Paint().apply {
            isAntiAlias = true
            textSize = textSizePx
            letterSpacing = style.letterSpacing
            typeface = boldVariant(contentTextTypeface ?: Typeface.DEFAULT)
        }
    }
    val imagePaint = remember(imageAntiAlias) { Paint().apply { isAntiAlias = imageAntiAlias } }
    // 引擎可能原地更新同一排版实例，用版本号强制重建绘制块
    key(pageVersion) {
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            // API 35+ drawText 会将 letterSpacing 应用在两侧，绘制期补偿半格
            // （两支画笔字号字距一致，补偿值相同）
            val halfSpacing =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    contentPaint.letterSpacing * contentPaint.textSize * 0.5f
                } else {
                    0f
                }
            for (line in snapshot.lines) {
                val paint = if (line.isTitle) titlePaint else contentPaint
                for ((index, chunk) in line.chunks.withIndex()) {
                    nativeCanvas.drawText(chunk, line.x[index] + halfSpacing, line.baseY, paint)
                }
            }
            for (slot in snapshot.images) {
                drawImageSlot(nativeCanvas, slot, imagePaint)
            }
        }
    }
}

/**
 * 标题加粗变体：与引擎 textBold=1 同机制（API 28+ 可变字重 900，
 * 旧版回退粗体样式）。
 */
private fun boldVariant(base: Typeface): Typeface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base, 900, false)
    } else {
        @Suppress("DEPRECATION")
        Typeface.create(base, Typeface.BOLD)
    }

/**
 * 绘制图片槽位：按列宽×行高向宿主闭包取图，铺满（fullLine）或以宽度为
 * 基准等比居中（与 View 版 ImageColumn 一致）；取图失败/尺寸异常跳过。
 */
private fun drawImageSlot(canvas: Canvas, slot: ReaderImageSlot, paint: Paint) {
    val width = (slot.x1 - slot.x0).toInt()
    val height = slot.lineHeight.toInt()
    if (width <= 0 || height <= 0) return
    val bitmap = slot.loader(width, height) ?: return
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    val rectF = if (slot.fullLine) {
        RectF(slot.x0, slot.lineTop, slot.x1, slot.lineBottom)
    } else {
        val h = (slot.x1 - slot.x0) / bitmap.width * bitmap.height
        val div = (slot.lineHeight - h) / 2f
        RectF(slot.x0, slot.lineTop + div, slot.x1, slot.lineBottom - div)
    }
    canvas.drawBitmap(bitmap, null, rectF, paint)
}
