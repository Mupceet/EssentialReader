package io.legado.app.eink

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.legado.app.eink.theme.EInkTheme

/**
 * E-Ink 版本单 Activity 入口。
 *
 * 遵循 E-Ink Design System 规范 §54: 推荐单 Activity 架构，
 * 所有 E-Ink 屏幕通过 Compose 状态路由（[EInkApp]）管理，
 * 避免多个 Activity 之间的传统 Android 页面转场。
 *
 * 根布局职责（纯 Foundation，无 Material3）:
 *  - Edge-to-Edge：窗口始终延伸到系统栏后方（[enableEdgeToEdge]），
 *    系统栏透明覆盖在背景之上，进出各界面窗口尺寸恒定，无内容跳动；
 *    系统栏避让由各界面自行用 insets padding 完成（阅读界面自管，
 *    其余界面见 [EInkApp] 的 safeDrawingPadding 包裹）。
 *  - 铺设主题背景色（延伸到系统栏区域）；
 *  - 系统栏图标颜色跟随主题背景亮度（浅底黑图标 / 深底白图标），
 *    保证状态栏与纯黑白主题一致。
 */
class EInkMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EInkTheme {
                EInkRoot()
            }
        }
    }
}

@Composable
private fun EInkRoot() {
    val scheme = EInkTheme.colorScheme

    // 系统栏图标外观随主题底色切换：亮底 → 深色图标；暗底 → 浅色图标
    val view = LocalView.current
    DisposableEffect(scheme.background) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            val isLightBackground = scheme.background.luminance() > 0.5f
            controller.isAppearanceLightStatusBars = isLightBackground
            controller.isAppearanceLightNavigationBars = isLightBackground
        }
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        EInkApp()
    }
}
