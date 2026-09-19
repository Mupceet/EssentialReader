package io.legado.app.eink.designsystem.refresh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 默认刷新策略的意图 → 档位映射：交互/文本输入受快速刷新能力降级
 * （宁可慢不可闪），全刷/清残影受全刷能力降级，翻页与内容稳定不降级。
 */
class DefaultEInkRefreshPolicyTest {

    /** 全能力设备。 */
    private val full = EInkDeviceProfile(
        grayscaleLevels = 16,
        supportsPartialRefresh = true,
        supportsFastRefresh = true,
        supportsFullRefresh = true,
        supportsColor = false,
        physicalPageKeys = true,
        touchInput = true,
        keyboardInput = true,
    )

    /** 无快速刷新。 */
    private val noFast = full.copy(supportsFastRefresh = false)

    /** 无全刷。 */
    private val noFull = full.copy(supportsFullRefresh = false)

    private fun decide(intent: EInkRefreshIntent, profile: EInkDeviceProfile) =
        DefaultEInkRefreshPolicy.decide(intent, profile)

    @Test
    fun `内容稳定恒为常规灰阶`() {
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.ContentStable, full))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.ContentStable, noFast))
    }

    @Test
    fun `交互与文本输入按快速刷新能力分档`() {
        assertEquals(EInkRefreshTier.Interactive, decide(EInkRefreshIntent.Interactive, full))
        assertEquals(EInkRefreshTier.Interactive, decide(EInkRefreshIntent.TextInput, full))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Interactive, noFast))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.TextInput, noFast))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Interactive, EInkDeviceProfile.Conservative))
    }

    @Test
    fun `翻页意图映射设备默认翻页档且不受能力降级`() {
        assertEquals(EInkRefreshTier.PageTurn, decide(EInkRefreshIntent.PageTurn, full))
        assertEquals(EInkRefreshTier.PageTurn, decide(EInkRefreshIntent.PageTurn, noFast))
        assertEquals(EInkRefreshTier.PageTurn, decide(EInkRefreshIntent.PageTurn, noFull))
    }

    @Test
    fun `导航与覆盖层恒为常规灰阶`() {
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Navigation, full))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Overlay, full))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Navigation, noFast))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.Overlay, noFull))
    }

    @Test
    fun `全刷与清残影按全刷能力分档`() {
        assertEquals(EInkRefreshTier.FullRedraw, decide(EInkRefreshIntent.FullRedraw, full))
        assertEquals(EInkRefreshTier.FullRedraw, decide(EInkRefreshIntent.ClearGhosting, full))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.FullRedraw, noFull))
        assertEquals(EInkRefreshTier.Stable, decide(EInkRefreshIntent.ClearGhosting, noFull))
        assertEquals(EInkRefreshTier.FullRedraw, decide(EInkRefreshIntent.ClearGhosting, EInkDeviceProfile.Conservative))
    }

    @Test
    fun `保守默认档只支持全刷的黑白触屏`() {
        val profile = EInkDeviceProfile.Conservative
        assertEquals(false, profile.supportsFastRefresh)
        assertEquals(true, profile.supportsFullRefresh)
        assertEquals(true, profile.touchInput)
    }
}
