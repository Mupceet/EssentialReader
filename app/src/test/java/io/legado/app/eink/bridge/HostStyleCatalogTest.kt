package io.legado.app.eink.bridge

import io.legado.app.eink.contract.ReaderStyleParam
import io.legado.app.eink.contract.ReaderStyleParamIds as Ids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStyleCatalogTest {

    private val catalog = HostStyleCatalog.create()

    @Test
    fun `完整目录覆盖全部32个参数且全部可用`() {
        assertEquals(32, catalog.params.size)
        assertEquals(32, catalog.params.map { it.id }.toSet().size)
        assertTrue(catalog.params.all { it.available })
    }

    @Test
    fun `值域与默认值与宿主排版设置同源`() {
        fun stepped(id: String) = catalog.find(id) as ReaderStyleParam.Stepped
        assertEquals(5f..50f, stepped(Ids.BODY_SIZE).let { it.min..it.max })
        assertEquals(-0.5f..0.5f, stepped(Ids.BODY_LETTER_SPACING).let { it.min..it.max })
        assertEquals(0f..20f, stepped(Ids.BODY_LINE_SPACING).let { it.min..it.max })
        assertEquals(8f..60f, stepped(Ids.TITLE_SIZE).let { it.min..it.max })
        assertEquals(20f, stepped(Ids.TITLE_SIZE).default)
        assertEquals(100f..900f, stepped(Ids.BODY_WEIGHT).let { it.min..it.max })
        assertEquals(500f, stepped(Ids.BODY_WEIGHT).default)
        assertEquals(0f..200f, stepped(Ids.TITLE_TOP_SPACING).let { it.min..it.max })
        assertEquals(12f, stepped(Ids.HEADER_SIZE).default)
    }

    @Test
    fun `仅页眉页脚左右边距不影响分页`() {
        val paintOnly = setOf(
            Ids.HEADER_PADDING_LEFT, Ids.HEADER_PADDING_RIGHT,
            Ids.FOOTER_PADDING_LEFT, Ids.FOOTER_PADDING_RIGHT,
        )
        assertFalse(paintOnly.map { catalog.find(it)!!.affectsLayout }.any { it })
        assertTrue(
            catalog.params.filter { it.id !in paintOnly }.all { it.affectsLayout }
        )
    }

    @Test
    fun `标题位置选项与宿主语义同构`() {
        val choice = catalog.find(Ids.TITLE_MODE) as ReaderStyleParam.Choice
        assertEquals(listOf(0, 1, 2), choice.options.map { it.value })
        assertEquals(0, choice.default)
    }

    @Test
    fun `目录id成员恰为ReaderStyleParamIds全部常量`() {
        val declaredIds = Ids::class.java.fields
            .filter { it.type == String::class.java } // 排除 object 的 INSTANCE 字段
            .map { it.get(null) as String }
        assertEquals(32, declaredIds.size)
        assertEquals(declaredIds.toSet(), catalog.params.map { it.id }.toSet())
    }

    @Test
    fun `全部Stepped参数默认值落在值域内`() {
        catalog.params.filterIsInstance<ReaderStyleParam.Stepped>().forEach { param ->
            assertTrue(
                "${param.id} default=${param.default} 不在 ${param.min}..${param.max} 内",
                param.default >= param.min && param.default <= param.max,
            )
        }
    }
}
