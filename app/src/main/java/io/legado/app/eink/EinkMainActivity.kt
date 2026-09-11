package io.legado.app.eink

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.constant.PreferKey
import io.legado.app.domain.gateway.AppUiConfigurationGateway
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.eink.contract.EInkHostActivity
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.main.MainIntent
import io.legado.app.ui.theme.rememberCustomFont
import org.koin.core.context.GlobalContext

/**
 * E-Ink 版本单 Activity 入口（模块模板基类 [EInkHostActivity] 的宿主子类）。
 *
 * 进入条件：实验室「墨水屏显示」开关（PreferKey.labEInkDisplay）打开。
 * 打开即接管界面（MainActivity 冷启动分流至此；实验室页拨开即时切换）；
 * E-Ink 内「退出到完整模式」时关闭该开关并回到 MainActivity。
 *
 * 生命周期编排（引擎装配时机、字体缩放、启动清理、直达最近阅读、
 * 跟随系统深浅色主题、按键分发）全部由基类承担，宿主差异只剩两个钩子
 * （引擎装配与退出出口）加可选 UI 字体钩子（[uiFontFamily]，接完整模式
 * 「外观 → 字体」设置）。
 */
class EInkMainActivity : EInkHostActivity() {

    private val appUiConfigurationGateway: AppUiConfigurationGateway =
        GlobalContext.get().get()

    override fun onInstallEngines() = EInkBridge.install()

    /**
     * E-Ink 界面 UI 字体跟随宿主「外观 → 字体」（appFontPath）：复用
     * [rememberCustomFont] 的进程级缓存与异步加载（完整模式同一份缓存，
     * 跨模式往返首帧即命中）；字体文件加载完成前先以 null（平台默认）
     * 渲染，就位后重组替换。订阅网关状态流——完整模式改动字体后回到
     * E-Ink（Activity 未重建）也能实时生效。
     */
    @Composable
    override fun uiFontFamily(): FontFamily? {
        val configuration by appUiConfigurationGateway.configuration
            .collectAsStateWithLifecycle(appUiConfigurationGateway.currentConfiguration)
        return rememberCustomFont(configuration.theme.appFontPath)
    }

    override fun onExitToFullMode(context: Context) {
        // E-Ink 存续期间没有完整模式的 BaseActivity 在同步网关的系统
        // 深浅色状态（跟随系统的完整模式依赖它），退出前补偿一次。深浅
        // 读基类跟踪的 State——入口包装冻结了 resources.configuration，
        // 存续期间切换系统深浅后它是旧值
        appUiConfigurationGateway.synchronizeSystemDarkTheme(isSystemDarkTheme)
        // 完整模式（View UI）——导入导出等管理功能在完整模式中完成，
        // 再次启用需在 实验室 → 墨水屏显示 重新打开
        AppConfigStore.putBoolean(PreferKey.labEInkDisplay, false)
        // 不能显式指向 MainActivity：切换图标后该组件被禁用，
        // 须解析当前启用的 launcher 组件（主类或 Launcher 别名）
        context.startActivity(
            MainIntent.createLauncherIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}
