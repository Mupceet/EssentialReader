package io.legado.app.eink.contract

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_UNDEFINED
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Configuration.UI_MODE_TYPE_NORMAL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口基类系统深浅色解析：night 掩码位为 YES 视为深色，其余一律浅色。
 * 深浅 State 由 onConfigurationChanged 以下发的 newConfig 推进（入口包装
 * 冻结了 resources 配置，不能依赖 LocalConfiguration），此函数是其纯核。
 */
class ResolveEInkDarkThemeTest {

    @Test
    fun `night yes 判为深色且不受类型位影响`() {
        assertTrue(resolveEInkDarkTheme(UI_MODE_NIGHT_YES))
        assertTrue(resolveEInkDarkTheme(UI_MODE_NIGHT_YES or UI_MODE_TYPE_NORMAL))
    }

    @Test
    fun `night no 与 undefined 判为浅色`() {
        assertFalse(resolveEInkDarkTheme(UI_MODE_NIGHT_NO))
        assertFalse(resolveEInkDarkTheme(UI_MODE_NIGHT_UNDEFINED))
        assertFalse(resolveEInkDarkTheme(0))
    }
}
