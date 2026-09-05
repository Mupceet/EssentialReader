package io.legado.app.eink

import android.content.Context
import android.content.Intent
import io.legado.app.constant.PreferKey
import io.legado.app.domain.gateway.AppUiConfigurationGateway
import io.legado.app.eink.app.EInkHostActivity
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.isNightMode
import io.legado.app.utils.startActivity
import org.koin.core.context.GlobalContext

/**
 * E-Ink 版本单 Activity 入口（模块模板基类 [EInkHostActivity] 的宿主子类）。
 *
 * 进入条件：实验室「墨水屏显示」开关（PreferKey.labEInkDisplay）打开。
 * 打开即接管界面（MainActivity 冷启动分流至此；实验室页拨开即时切换）；
 * E-Ink 内「退出到完整模式」时关闭该开关并回到 MainActivity。
 *
 * 生命周期编排（引擎装配时机、字体缩放、启动清理、直达最近阅读、
 * 跟随系统深浅色主题、按键分发）全部由基类承担，宿主差异只剩两个钩子：
 * 引擎装配（[EInkBridge]，bridge/ 是移植时唯一需要重写的部分）与
 * 退出出口。
 */
class EInkMainActivity : EInkHostActivity() {

    private val appUiConfigurationGateway: AppUiConfigurationGateway =
        GlobalContext.get().get()

    override fun onInstallEngines() = EInkBridge.install()

    override fun onExitToFullMode(context: Context) {
        // E-Ink 存续期间没有完整模式的 BaseActivity 在同步网关的系统
        // 深浅色状态（跟随系统的完整模式依赖它），退出前补偿一次
        appUiConfigurationGateway.synchronizeSystemDarkTheme(
            context.resources.configuration.isNightMode
        )
        // 完整模式（View UI）——导入导出等管理功能在完整模式中完成，
        // 再次启用需在 实验室 → 墨水屏显示 重新打开
        AppConfigStore.putBoolean(PreferKey.labEInkDisplay, false)
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }
}
