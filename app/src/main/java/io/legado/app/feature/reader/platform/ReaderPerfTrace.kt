package io.legado.app.feature.reader.platform

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, reader-local Perfetto vocabulary. Keep sections coarse: the point is to explain the
 * path to the first readable page, not to trace every Canvas draw or Compose recomposition.
 */
internal object ReaderPerfTrace {
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

    fun marker(name: String) {
        if (!tracingUsable) return
        begin("reader.$name")
        end()
    }

    fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()

    /** Aggregate costs from an interleaved operation without tracing every paragraph. */
    fun counter(name: String, value: Long) {
        if (isEnabled()) Trace.setCounter("reader.$name", value)
    }
}
