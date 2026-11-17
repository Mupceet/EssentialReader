package io.legado.app.help.update

/**
 * 更新清单与安装包的获取渠道。
 *
 * - [AUTO]：先走国内镜像，失败后回退直连 GitHub；安装包经镜像可达探测决定链路，
 *   探测不可达时回退直连（见 [UpdateAccelerator]）。
 * - [GITHUB]：只走 GitHub，请求不经过任何第三方。
 * - [MIRROR]：只走国内镜像，完全不回连 github.com，也不回落 GitHub API。
 */
internal enum class AppUpdateEndpoint(val value: String) {
    AUTO("auto"),
    GITHUB("github"),
    MIRROR("mirror");

    /**
     * 按尝试顺序返回清单地址；顺序即优先级。AUTO 镜像优先是墨本面向国内网络的
     * 有意取舍（直连 raw 国内普遍黑洞，先直连等于每次检查白等一轮超时）。
     */
    fun manifestUrls(repoPath: String, channel: String): List<String> {
        val github = githubManifestUrl(repoPath, channel)
        return when (this) {
            AUTO -> listOf(mirrorUrl(github), github)
            GITHUB -> listOf(github)
            MIRROR -> listOf(mirrorUrl(github))
        }
    }

    /** 镜像模式不再回落 GitHub API：直连不通时再等一轮超时只会更慢。 */
    val usesGitHubApi: Boolean
        get() = this != MIRROR

    companion object {
        fun of(value: String?): AppUpdateEndpoint =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

/**
 * GitHub 反代前缀。`gh-proxy.com` 是 2026-09-18 国内网络三轮实测的吞吐胜者
 * （4MB 分段下载 54~247KB/s 全胜 ghfast.top / ghproxy.net，直连同期 0~25KB/s
 * 且有整轮黑洞；2026-11-17 复测清单 0.79s vs ghfast.top 2.13s，后者贴应用内
 * 2.5s 清单超时线）。第三方镜像随时可能失效，换镜像只需要改这一处，设置项
 * 保存的是语义值而不是域名。
 */
internal const val UPDATE_MIRROR_PREFIX = "https://gh-proxy.com/"

internal fun githubManifestUrl(repoPath: String, channel: String): String =
    "https://raw.githubusercontent.com/$repoPath/update-manifests/$channel.json"

/** 已带前缀时保持幂等，避免清单地址被重复包装。 */
internal fun mirrorUrl(url: String): String =
    if (url.startsWith(UPDATE_MIRROR_PREFIX)) url else UPDATE_MIRROR_PREFIX + url
