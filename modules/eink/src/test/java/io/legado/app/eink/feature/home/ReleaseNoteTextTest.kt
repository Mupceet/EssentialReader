package io.legado.app.eink.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发布说明 markdown → 纯文本剥离规则（更新弹层不引 markdown 渲染，
 * 语法符号最小剥离的行为基线）。
 */
class ReleaseNoteTextTest {

    @Test
    fun `heading markers are stripped`() {
        assertEquals(
            "更新日志\n标题",
            releaseNoteToPlainText("# 更新日志\n## 标题")
        )
    }

    @Test
    fun `bold markers keep inner text`() {
        assertEquals(
            "必读内容",
            releaseNoteToPlainText("**必读**内容")
        )
    }

    @Test
    fun `links keep label only`() {
        assertEquals(
            "致谢 legado 开源阅读",
            releaseNoteToPlainText("致谢 [legado 开源阅读](https://github.com/gedoor/legado)")
        )
    }

    @Test
    fun `bullet markers are stripped but text kept`() {
        assertEquals(
            "第一项\n第二项",
            releaseNoteToPlainText("* 第一项\n- 第二项")
        )
    }

    @Test
    fun `divider line becomes single blank line`() {
        assertEquals(
            "上段\n\n下段",
            releaseNoteToPlainText("上段\n----\n下段")
        )
    }

    @Test
    fun `consecutive blank lines are squeezed and edges trimmed`() {
        assertEquals(
            "首行\n\n尾行",
            releaseNoteToPlainText("\n\n首行\n\n\n\n尾行\n\n")
        )
    }

    @Test
    fun `empty note stays empty`() {
        assertEquals("", releaseNoteToPlainText(""))
    }

    @Test
    fun `real first release note is fully flattened`() {
        val note = """
            # 更新日志

            墨本阅读——**同一本书，两种读法**。

            ## 3.26.17（测试）

            * 新增：我的页「检查更新」入口
            * 详见 [发布页](https://github.com/Mupceet/EssentialReader)

            ----

            * 历史日志略
        """.trimIndent()
        assertEquals(
            "更新日志\n\n墨本阅读——同一本书，两种读法。\n\n3.26.17（测试）\n\n新增：我的页「检查更新」入口\n详见 发布页\n\n历史日志略",
            releaseNoteToPlainText(note)
        )
    }
}
