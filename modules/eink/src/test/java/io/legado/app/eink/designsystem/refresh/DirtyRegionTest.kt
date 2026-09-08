package io.legado.app.eink.designsystem.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脏区矩形数学：坐标四边包含、normalized 边序规整、Empty 哨兵为负宽高。
 */
class DirtyRegionTest {

    @Test
    fun `宽高按包含坐标计算`() {
        val region = DirtyRegion(left = 2, top = 3, right = 2, bottom = 3)
        assertEquals(1, region.width)
        assertEquals(1, region.height)
        assertEquals(10, DirtyRegion(0, 0, 9, 4).width)
        assertEquals(5, DirtyRegion(0, 0, 9, 4).height)
    }

    @Test
    fun `normalized 规整边序`() {
        val normalized = DirtyRegion(left = 8, top = 7, right = 1, bottom = 2).normalized()
        assertEquals(DirtyRegion(left = 1, top = 2, right = 8, bottom = 7), normalized)
        assertEquals(8, normalized.width)
        assertEquals(6, normalized.height)
        assertTrue(normalized.width > 0)
    }

    @Test
    fun `已规整区域 normalize 后不变`() {
        val region = DirtyRegion(1, 2, 8, 7)
        assertEquals(region, region.normalized())
    }

    @Test
    fun `Empty 哨兵为非正宽高`() {
        assertTrue(DirtyRegion.Empty.width <= 0)
        assertTrue(DirtyRegion.Empty.height <= 0)
    }
}
