package io.legado.app.eink.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 题签式文字封面的文本解析与截断：样例取自真实 AO3 书目（书名带
 * 【CP/题材】前缀与 (副标题) 尾缀），解析错档会把标签/副标题当书名
 * 排进题签，截断错档会塞满题签或在竖排里出现悬空标点。
 */
class EInkCoverTextTest {

    @Test
    fun `前缀标签与尾缀副标题同时剥除`() {
        val parsed = parseEInkCoverText("【五夏】欺身而上 (曾用标题:一树海棠压梨花)")
        assertEquals("五夏", parsed.tag)
        assertEquals("欺身而上", parsed.title)
    }

    @Test
    fun `半角括号尾缀剥除且不影响正文内标点`() {
        val parsed = parseEInkCoverText("【五夏/R】夜行山路，当心狐妖(pwp)")
        assertEquals("五夏/R", parsed.tag)
        assertEquals("夜行山路，当心狐妖", parsed.title)
    }

    @Test
    fun `全角括号尾缀剥除`() {
        val parsed = parseEInkCoverText("【五夏】专属419（连载中）")
        assertEquals("五夏", parsed.tag)
        assertEquals("专属419", parsed.title)
    }

    @Test
    fun `无标签书名保持原样`() {
        val parsed = parseEInkCoverText("不忠之人")
        assertNull(parsed.tag)
        assertEquals("不忠之人", parsed.title)
    }

    @Test
    fun `含斜杠的标签整段保留`() {
        val parsed = parseEInkCoverText("【五夏/ABO】理想国")
        assertEquals("五夏/ABO", parsed.tag)
        assertEquals("理想国", parsed.title)
    }

    @Test
    fun `多个连续前缀标签以空格连接`() {
        val parsed = parseEInkCoverText("【五夏】【先婚后爱】裸婚时代")
        assertEquals("五夏 先婚后爱", parsed.tag)
        assertEquals("裸婚时代", parsed.title)
    }

    @Test
    fun `正文中间的括号不剥除`() {
        val parsed = parseEInkCoverText("夜行(中间)山路")
        assertNull(parsed.tag)
        assertEquals("夜行(中间)山路", parsed.title)
    }

    @Test
    fun `只有闭括号不误剥`() {
        val parsed = parseEInkCoverText("欺身而上)")
        assertEquals("欺身而上)", parsed.title)
    }

    @Test
    fun `整名都是括号时回退为原名`() {
        val parenOnly = parseEInkCoverText("(pwp)")
        assertNull(parenOnly.tag)
        assertEquals("(pwp)", parenOnly.title)

        val tagOnly = parseEInkCoverText("【五夏】")
        assertNull(tagOnly.tag)
        assertEquals("【五夏】", tagOnly.title)
    }

    @Test
    fun `截断清掉截点句尾标点再补省略号`() {
        assertEquals("夜行山路，当心…", truncateEInkCoverTitle("夜行山路，当心狐妖", 8))
        assertEquals("夜行山路…", truncateEInkCoverTitle("夜行山路，当心狐妖", 5))
    }

    @Test
    fun `不超长不截断`() {
        assertEquals("欺身而上", truncateEInkCoverTitle("欺身而上", 8))
        assertEquals("一二三四五六七八九十", truncateEInkCoverTitle("一二三四五六七八九十", 12))
    }

    @Test
    fun `截断结果不超过展示位`() {
        val truncated = truncateEInkCoverTitle("夜行山路，当心狐妖", 8)
        assertEquals(8, truncated.length)
    }
}
