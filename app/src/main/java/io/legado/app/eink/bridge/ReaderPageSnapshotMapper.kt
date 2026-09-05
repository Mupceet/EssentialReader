package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [ReaderPageSnapshot]：上游 TextLine 的字段
 * 漂移（extraLetterSpacing/wordSpacing/isHtml 等）由本映射器消化，模块
 * 画布只有一份绘制实现。API35+ 的逐字字距半格补偿（View 版画布行为）
 * 在映射期算进 x 坐标，模块不再感知。
 *
 * 画笔规格只拷贝测量耦合参数（字号/字距/字体/可变字重）——快照列坐标
 * 是引擎按这些参数测量的，模块必须按同值绘制才不错位；阴影/斜体等纯
 * 视觉效果不跨桥（E-Ink 阅读不渲染，既定产品取舍）。
 *
 * 在引擎回调线程调用（onContentUpdated 内），产物不可变、跨线程安全。
 */
internal object ReaderPageSnapshotMapper {

    /** 生产入口：规格取自引擎当前共享画笔，补偿按真实 SDK 版本。 */
    fun map(page: TextPage): ReaderPageSnapshot =
        mapWithSpecs(
            page = page,
            titleSpec = ChapterProvider.titlePaint.copyPaintSpec(),
            contentSpec = ChapterProvider.contentPaint.copyPaintSpec(),
            sdkInt = Build.VERSION.SDK_INT,
            imageLoader = ::defaultImageLoader,
        )

    /** 纯函数核心（单测直接喂规格与 SDK 版本）。 */
    internal fun mapWithSpecs(
        page: TextPage,
        titleSpec: ReaderPaintSpec,
        contentSpec: ReaderPaintSpec,
        sdkInt: Int,
        imageLoader: (Book, String) -> (Int, Int) -> Bitmap?,
    ): ReaderPageSnapshot {
        val lines = ArrayList<ReaderPageLine>(page.lines.size)
        val images = ArrayList<ReaderImageSlot>()
        for (line in page.lines) {
            val spec = if (line.isTitle) titleSpec else contentSpec
            // API 35+ drawText 会将 letterSpacing 应用在两侧，View 版同样补偿半格
            val halfSpacing =
                if (sdkInt >= 35) spec.letterSpacing * spec.textSizePx * 0.5f else 0f
            val chunks = ArrayList<String>()
            val xs = ArrayList<Float>()
            for (column in line.columns) {
                when (column) {
                    is TextColumn -> {
                        chunks.add(column.charData)
                        xs.add(column.start + halfSpacing)
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
            titleSpec = titleSpec,
            contentSpec = contentSpec,
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

/**
 * 引擎画笔 → 渲染规格（只拷贝测量耦合参数；color 由模块主题自涂，
 * 阴影/斜体/linearText 等纯视觉效果不跨桥）。这些 getter 的 API 级别
 * 均 ≤ 26（= :app minSdk），无需门控。
 */
internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
    textSizePx = textSize, // API 1
    letterSpacing = letterSpacing, // API 21
    typeface = typeface, // API 1
    fontVariationSettings = fontVariationSettings, // API 26 = minSdk
)
