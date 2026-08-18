package io.legado.app.eink.widget

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder

/**
 * 不修改请求时的默认 transform。提取为顶层单例，避免每次重组都生成新 lambda
 * 导致 [GlideImage] 内部 [androidx.compose.runtime.remember] 失效、请求被反复重建。
 */
private val NoOpRequestBuilderTransform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> = { it }

/**
 * E-Ink 统一的图片加载组件。
 *
 * 对应 JBusDriver 的 AppAsyncImage：全站所有网络图片都通过这一个薄封装进入
 * 图片库的 Compose 入口，保证 ContentScale、占位/失败态和请求选项一致。
 *
 * 本项目底层使用 Glide（而非 JBusDriver 的 Coil），因此这里封装的是
 * Glide Compose 的 [GlideImage]；内存缓存、磁盘缓存、请求生命周期均由
 * Glide 管理，调用方不要再自行 copy bitmap 或维护第二份 LruCache。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun EInkAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loading: (@Composable () -> Unit)? = null,
    failure: (@Composable () -> Unit)? = null,
    requestBuilderTransform: (RequestBuilder<Drawable>) -> RequestBuilder<Drawable> =
        NoOpRequestBuilderTransform,
) {
    GlideImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = if (loading != null) placeholder { loading() } else null,
        failure = if (failure != null) placeholder { failure() } else null,
        requestBuilderTransform = requestBuilderTransform,
    )
}