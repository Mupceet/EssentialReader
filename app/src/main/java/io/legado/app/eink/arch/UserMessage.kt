package io.legado.app.eink.arch

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 一次性 UI 消息（Snackbar / Toast）。
 *
 * ViewModel 永不持有本地化字符串 —— 通过 [stringRes] (Int) 传递资源 ID，
 * 本地化在 UI 层完成。这与 JBusDriver 的模式一致，支持多语言和 ProGuard 安全。
 *
 * 使用 [MutableSharedFlow]<UserMessage> 发射，UI 层 collect 后调用 [format] 渲染。
 *
 * 参考: JBusDriver `ui/UserMessage.kt`
 */
sealed interface UserMessage {

    /**
     * 格式化为本地化字符串。
     */
    fun format(context: Context): String

    companion object {
        /**
         * 快捷构造：纯字符串资源消息。
         */
        fun from(@StringRes resId: Int): UserMessage = ResourceMessage(resId)

        /**
         * 快捷构造：带格式化参数的字符串资源消息。
         */
        fun from(@StringRes resId: Int, vararg args: Any): UserMessage =
            FormattedResourceMessage(resId, args.toList())
    }
}

/**
 * 纯字符串资源消息。
 */
private data class ResourceMessage(
    @StringRes val stringRes: Int,
) : UserMessage {
    override fun format(context: Context): String = context.getString(stringRes)
}

/**
 * 带格式化参数的字符串资源消息。
 */
private data class FormattedResourceMessage(
    @StringRes val stringRes: Int,
    val args: List<Any>,
) : UserMessage {
    override fun format(context: Context): String =
        context.getString(stringRes, *args.toTypedArray())
}

/**
 * 复数形式的字符串资源消息（如"删除了 N 本书"）。
 */
data class PluralMessage(
    @PluralsRes val pluralRes: Int,
    val quantity: Int,
    val args: List<Any> = emptyList(),
) : UserMessage {
    override fun format(context: Context): String =
        context.resources.getQuantityString(pluralRes, quantity, *args.toTypedArray())
}

/**
 * Compose 中方便地获取消息文本。
 */
@Composable
fun UserMessage.asString(): String = format(LocalContext.current)
