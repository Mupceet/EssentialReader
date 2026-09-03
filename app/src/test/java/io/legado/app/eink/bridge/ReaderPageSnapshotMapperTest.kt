package io.legado.app.eink.bridge

import android.app.Application
import android.graphics.Bitmap
import io.legado.app.data.entities.Book
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
    ) = ReaderPageSnapshotMapper.mapPage(
        page = textPage(lines.toList()),
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
        val snapshot = ReaderPageSnapshotMapper.mapPage(
            page = textPage(listOf(line)),
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
        val snapshot = ReaderPageSnapshotMapper.mapPage(
            page = textPage(emptyList(), title = "第一章"),
            imageLoader = { _, _ -> { _, _ -> null } },
        )

        assertEquals("第一章", snapshot.title)
        assertEquals("0.0%", snapshot.readProgress)
    }
}
