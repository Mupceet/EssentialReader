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
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.designsystem.theme.EInkTheme

/**
 * 阅读页绘制层（模块自持）。
 *
 * 绘制宿主映射来的 [ReaderPageSnapshot]：行 chunk 按预计算 x 坐标画字，
 * 图片槽位按铺满/等比居中画位图。排版本身由引擎（宿主 ChapterProvider）
 * 完成，这里不做二次排版 —— 结果与 View 版 ContentTextView 一致。
 *
 * 字色随日/夜间主题每次绘制前钉上（首帧即正确，主题切换重组自动重绘）；
 * 画笔渲染规格（字号/字距/字体/斜体/阴影等）取自快照规格 —— 引擎配置与
 * 完整模式共享，完整模式设置的斜体/阴影在 E-Ink 同样可见。
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
    modifier: Modifier = Modifier,
) {
    val themeTextColorArgb = EInkTheme.colorScheme.onBackground.toArgb()
    val imageAntiAlias = EInkEngineRegistry.globalSettings.useAntiAlias
    val titlePaint = remember { Paint() }
    val contentPaint = remember { Paint() }
    val imagePaint = remember(imageAntiAlias) { Paint().apply { isAntiAlias = imageAntiAlias } }
    // 引擎可能原地更新同一排版实例，用版本号强制重建绘制块
    key(pageVersion) {
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            titlePaint.applySpec(snapshot.titleSpec, themeTextColorArgb)
            contentPaint.applySpec(snapshot.contentSpec, themeTextColorArgb)
            for (line in snapshot.lines) {
                val paint = if (line.isTitle) titlePaint else contentPaint
                for ((index, chunk) in line.chunks.withIndex()) {
                    nativeCanvas.drawText(chunk, line.x[index], line.baseY, paint)
                }
            }
            for (slot in snapshot.images) {
                drawImageSlot(nativeCanvas, slot, imagePaint)
            }
        }
    }
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

/**
 * 把快照规格应用到画笔。API35+ 的逐字半格补偿已在映射期算进 x，画笔
 * 字距保持引擎原值（单字符 drawText 的字形行为与 View 版一致）。
 */
private fun Paint.applySpec(spec: ReaderPaintSpec, colorArgb: Int) {
    // 引擎 upStyle 硬编码 isAntiAlias = true，显式对齐（不依赖 Paint() 默认 flags）
    isAntiAlias = true
    color = colorArgb
    textSize = spec.textSizePx
    letterSpacing = spec.letterSpacing
    typeface = spec.typeface ?: Typeface.DEFAULT
    textSkewX = spec.textSkewX
    isLinearText = spec.isLinearText
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // 空字符串 = 清除可变字重设置（宿主 null 对应引擎未设置）
        fontVariationSettings = spec.fontVariationSettings ?: ""
    }
    val shadow = spec.shadow
    if (shadow != null) {
        setShadowLayer(shadow.radius, shadow.dx, shadow.dy, shadow.color)
    } else {
        clearShadowLayer()
    }
}
