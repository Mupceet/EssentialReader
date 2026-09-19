package io.legado.app.eink.bridge

import io.legado.app.data.entities.BaseSource
import io.legado.app.eink.contract.CoverEngine
import io.legado.app.help.coil.CoverFetcher
import io.legado.app.help.coil.CoverFileCache
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 封面字节端口实现：E-Ink 模块自有 ImageLoader 经
 * [fetchCoverBytes] 回到宿主管线抓字节。
 *
 * 与完整模式 CoverInterceptor/CoverFetcher 同键同源、行为对齐：
 *  - 持久文件缓存快路径（CoverFileCache 精确键——eink 侧完整模式已
 *    写入的封面直接命中，不解析规则不联网）；
 *  - 书源解析（AnalyzeUrl 防盗链请求头 + 最终地址，规则可能执行 JS）；
 *  - 失败冷却（与 CoverFetcher 共享同一缓存，坏源不重试风暴）；
 *  - OkHttp 两级读取（FORCE_CACHE 只读缓存优先，miss 走网络 + 30 天
 *    缓存）；
 *  - 书源图片解密（ImageUtils.decode）。
 *
 * 与完整模式的有意差异：eink 请求不带 bookUrl/PreferCache——不写持久
 * 缓存别名键、不走断网别名回退（完整模式继续经拦截器链承担写入侧）。
 * 失败一律返回 null（模块回退占位封面），CancellationException 透传。
 */
internal object CoverEngineImpl : CoverEngine {

    override suspend fun fetchCoverBytes(url: String, sourceOrigin: String?): ByteArray? {
        // 1) 持久文件缓存快路径（与完整模式 CoverInterceptor 同键）
        val cachedBytes = try {
            withContext(Dispatchers.IO) {
                CoverFileCache.read(url)
                    ?.takeIf { it.length() in 1..MAX_CACHE_FILE_BYTES }
                    ?.readBytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cachedBytes != null) return cachedBytes

        // 2) 书源解析（防盗链请求头 / 最终地址）
        val source = sourceOrigin?.let { origin ->
            withContext(Dispatchers.IO) { SourceHelp.getSource(origin) }
        }
        val resolved = try {
            withContext(Dispatchers.IO) { AnalyzeUrl(url, source = source).getUrlAndHeaders() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val (finalUrl, headers) = resolved

        // 3) 失败冷却（与完整模式 CoverFetcher 同一缓存）
        if (CoverFetcher.isFailed(finalUrl)) return null

        // 4) OkHttp 两级读取：只读缓存优先，miss 走网络（30 天缓存）
        val rawBytes = fetchHttpBytes(finalUrl, source, headers)
        if (rawBytes == null) {
            CoverFetcher.markFailed(finalUrl)
            return null
        }

        // 5) 解密（书源封面可加密；解密失败不进失败冷却，与完整模式口径一致）
        val decoded = try {
            if (ImageUtils.skipDecode(source, true)) {
                rawBytes
            } else {
                withContext(Dispatchers.IO) { ImageUtils.decode(url, rawBytes, true, source) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        CoverFetcher.clearFailure(finalUrl)
        return decoded
    }

    /**
     * OkHttp 抓取（[CoverFetcher] 同款两级策略）：先 FORCE_CACHE 只读
     * 命中（断网/弱网仍能出图），miss 再走网络并允许 30 天缓存。网络
     * 异常返回 null（失败冷却由调用方标记）。
     */
    private suspend fun fetchHttpBytes(
        finalUrl: String,
        source: BaseSource?,
        headers: Map<String, String>?,
    ): ByteArray? {
        // 4a) 只读缓存级
        val cached = try {
            withContext(Dispatchers.IO) {
                val cacheRequest = Request.Builder()
                    .url(finalUrl)
                    .tag(BaseSource::class.java, source)
                    .apply { headers?.forEach { (key, value) -> addHeader(key, value) } }
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build()
                okHttpClient.newCall(cacheRequest).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cached != null) return cached

        // 4b) 网络级
        return try {
            withContext(Dispatchers.IO) {
                val networkRequest = Request.Builder()
                    .url(finalUrl)
                    .tag(BaseSource::class.java, source)
                    .apply { headers?.forEach { (key, value) -> addHeader(key, value) } }
                    .tag(CoverFetcher.COVER_REQUEST_TAG)
                    .cacheControl(
                        CacheControl.Builder()
                            .maxAge(30, TimeUnit.DAYS)
                            .build()
                    )
                    .build()
                okHttpClient.newCall(networkRequest).execute().use { response ->
                    if (response.isSuccessful) response.body.bytes() else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 缓存文件尺寸护栏（与 CoverFetcher 别名回退同值）。 */
    private const val MAX_CACHE_FILE_BYTES = 20L * 1024 * 1024
}
