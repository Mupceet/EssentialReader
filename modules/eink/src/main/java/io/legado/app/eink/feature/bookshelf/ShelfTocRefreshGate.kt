package io.legado.app.eink.feature.bookshelf

import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单本目录刷新的超时上界（5 分钟）：覆盖 OkHttp callTimeout 60s × 书源
 * URL option retry（常见 ≤3）+ 规则解析/JS 执行耗时。超过该上界的书源
 * 对刷新 UX 而言已等同失效，不值得让它把整轮刷新无限拖住。
 */
internal const val SHELF_TOC_REFRESH_TIMEOUT_MS = 300_000L

/**
 * 单本目录刷新超时闸门：超过 [timeoutMs] 放弃该书的管线，返回
 * "timeout"（bench 口径按失败计），整轮刷新继续其余书籍。
 *
 * 为什么需要：整轮刷新只有全部逐本管线返回才结束，isRefreshing（顶栏
 * 刷新按钮置灰依据）只在整轮结束的 finally 里复位。管线里网络、WebView、
 * 重试、并发限速全部有界，唯一无界的耗时是书源规则 JS（preUpdateJs/
 * loginCheckJs/规则 JS）——无闸门时一个病态书源就能让刷新按钮永久置灰，
 * 退出书架重建 ViewModel 才恢复。
 *
 * 取消语义：只吞超时。rhino fork 的 observeInstructionCount 已挂协程
 * 取消检查（RhinoContext.ensureActive），withTimeout 的取消可真正杀死
 * 死循环 JS，超时放弃的管线协程随之终止、不泄漏线程；外部取消（VM 销
 * 毁）与管线业务异常原样穿透，不被误报成 timeout。
 */
internal suspend fun refreshTocBounded(
    timeoutMs: Long = SHELF_TOC_REFRESH_TIMEOUT_MS,
    refresh: suspend () -> String,
): String = withTimeoutOrNull(timeoutMs) { refresh() } ?: "timeout"
