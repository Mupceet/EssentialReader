package io.legado.app.eink.designsystem.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版阶梯与字重角色守护：
 * - 阶梯：14sp 是地板不是聚点——body 18/16/14、title 22/18/16、label 16/14/14；
 * - 字重按角色：body 三档统一 Normal（防字重倒挂 + ROM medium 档黑洞），
 *   其余角色 Bold；
 * - 几何行高契约：titleMedium(24)/bodySmall(16)/labelMedium(16) 被书架列表
 *   行高、网格标题高等几何单点消费，变动即布局密度变化，改动须有意为之；
 * - 宿主 UI 字体钩子（EInkTheme.fontFamily）挂载全部 15 个样式。
 */
class EInkThemeTypographyTest {

    @Test
    fun `body 族统一 Normal 避免字重倒挂与 ROM medium 黑洞`() {
        listOf(
            EInkTypography.bodyLarge,
            EInkTypography.bodyMedium,
            EInkTypography.bodySmall,
        ).forEach { style ->
            assertEquals(FontWeight.Normal, style.fontWeight)
        }
    }

    @Test
    fun `尺寸阶梯 body 18-16-14 title 22-18-16 label 16-14-14`() {
        // body：Material 16/14/12 因 14sp 下限整体 +2
        assertEquals(18f, EInkTypography.bodyLarge.fontSize.value)
        assertEquals(16f, EInkTypography.bodyMedium.fontSize.value)
        assertEquals(14f, EInkTypography.bodySmall.fontSize.value)
        // title
        assertEquals(22f, EInkTypography.titleLarge.fontSize.value)
        assertEquals(18f, EInkTypography.titleMedium.fontSize.value)
        assertEquals(16f, EInkTypography.titleSmall.fontSize.value)
        // label：medium/small 贴 14sp 下限（Material 12/11 被钳制）
        assertEquals(16f, EInkTypography.labelLarge.fontSize.value)
        assertEquals(14f, EInkTypography.labelMedium.fontSize.value)
        assertEquals(14f, EInkTypography.labelSmall.fontSize.value)
    }

    @Test
    fun `全表字号不低于 14sp 硬下限`() {
        allStyles().forEach { style ->
            assertTrue("${style.fontSize} 低于 14sp 下限", style.fontSize.value >= 14f)
        }
    }

    @Test
    fun `几何契约行高钉住 titleMedium24 bodyMedium24 titleSmall20`() {
        assertEquals(24f, EInkTypography.titleMedium.lineHeight.value)
        assertEquals(24f, EInkTypography.bodyMedium.lineHeight.value)
        assertEquals(20f, EInkTypography.titleSmall.lineHeight.value)
    }

    @Test
    fun `宿主字体族挂载到全部 15 个样式`() {
        val system = createTypographySystem(FontFamily.Serif)
        allStyles(system).forEach { style ->
            assertEquals(FontFamily.Serif, style.fontFamily)
        }
    }

    @Test
    fun `未提供字体族时全部样式回落平台默认`() {
        allStyles(createTypographySystem(null)).forEach { style ->
            assertEquals(FontFamily.Default, style.fontFamily)
        }
    }

    @Test
    fun `挂载字体族不改字号行距字距与字重`() {
        listOf("bodySmall", "titleMedium", "bodyLarge").forEach { name ->
            val base = styleByName(name)
            val mounted = styleByName(name, createTypographySystem(FontFamily.Serif))
            assertEquals("$name fontSize", base.fontSize, mounted.fontSize)
            assertEquals("$name lineHeight", base.lineHeight, mounted.lineHeight)
            assertEquals("$name letterSpacing", base.letterSpacing, mounted.letterSpacing)
            assertEquals("$name fontWeight", base.fontWeight, mounted.fontWeight)
        }
    }

    private fun allStyles(
        system: EInkTypographySystem = createTypographySystem(null),
    ) = listOf(
        system.displayLarge, system.displayMedium, system.displaySmall,
        system.headlineLarge, system.headlineMedium, system.headlineSmall,
        system.titleLarge, system.titleMedium, system.titleSmall,
        system.bodyLarge, system.bodyMedium, system.bodySmall,
        system.labelLarge, system.labelMedium, system.labelSmall,
    )

    private fun styleByName(name: String, system: EInkTypographySystem = createTypographySystem(null)) =
        when (name) {
            "bodySmall" -> system.bodySmall
            "titleMedium" -> system.titleMedium
            "bodyLarge" -> system.bodyLarge
            else -> error("未覆盖的样式名 $name")
        }
}
