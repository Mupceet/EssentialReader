package io.legado.app.eink.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * E-Ink 统一的图片加载组件。
 *
 * 对应 JBusDriver 的 AppAsyncImage / MD3 主工程的 Coil AsyncImage：全站
 * 所有网络图片都通过这一个薄封装进入图片库的 Compose 入口，保证
 * ContentScale、占位/失败态一致。
 *
 * 底层使用 Coil（与宿主共用 SingletonImageLoader：内存/磁盘缓存、请求
 * 生命周期、书源请求头拦截器均由宿主 ImageLoader 承担），调用方不要再
 * 自行 copy bitmap 或维护第二份 LruCache。
 *
 * 刻意不用 SubcomposeAsyncImage（其文档明示子组合慢、不宜用于 Lazy
 * 列表；墨水屏弱 SoC 上会拖慢整页翻帧）：加载中/失败占位以普通 Box
 * 叠层绘制，请求本身仍走无子组合的 [AsyncImage]。
 */
@Composable
fun EInkAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loading: (@Composable () -> Unit)? = null,
    failure: (@Composable () -> Unit)? = null,
) {
    // null = 请求尚未出首个状态，与 Loading 一样先显示占位（对齐
    // GlideImage「占位立即可见」的行为）
    var state by remember { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(modifier = modifier) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
            onState = { state = it },
        )
        when (state) {
            is AsyncImagePainter.State.Error -> failure?.invoke()
            is AsyncImagePainter.State.Success -> Unit
            else -> loading?.invoke()
        }
    }
}
