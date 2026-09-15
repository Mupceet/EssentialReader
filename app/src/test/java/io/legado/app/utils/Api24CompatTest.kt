package io.legado.app.utils

import android.app.Application
import android.view.View
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.platform.ReaderAndroidPaintFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.init.injectAsAppCtx
import java.io.File

/**
 * minSdk 24 的兼容门：这些路径在 API 24/25 上会避开 26 才有的 API
 * （Typeface.Builder、BitmapFactory.Options#inPreferredColorSpace、View#setImportantForAutofill）。
 *
 * Robolectric 按 `sdk = [24]` 加载 android-all，越界调用会直接抛
 * NoClassDefFoundError / NoSuchFieldError / NoSuchMethodError，
 * 因此这里不是"看断言"而是"不炸"即为回归证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Api24CompatTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.injectAsAppCtx()
    }

    @Test
    fun `字体文件在 API 24 走 createFromFile 分支`() {
        val fontFile = File(context.cacheDir, "number.ttf")
        context.assets.open("font/number.ttf").use { input ->
            fontFile.outputStream().use { output -> input.copyTo(output) }
        }

        assertNotNull(loadTypefaceOrNull(context, fontFile.absolutePath))
    }

    @Test
    fun `字体缺失或为空时返回 null 由调用方回落`() {
        assertNull(loadTypefaceOrNull(context, ""))
        assertNull(loadTypefaceOrNull(context, "missing-reader-font.ttf"))
    }

    @Test
    fun `禁用自动填充在 API 24 不触碰 26 特有方法`() {
        View(context).disableAutoFill()
    }

    @Test
    fun `位图解码在 API 24 跳过 inPreferredColorSpace`() {
        val bitmap = BitmapUtils.decodeAssetsBitmap(context, "web/uploadBook/img/close.png", 8, 8)

        assertNotNull(bitmap)
        assertTrue(bitmap!!.width > 0)
        assertTrue(bitmap.height > 0)
    }

    @Test
    fun `阅读器画笔在 API 24 可构建`() {
        val paint = ReaderAndroidPaintFactory.create(ReaderTextStyle(colorArgb = 0, fontSizePx = 24f))

        assertEquals(24f, paint.textSize, 0f)
    }
}
