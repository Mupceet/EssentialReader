package io.legado.app.eink.hanvon

import android.app.ActivityManager
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * 墨本阅读增强：Hanvon 墨水屏「回桌面清后台」免疫（LSPosed/Xposed 模块入口）
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ## 背景（N10Touch / Android 11 真机调研结论，详见 D:\Projects\HanvanS10\05）
 *
 * Hanvon 系统有两处会按包名杀第三方后台，且互不相通：
 *
 * 1. **hvSettings 清理链**——按 HOME 后桌面（hanvon.aebr.hvLauncher）发广播
 *    `hanvon.intent.action.onekeyclear.notip_3rdapp`，设置进程延迟 3 秒执行
 *    `hvApkUtils.OneKeyClear()`，遍历运行中进程逐个调
 *    [ActivityManager.killBackgroundProcesses]；sideload 应用必死（微信读书
 *    因包名在代码中硬编码特赦而幸免），从按 HOME 到死亡约 4~6 秒。
 * 2. **hvLauncher 进程内直杀**——系统自带阅读器（fbreader 全套代码驻留桌面
 *    进程）在按返回退出 / 进入多窗口时经
 *    `com.fbreader.util.CommonUtils.exitActivityByPkName` 直接调用同一 API。
 *
 * ## 设计：单一咽喉点
 *
 * 不去模仿免杀判定（判定函数在固件间可能变化，且部分分支语义存疑），而是
 * 在上述两个宿主进程内拦截 [ActivityManager.killBackgroundProcesses] 这一
 * 必经 API：参数命中本应用包名时直接吞掉调用（[XC_MethodHook.MethodHookParam.setResult]），
 * 其余调用原样放行。因此：
 *
 * - 同一次清理中其他应用照旧被清理（对系统行为零扩大影响）；
 * - 最近任务划卡（removeTask，system_server 内部执行）、lmkd、强行停止、
 *   第三方清理工具均不经过本 API，完全不受影响；
 * - 固件更新改判定逻辑也不影响免疫有效性。
 *
 * ## 装载协议
 *
 * 本类经 `src/main/assets/xposed_init` 声明为模块入口（LSPosed 传统协议），
 * 在每个作用域进程的包加载阶段被实例化并回调 [handleLoadPackage]。
 * 作用域（需用户在 LSPosed 中勾选）：hanvon.aebr.hvsettings + hanvon.aebr.hvLauncher。
 * 未启用 LSPosed 的设备上本类永远不会执行（仅多占数 KB dex）。
 */
class HanvonCleanerHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 双通道日志：XposedBridge.log 直达 lspd 的 modules.log（LSPosed 管理
        // 日志可查）；本机（Hanvon 设备）实测宿主进程的 logcat 输出不可见，
        // Log.i 仅供标准设备。模块只会在作用域进程内加载，入口日志无噪音。
        XposedBridge.log("MoBenEnhance: entry ${lpparam.packageName} proc=${lpparam.processName}")
        Log.i(TAG, "entry: ${lpparam.packageName} (proc=${lpparam.processName})")

        // 只在两个宿主进程内挂钩；其他进程直接返回（作用域外的进程本就不会
        // 加载本模块，此处再过滤一次是双保险）
        if (lpparam.packageName !in HOSTS) return

        try {
            val unhook = XposedHelpers.findAndHookMethod(
                ActivityManager::class.java,
                "killBackgroundProcesses",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 只吞掉针对本应用的调用：killBackgroundProcesses 唯一
                        // 参数是包名（String），命中即 setResult 阻断原方法——
                        // 对 void 方法传 null
                        val target = param.args[0]
                        if (target in TARGET_PACKAGES) {
                            XposedBridge.log("MoBenEnhance: 已保护 $target 不被清后台（宿主 ${lpparam.packageName}）")
                            Log.i(TAG, "已保护 $target 不被清后台（宿主 ${lpparam.packageName}）")
                            param.setResult(null)
                        }
                    }
                },
            )
            XposedBridge.log("MoBenEnhance: hook 生效于 ${lpparam.packageName}（unhook=$unhook）")
            Log.i(TAG, "hook 生效于 ${lpparam.packageName}（unhook=$unhook）")
        } catch (t: Throwable) {
            // 宿主固件升级移除该方法签名等场景：记录后静默退出，
            // 绝不向宿主进程抛异常
            XposedBridge.log("MoBenEnhance: hook 失败于 ${lpparam.packageName}: $t")
            Log.e(TAG, "hook 失败于 ${lpparam.packageName}", t)
        }
    }

    private companion object {

        private const val TAG = "MoBenEnhance"

        /**
         * 需要挂钩的两个宿主进程（双执行体，缺一不可）：
         * - [hanvon.aebr.hvsettings]：回桌面清理链（OneKeyClear）执行者；
         * - [hanvon.aebr.hvLauncher]：桌面进程，内驻系统阅读器的直接清杀调用点
         *   （FBReader.onBackPressed / HwMainMenuFragment.enterMutiWind）。
         */
        private val HOSTS = setOf(
            "hanvon.aebr.hvsettings",
            "hanvon.aebr.hvLauncher",
        )

        /**
         * 受保护的包名：release 与 debug 两个 applicationId 都收（模块由任一
         * 变体携带时，另一个变体同样受保护；多保护一个包名对宿主零成本）。
         */
        private val TARGET_PACKAGES = setOf(
            "io.legato.kazusa.eink",
            "io.legato.kazusa.eink.debug",
        )
    }
}
