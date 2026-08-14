package io.legado.app.eink

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
 *  - 以 [safeDrawingPadding] 避让系统栏（状态栏/导航栏/挖孔），
 *    保证顶部搜索框与底部操作栏不被系统栏遮挡或抢占触摸；
 *  - 铺设主题背景色；
 *  - 系统栏图标颜色跟随主题背景亮度（浅底黑图标 / 深底白图标），
 *    保证状态栏与纯黑白主题一致。
 */
class EInkMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // 系统栏图标外观随主题底色亮度切换：亮底 → 深色图标；暗底 → 浅色图标
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
            .safeDrawingPadding()
    ) {
        EInkApp()
    }
}
