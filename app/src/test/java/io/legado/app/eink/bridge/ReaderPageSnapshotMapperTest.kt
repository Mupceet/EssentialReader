package io.legado.app.eink.bridge

import android.app.Application
import android.graphics.Bitmap
import io.legado.app.data.entities.Book
import io.legado.app.eink.contract.ReaderPaintSpec
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageId
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

// application = Application::class 与其余 Robolectric 测试一致：不指定的话 Robolectric
// 会从 manifest 取真的 io.legado.app.App，App.onCreate() 要拉 Koin/DB/Cronet，单测里必炸。
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReaderPageSnapshotMapperTest {

    @Before
    fun setUp() {
        // ReaderTextStyle 等模型不读上下文，但映射链路统一按仓库惯例注入
        // 应用上下文，避免个别路径触碰 splitties appCtx。
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private val contentSpec = ReaderPaintSpec(
        textSizePx = 40f,
        letterSpacing = 0f,
        typeface = null,
        fontVariationSettings = null,
    )
    // ReaderPaintSpec 非 data class（无 copy），标题规格显式构造
    private val titleSpec = ReaderPaintSpec(
        textSizePx = 40f,
        letterSpacing = 0.1f,
        typeface = null,
        fontVariationSettings = null,
    )

    private val bodyStyle = ReaderTextStyle(colorArgb = 0, fontSizePx = 40f)

    private fun readerPage(elements: List<ReaderElement>, title: String = "章节标题") =
        ReaderPage(
            id = ReaderPageId(chapterIndex = 0, pageIndex = 0),
            chapterTitle = title,
            text = "正文",
            widthPx = 1000,
            heightPx = 1400,
            contentTopPx = 0f,
            contentBottomPx = 1400f,
            elements = elements,
            revision = 1L,
        )

    private fun textElement(
        x: Float,
        top: Float,
        value: String,
        emphasized: Boolean = false,
        baselinePx: Float = top + 40f,
        chapterPosition: Int = 0,
        height: Float = 50f,
    ) = ReaderElement.Text(
        bounds = ReaderRect(x, top, x + 20f, top + height),
        baselinePx = baselinePx,
        value = value,
        style = bodyStyle,
        selected = false,
        emphasized = emphasized,
        chapterPosition = chapterPosition,
    )

    private fun imageElement(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        src: String = "img.png",
    ) = ReaderElement.Image(
        bounds = ReaderRect(left, top, right, bottom),
        source = src,
        action = null,
    )

    private fun mapElements(
        vararg elements: ReaderElement,
        sdkInt: Int = 30,
        sessionBook: Book? = null,
    ) = ReaderPageSnapshotMapper.mapWithSpecs(
        page = readerPage(elements.toList()),
        titleSpec = titleSpec,
        contentSpec = contentSpec,
        sdkInt = sdkInt,
        sessionBook = sessionBook,
        readProgress = "12.3%",
        imageLoader = { _, _ -> { _, _ -> null } },
    )

    @Test
    fun `文本元素按行折叠为 chunk 与起点 x`() {
        val snapshot = mapElements(
            textElement(10f, 0f, "你"),
            textElement(30f, 0f, "好"),
        )

        assertEquals(1, snapshot.lines.size)
        val line = snapshot.lines[0]
        assertEquals(40f, line.baseY, 0.001f)
        assertFalse(line.isTitle)
        assertEquals(listOf("你", "好"), line.chunks)
        assertEquals(2, line.x.size)
        assertEquals(10f, line.x[0], 0.001f)
        assertEquals(30f, line.x[1], 0.001f)
    }

    @Test
    fun `不同行顶的元素拆为多行`() {
        val snapshot = mapElements(
            textElement(0f, 0f, "上"),
            textElement(0f, 50f, "下"),
        )

        assertEquals(2, snapshot.lines.size)
        assertEquals(40f, snapshot.lines[0].baseY, 0.001f)
        assertEquals(90f, snapshot.lines[1].baseY, 0.001f)
    }

    @Test
    fun `API35 以上加字距半格补偿`() {
        // contentSpec.letterSpacing=0、textSizePx=40 → 补偿 0
        val s1 = mapElements(textElement(10f, 0f, "a"), sdkInt = 35)
        assertEquals(10f, s1.lines[0].x[0], 0.001f)

        // 标题元素：titleSpec.letterSpacing=0.1、textSizePx=40 → 补偿 2.0
        val s2 = mapElements(textElement(10f, 0f, "a", emphasized = true), sdkInt = 35)
        assertEquals(12f, s2.lines[0].x[0], 0.001f)

        // API35 以下无补偿
        val s3 = mapElements(textElement(10f, 0f, "a", emphasized = true), sdkInt = 34)
        assertEquals(10f, s3.lines[0].x[0], 0.001f)
    }

    @Test
    fun `标题元素携带 isTitle 标记`() {
        val snapshot = mapElements(
            textElement(0f, 0f, "题", emphasized = true),
            textElement(0f, 50f, "文"),
        )

        assertTrue(snapshot.lines[0].isTitle)
        assertFalse(snapshot.lines[1].isTitle)
    }

    @Test
    fun `行携带元素章内位置与行盒`() {
        val snapshot = mapElements(
            textElement(0f, 10f, "第一段第一行", chapterPosition = 0, height = 20f, baselinePx = 28f),
            textElement(0f, 34f, "第一段第二行", chapterPosition = 7, height = 20f, baselinePx = 52f),
            sdkInt = 34,
        )

        val line0 = snapshot.lines[0]
        assertArrayEquals(intArrayOf(0), line0.chapterPositions)
        assertEquals(10f, line0.top, 0.001f)
        assertEquals(30f, line0.bottom, 0.001f)
        val line1 = snapshot.lines[1]
        assertArrayEquals(intArrayOf(7), line1.chapterPositions)
        assertEquals(34f, line1.top, 0.001f)
        assertEquals(54f, line1.bottom, 0.001f)
        // 装饰桥本任务只落契约：映射侧恒空，Task 2 接入提取
        assertTrue(line0.decorations.isEmpty())
    }

    @Test
    fun `同行元素行盒底取元素最大值`() {
        val snapshot = mapElements(
            textElement(0f, 10f, "上", height = 20f, baselinePx = 28f),
            textElement(30f, 10f, "下", height = 30f, baselinePx = 28f),
        )

        assertEquals(1, snapshot.lines.size)
        assertEquals(10f, snapshot.lines[0].top, 0.001f)
        assertEquals(40f, snapshot.lines[0].bottom, 0.001f)
    }

    @Test
    fun `图片元素成为槽位并原样透传最终布局矩形`() {
        val loaderCalls = mutableListOf<Pair<Int, Int>>()
        val loader: (Book, String) -> (Int, Int) -> Bitmap? = { _, _ ->
            { w, h ->
                loaderCalls.add(w to h)
                null
            }
        }
        val book = Book()
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = readerPage(listOf(imageElement(5f, 100f, 25f, 150f))),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            sessionBook = book,
            readProgress = "0.0%",
            imageLoader = loader,
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(1, snapshot.images.size)
        val slot = snapshot.images[0]
        // 新引擎图片元素自带最终布局矩形（缩放/居中已定），槽位整框透传
        assertEquals(5f, slot.x0, 0.001f)
        assertEquals(25f, slot.x1, 0.001f)
        assertEquals(100f, slot.lineTop, 0.001f)
        assertEquals(150f, slot.lineBottom, 0.001f)
        assertEquals(50f, slot.lineHeight, 0.001f)
        assertTrue(slot.fullLine)
        // loader 透传调用方传入的 w/h
        assertNull(slot.loader(20, 50))
        assertEquals(listOf(20 to 50), loaderCalls)
    }

    @Test
    fun `无会话书时图片槽位 loader 恒空`() {
        val snapshot = mapElements(imageElement(0f, 0f, 40f, 40f))

        assertNull(snapshot.images[0].loader(20, 20))
    }

    @Test
    fun `文本与行内嵌图共存时各自保留`() {
        val snapshot = mapElements(
            textElement(0f, 0f, "文"),
            imageElement(20f, 0f, 40f, 40f),
            textElement(40f, 0f, "字"),
        )

        // 图片打断文本行：文本拆为同基线的两段（绝对 x 坐标绘制，视觉不变）
        assertEquals(2, snapshot.lines.size)
        assertEquals(listOf("文"), snapshot.lines[0].chunks)
        assertEquals(listOf("字"), snapshot.lines[1].chunks)
        assertEquals(1, snapshot.images.size)
        assertTrue(snapshot.images[0].fullLine)
    }

    @Test
    fun `评论等非文本元素不进入快照`() {
        val snapshot = mapElements(
            ReaderElement.Review(
                bounds = ReaderRect(0f, 0f, 10f, 10f),
                count = 3,
                paragraphIndex = 0,
            ),
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(0, snapshot.images.size)
    }

    @Test
    fun `快照携带标题与进度文本`() {
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = readerPage(emptyList(), title = "第一章"),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            sessionBook = null,
            readProgress = "12.3%",
            imageLoader = { _, _ -> { _, _ -> null } },
        )

        assertEquals("第一章", snapshot.title)
        assertEquals("12.3%", snapshot.readProgress)
    }

    // ==== 进度文本公式（沿用旧 TextPage.readProgress）====

    @Test
    fun `进度公式覆盖零状态与章节折算分支`() {
        // 章节数为 0：恒 0.0%
        assertEquals(
            "0.0%",
            ReaderPageSnapshotMapper.readProgress(
                chapterIndex = 0, localPageIndex = 0, chapterPageCount = 0, chapterSize = 0,
            ),
        )
        // 章节未分页且为首章：命中旧公式守卫，恒 0.0%
        assertEquals(
            "0.0%",
            ReaderPageSnapshotMapper.readProgress(
                chapterIndex = 0, localPageIndex = 0, chapterPageCount = 0, chapterSize = 2,
            ),
        )
        // 章节未分页（非首章）：按章节序号折算（该分支与旧实现一致，不做末页钳制）
        assertEquals(
            "100.0%",
            ReaderPageSnapshotMapper.readProgress(
                chapterIndex = 1, localPageIndex = 0, chapterPageCount = 0, chapterSize = 2,
            ),
        )
        // 正常页：章节进度 + 页内折算
        assertEquals(
            "37.5%",
            ReaderPageSnapshotMapper.readProgress(
                chapterIndex = 1, localPageIndex = 1, chapterPageCount = 4, chapterSize = 4,
            ),
        )
        // 末章末页：保持 100.0%
        assertEquals(
            "100.0%",
            ReaderPageSnapshotMapper.readProgress(
                chapterIndex = 1, localPageIndex = 0, chapterPageCount = 1, chapterSize = 2,
            ),
        )
    }

    // ==== 画笔规格拷贝（Robolectric：需要 android.graphics 原生行为）====

    @Test
    fun `画笔规格拷贝测量耦合属性`() {
        val paint = android.text.TextPaint().apply {
            textSize = 42f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val spec = paint.copyPaintSpec()

        assertEquals(42f, spec.textSizePx, 0.001f)
        assertEquals(0.08f, spec.letterSpacing, 0.0001f)
        assertEquals(android.graphics.Typeface.MONOSPACE, spec.typeface)
        // fontVariationSettings：Robolectric 4.16 ShadowPaint 未实现 get/set
        // 往返，不做断言（属 shadow 能力限制）；阴影/斜体不在规格内（E-Ink 不渲染）。
    }
}
