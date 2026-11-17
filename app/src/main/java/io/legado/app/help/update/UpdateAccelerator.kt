package io.legado.app.help.update

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 应用更新安装包直链的镜像加速选择（AUTO 更新源使用）：向镜像源发 Range 0-0
 * 极小请求探测本条直链可达，可达则前缀改写走镜像，不可达（超时/非 2xx/异常）
 * 回退直连原文。
 *
 * 仅在已发现新版本、即将发起下载时调用，一次探测约一个 RTT；不做进程级缓存，
 * 保证镜像源进程存续期间失效后能立即回退。公共镜像会随时间失效，回退直连是
 * 唯一逃生通道，回退语义不可移除。
 *
 * 镜像域名见 [UPDATE_MIRROR_PREFIX]。GitHub API 链路保持直连不走镜像：公共
 * 代理出口 IP 共享 GitHub 按 IP 的 API 限流额度，改写会放大 403/429。
 */
internal object UpdateAccelerator {

    private const val reachabilityTimeoutMillis = 3000L

    suspend fun resolveDownloadUrl(url: String): String {
        val mirrorReachable = try {
            withTimeoutOrNull(reachabilityTimeoutMillis) {
                okHttpClient.newCallResponse {
                    url(mirrorUrl(url))
                    header("Range", "bytes=0-0")
                }.use { it.isSuccessful }
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return if (mirrorReachable) mirrorUrl(url) else url
    }
}
