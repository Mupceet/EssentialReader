package io.legado.app.eink.bridge

import io.legado.app.eink.contract.CoverEngine
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 封面字节端口实现（本宿主图片栈 Glide，Coil 为模块自带依赖）。
 *
 * 字节抓取用宿主自身 OkHttp：按书源 origin 解析 headerMap（AnalyzeUrl
 * 构造期完成 headerRule 规则求值，含防盗链头）后抓取——较旧形态
 * （Builder 配置块、无请求头，防盗链封面回退占位）恢复了防盗链能力。
 *
 * 无持久缓存与失败冷却（宿主无对应封面管线；冷启动重抓、坏源不冷却），
 * 将来可自行增强。失败一律返回 null（模块回退占位封面），
 * CancellationException 透传。
 */
internal object CoverEngineImpl : CoverEngine {

    override suspend fun fetchCoverBytes(url: String, sourceOrigin: String?): ByteArray? {
        return try {
            withContext(Dispatchers.IO) {
                val headers: Map<String, String> = sourceOrigin?.let { origin ->
                    runCatching {
                        val source = SourceHelp.getSource(origin)
                        AnalyzeUrl(url, source = source).headerMap
                    }.getOrNull()
                } ?: emptyMap()
                val request = Request.Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
