package io.legado.app.ui.login

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.ReadAloud
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.ui.widget.dialog.BottomWebViewDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

@Suppress("unused")
class SourceLoginJsExtensions(
    activity: AppCompatActivity?, source: BaseSource?,
    private val bookType: Int = 0,
    callback: Callback? = null,
    /**
     * [showBrowser] 弹框强制全屏呈现（墨水屏模式用）：默认 false 保持
     * 完整模式的半屏面板 + 上拉交互；true 时无论书源 JS 传入什么
     * config，都强制全屏展开、关闭背景 dim、下滑直接关闭（见
     * [forcedFullscreenBrowserConfig]——墨水屏上 dim 层残影脏、拖拽
     * 手势不可靠，半屏收起态是两个痛点源头）。
     */
    private val forceFullscreenBrowser: Boolean = false
) : RssJsExtensions(activity, source) {
    private val callbackRef: WeakReference<Callback> = WeakReference(callback)
    interface Callback {
        fun upUiData(data: Map<String, Any?>?)
        fun reUiView(deltaUp: Boolean = false)
    }

    fun upLoginData(data: Map<String, Any?>?) {
        callbackRef.get()?.upUiData(data)
    }

    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) {
        callbackRef.get()?.reUiView(deltaUp)
    }

    fun refreshExplore() {
        callbackRef.get()?.reUiView()
    }

    override fun open(name: String, url: String?, title: String?, origin: String?) {
        if (name == "login") {
            if (activityRef.get() is MainActivity && MainActivity.hasActiveSourceLoginRoute) {
                activityRef.get()?.toastOnUi("已在登录界面")
            } else {
                super.open(name, url, title, origin)
            }
            return
        }
        super.open(name, url, title, origin)
    }

    fun refreshBookInfo() {
        postEvent(EventBus.REFRESH_BOOK_INFO, true)
    }

    fun refreshBookToc() {
        postEvent(EventBus.REFRESH_BOOK_TOC, true)
    }

    fun refreshContent() {
        postEvent(EventBus.REFRESH_BOOK_CONTENT, true)
    }

    fun copyText(text: String) {
        activityRef.get()?.sendToClip(text)
    }

    fun clearTtsCache() {
        if (getSource() !is HttpTTS) return
        val activity = activityRef.get() ?: return
        activity.lifecycleScope.launch(IO) {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath =
                "${activity.cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            activity.toastOnUi(R.string.clear_cache_success)
        }
    }

    @JvmOverloads
    fun showBrowser(
        url: String,
        html: String? = null,
        preloadJs: String? = null,
        config: String? = null
    ) {
        val activity = activityRef.get() ?: return
        val source = getSource() ?: return
        activity.showDialogFragment(
            BottomWebViewDialog(
                source.getKey(),
                bookType,
                url,
                html,
                preloadJs,
                if (forceFullscreenBrowser) forcedFullscreenBrowserConfig(config) else config,
                // 墨水屏：与强制全屏同路径去过渡动画（窗口零动画 + 预置全屏态）
                noTransition = forceFullscreenBrowser
            )
        )
    }

}

/**
 * 墨水屏全屏呈现的浏览器弹框强制配置（[SourceLoginJsExtensions.forceFullscreenBrowser]）。
 *
 * 在书源 JS 传入的 config 基础上强制覆盖以下键（键名与
 * `BottomWebViewDialog.Config` 的 Gson 字段一一对应）：
 *  - `state` = [BottomSheetBehavior.STATE_EXPANDED]：直接全屏展开，
 *    不出现半屏 + dim 背景的中间态（墨水屏上 dim 层残影脏）；
 *  - `dialogHeight` = MATCH_PARENT：铺满整屏（部分书源 config 自带
 *    半屏高度，一并覆盖）；
 *  - `shouldDimBackground` = false：全程无 dim——下滑关闭途中也不留
 *    脏背景；
 *  - `skipCollapsed` = true + `isHideable` = true：下滑越过全屏态直接
 *    关闭，不落回半屏收起态（墨水屏拖拽不可靠，收起态退出很费劲）；
 *  - `pageControls` = true：底部悬浮「上一页 / 关闭 / 下一页」控制条
 *    （见 BottomWebViewDialog.Config.pageControls）——WebView 触摸滚动
 *    在墨水屏上不可靠，按钮步进滚动 + 显式关闭是主交互。
 *
 * 书源 config 的其余键（peekHeight、宽度等）原样保留；config 解析失败
 * 时退化为仅强制键（弹框自身的 config 解析对垃圾输入本就不生效，
 * 这里不放大它）。纯字符串变换，单测直接钉住键级行为。
 */
internal fun forcedFullscreenBrowserConfig(sourceConfig: String?): String {
    val merged = sourceConfig
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JSONObject()
    merged.put("state", BottomSheetBehavior.STATE_EXPANDED)
    merged.put("dialogHeight", ViewGroup.LayoutParams.MATCH_PARENT)
    merged.put("shouldDimBackground", false)
    merged.put("skipCollapsed", true)
    merged.put("isHideable", true)
    merged.put("pageControls", true)
    return merged.toString()
}
