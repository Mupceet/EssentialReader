package io.legado.app.eink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.login.SourceLoginIntent
import io.legado.app.ui.login.SourceLoginRoute
import io.legado.app.ui.login.SourceLoginType
import io.legado.app.ui.login.SourceLoginViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 书源登录薄宿主 Activity：完整模式经 MainActivity 深链（
 * MainIntent.createSourceLoginIntent）进入登录模态路由；墨水屏模式下
 * MainActivity 会被 eInkMode 分流弹回、深链不可达，故以本薄宿主直接
 * 承载 [SourceLoginRoute]（同一 Compose 界面、同一 ViewModel），由
 * eink 桥（BookSourceEngineImpl.openSourceLogin）覆盖启动在 E-Ink
 * 界面之上。登录完成（Finish 效果）即 finish 返回 E-Ink。
 *
 * 不设返回栈拦截：登录界面自身的返回语义（Back intent）由
 * [SourceLoginRoute] 的 onBack 收口为 finish。
 */
class SourceLoginActivity : BaseComposeActivity() {

    /**
     * 墨水屏进出零窗口动画（对齐 lib/permission/PermissionActivity 先例）：
     * 本 Activity 由 eink 桥覆盖启动，系统默认 Activity 过渡在墨水屏上
     * 表现为整屏闪变/残影，进入与退出都消掉。
     */
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    @Composable
    override fun Content() {
        val type = intent.getStringExtra(EXTRA_TYPE)
            ?.let { runCatching { SourceLoginType.valueOf(it) }.getOrNull() }
            ?: SourceLoginType.BookSource
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL)
        val viewModel = koinViewModel<SourceLoginViewModel>(
            key = "SourceLogin:$type:$sourceKey:$bookUrl",
        )
        SourceLoginRoute(
            request = SourceLoginIntent.Initialize(type, sourceKey, bookUrl),
            viewModel = viewModel,
            host = this,
            onBack = ::finish,
        )
    }

    companion object {

        private const val EXTRA_TYPE = "sourceLoginType"
        private const val EXTRA_SOURCE_KEY = "sourceLoginKey"
        private const val EXTRA_BOOK_URL = "sourceLoginBookUrl"

        /** 登录入口（默认书源类型；sourceKey = 书源地址）。 */
        fun createIntent(
            context: Context,
            sourceKey: String,
            type: SourceLoginType = SourceLoginType.BookSource,
            bookUrl: String? = null,
        ): Intent = Intent(context, SourceLoginActivity::class.java).apply {
            putExtra(EXTRA_TYPE, type.name)
            putExtra(EXTRA_SOURCE_KEY, sourceKey)
            putExtra(EXTRA_BOOK_URL, bookUrl)
        }
    }
}
