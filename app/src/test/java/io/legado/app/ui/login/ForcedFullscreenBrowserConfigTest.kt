package io.legado.app.ui.login

import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 墨水屏全屏浏览器弹框的强制配置键级行为（org.json 需 Android 运行时，
 * Robolectric 与其余 app 测试同轨）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ForcedFullscreenBrowserConfigTest {

    @Test
    fun `无书源 config 时仅含强制键`() {
        val config = JSONObject(forcedFullscreenBrowserConfig(null))

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, config.getInt("state"))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT.toLong(), config.getLong("dialogHeight"))
        assertFalse(config.getBoolean("shouldDimBackground"))
        assertEquals(true, config.getBoolean("skipCollapsed"))
        assertEquals(true, config.getBoolean("isHideable"))
        assertEquals(true, config.getBoolean("pageControls"))
        assertEquals(6, config.length())
    }

    @Test
    fun `书源 config 的强制键被覆盖其余键保留`() {
        val sourceConfig = JSONObject()
            .put("state", BottomSheetBehavior.STATE_COLLAPSED)
            .put("heightPercentage", 0.6)
            .put("peekHeight", 400)
            .toString()

        val config = JSONObject(forcedFullscreenBrowserConfig(sourceConfig))

        // 强制键胜出
        assertEquals(BottomSheetBehavior.STATE_EXPANDED, config.getInt("state"))
        assertFalse(config.getBoolean("shouldDimBackground"))
        assertEquals(true, config.getBoolean("pageControls"))
        // 书源其余键原样保留
        assertEquals(0.6, config.getDouble("heightPercentage"), 0.0001)
        assertEquals(400, config.getInt("peekHeight"))
    }

    @Test
    fun `书源 config 解析失败时退化为仅强制键`() {
        val config = JSONObject(forcedFullscreenBrowserConfig("not-a-json{"))

        assertEquals(BottomSheetBehavior.STATE_EXPANDED, config.getInt("state"))
        assertEquals(6, config.length())
    }
}
