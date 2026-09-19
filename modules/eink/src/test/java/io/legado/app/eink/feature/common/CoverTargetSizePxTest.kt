package io.legado.app.eink.feature.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面目标像素换算：Dp → px 向下取整。显示、预取与同步命中三处共用
 * 本函数生成同一缓存键，换算必须逐字节一致（此处锁定取整语义）。
 */
class CoverTargetSizePxTest {

    @Test
    fun `整倍率密度精确换算`() {
        assertEquals(132 to 180, coverTargetSizePx(66.dp, 90.dp, Density(2f)))
        assertEquals(66 to 90, coverTargetSizePx(66.dp, 90.dp, Density(1f)))
    }

    @Test
    fun `小数像素向下取整`() {
        assertEquals(99 to 135, coverTargetSizePx(66.dp, 90.dp, Density(1.5f)))
        // 66 × 1.1 = 72.6 → 72；90 × 1.1 = 99.000002（1.1f 浮点表示）→ 99
        assertEquals(72 to 99, coverTargetSizePx(66.dp, 90.dp, Density(1.1f)))
    }
}
