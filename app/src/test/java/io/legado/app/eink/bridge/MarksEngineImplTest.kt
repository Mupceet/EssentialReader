package io.legado.app.eink.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MarksEngineImpl.kt 顶层纯函数行为锚定（thought 推导 / 确认文案）。 */
class MarksEngineImplTest {

    @Test
    fun `underlineMode 2 为想法，实线与空样式为划线`() {
        assertTrue(markingThought("""{"underlineMode":2}"""))
        assertFalse(markingThought("""{"underlineMode":1}"""))
        assertFalse(markingThought(null))
        assertFalse(markingThought("{bad"))
    }

    @Test
    fun `确认文案含章节名`() {
        val msg = confirmJumpMessage("第二章 坠落")
        assertTrue(msg.contains("第二章 坠落"))
    }
}
