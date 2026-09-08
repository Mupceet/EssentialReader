package io.legado.app.eink.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 冷启动初始栈：默认仅书架；设置「启动直达最近阅读」时书架之上叠
 * 阅读页（底部返回栈保证返回可见书架）。
 */
class EInkInitialStackTest {

    @Test
    fun `无直达书时仅书架`() {
        assertEquals(listOf<EInkScreen>(EInkScreen.Home), initialStack(null))
    }

    @Test
    fun `有直达书时书架之上叠阅读页`() {
        assertEquals(
            listOf<EInkScreen>(EInkScreen.Home, EInkScreen.Reader("book-url")),
            initialStack("book-url"),
        )
    }
}
