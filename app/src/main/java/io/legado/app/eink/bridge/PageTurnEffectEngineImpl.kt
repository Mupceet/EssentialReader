package io.legado.app.eink.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import io.legado.app.eink.contract.PageTurnEffectEngine
import io.legado.app.eink.contract.PageTurnRippleMode
import java.lang.reflect.Method
import splitties.init.appCtx

/**
 * 掌阅固件 EPDCDevice 水波纹实现（反射，对拍 qianshang-legado-eink 的
 * IReaderPageH）：`nativePostCommand("next-effect-type <effect>")` +
 * `setForceNextPostMode(PAGE_H)` 强制下一个提交帧以水波纹波形刷新。
 * 编码计算见 [PageTurnEffectCodes]。
 *
 * 能力判定（[supported]）= 品牌是掌阅 **且** 固件反射探测通过——品牌
 * 字符串对拍掌阅真机崩溃报告（Ocean 5 Pro：MANUFACTURER=iReader /
 * BRAND=iReader），先挡其它厂商恰有同名 EPDCDevice 类的误判，再做
 * 反射探测。
 *
 * 降级语义：任一判定不过、固件方法签名变化或运行期调用失败，均静默
 * no-op（失败闭锁 supportedResult=false，对拍 qianshang 的 initFailed
 * 语义）——效果是纯增益，任何失败都不阻断翻页，也不在无关设备留痕迹。
 */
internal object PageTurnEffectEngineImpl : PageTurnEffectEngine {

    private const val TAG = "PageTurnRipple"

    /** 固件隐藏类（boot classpath，无需 root/权限，仅掌阅固件存在）。 */
    private const val EPDC_CLASS = "android.eink.EPDCDevice"

    // nativePostCommand(String)（旧固件）与 nativePostCommand(String, String[])
    // （Android 14+ 固件）签名并存，运行期择一可用即可。
    private var postCommand: Method? = null
    private var postCommandNative: Method? = null
    private var forceNextMode: Method? = null
    private var probed = false
    private var supportedResult = false

    override val supported: Boolean
        get() = isIReaderBrand() && probe()

    /** 品牌判定（字符串对拍掌阅真机崩溃报告：MANUFACTURER/BRAND=iReader）。 */
    private fun isIReaderBrand(): Boolean =
        Build.MANUFACTURER.equals("iReader", ignoreCase = true) ||
            Build.BRAND.equals("iReader", ignoreCase = true)

    @Synchronized
    private fun probe(): Boolean {
        if (probed) return supportedResult
        probed = true
        supportedResult = runCatching {
            val clazz = Class.forName(EPDC_CLASS)
            postCommandNative = runCatching {
                clazz.getMethod("nativePostCommand", String::class.java, Array<String>::class.java)
            }.getOrNull()
            postCommand = runCatching {
                clazz.getMethod("nativePostCommand", String::class.java)
            }.getOrNull()
            forceNextMode = clazz.getMethod("setForceNextPostMode", Int::class.java)
            (postCommand != null || postCommandNative != null) && forceNextMode != null
        }.onFailure {
            Log.w(TAG, "EPDCDevice probe failed: ${it.message}")
        }.getOrDefault(false)
        return supportedResult
    }

    override fun preparePageTurn(forward: Boolean, mode: PageTurnRippleMode) {
        if (mode == PageTurnRippleMode.OFF) return
        // 整体兜底（契约：任何失败都不阻断翻页）——包括旋转解析在内的
        // 非反射段落同样可能抛（见 currentRotationSnapshot），不允许逃逸成崩溃
        runCatching {
            if (!probe()) return
            val snapshot = currentRotationSnapshot()
            val effect = PageTurnEffectCodes.effectCode(forward, snapshot.rotation, mode)
            val cmd = "next-effect-type $effect"
            if (postCommandNative != null) {
                postCommandNative!!.invoke(null, cmd, null)
            } else {
                postCommand!!.invoke(null, cmd)
            }
            forceNextMode!!.invoke(null, PageTurnEffectCodes.FORCE_NEXT_PAGE_H)
            Log.i(
                TAG,
                "PAGE_H prepared, forward=$forward mode=$mode effect=$effect " +
                    "rotation=${snapshot.rotation}(${snapshot.source})",
            )
        }.onFailure {
            Log.w(TAG, "preparePageTurn failed: ${it.message}")
            // 失败闭锁：签名/权限已破坏，后续翻页与档位行可见性一并降级
            supportedResult = false
        }
    }

    /** 旋转读数（值 + 来源），来源仅供日志核对获取链路。 */
    private data class RotationSnapshot(val rotation: Int, val source: String)

    /**
     * 旋转读数快照。
     *
     * Application Context 的 [Context.getDisplay] 在 API 30+ 上对非
     * display 关联上下文**直接抛** UnsupportedOperationException（掌阅
     * Ocean 5 Pro / Android 14 真机实测），不能取 `appCtx.display`——
     * 优先经宿主 Activity 窗口取，无窗口时回落 WindowManager 默认
     * Display，再失败按竖屏。入口包装冻结了 resources.configuration，
     * 但 Display 查询读的是显示设备实时状态，不受冻结影响。
     */
    @Suppress("DEPRECATION")
    private fun currentRotationSnapshot(): RotationSnapshot {
        EInkBridge.hostActivity()?.windowManager?.defaultDisplay?.rotation?.let {
            return RotationSnapshot(it, "activity-window")
        }
        val fallback = (appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay?.rotation
        return if (fallback != null) {
            RotationSnapshot(fallback, "wm-default")
        } else {
            RotationSnapshot(Surface.ROTATION_0, "assumed-portrait")
        }
    }

    /**
     * 调试用：旋转获取链路的即时读数——**不依赖 EPDC 能力**，任何设备
     * 可触发（入口 onResume / onConfigurationChanged 挂点），供真机验证
     * 旋转后取值是否即时/正确。R8 release 中 Log 整体剥离，实际仅
     * debug / noR8 构建可见；logcat 按 TAG 过滤。
     */
    internal fun debugLogRotation(trigger: String) {
        runCatching {
            val snapshot = currentRotationSnapshot()
            Log.i(TAG, "rotation[$trigger] = ${snapshot.rotation} (${snapshot.source})")
        }.onFailure {
            Log.w(TAG, "rotation snapshot failed: ${it.message}")
        }
    }
}
