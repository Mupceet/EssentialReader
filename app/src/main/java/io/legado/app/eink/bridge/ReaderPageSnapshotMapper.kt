package io.legado.app.eink.bridge

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import io.legado.app.eink.contract.ReaderImageSlot
import io.legado.app.eink.contract.ReaderPageLine
import io.legado.app.eink.contract.ReaderPageSnapshot
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * TextPage → 模块快照映射器（宿主唯一新增渲染职责）。
 *
 * 引擎排版完成后把页面映射为 [ReaderPageSnapshot]：上游排版结构的字段
 * 漂移由本映射器消化，模块画布只有一份绘制实现。API35+ 的逐字字距半格
 * 补偿在映射期算进 x 坐标，模块不再感知。
 *
 * 画笔规格只拷贝测量耦合参数（字号/字距/字体）——快照列坐标是引擎按
 * 这些参数测量的，模块必须按同值绘制才不错位；阴影/斜体等纯视觉效果
 * 不跨桥（E-Ink 阅读不渲染，既定产品取舍）。本宿主 minSdk 21：
 * 可变字重 getter 为 API 26，映射期按 SDK 门控（21~25 无可变字重排版，
 * 恒 null 安全）。
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
        imageLoader: (String) -> (Int, Int) -> Bitmap?,
    ): ReaderPageSnapshot {
        val lines = ArrayList<ReaderPageLine>(page.lines.size)
        val images = ArrayList<ReaderImageSlot>()
        for (line in page.lines) {
            val spec = if (line.isTitle) titleSpec else contentSpec
            // API 35+ drawText 会将 letterSpacing 应用在两侧，引擎画布同样补偿半格
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
                            loader = imageLoader(column.src),
                        )
                    )

                    else -> Unit // 评论列/HTML 列等：E-Ink 不渲染（同引擎画布 else 分支）
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
     * 位图解析闭包：本宿主 ImageColumn 无 book 字段（引擎绘制时读全局
     * ReadBook.book），映射期同样按映射时刻的全局书籍取图——换书瞬间
     * 旧页若仍绘制，取到新书的目录（与引擎画布行为一致）；尺寸 ≤ 0
     * 直接返回 null，异常吞并返回 null，由画布跳过槽位。
     */
    private fun defaultImageLoader(src: String): (Int, Int) -> Bitmap? =
        { w, h ->
            val book = ReadBook.book
            if (w <= 0 || h <= 0 || book == null) {
                null
            } else {
                runCatching { ImageProvider.getImage(book, src, w, h) }.getOrNull()
            }
        }
}

/**
 * 引擎画笔 → 渲染规格（只拷贝测量耦合参数；color 由模块主题自涂，
 * 阴影/斜体等纯视觉效果不跨桥）。fontVariationSettings 为 API 26，
 * minSdk 21 宿主需门控。
 */
internal fun Paint.copyPaintSpec(): ReaderPaintSpec = ReaderPaintSpec(
    textSizePx = textSize, // API 1
    letterSpacing = letterSpacing, // API 21
    typeface = typeface, // API 1
    fontVariationSettings =
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings else null, // API 26
)
