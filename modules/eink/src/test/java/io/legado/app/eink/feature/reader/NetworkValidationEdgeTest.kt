package io.legado.app.eink.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NET_CAPABILITY_VALIDATED 边沿状态机锚定：单次连接周期内
 * 未验证→已验证只放行一次；onLost 重置后允许下一次连接周期的边沿。
 */
class NetworkValidationEdgeTest {

    @Test
    fun `未验证到已验证触发一次`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        assertFalse(edge.onCapabilities(true)) // 已验证态重复上报不再触发
    }

    @Test
    fun `保持未验证不触发`() {
        val edge = NetworkValidationEdge()
        assertFalse(edge.onCapabilities(false))
        assertFalse(edge.onCapabilities(false))
    }

    @Test
    fun `掉回未验证后再验证再次触发`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        assertFalse(edge.onCapabilities(false)) // 掉回未验证：不触发，只降状态
        assertTrue(edge.onCapabilities(true))
    }

    @Test
    fun `onLost 重置后重新验证触发`() {
        val edge = NetworkValidationEdge()
        assertTrue(edge.onCapabilities(true))
        edge.onLost()
        assertTrue(edge.onCapabilities(true))
    }
}
