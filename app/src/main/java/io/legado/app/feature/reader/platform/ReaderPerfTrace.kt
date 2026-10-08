package io.legado.app.feature.reader.platform

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, reader-local Perfetto vocabulary. Keep sections coarse: the point is to explain the
 * path to the first readable page, not to trace every Canvas draw or Compose recomposition.
 */
internal object ReaderPerfTrace {
    private const val MAX_NAME = 100
    private val nextAsyncCookie = AtomicInteger()

    // 纯 JVM 单测里 android.os.Trace 未 mock，首次调用直接抛 RuntimeException。
    // 追踪只做观测，任何环境都不允许它拖垮被测/调用路径，失败一次后整体降级为 no-op。
    @Volatile
    private var tracingUsable = true

    @PublishedApi
    internal fun begin(name: String) {
        if (!tracingUsable) return
        try {
            Trace.beginSection(name)
        } catch (e: RuntimeException) {
            tracingUsable = false
        }
    }

    @PublishedApi
    internal fun end() {
        if (!tracingUsable) return
        try {
            Trace.endSection()
        } catch (e: RuntimeException) {
            tracingUsable = false
        }
    }

    @PublishedApi
    internal fun beginAsync(name: String, cookie: Int) {
        if (!tracingUsable || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            Trace.beginAsyncSection(name, cookie)
        } catch (e: RuntimeException) {
            tracingUsable = false
        }
    }

    @PublishedApi
    internal fun endAsync(name: String, cookie: Int) {
        if (!tracingUsable || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            Trace.endAsyncSection(name, cookie)
        } catch (e: RuntimeException) {
            tracingUsable = false
        }
    }

    inline fun <T> section(name: String, block: () -> T): T {
        begin("reader.$name")
        return try {
            block()
        } finally {
            end()
        }
    }

    suspend fun <T> suspendSection(name: String, block: suspend () -> T): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return block()
        val cookie = nextAsyncCookie.incrementAndGet()
        beginAsync("reader.$name", cookie)
        return try {
            block()
        } finally {
            endAsync("reader.$name", cookie)
        }
    }

    /**
     * 归因打点。`marker` 会被放在 composable 体内，每次重组都执行；未开 tracing 时必须尽早
     * 返回，否则每条 marker 都是两次 `Trace` 静态调用乘以重组次数。
     * 注意：API < 29 没有 `Trace.isEnabled()`，这里随之整体跳过（与 suspendSection 一致）。
     * 段名必须截断：`Trace.beginSection` 对超过 127 字符的名字直接抛 IllegalArgumentException，
     * 而 marker 常在组合期调用，抛出即整个应用崩溃。
     */
    fun marker(name: String) {
        // isEnabled 前置（上游：marker 在 composable 体内每次重组执行，未开 tracing 时
        // 尽早返回省两次 Trace 静态调用；纯 JVM 单测 SDK_INT=0 在此短路）。begin/end
        // 内部保留失效软化，Trace 异常环境降级为 no-op 而不拖垮调用方。
        if (!isEnabled()) return
        begin(markerSectionName(name))
        end()
    }

    /** 段名截断（上游 3c57 轮并入）：begin 走失效软化，截断让超长名仍可追踪而非整体降级。 */
    private fun markerSectionName(name: String): String =
        if (name.length <= MAX_NAME) "reader.$name" else "reader." + name.substring(0, MAX_NAME)

    fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()

    /** Aggregate costs from an interleaved operation without tracing every paragraph. */
    fun counter(name: String, value: Long) {
        if (isEnabled()) Trace.setCounter("reader.$name", value)
    }
}
