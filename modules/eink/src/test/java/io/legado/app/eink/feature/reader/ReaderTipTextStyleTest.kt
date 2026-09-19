package io.legado.app.eink.feature.reader

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
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
}
