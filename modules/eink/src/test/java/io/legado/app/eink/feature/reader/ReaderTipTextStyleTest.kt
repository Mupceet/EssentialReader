package io.legado.app.eink.feature.reader

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTipTextStyleTest {

    @Test
    fun `配置字号不随应用字体缩放放大`() {
        val unscaled = Density(density = 3f, fontScale = 1f)
        val scaled = Density(density = 3f, fontScale = 1.6f)

        val unscaledPx = with(unscaled) {
            configuredTipFontSizeSp(12, unscaled).toPx()
        }
        val scaledPx = with(scaled) {
            configuredTipFontSizeSp(12, scaled).toPx()
        }

        assertEquals(36f, unscaledPx, 0.01f)
        assertEquals(unscaledPx, scaledPx, 0.01f)
    }

    // --- 字号按渲染字体行需求钳制：固定 lineHeight 下长文本（StaticLayout 路径）
    // 行盒收窄、字形下缘被裁的回归；ratio 口径 = (bottom − top)/字号（自然行高） ---

    @Test
    fun `根因复现 CJK 自定义字体行需求超过行高预算时按比例缩到恰好放得下`() {
        // density=2：12sp 配置字号 → 请求 24px；行需求比 1.5em（思源系 CJK 自定义字体
        // (bottom−top)/textSize 的典型值）；可用行高 30px（宿主 extent 32px 扣 2dp
        // 进度条后）。请求行需求 24×1.5=36px > 30px → 透支 6px，旧实现下缘被裁。
        val clamped = clampTipFontSizeToAvailablePx(
            requestedFontSizePx = 24f,
            availablePx = 30f,
            fontLineRequirementRatio = 1.5f,
        )

        // 钳制后行需求恰好等于可用高度，字形完整落在行盒内。
        assertEquals(20f, clamped, 0.01f)
        assertTrue(clamped * 1.5f <= 30f + 0.01f)
    }

    @Test
    fun `行需求在预算内的字体不缩放保持配置字号`() {
        // 默认无字体（ratio=null）或常规字体（1.17em）请求行需求未超预算 → 原样返回。
        assertEquals(
            24f,
            clampTipFontSizeToAvailablePx(24f, 30f, null),
            0.01f,
        )
        assertEquals(
            24f,
            clampTipFontSizeToAvailablePx(24f, 30f, 1.17f),
            0.01f,
        )
        assertEquals(
            24f,
            clampTipFontSizeToAvailablePx(24f, 30f, 0f),
            0.01f,
        )
    }

    @Test
    fun `可用高度非正或异常 ratio 时不钳制`() {
        assertEquals(24f, clampTipFontSizeToAvailablePx(24f, 0f, 1.5f), 0.01f)
        assertEquals(24f, clampTipFontSizeToAvailablePx(24f, -3f, 1.5f), 0.01f)
    }

    @Test
    fun `推导字号路径同样受钳制`() {
        // 未配置字号时推导字号 = 可用高度×14/20=21px；行需求 1.5em → 需 31.5px > 30px。
        // 钳制语义与配置字号路径一致：任何字体组合下行需求 ≤ 可用高度。
        val requested = 30f * 14f / 20f
        val clamped = clampTipFontSizeToAvailablePx(requested, 30f, 1.5f)
        assertTrue(clamped * 1.5f <= 30f + 0.01f)
        assertEquals(20f, clamped, 0.01f)
    }
}
