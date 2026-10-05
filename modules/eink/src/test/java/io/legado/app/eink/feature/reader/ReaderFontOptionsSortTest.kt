package io.legado.app.eink.feature.reader

import io.legado.app.eink.contract.ReaderFontOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字体选择列表排序：名称（去扩展名）升序，中文按简体拼音。
 */
class ReaderFontOptionsSortTest {

    @Test
    fun `中文名按拼音升序`() {
        val sorted = sortFontOptions(
            listOf(
                ReaderFontOption("霞鹜文楷.ttf", "path-1"),
                ReaderFontOption("思源黑体.otf", "path-2"),
            ),
        )
        assertEquals(
            listOf("思源黑体.otf", "霞鹜文楷.ttf"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `拉丁名排在中文名前`() {
        val sorted = sortFontOptions(
            listOf(
                ReaderFontOption("霞鹜文楷.ttf", "path-1"),
                ReaderFontOption("NotoSerifCJK.otf", "path-2"),
            ),
        )
        assertEquals(
            listOf("NotoSerifCJK.otf", "霞鹜文楷.ttf"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `显示名相同按原文件名排序`() {
        val sorted = sortFontOptions(
            listOf(
                ReaderFontOption("SourceHanSerif.ttf", "path-1"),
                ReaderFontOption("SourceHanSerif.otf", "path-2"),
            ),
        )
        assertEquals(
            listOf("SourceHanSerif.otf", "SourceHanSerif.ttf"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `输入顺序不影响结果且返回新列表`() {
        val input = listOf(
            ReaderFontOption("b.ttf", "path-1"),
            ReaderFontOption("a.otf", "path-2"),
            ReaderFontOption("c.ttf", "path-3"),
        )
        assertEquals(
            listOf("a.otf", "b.ttf", "c.ttf"),
            sortFontOptions(input).map { it.name },
        )
        assertEquals(listOf("b.ttf", "a.otf", "c.ttf"), input.map { it.name })
    }
}
