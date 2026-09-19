package io.legado.app.eink.designsystem.control

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class EInkSteppedSliderTest {

    @Test
    fun `滑块宽度随字体缩放放大以容纳标签`() {
        val normal = Density(density = 3f, fontScale = 1f)
        val scaled = Density(density = 3f, fontScale = 1.6f)

        assertEquals(48f, sliderThumbWidth(normal).value, 0.01f)
        assertEquals(76.8f, sliderThumbWidth(scaled).value, 0.01f)
    }
}
