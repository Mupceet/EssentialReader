package io.legado.app.eink.reader

import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 阅读页绘制层。
 *
 * 复用 View 版渲染引擎的排版结果：[ChapterProvider] 已完成测量与分页，
 * [TextPage] 的每个 TextLine 携带绝对坐标（lineBase / column start-end），
 * 这里仅按坐标把字符/图片画到 Compose Canvas 上，不做二次排版 ——
 * 与 View 版 ContentTextView 的绘制结果一致（字号、字距、行距、段距、
 * 缩进、边距全部由引擎决定）。
 *
 * [pageVersion] 用于强制重绘（同一 TextPage 实例在引擎内会被原地更新）。
 *
 * 正文字色随日/夜间主题（决策 B1 修订：不读取 textColorEInk 配置）：
 * 每次绘制前把主题色钉到引擎共享画笔 —— 首帧即正确，
 * [ChapterProvider.upStyle] 重建画笔后随下次绘制自动纠正，
 * 主题切换时重组生成新绘制块、自动重绘。
 *
 * 本上游差异：ImageColumn 自带创建时的 book 引用（换书瞬间旧页重绘
 * 不再误取新书目录）；抗锯齿开关经 [antiAlias] 参数注入（本仓架构护栏
 * 禁止 Compose 文件导入兼容 Config，取值走 EInkBridge.useAntiAlias）。
 */
@Composable
internal fun ReaderPageCanvas(
    page: TextPage?,
    pageVersion: Int,
    antiAlias: Boolean,
    modifier: Modifier = Modifier,
) {
    val imagePaint = remember(antiAlias) {
        Paint().apply { isAntiAlias = antiAlias }
    }
    // 引擎可能原地更新同一 TextPage 实例，用版本号强制重建绘制块
    val themeTextColorArgb = EInkTheme.colorScheme.onBackground.toArgb()
    key(pageVersion) {
        Canvas(modifier = modifier) {
            // 主题字色钉到引擎共享画笔（日/夜间随主题，见类注释）
            ChapterProvider.titlePaint.color = themeTextColorArgb
            ChapterProvider.contentPaint.color = themeTextColorArgb
            val textPage = page ?: return@Canvas
            val nativeCanvas = drawContext.canvas.nativeCanvas
            for (line in textPage.lines) {
                val paint = if (line.isTitle) {
                    ChapterProvider.titlePaint
                } else {
                    ChapterProvider.contentPaint
                }
                // API 35+ drawText 会将 letterSpacing 应用在两侧，View 版同样补偿半格
                val letterSpacingHalf =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        paint.letterSpacing * paint.textSize * 0.5f
                    } else {
                        0f
                    }
                for (column in line.columns) {
                    when (column) {
                        is TextColumn -> {
                            nativeCanvas.drawText(
                                column.charData,
                                column.start + letterSpacingHalf,
                                line.lineBase,
                                paint
                            )
                        }

                        is ImageColumn -> {
                            val width = (column.end - column.start).toInt()
                            val height = line.height.toInt()
                            if (width <= 0 || height <= 0) continue
                            kotlin.runCatching {
                                val bitmap = ImageProvider.getImage(
                                    column.book,
                                    column.src,
                                    width,
                                    height
                                )
                                if (bitmap.width <= 0 || bitmap.height <= 0) return@runCatching
                                val rectF = if (line.isImage) {
                                    RectF(column.start, line.lineTop, column.end, line.lineBottom)
                                } else {
                                    // 以宽度为基准保持原始比例叠加（与 View 版 ImageColumn 一致）
                                    val h = (column.end - column.start) / bitmap.width * bitmap.height
                                    val div = (line.height - h) / 2f
                                    RectF(
                                        column.start,
                                        line.lineTop + div,
                                        column.end,
                                        line.lineBottom - div
                                    )
                                }
                                nativeCanvas.drawBitmap(bitmap, null, rectF, imagePaint)
                            }
                        }

                        is ReviewColumn -> Unit // 评论列 E-Ink 阅读不渲染
                        else -> Unit
                    }
                }
            }
        }
    }
}
