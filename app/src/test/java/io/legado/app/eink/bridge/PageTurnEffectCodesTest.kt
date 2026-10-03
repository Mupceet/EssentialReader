package io.legado.app.eink.bridge

import io.legado.app.eink.contract.PageTurnRippleMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 掌阅 EPDCDevice 水波纹 effect 编码对拍 qianshang-legado-eink 的
 * IReaderPageH（KOReader 方向映射）：effect = 方向码 or 速度位。方向码按
 * `rotation and 3` 查表（1=右 2=左 3=下 4=上）；速度位（对拍 qianshang
 * SPEED_BITS）慢速=128、标准=64、快速=0。
 */
class PageTurnEffectCodesTest {

    @Test
    fun `前进波纹按旋转查 NEXT 表`() {
        assertEquals(1, PageTurnEffectCodes.effectCode(forward = true, rotation = 0, mode = PageTurnRippleMode.FAST))
        assertEquals(4, PageTurnEffectCodes.effectCode(forward = true, rotation = 1, mode = PageTurnRippleMode.FAST))
        assertEquals(2, PageTurnEffectCodes.effectCode(forward = true, rotation = 2, mode = PageTurnRippleMode.FAST))
        assertEquals(3, PageTurnEffectCodes.effectCode(forward = true, rotation = 3, mode = PageTurnRippleMode.FAST))
    }

    @Test
    fun `后退波纹按旋转查 PREV 表`() {
        assertEquals(2, PageTurnEffectCodes.effectCode(forward = false, rotation = 0, mode = PageTurnRippleMode.FAST))
        assertEquals(3, PageTurnEffectCodes.effectCode(forward = false, rotation = 1, mode = PageTurnRippleMode.FAST))
        assertEquals(1, PageTurnEffectCodes.effectCode(forward = false, rotation = 2, mode = PageTurnRippleMode.FAST))
        assertEquals(4, PageTurnEffectCodes.effectCode(forward = false, rotation = 3, mode = PageTurnRippleMode.FAST))
    }

    @Test
    fun `速度位对拍 qianshang 慢速128 标准64 快速0`() {
        // 前进（方向码 1）× 三档速度位
        assertEquals(129, PageTurnEffectCodes.effectCode(forward = true, rotation = 0, mode = PageTurnRippleMode.SLOW))
        assertEquals(65, PageTurnEffectCodes.effectCode(forward = true, rotation = 0, mode = PageTurnRippleMode.STANDARD))
        assertEquals(1, PageTurnEffectCodes.effectCode(forward = true, rotation = 0, mode = PageTurnRippleMode.FAST))
        // 后退（方向码 2）× 三档速度位
        assertEquals(130, PageTurnEffectCodes.effectCode(forward = false, rotation = 0, mode = PageTurnRippleMode.SLOW))
        assertEquals(66, PageTurnEffectCodes.effectCode(forward = false, rotation = 0, mode = PageTurnRippleMode.STANDARD))
        assertEquals(2, PageTurnEffectCodes.effectCode(forward = false, rotation = 0, mode = PageTurnRippleMode.FAST))
    }

    @Test
    fun `旋转值按低两位规整`() {
        // Surface.ROTATION_* 恒为 0..3，防御异常旋转值（掩码同 qianshang）
        assertEquals(
            PageTurnEffectCodes.effectCode(forward = true, rotation = 0, mode = PageTurnRippleMode.SLOW),
            PageTurnEffectCodes.effectCode(forward = true, rotation = 4, mode = PageTurnRippleMode.SLOW),
        )
    }

    @Test
    fun `PAGE_H 强制模式码对拍固件常量`() {
        assertEquals(0x01000063, PageTurnEffectCodes.FORCE_NEXT_PAGE_H)
    }
}
