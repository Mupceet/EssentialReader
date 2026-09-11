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
    fun `长标记起点在提示之前超旧回搜窗口时仍可定位`() {
        // v2 点按链把 selectedText 覆写为标记完整原文、以点按行内位置作 start
        // 提示：原文真实起点在提示之前，长标记（尤其跨页）前缀距离可超过旧
        // 回搜窗口 256，回搜距离须至少覆盖原文全长
        val prefix = "前".repeat(100)
        val marked = "选文头" + "中".repeat(444) + "选文尾" // 450 字符，全文唯一
        val suffix = "后".repeat(50)
        val longContent = prefix + marked + suffix // 共 600 字符
        val start = prefix.length
        // 提示位置在选文起点之后 400 字符处（仍在选文内部），旧窗口 256 回搜不到
        assertEquals(start, locateInContent(longContent, start + 400, marked))
    }

    @Test
    fun `找不到文本返回 -1`() {
        assertEquals(-1, locateInContent(content, 0, "不存在的文本"))
    }

    @Test
    fun `提示位漂移超窗口但全文唯一命中时仍可定位`() {
        // 选区文本在全文唯一（带段首标记），提示位被钳到文末、回搜窗口也够不到
        val text = "第0段" + "一二三"
        val textStart = content.indexOf(text)
        val farHint = content.length
        assertEquals(textStart, locateInContent(content, farHint, text))
    }

    @Test
    fun `全文多次命中且都在窗口外时不猜位置`() {
        // 两次命中都落在「提示位前看 256」与「回搜 256」之外：无歧义可循 → 从严 -1
        val filler = "甲".repeat(400)
        val text = "乙丙丁"
        val doubled = filler + text + filler + text + filler
        assertEquals(-1, locateInContent(doubled, 0, text))
    }

    @Test
    fun `uniqueOccurrence 只在唯一命中时给位置`() {
        assertEquals(2, uniqueOccurrence("abcdef", "cd"))
        assertEquals(-1, uniqueOccurrence("abcabc", "abc"))
        assertEquals(-1, uniqueOccurrence("abc", "zz"))
        assertEquals(-1, uniqueOccurrence("abc", ""))
    }

    @Test
    fun `上下文各取 48 字符并钳制边界`() {
        val start = 0
        val length = 10
        val (before, after) = extractContext(content, start, length)
        assertEquals("", before)
        assertEquals(48, after.length)
    }

    @Test
    fun `eink 划线样式为实线`() {
        val style = einkMarkingStyle(thought = false)
        assertEquals(1, style.underlineMode)
        // 纯黑：eink 页面按主题黑绘制，落库色同值 → 完整模式显示一致
        assertEquals(0xFF000000.toInt(), style.underlineColor)
        assertEquals(null, style.bgColor)
    }

    @Test
    fun `eink 想法样式为虚线`() {
        val style = einkMarkingStyle(thought = true)
        assertEquals(2, style.underlineMode)
        assertEquals(0xFF000000.toInt(), style.underlineColor)
        assertEquals(null, style.bgColor)
    }
}
