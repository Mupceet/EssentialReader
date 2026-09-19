package io.legado.app.eink.feature.bookshelf

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列宽主导的列数推导（格距 16dp、左右内容边距 16dp×2）：
 * 360dp 手机 + 120dp 封面宽 = 2 列；617dp 七英寸墨水屏 = 4 列；
 * 96dp（旧门槛）在 360dp 回到 3 列；可用宽不足单格时钳 1 列。
 */
class AdaptiveGridColumnsTest {

    @Test
    fun `360dp 与 120dp 推导 2 列`() = assertEquals(2, adaptiveGridColumns(360.dp, 120.dp))

    @Test
    fun `617dp 与 120dp 推导 4 列`() = assertEquals(4, adaptiveGridColumns(617.dp, 120.dp))

    @Test
    fun `360dp 与 96dp 推导 3 列`() = assertEquals(3, adaptiveGridColumns(360.dp, 96.dp))

    @Test
    fun `可用宽不足单格时钳制为 1 列`() = assertEquals(1, adaptiveGridColumns(50.dp, 120.dp))
}
