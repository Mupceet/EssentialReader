package io.legado.app.eink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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
 * 注意: 此 Activity 暂未注册到 AndroidManifest.xml，
 * 将在 Phase 2 完成书架+阅读闭环后正式启用。
 */
class EinkMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EInkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EinkApp()
                }
            }
        }
    }
}
