package io.legado.app.eink

import android.content.Context
import android.content.Intent
import io.legado.app.constant.PreferKey
import io.legado.app.eink.bridge.EInkBridge
import io.legado.app.eink.contract.EInkHostActivity
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.putPrefString
import io.legado.app.utils.startActivity

/**
 * E-Ink 版本单 Activity 入口（模块模板基类 [EInkHostActivity] 的宿主子类）。
 *
 * 进入条件：本宿主以 themeMode == "4"（纯净阅读/墨水屏 Compose 模式）
 * 分流——MainActivity.onActivityCreated 冷启动分流至此；退出时把
 * themeMode 写回 "0"（跟随系统）并回到 MainActivity。
 *
 * 生命周期编排（引擎装配时机、字体缩放、启动清理、直达最近阅读、
 * 跟随系统深浅色主题、按键分发）全部由基类承担，宿主差异只剩两个钩子：
 * 引擎装配（[EInkBridge]，bridge/ 是移植时唯一需要重写的部分）与
 * 退出出口。
 */
class EInkMainActivity : EInkHostActivity() {

    override fun onInstallEngines() = EInkBridge.install()

    override fun onExitToFullMode(context: Context) {
        // 完整模式（View UI）——导入导出等管理功能在完整模式中完成，
        // 再次启用需把主题模式切回 "4"（宿主主题设置的分流键）
        context.putPrefString(PreferKey.themeMode, "0")
        AppConfig.themeMode = "0"
        context.startActivity<MainActivity> {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }
}
