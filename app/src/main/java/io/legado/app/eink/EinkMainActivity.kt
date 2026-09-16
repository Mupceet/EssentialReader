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
 * 进入方式：「我的」页顶部「墨水屏模式」开关（eInkMode）打开即进入
 * （CLEAR_TASK 整体切换），偏好打开时 MainActivity 冷启动分流至此；
 * 实验室「墨水屏显示」(labEInkDisplay) 是该开关条目的显隐门控，与模式
 * 状态独立。E-Ink 内「退出到完整模式」时关闭 eInkMode 并回到 MainActivity。
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
     * 挂载为端口弹层的宿主 Activity（段评半屏 WebView 等宿主 DialogFragment
     * 的事务宿主与生命周期作用域；见 EInkBridge.attachHostActivity）。
     * 基类 onCreate 为 final 编排，onStart/onStop 是可用的生命周期挂点。
     */
    override fun onStart() {
        super.onStart()
        EInkBridge.attachHostActivity(this)
    }

    override fun onStop() {
        EInkBridge.detachHostActivity(this)
        super.onStop()
    }

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
        // 再次进入经 我的 页顶部「墨水屏模式」开关。退出只关 eInkMode（模式
        // 状态），「墨水屏显示」门控不受影响——条目仍显示、开关已回弹：
        // 同步写 AppConfigStore 无竞态，preferencesFlow 派生的 LabSettings 流
        // 会随之更新；MainActivity 冷启动分流立即读该键，不会被弹回
        AppConfigStore.putBoolean(PreferKey.eInkMode, false)
        // 不能显式指向 MainActivity：切换图标后该组件被禁用，
        // 须解析当前启用的 launcher 组件（主类或 Launcher 别名）
        context.startActivity(
            MainIntent.createLauncherIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}
