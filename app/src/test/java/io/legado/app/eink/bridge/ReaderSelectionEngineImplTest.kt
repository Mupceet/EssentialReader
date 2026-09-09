package io.legado.app.eink.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** 选区定位与上下文提取纯函数（ReaderSelectionEngineImpl.kt 顶层）的行为锚定。 */
class ReaderSelectionEngineImplTest {

    private val content = buildString {
        repeat(3) { paragraph -> append("第${paragraph}段").append("一二三四五六七八九十".repeat(8)).append("\n") }
    }

    @Test
    fun `定位优先提示位置精确命中`() {
        val start = content.indexOf("第1段") + 3
        val text = content.substring(start, start + 10)
        assertEquals(start, locateInContent(content, start, text))
    }

    @Test
    fun `提示位置漂移越界时窗口回搜纠偏`() {
        // 纯正文在三段中周期重复、无法区分段落，选中文本带段首标记保证全文唯一
        val textStart = content.indexOf("第2段")
        val text = content.substring(textStart, textStart + 13)
        // 提示位置后漂 300（钳制到文末后从窗口回搜命中）
        assertEquals(textStart, locateInContent(content, textStart + 300, text))
    }

    @Test
    fun `找不到文本返回 -1`() {
        assertEquals(-1, locateInContent(content, 0, "不存在的文本"))
    }

    @Test
    fun `上下文各取 48 字符并钳制边界`() {
        val start = 0
        val length = 10
        val (before, after) = extractContext(content, start, length)
        assertEquals("", before)
        assertEquals(48, after.length)
    }
}
