package io.legado.app.eink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.legado.app.eink.theme.EInkTheme

/**
 * E-Ink 版本单 Activity 入口。
 *
 * 遵循 E-Ink Design System 规范 §54: 推荐单 Activity 架构，
 * 所有 E-Ink 屏幕通过 Compose 状态路由（[EinkApp]）管理，
 * 避免多个 Activity 之间的传统 Android 页面转场。
 *
 * 根布局以 [safeDrawingPadding] 避让系统栏（状态栏/导航栏/挖孔），
 * 保证顶部搜索框与底部操作栏不被系统栏遮挡或抢占触摸。
 */
class EinkMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EInkTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    EinkApp()
                }
            }
        }
    }
}
