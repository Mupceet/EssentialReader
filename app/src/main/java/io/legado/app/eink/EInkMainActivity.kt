package io.legado.app.eink

import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.eink.bridge.TextPageContent
import io.legado.app.eink.reader.ReaderPageCanvas
import io.legado.app.eink.theme.EInkTheme
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // 注册引擎端口（:modules:eink 的 VM 经 EInkEngineRegistry 取用），
        // 必须早于任何 E-Ink Composable 组合（VM 构造）
        EInkBridge.install(this)
        // 清理未加书架的隐藏行（对齐 View 版 MainViewModel 启动时的
        // deleteNotShelfBook；详情页预取/未加架阅读都会落这类行）
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookDao.deleteNotShelfBook()
        }
        // 自动跳转最近阅读（对齐 View 版完整模式行为）：开关开启时由
        // WelcomeActivity 传入标记，此处解析最近阅读书并以
        // [书架, 阅读页] 初始栈启动，阅读页返回即书架
        val lastReadBookUrl = if (intent.getBooleanExtra(EXTRA_DEFAULT_TO_READ, false)) {
            appDb.bookDao.lastReadBook?.bookUrl
        } else {
            null
        }
        setContent {
            EInkTheme {
                EInkRoot(lastReadBookUrl)
            }
        }
    }

    companion object {
        /** 冷启动自动进入最近阅读（由 WelcomeActivity 按 defaultToRead 设置判定后传入）。 */
        const val EXTRA_DEFAULT_TO_READ = "defaultToRead"
    }
}

@Composable
private fun EInkRoot(initialReaderBookUrl: String?) {
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

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        EInkApp(
            initialReaderBookUrl = initialReaderBookUrl,
            // 宿主引擎能力出口 1：完整模式（View UI）——恢复原主题并全量
            // 跳转（导入导出等管理功能在完整模式中完成，再次启用需在
            // 完整模式中选择纯净阅读(墨水屏)主题）
            onExitToFullMode = {
                AppConfig.exitEInkPureMode()
                context.startActivity<MainActivity> {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            },
            // 宿主引擎能力出口 2：阅读绘制叶子——直接操作引擎
            // ChapterProvider 画笔与 TextPage 坐标，与 View 版渲染零分岔
            pageRenderer = { page, version, modifier ->
                ReaderPageCanvas(
                    page = (page as? TextPageContent)?.textPage,
                    pageVersion = version,
                    modifier = modifier,
                )
            },
        )
    }
}
