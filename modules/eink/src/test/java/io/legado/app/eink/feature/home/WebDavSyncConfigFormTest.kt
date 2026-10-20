package io.legado.app.eink.feature.home

import io.legado.app.eink.contract.WebDavSyncConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份与恢复页保存归一化与动作门控口径（纯函数行为基线）。
 */
class WebDavSyncConfigFormTest {

    @Test
    fun `空地址与文件夹回落产品默认`() {
        val normalized = normalizeWebDavConfig(
            WebDavSyncConfig("", "user@example.com", "secret", "", "墨水屏")
        )
        assertEquals(WebDavSyncConfig.DEFAULT_SERVER_URL, normalized.serverUrl)
        assertEquals(WebDavSyncConfig.DEFAULT_DIR, normalized.dir)
    }

    @Test
    fun `地址与文件夹保留用户输入不回落`() {
        val normalized = normalizeWebDavConfig(
            WebDavSyncConfig(
                "https://dav.example.com/dav/",
                "user@example.com",
                "secret",
                "books",
                "墨水屏"
            )
        )
        assertEquals("https://dav.example.com/dav/", normalized.serverUrl)
        assertEquals("books", normalized.dir)
    }

    @Test
    fun `地址账号文件夹设备名 trim`() {
        val normalized = normalizeWebDavConfig(
            WebDavSyncConfig(
                "  https://dav.jianguoyun.com/dav/ ",
                "  user@example.com  ",
                "secret",
                " legado ",
                " 墨水屏 "
            )
        )
        assertEquals("https://dav.jianguoyun.com/dav/", normalized.serverUrl)
        assertEquals("user@example.com", normalized.account)
        assertEquals("legado", normalized.dir)
        assertEquals("墨水屏", normalized.deviceName)
    }

    @Test
    fun `密码原样保留含首尾空格`() {
        // 宿主存储与校验均不 trim：首尾空格可能是合法密码组成
        val normalized = normalizeWebDavConfig(
            WebDavSyncConfig(
                serverUrl = "",
                account = "user@example.com",
                password = " pass word ",
                dir = "",
                deviceName = ""
            )
        )
        assertEquals(" pass word ", normalized.password)
    }

    @Test
    fun `已归一化配置幂等`() {
        val once = normalizeWebDavConfig(
            WebDavSyncConfig("", "u", "p", "", "d")
        )
        assertEquals(once, normalizeWebDavConfig(once))
    }

    @Test
    fun `cloudReady 要求账号非空白且密码非空`() {
        // 宿主 isConfigured 同口径：account.trim() 非空 && password 非空
        fun state(account: String, password: String) =
            WebDavSyncViewModel.UiState(
                config = WebDavSyncConfig("", account, password, "", "")
            )

        assertTrue(state("user@example.com", "secret").cloudReady)
        assertFalse(state("", "secret").cloudReady)
        assertFalse(state("   ", "secret").cloudReady)
        assertFalse(state("user@example.com", "").cloudReady)
    }
}
