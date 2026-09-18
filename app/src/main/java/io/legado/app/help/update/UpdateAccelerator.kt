package io.legado.app.help.update

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 应用更新网络链路的 GitHub 加速：对直链做前缀改写，内置单一加速源，
 * 无用户配置；所有使用点统一「加速优先、失败回退直连」。
 *
 * - 检查链路 manifest 拉取：先加速后直连，两次尝试各受原有超时约束；
 * - 更新包直链：[resolveDownloadUrl] 探测可达后决定改写与否。
 *
 * 加速源 gh-proxy.com 由 2026-09-18 国内网络三轮实测选出（4MB 分段
 * 下载吞吐 54~247KB/s 三轮全部领先 ghfast.top / ghproxy.net，直连同期
 * 0~25KB/s 且出现整轮黑洞；manifest 小文件 1.2~4.3s，超时轮次由直连
 * 兜底接住）。公共加速源会随时间失效，回退直连是唯一逃生通道，回退
 * 语义不可移除。
 *
 * GitHub API 兜底链路保持直连不走加速：公共代理出口 IP 共享 GitHub
 * 按 IP 的 API 限流额度，改写会放大 403/429；manifest 加速已足够解除
 * 检查链路在国内的可达性问题。
 */
internal object UpdateAccelerator {

    private const val acceleratorPrefix = "https://gh-proxy.com/"
    private const val reachabilityTimeoutMillis = 3000L

    fun accelerate(url: String): String = acceleratorPrefix + url

    /**
     * 下载直链选择：向加速源发 Range 0-0 极小请求探测本条直链可达，
     * 可达则前缀改写，不可达（超时/非 2xx/异常）回退直连原文。
     *
     * 仅在已发现新版本时调用，一次探测约一个 RTT；不做进程级缓存，
     * 保证加速源进程存续期间失效后能立即回退。
     */
    suspend fun resolveDownloadUrl(url: String): String {
        val acceleratedReachable = try {
            withTimeoutOrNull(reachabilityTimeoutMillis) {
                okHttpClient.newCallResponse {
                    url(accelerate(url))
                    header("Range", "bytes=0-0")
                }.use { it.isSuccessful }
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return if (acceleratedReachable) accelerate(url) else url
    }
}
