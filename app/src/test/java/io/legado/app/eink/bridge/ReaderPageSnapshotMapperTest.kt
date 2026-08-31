package io.legado.app.eink.bridge

import android.app.Application
import android.graphics.Bitmap
import io.legado.app.data.entities.Book
import io.legado.app.eink.engine.ReaderPaintSpec
import io.legado.app.eink.engine.ReaderShadowSpec
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
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
        // TextPage 构造/读取链路经 splitties appCtx 读资源；startup ContentProvider
        // 在 Robolectric 下不运行，按仓库惯例手动注入应用上下文。
        RuntimeEnvironment.getApplication().injectAsAppCtx()
    }

    private val contentSpec = ReaderPaintSpec(
        textSizePx = 40f,
        letterSpacing = 0f,
        typeface = null,
        fontVariationSettings = null,
        textSkewX = 0f,
        isLinearText = false,
        shadow = null,
    )
    // ReaderPaintSpec 非 data class（无 copy），标题规格显式构造
    private val titleSpec = ReaderPaintSpec(
        textSizePx = 40f,
        letterSpacing = 0.1f,
        typeface = null,
        fontVariationSettings = null,
        textSkewX = 0f,
        isLinearText = false,
        shadow = null,
    )

    private fun textPage(lines: List<TextLine>, title: String = "章节标题"): TextPage =
        TextPage(
            index = 0,
            text = "正文",
            title = title,
            textLines = ArrayList(lines),
            chapterSize = 1,
            chapterIndex = 0,
            height = 0f,
            leftLineSize = 0,
            renderHeight = 0,
        )

    private fun textLine(
        isTitle: Boolean = false,
        isImage: Boolean = false,
        lineTop: Float = 0f,
        lineBase: Float = 0f,
        lineBottom: Float = 0f,
        columns: List<BaseColumn> = emptyList(),
    ): TextLine = TextLine(
        text = "line",
        textColumns = ArrayList(columns),
        lineTop = lineTop,
        lineBase = lineBase,
        lineBottom = lineBottom,
        isTitle = isTitle,
        isImage = isImage,
    )

    private fun textCol(start: Float, char: String) =
        TextColumn(start = start, end = start + 20f, charData = char)

    private fun mapLines(
        vararg lines: TextLine,
        sdkInt: Int = 30,
    ) = ReaderPageSnapshotMapper.mapWithSpecs(
        page = textPage(lines.toList()),
        titleSpec = titleSpec,
        contentSpec = contentSpec,
        sdkInt = sdkInt,
        imageLoader = { _, _ -> { _, _ -> null } },
    )

    @Test
    fun `文本列按 chunk 与起点 x 映射`() {
        val snapshot = mapLines(
            textLine(lineBase = 50f, columns = listOf(textCol(10f, "你"), textCol(30f, "好")))
        )

        assertEquals(1, snapshot.lines.size)
        val line = snapshot.lines[0]
        assertEquals(50f, line.baseY, 0.001f)
        assertFalse(line.isTitle)
        assertEquals(listOf("你", "好"), line.chunks)
        assertEquals(2, line.x.size)
        assertEquals(10f, line.x[0], 0.001f)
        assertEquals(30f, line.x[1], 0.001f)
    }

    @Test
    fun `API35 以上加字距半格补偿`() {
        // contentSpec.letterSpacing=0、textSizePx=40 → 补偿 0
        val s1 = mapLines(textLine(columns = listOf(textCol(10f, "a"))), sdkInt = 35)
        assertEquals(10f, s1.lines[0].x[0], 0.001f)

        // 标题行：titleSpec.letterSpacing=0.1、textSizePx=40 → 补偿 2.0
        val s2 = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(10f, "a"))),
            sdkInt = 35,
        )
        assertEquals(12f, s2.lines[0].x[0], 0.001f)

        // API35 以下无补偿
        val s3 = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(10f, "a"))),
            sdkInt = 34,
        )
        assertEquals(10f, s3.lines[0].x[0], 0.001f)
    }

    @Test
    fun `标题行携带 isTitle 标记`() {
        val snapshot = mapLines(
            textLine(isTitle = true, columns = listOf(textCol(0f, "题"))),
            textLine(columns = listOf(textCol(0f, "文"))),
        )

        assertTrue(snapshot.lines[0].isTitle)
        assertFalse(snapshot.lines[1].isTitle)
    }

    @Test
    fun `图片列成为槽位并携带行盒几何`() {
        val loaderCalls = mutableListOf<Pair<Int, Int>>()
        val loader: (Book, String) -> (Int, Int) -> Bitmap? = { _, _ ->
            { w, h ->
                loaderCalls.add(w to h)
                null
            }
        }
        val book = Book()
        val imageColumn = ImageColumn(start = 5f, end = 25f, src = "img.png", book = book)
        val line = textLine(
            isImage = true,
            lineTop = 100f,
            lineBottom = 150f,
            columns = listOf(imageColumn),
        )
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = textPage(listOf(line)),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            imageLoader = loader,
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(1, snapshot.images.size)
        val slot = snapshot.images[0]
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
    fun `行内嵌图 fullLine 为 false`() {
        val imageColumn = ImageColumn(start = 5f, end = 25f, src = "img.png", book = Book())
        val snapshot = mapLines(
            textLine(isImage = false, lineTop = 0f, lineBottom = 50f, columns = listOf(imageColumn))
        )

        assertFalse(snapshot.images[0].fullLine)
    }

    @Test
    fun `混合行文本与行内嵌图共存`() {
        val snapshot = mapLines(
            textLine(
                lineBase = 40f,
                lineTop = 0f,
                lineBottom = 50f,
                columns = listOf(
                    textCol(0f, "文"),
                    ImageColumn(start = 20f, end = 40f, src = "img.png", book = Book()),
                ),
            )
        )

        assertEquals(1, snapshot.lines.size)
        assertEquals(listOf("文"), snapshot.lines[0].chunks)
        assertEquals(1, snapshot.images.size)
        assertFalse(snapshot.images[0].fullLine)
    }

    @Test
    fun `评论列等非文本列不进入快照`() {
        val snapshot = mapLines(
            textLine(columns = listOf(ReviewColumn(start = 0f, end = 10f)))
        )

        assertEquals(0, snapshot.lines.size)
        assertEquals(0, snapshot.images.size)
    }

    @Test
    fun `快照携带标题与进度文本`() {
        val snapshot = ReaderPageSnapshotMapper.mapWithSpecs(
            page = textPage(emptyList(), title = "第一章"),
            titleSpec = titleSpec,
            contentSpec = contentSpec,
            sdkInt = 30,
            imageLoader = { _, _ -> { _, _ -> null } },
        )

        assertEquals("第一章", snapshot.title)
        assertEquals("0.0%", snapshot.readProgress)
    }

    // ==== 画笔规格拷贝（Robolectric：需要 android.graphics 原生行为）====

    @Test
    fun `画笔规格全量拷贝引擎画笔属性`() {
        val paint = android.text.TextPaint().apply {
            textSize = 42f
            letterSpacing = 0.08f
            textSkewX = -0.25f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        // 阴影不读 Paint（shadowLayer* getter 系 API 29+，minSdk 26 必崩），
        // 生产路径经 shadowSpecFromConfig 从 ReadBookConfig 取值后传入。
        val spec = paint.copyPaintSpec(shadow = null)

        assertEquals(42f, spec.textSizePx, 0.001f)
        assertEquals(0.08f, spec.letterSpacing, 0.0001f)
        assertEquals(android.graphics.Typeface.MONOSPACE, spec.typeface)
        assertEquals(-0.25f, spec.textSkewX, 0.0001f)
        // isLinearText / fontVariationSettings：Robolectric 4.16 ShadowPaint 未实现
        // setLinearText/getFontVariationSettings，set/get 不保真，不做断言（属 shadow 能力限制）。
    }

    @Test
    fun `阴影规格按参数原样进入规格`() {
        val paint = android.text.TextPaint()
        val shadow = ReaderShadowSpec(radius = 4f, dx = 1f, dy = 2f, color = 0xFF00FF00.toInt())
        val spec = paint.copyPaintSpec(shadow)

        assertEquals(shadow, spec.shadow)
    }
}
