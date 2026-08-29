package io.legado.app.eink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.AppUiConfigurationGateway
import io.legado.app.eink.app.EInkApp
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.eink.bridge.TextPageContent
import io.legado.app.eink.designsystem.theme.EInkTheme
import io.legado.app.eink.reader.ReaderPageCanvas
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.theme.resolveAppFontScale
import io.legado.app.utils.isNightMode
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * E-Ink 版本单 Activity 入口。
 *
 * 进入条件：实验室「墨水屏显示」开关（PreferKey.labEInkDisplay）打开。
 * 打开即接管界面（MainActivity 冷启动分流至此；实验室页拨开即时切换）；
 * E-Ink 内「退出到完整模式」时关闭该开关并回到 MainActivity。
 *
 * 遵循 E-Ink Design System 规范 §54: 推荐单 Activity 架构，
 * 所有 E-Ink 屏幕通过 Compose 状态路由（[EInkApp]）管理，
 * 避免多个 Activity 之间的传统 Android 页面转场。
 *
 * 应用内字体缩放：经 [attachBaseContext] 应用全局 PreferKey.fontScale
 * （与 EssentialReader 的 AppContextWrapper.wrap、完整模式 Compose 的
 * rememberAppDensity 同一设置键），E-Ink「我的」页可读写。
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

    private val appUiConfigurationGateway: AppUiConfigurationGateway =
        GlobalContext.get().get()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.wrapAppFontScale())
    }

    /**
     * 应用内字体缩放（语义对齐 EssentialReader 的 AppContextWrapper.getFontScale）：
     * 设置值 ÷10，0.8~1.6 之外或未设置时回落系统缩放。attach 阶段经
     * AppConfigStore 同步快照读取（写入方在其 pending overlay 中立即可见）。
     */
    private fun Context.wrapAppFontScale(): Context {
        val config = Configuration(resources.configuration)
        AppConfigStore.getInt(PreferKey.fontScale)?.let { setting ->
            config.fontScale = resolveAppFontScale(setting, config.fontScale)
        }
        return createConfigurationContext(config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 注册引擎端口（:modules:eink 的 VM 经 EInkEngineRegistry 取用），
        // 必须早于任何 E-Ink Composable 组合（VM 构造）
        EInkBridge.install(this)
        // 清理未加书架的隐藏行（对齐 View 版启动时的 deleteNotShelfBook；
        // 详情页预取/未加架阅读都会落这类行）
        lifecycleScope.launch(Dispatchers.IO) {
            appDb.bookDao.deleteNotShelfBook()
        }
        // 自动跳转最近阅读（对齐 View 版 defaultToRead 行为）：开关开启时
        // 解析最近阅读书并以 [书架, 阅读页] 初始栈启动，阅读页返回即书架
        val lastReadBookUrl = if (AppConfigStore.getBoolean(PreferKey.defaultToRead) == true) {
            appDb.bookDao.lastReadBook?.bookUrl
        } else {
            null
        }
        // 初始 UI 配置在组合外同步读取（组合内读同步快照会违反
        // verifyConfigArchitecture 的 Composable 禁读配置护栏）
        val initialUiConfiguration = appUiConfigurationGateway.currentConfiguration
        setContent {
            // 黑白主题对齐完整模式：AppUiConfiguration.isDarkTheme 与主壳
            // 同源（themeMode 0=跟随系统 1=浅色 2=深色），经网关 StateFlow
            // 热切换；系统深浅色变化经下方 onConfigurationChanged 同步进网关
            //（本 Activity 声明了 uiMode configChanges，不会自动重建）
            val uiConfiguration by appUiConfigurationGateway.configuration
                .collectAsStateWithLifecycle(initialUiConfiguration)

            EInkTheme(darkTheme = uiConfiguration.isDarkTheme) {
                EInkRoot(lastReadBookUrl)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        appUiConfigurationGateway.synchronizeSystemDarkTheme(newConfig.isNightMode)
        super.onConfigurationChanged(newConfig)
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
            // 宿主引擎能力出口 1：完整模式（View UI）——关闭实验室
            // 「墨水屏显示」开关并全量跳转（导入导出等管理功能在完整模式
            // 中完成，再次启用需在 实验室 → 墨水屏显示 重新打开）
            onExitToFullMode = {
                AppConfigStore.putBoolean(PreferKey.labEInkDisplay, false)
                context.startActivity<MainActivity> {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            },
            // 宿主引擎能力出口 2：阅读绘制叶子——直接操作引擎
            // ChapterProvider 画笔与 TextPage 坐标，与 View 版渲染零分岔
            pageRenderer = { page, version, modifier ->
                ReaderPageCanvas(
                    page = (page as? TextPageContent)?.textPage,
                    pageVersion = version,
                    antiAlias = EInkBridge.useAntiAlias,
                    modifier = modifier,
                )
            },
        )
    }
}
