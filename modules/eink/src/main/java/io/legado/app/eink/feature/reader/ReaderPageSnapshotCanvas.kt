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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.legado.app.eink.contract.EInkEngineRegistry
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.feature.reader.selection.decorationSpanX

/**
 * 阅读页绘制层（模块自持）。
 *
 * 绘制宿主映射来的 [ReaderPageSnapshot]：行 chunk 按预计算 x 坐标画字，
 * 图片槽位按铺满/等比居中画位图。装饰（用户划线/高亮）按三遍绘制：
 * 高亮带垫在正文之下 → 正文/图片 → 下划线压在正文之上。排版本身由引擎
 * （宿主 ChapterProvider）完成，这里不做二次排版 —— 结果与 View 版
 * ContentTextView 一致。
 *
 * 字色随日/夜间主题每次绘制前钉上（首帧即正确，主题切换重组自动重绘）；
 * 画笔渲染规格（字号/字距/字体/可变字重）取自快照规格——这些参数与引擎
 * 排版测量耦合，必须按同值绘制才不错位。阴影/斜体等纯视觉效果不跨桥、
 * 不渲染（E-Ink 既定取舍，宿主配置了也不生效）。
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
    val themeHighlightArgb = EInkTheme.colorScheme.secondaryContainer.toArgb()
    val themePrimaryArgb = EInkTheme.colorScheme.primary.toArgb()
    val imageAntiAlias = EInkEngineRegistry.globalSettings.useAntiAlias
    // 页眉预留高度（px）：书签角标贴其下缘（正文顶缘）起画——宿主
    // ReaderBookmarkBadge 同位（topPx = contentTopPx）
    val headerExtentPx = EInkEngineRegistry.readerEngine.headerDecorationExtentPx
    val titlePaint = remember { Paint() }
    val contentPaint = remember { Paint() }
    val highlightPaint = remember { Paint() }
    val imagePaint = remember(imageAntiAlias) { Paint().apply { isAntiAlias = imageAntiAlias } }
    // 引擎可能原地更新同一排版实例，用版本号强制重建绘制块
    key(pageVersion) {
        Canvas(modifier = modifier) {
            val snapshot = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            titlePaint.applySpec(snapshot.titleSpec, themeTextColorArgb)
            contentPaint.applySpec(snapshot.contentSpec, themeTextColorArgb)

            // 1) 高亮带（正文之下）：secondaryContainer 实灰。与选区高亮带
            //    同一 token（ReaderSelectionOverlay 先例）——E-Ink 禁 alpha
            //    混灰（残影），surfaceVariant 在高对比灰阶板下与背景同值
            //    不可见；secondaryContainer 在灰阶板下为可辨实灰，高分板下
            //    亦与背景同值（装饰随选区带同一既定取舍：最大对比档不做灰底）。
            highlightPaint.color = themeHighlightArgb
            for (line in snapshot.lines) {
                for (run in line.decorations) {
                    if (!run.highlight) continue
                    // 测量闭包与正文绘制同一把尺（contentPaint 已按快照规格钉好）
                    val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                    nativeCanvas.drawRect(
                        span.first, line.top, span.second, line.bottom, highlightPaint,
                    )
                }
            }

            // 2) 文本与图片（既有逻辑不变）
            for (line in snapshot.lines) {
                val paint = if (line.isTitle) titlePaint else contentPaint
                for ((index, chunk) in line.chunks.withIndex()) {
                    nativeCanvas.drawText(chunk, line.x[index], line.baseY, paint)
                }
            }
            for (slot in snapshot.images) {
                drawImageSlot(nativeCanvas, slot, imagePaint)
            }

            // 3) 下划线（正文之上）：**几何全部取宿主导入的 run.underline**
            //    （y = 行盒下沿 + offsetPx、线宽/虚线节距/波浪/双线间距同源），
            //    与完整模式逐像素对齐；宿主未提供（旧宿主/缺省）时回落到
            //    EInkUnderlineDefaults（= 宿主 TextProcessStyle 默认几何）。
            //    模式 1 实线 / 2 虚线 / 3 波浪 / 4 双线原生绘制，5（SVG 花色）
            //    及未知值降级实线（与 contract 透传约定一致）
            for (line in snapshot.lines) {
                for (run in line.decorations) {
                    if (run.underlineMode == 0) continue
                    val span = decorationSpanX(line, run) { contentPaint.measureText(it) } ?: continue
                    val geometry = run.underline
                    val strokeWidth = geometry?.widthPx ?: EInkUnderlineDefaults.widthPx(density)
                    val y = line.bottom + (geometry?.offsetPx ?: EInkUnderlineDefaults.offsetPx(density))
                    when (run.underlineMode) {
                        2 -> drawDashedLine(
                            span.first, span.second, y, strokeWidth, themeTextColorArgb,
                            dashOnPx = geometry?.dashOnPx,
                            dashOffPx = geometry?.dashOffPx,
                        )
                        3 -> drawWaveLine(
                            span.first, span.second, y, strokeWidth, themeTextColorArgb,
                            waveLengthPx = geometry?.waveLengthPx,
                            waveAmplitudePx = geometry?.waveAmplitudePx,
                        )
                        4 -> {
                            // 双线：副线 = 主线 + 宿主间距 + 线宽（与宿主同公式）
                            drawSolidLine(span.first, span.second, y, strokeWidth, themeTextColorArgb)
                            drawSolidLine(
                                span.first, span.second,
                                y + (geometry?.doubleLineGapPx
                                    ?: EInkUnderlineDefaults.doubleLineGapPx(density)) + strokeWidth,
                                strokeWidth, themeTextColorArgb,
                            )
                        }

                        else -> drawSolidLine(span.first, span.second, y, strokeWidth, themeTextColorArgb)
                    }
                }
            }

            // 4) 页面书签角标（v2 Task 9，设计 §4/§6）：当前页带书签时在页眉
            //    避让区右缘画一枚实心书签折角（主题 primary，16×24dp，底缘
            //    中央内切缺口），贴正文顶缘、右缩进 6dp——宿主 ReaderBookmarkBadge
            //    同位（topPx = contentTopPx）。零动画，随页快照直切
            if (snapshot.bookmarkBadge) {
                val badgeWidth = 16.dp.toPx()
                val badgeHeight = 24.dp.toPx()
                val right = size.width - 6f * density
                val top = headerExtentPx
                val notch = badgeHeight * 0.25f
                val ribbon = Path().apply {
                    moveTo(right - badgeWidth, top)
                    lineTo(right, top)
                    lineTo(right, top + badgeHeight)
                    lineTo(right - badgeWidth / 2f, top + badgeHeight - notch)
                    lineTo(right - badgeWidth, top + badgeHeight)
                    close()
                }
                drawPath(ribbon, Color(themePrimaryArgb))
            }
        }
    }
}

/**
 * 模块内置下划线几何默认（dp→px）：**仅在宿主未透传几何时使用**（旧宿主 /
 * 契约缺省）。取值对齐宿主默认——`TextProcessStyle.underlineWidth = 1dp`、
 * `underlineOffset = 2dp`，虚线 8/5dp、波浪 12/3dp、双线间距 3dp
 * （LegacyReaderStyleRangeMapper / ReaderPageDecorationDrawCache 常量）。
 * 正常路径下几何由 [io.legado.app.eink.contract.ReaderUnderlineGeometry]
 * 逐段透传，两种模式逐像素一致。
 */
internal object EInkUnderlineDefaults {
    fun widthPx(density: Float): Float = 1f * density
    fun offsetPx(density: Float): Float = 2f * density
    fun dashOnPx(density: Float): Float = 8f * density
    fun dashOffPx(density: Float): Float = 5f * density
    fun waveAmplitudePx(density: Float): Float = 3f * density
    fun waveLengthPx(density: Float): Float = 12f * density
    fun doubleLineGapPx(density: Float): Float = 3f * density
}

/** 实线（Compose drawLine，方头）。零长区间画不出可见笔迹，安全。 */
private fun DrawScope.drawSolidLine(
    left: Float,
    right: Float,
    y: Float,
    strokeWidth: Float,
    colorArgb: Int,
) {
    drawLine(
        color = Color(colorArgb),
        start = Offset(left, y),
        end = Offset(right, y),
        strokeWidth = strokeWidth,
    )
}

/** 虚线：宿主未提供节距时用 8dp 墨 / 5dp 空（宿主 LegacyReaderStyleRangeMapper 默认）。 */
private fun DrawScope.drawDashedLine(
    left: Float,
    right: Float,
    y: Float,
    strokeWidth: Float,
    colorArgb: Int,
    dashOnPx: Float? = null,
    dashOffPx: Float? = null,
) {
    val path = Path().apply {
        moveTo(left, y)
        lineTo(right, y)
    }
    drawPath(
        path = path,
        color = Color(colorArgb),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(
                    (dashOnPx ?: EInkUnderlineDefaults.dashOnPx(density)).coerceAtLeast(0.1f),
                    (dashOffPx ?: EInkUnderlineDefaults.dashOffPx(density)).coerceAtLeast(0.1f),
                )
            ),
        ),
    )
}

/**
 * 波浪线：整波长 quadTo、控制点在段中点、幅度 ±3dp、波长 12dp——与宿主
 * ReaderPageDecorationDrawCache.createWavePath 同款几何（视觉周期为 2×波长）。
 * 零长区间（left==right）不进循环，空路径无绘制。
 */
private fun DrawScope.drawWaveLine(
    left: Float,
    right: Float,
    y: Float,
    strokeWidth: Float,
    colorArgb: Int,
    waveLengthPx: Float? = null,
    waveAmplitudePx: Float? = null,
) {
    val wavelength = (waveLengthPx ?: EInkUnderlineDefaults.waveLengthPx(density))
        .coerceAtLeast(0.1f)
    val amplitude = waveAmplitudePx ?: EInkUnderlineDefaults.waveAmplitudePx(density)
    val path = Path().apply {
        moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            val next = (x + wavelength).coerceAtMost(right)
            val mid = (x + next) / 2f
            if (up) {
                quadraticTo(mid, y - amplitude, next, y)
            } else {
                quadraticTo(mid, y + amplitude, next, y)
            }
            up = !up
            x = next
        }
    }
    drawPath(path, Color(colorArgb), style = Stroke(width = strokeWidth))
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
 * 把快照规格应用到画笔。规格只含测量耦合参数（字号/字距/字体/可变字重），
 * 阴影/斜体等纯视觉效果不跨桥、不渲染（E-Ink 既定取舍）。API35+ 的逐字
 * 半格补偿已在映射期算进 x，画笔字距保持引擎原值（单字符 drawText 的
 * 字形行为与 View 版一致）。
 *
 * internal：选区覆盖层（feature/reader/selection）复用同规格画笔做
 * 命中测试/高亮带测量，保证与正文绘制按同一字体度量。
 */
internal fun Paint.applySpec(spec: ReaderPaintSpec, colorArgb: Int) {
    // 引擎 upStyle 硬编码 isAntiAlias = true，显式对齐（不依赖 Paint() 默认 flags）
    isAntiAlias = true
    color = colorArgb
    textSize = spec.textSizePx
    letterSpacing = spec.letterSpacing
    typeface = spec.typeface ?: Typeface.DEFAULT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // 空字符串 = 清除可变字重设置（宿主 null 对应引擎未设置）
        fontVariationSettings = spec.fontVariationSettings ?: ""
    }
}
