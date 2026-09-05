package io.legado.app.eink.contract

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import io.legado.app.eink.app.EInkApp
import io.legado.app.eink.designsystem.theme.EInkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * E-Ink 单 Activity 入口模板基类——宿主差异的全部剩余面 = 两个抽象钩子
 * （[onInstallEngines] 与 [onExitToFullMode]）。
 *
 * 遵循 E-Ink Design System 规范 §54: 推荐单 Activity 架构，所有 E-Ink
 * 屏幕通过 Compose 状态路由（[EInkApp]）管理。
 *
 * 典型启动与退出流程：
 * ```text
 * attachBaseContext
 *  ├─ onInstallEngines()        ← 宿主钩子①：注册全部端口进 Registry
 *  └─ fontScale 包装            ← GlobalSettings 同步读 → Configuration Context
 * onCreate
 *  ├─ 启动清理                  ← IO 协程：deleteBooksNotInBookshelf()
 *  ├─ 直达最近阅读解析          ← defaultToRead 开启时 lastReadBookUrl()
 *  └─ setContent ─► EInkTheme(isSystemInDarkTheme()) { EInkRoot }
 *                        └─ EInkApp 初始栈：[书架] 或 [书架, 阅读页]
 *
 * 系统按键 ─► onKeyDown/onKeyUp ─► keyEventHub.dispatch ─► 活跃屏幕处理器
 *                                              └─ 未消费 ─► 系统默认行为
 * 「退出到完整模式」─► onExitToFullMode(context)   ← 宿主钩子②
 * ```
 *
 * 基类用 final 生命周期方法编排三条顺序约束，宿主无法破坏：
 *  - [onInstallEngines] 在 attachBaseContext 首行调用（早于任何端口读取
 *    与任何 Compose 组合；此时 Activity 尚未 attach base context，
 *    实现不得使用本 Activity 的 resources/context）；
 *  - 应用内字体缩放在 attach 阶段经 GlobalSettings 端口同步读取并包装
 *    Configuration（写入方在其落盘中立即可见，recreate 入口后生效）；
 *  - 初始导航值（直达最近阅读）在组合外同步解析。
 *
 * 黑白主题完全跟随系统深浅色——不消费宿主主题模式设置，无任何主题钩子；
 * 入口 Manifest 需声明 uiMode configChanges（不重建），系统深浅切换经
 * LocalConfiguration 更新驱动 [isSystemInDarkTheme] 重组。
 *
 * 根布局职责（纯 Foundation，无 Material3）:
 *  - Edge-to-Edge：窗口始终延伸到系统栏后方，系统栏避让由各界面自行用
 *    insets padding 完成（阅读界面自管，其余界面见 [EInkApp]）；
 *  - 铺设主题背景色（延伸到系统栏区域）；
 *  - 系统栏图标颜色跟随主题背景亮度（浅底黑图标 / 深底白图标）。
 */
abstract class EInkHostActivity : ComponentActivity() {

    /**
     * 宿主引擎装配：把各端口实现注册进 [EInkEngineRegistry]
     * （装配模板见本仓宿主侧 `eink/bridge/EInkBridge`）。
     *
     * 调用时机：attachBaseContext 首行——早于任何端口读取与任何
     * Composable 组合；此时本 Activity 尚未 attach base context，实现
     * **不得**使用本 Activity 的 resources/context。
     *
     * 重复调用语义：整体替换（last-wins）；每次进入 E-Ink 重新装配，
     * 同时承担宿主设置快照对齐（防跨模式往返陈旧值）。
     */
    protected abstract fun onInstallEngines()

    /**
     * 「退出到完整模式」出口（E-Ink 内由 [EInkApp] 的「我的」页触发）。
     *
     * 宿主实现通常：关闭入口分流开关（下次冷启动不再进入 E-Ink）并以
     * NEW_TASK + CLEAR_TASK 跳转完整模式入口——导入导出等管理功能在
     * 完整模式中完成。
     *
     * @param context 组合内取得的 Context（启动跳转用）。
     */
    protected abstract fun onExitToFullMode(context: Context)

    /**
     * attach 期编排：先装配端口（必须早于下方 fontScale 的端口读取），
     * 再按 GlobalSettings.fontScaleSetting 包装字体缩放 Context。
     */
    final override fun attachBaseContext(newBase: Context) {
        // 端口装配必须早于下方经 GlobalSettings 的 fontScale 读取
        onInstallEngines()
        super.attachBaseContext(newBase.wrapEInkFontScale())
    }

    /**
     * 启动编排：edge-to-edge → 后台清理隐藏行 → 组合外解析直达阅读
     * 初始栈 → 进入组合（主题跟随系统深浅色）。
     */
    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 清理未加书架的隐藏行（详情页预取、未加架阅读都会落这类行）
        lifecycleScope.launch(Dispatchers.IO) {
            EInkEngineRegistry.bookshelfEngine.deleteBooksNotInBookshelf()
        }
        // 直达最近阅读：开关开启时解析最近阅读书，以 [书架, 阅读页]
        // 初始栈启动（阅读页返回即书架）
        val lastReadBookUrl = if (EInkEngineRegistry.globalSettings.defaultToRead) {
            EInkEngineRegistry.bookshelfEngine.lastReadBookUrl()
        } else {
            null
        }
        setContent {
            EInkTheme(darkTheme = isSystemInDarkTheme()) {
                EInkRoot(
                    initialReaderBookUrl = lastReadBookUrl,
                    exitToFullMode = ::onExitToFullMode,
                )
            }
        }
    }

    /**
     * 系统按键（按下）优先交给活跃屏幕的处理器
     * （[io.legado.app.eink.app.EInkKeyEventHub]，如阅读页音量键翻页）；
     * 无人消费时交还系统默认行为（音量调节等）。
     */
    final override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && EInkEngineRegistry.keyEventHub.dispatch(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * 系统按键（抬起）与按下同路分发（长按重复键在处理器内防抖，
     * 见阅读页音量键实现）。
     */
    final override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && EInkEngineRegistry.keyEventHub.dispatch(event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 应用内字体缩放（语义对齐宿主完整模式的界面字体缩放）：设置值 ÷10，
     * 0.8~1.6 之外或未设置（null）时保持原 Context 跟随系统缩放。
     */
    private fun Context.wrapEInkFontScale(): Context {
        val setting = EInkEngineRegistry.globalSettings.fontScaleSetting ?: return this
        val config = Configuration(resources.configuration)
        config.fontScale = resolveEInkFontScale(setting, config.fontScale)
        return createConfigurationContext(config)
    }
}

/**
 * [EInkHostActivity] 的字体缩放纯函数。
 *
 * @param fontScaleSetting 原始设置值（÷10 为倍率，如 11 = 1.1 倍）。
 * @param systemFontScale 系统当前字体缩放（区间外回落值）。
 * @return 解析后的 fontScale：设置值有效取 `setting / 10f`，越界回落
 *   [systemFontScale]（不截断）。
 */
internal fun resolveEInkFontScale(fontScaleSetting: Int, systemFontScale: Float): Float =
    (fontScaleSetting / 10f).takeIf { it in 0.8f..1.6f } ?: systemFontScale

/**
 * 根布局：铺主题背景 + 系统栏图标外观随底色亮度切换 + 承载 [EInkApp]。
 */
@Composable
private fun EInkRoot(
    /** 直达最近阅读的 bookUrl（defaultToRead 关闭或无最近阅读为 null）。 */
    initialReaderBookUrl: String?,
    /** 宿主退出出口（透传基类抽象钩子）。 */
    exitToFullMode: (Context) -> Unit,
) {
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
            onExitToFullMode = { exitToFullMode(context) },
        )
    }
}
