package io.legado.app.eink.bridge

import android.graphics.Bitmap
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [ReaderPageSnapshot]：上游 TextLine 的字段
 * 漂移（extraLetterSpacing/wordSpacing/isHtml 等）由本映射器消化，模块
 * 画布只有一份绘制实现。
 *
 * 快照只携带几何（x 为引擎排版原始列起点）：字号/字距/字体/粗体等渲染
 * 参数不进快照，模块画布按自身设置（ReaderTextStyle + 端口正文字体）
 * 渲染，宿主排版配置中的标题类设置被忽略。
 *
 * 在引擎回调线程调用（onUpContent 内），产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口。 */
    fun map(page: TextPage): ReaderPageSnapshot = mapPage(
        page = page,
        imageLoader = ::defaultImageLoader,
    )

    /** 纯函数核心（单测直接喂 imageLoader）。 */
    internal fun mapPage(
        page: TextPage,
        imageLoader: (Book, String) -> (Int, Int) -> Bitmap?,
    ): ReaderPageSnapshot {
        val lines = ArrayList<ReaderPageLine>(page.lines.size)
        val images = ArrayList<ReaderImageSlot>()
        for (line in page.lines) {
            val chunks = ArrayList<String>()
            val xs = ArrayList<Float>()
            for (column in line.columns) {
                when (column) {
                    is TextColumn -> {
                        chunks.add(column.charData)
                        xs.add(column.start)
                    }

                    is ImageColumn -> images.add(
                        ReaderImageSlot(
                            x0 = column.start,
                            x1 = column.end,
                            lineTop = line.lineTop,
                            lineBottom = line.lineBottom,
                            lineHeight = line.height,
                            fullLine = line.isImage,
                            loader = imageLoader(column.book, column.src),
                        )
                    )

                    else -> Unit // 评论列/HTML 列等：E-Ink 不渲染（同 View 画布 else 分支）
                }
            }
            if (chunks.isNotEmpty()) {
                lines.add(
                    ReaderPageLine(
                        baseY = line.lineBase,
                        isTitle = line.isTitle,
                        chunks = chunks,
                        x = xs.toFloatArray(),
                    )
                )
            }
        }
        return ReaderPageSnapshot(
            title = page.title,
            readProgress = page.readProgress,
            lines = lines,
            images = images,
        )
    }

    /**
     * 位图解析闭包：捕获创建时那一列的书（换书瞬间旧页不误取新书目录，
     * 与 View 版 ImageColumn 一致）；尺寸 ≤ 0 直接返回 null（对齐旧画布
     * 取图前防护），异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(book: Book, src: String): (Int, Int) -> Bitmap? =
        { w, h ->
            if (w <= 0 || h <= 0) {
                null
            } else {
                runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull()
            }
        }
}
