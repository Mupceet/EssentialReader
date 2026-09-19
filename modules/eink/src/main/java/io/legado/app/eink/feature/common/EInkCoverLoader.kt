package io.legado.app.eink.feature.common

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.legado.app.eink.contract.EInkEngineRegistry
import java.io.IOException
import okio.Buffer

/**
 * 封面请求的标记数据：http(s) 封面经此路由进 [EInkCoverFetcher]——
 * 由模块自有 ImageLoader 的组件表注册，非 http(s) 封面（data: 内联/
 * 本地路径/content）不包装，走 Coil 内置 fetcher。
 */
internal class EInkCoverData(
    val url: String,
    val sourceOrigin: String?,
)

/**
 * 请求构造的封面路由：http(s) → 端口抓取管线；其余 → 原样交给内置
 * fetcher 解析。纯函数，显示与预取共用。
 */
internal fun coverRequestData(url: String, sourceOrigin: String?): Any =
    if (url.startsWith("http", ignoreCase = true)) {
        EInkCoverData(url, sourceOrigin)
    } else {
        url
    }

/**
 * 封面字节抓取器：调 [io.legado.app.eink.contract.CoverEngine.fetchCoverBytes]
 * 拿原始字节。端口约定失败返回 null，这里转成异常交给 Coil 失败态
 * （模块 UI 回退占位封面，不重试）。
 */
internal class EInkCoverFetcher(
    private val data: EInkCoverData,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = EInkEngineRegistry.coverEngine.fetchCoverBytes(data.url, data.sourceOrigin)
            ?: throw IOException("封面抓取失败: ${data.url}")
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(bytes),
                fileSystem = options.fileSystem,
            ),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<EInkCoverData> {
        override fun create(
            data: EInkCoverData,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = EInkCoverFetcher(data, options)
    }
}

/**
 * 模块自有 ImageLoader：封面管线不骑宿主 SingletonImageLoader——宿主
 * 可能是 Glide 等其它图片栈，或宿主 Coil 单例带有模块不想要的配置
 * （全局 crossfade 等）。字节抓取经 [CoverEngine][io.legado.app.eink.contract.CoverEngine]
 * 端口回到宿主管线，模块只做解码/降采样/内存缓存。
 *
 * 持久缓存不在模块侧（磁盘去重归宿主管线职责，见端口 KDoc）；过渡
 * 动画零配置——Coil 默认即无 crossfade（墨水屏零动画规范），不引入
 * 跨版本差异的 crossfade 扩展。进程级单例，持 applicationContext。
 */
private val loaderLock = Any()
private var loaderInstance: ImageLoader? = null

fun einkImageLoader(context: Context): ImageLoader =
    loaderInstance ?: synchronized(loaderLock) {
        loaderInstance ?: ImageLoader.Builder(context.applicationContext)
            .components { add(EInkCoverFetcher.Factory()) }
            .build()
            .also { loaderInstance = it }
    }
