package io.legado.app.eink.feature.reader.screensaver

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates iReader lock-screen rendering and publication for one visible
 * reader surface.
 *
 * Invariants:
 * - all system gates are checked before allocating the target bitmap;
 * - page/settings bursts are debounced, then serialized with a process-wide
 *   mutex shared by every surface ([publishEpoch] supersedes a slow flush from
 *   a surface that was already closed);
 * - identical successful content is skipped unless a settings observer asks
 *   to reclaim the shared wallpaper after SystemUI reset it (rate limited so
 *   a firmware settings rewrite cannot form a publish feedback loop);
 * - COVER mode checks the digest fingerprint before decoding the cover bitmap;
 * - file write, validation, broadcast, and old-file cleanup happen in order.
 */
internal class ReaderScreensaverPublisher(
    context: Context,
    private val system: ReaderScreensaverSystem = ReaderScreensaverSystem(context),
    private val renderer: ReaderScreensaverRenderer = ReaderScreensaverRenderer(),
    private val writer: RmbFilePublisher = RmbFilePublisher(),
    private val coverLoader: ReaderScreensaverCoverLoader =
        ReaderScreensaverCoverLoader(context),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = ReaderScreensaverPublicationGate()
    private val debouncer = ReaderScreensaverDebouncer(
        scope = scope,
        debounceMillis = DEBOUNCE_MILLIS,
        onReady = ::publishLatest,
    )

    @Volatile
    private var latestRequest: ReaderScreensaverRequest? = null

    @Volatile
    private var acceptingRequests = true

    /** 本组合最近一次调度的发布代数；跨组合由 [ReaderScreensaverPublishEpoch] 仲裁。 */
    @Volatile
    private var scheduledEpoch = 0L

    private var forceNextPublish = false
    private var started = false

    /** 上次 settings 观察者触发 force 夺回的时刻（elapsedRealtime），限频断路器用。 */
    @Volatile
    private var lastForceReclaimAt = 0L

    @Volatile
    private var targetPlatform = false

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // System settings may rewrite its default wallpaper after changing
            // these keys. Force one reclaim even when our fingerprint matches.
            // Rate limited: if SystemUI normalizes any observed key after our
            // broadcast, an unthrottled force would form a publish loop.
            val now = SystemClock.elapsedRealtime()
            val force = synchronized(this@ReaderScreensaverPublisher) {
                if (now - lastForceReclaimAt >= FORCE_RECLAIM_INTERVAL_MS) {
                    lastForceReclaimAt = now
                    true
                } else {
                    false
                }
            }
            requestPublish(immediate = false, force = force)
        }
    }

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
            targetPlatform = system.isTargetPlatform()
        }
        if (targetPlatform) {
            system.registerObserver(settingsObserver)
        }
    }

    fun updateRequest(request: ReaderScreensaverRequest) {
        latestRequest = request
    }

    fun requestPublish(immediate: Boolean = false, force: Boolean = false) {
        if (!acceptingRequests) return
        start()
        if (!targetPlatform) return
        synchronized(this) {
            forceNextPublish = forceNextPublish || force
        }
        // 记录本次调度代数：晚于本组合的任何调度（含其它组合）都会使本
        // 代数过期，在副作用前被复核丢弃；close(flush) 不另行调度，flush
        // 携带旧代数——被新组合超越时整体放弃，正是期望行为
        scheduledEpoch = publishEpoch.schedule()
        debouncer.request(immediate)
    }

    /**
     * Stops accepting new work and always unregisters the settings observer.
     * With [flush], the latest request is attempted immediately even though no
     * further page/lifecycle requests can arrive.
     */
    fun close(flush: Boolean) {
        val shouldFlush = synchronized(this) {
            if (!acceptingRequests) return@close
            acceptingRequests = false
            val flushNow = flush && latestRequest != null && targetPlatform
            if (!flushNow) {
                debouncer.cancel()
            }
            flushNow
        }
        if (targetPlatform) system.unregisterObserver(settingsObserver)
        if (shouldFlush) {
            debouncer.request(immediate = true)
        }
    }

    private suspend fun publishLatest() {
        // 进程级串行：多组合并发发布按代数仲裁后仍需互斥执行，保证
        // cleanupObsolete 永远只能删除上一份已发布文件
        publicationMutex.withLock {
            val epoch = scheduledEpoch
            // 已有更新调度（含其它阅读组合）——本周期整体放弃
            if (!publishEpoch.isCurrent(epoch)) return@withLock
            val request = latestRequest ?: return@withLock
            val settings = system.readSettings()
            val mode = settings.mode
            if (!isCapable(settings) || mode == null) {
                gate.reset()
                return@withLock
            }

            var coverSource: ReaderScreensaverCoverSource? = null
            var cover: ReaderScreensaverCover? = null
            var illustration: android.graphics.Bitmap? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                // COVER 模式判重前置：先取源字节+摘要（无位图分配），指纹
                // 与上次发布一致时直接跳过——避免每次翻页都全量解码后才
                // 被指纹去重丢弃（eink 弱 CPU 上是持续的后台浪费）
                if (mode == ReaderScreensaverMode.COVER) {
                    coverSource = coverLoader.loadSource(request.bookUrl)
                    if (coverSource == null) {
                        Log.i(TAG, "cover unavailable book_url=${request.bookUrl.take(24)}")
                        return@withLock
                    }
                }
                val force = synchronized(this) {
                    val value = forceNextPublish
                    forceNextPublish = false
                    value
                }
                var fingerprint = ReaderScreensaverFingerprint.of(
                    request = request,
                    settings = settings,
                    coverDigest = coverSource?.digest,
                )
                if (!gate.shouldPublish(fingerprint, force)) return@withLock

                if (mode == ReaderScreensaverMode.COVER) {
                    val source = requireNotNull(coverSource)
                    val decoded = coverLoader.decodeAndUpgrade(source)
                    if (decoded == null) {
                        Log.i(TAG, "cover decode failed book_url=${request.bookUrl.take(24)}")
                        return@withLock
                    }
                    cover = decoded
                    // 大图重载可能换内容（摘要变化）——以最终摘要复核指纹，
                    // 已发布过的内容直接跳过；force 意图已被首查消费
                    if (decoded.digest != source.digest) {
                        fingerprint = ReaderScreensaverFingerprint.of(
                            request = request,
                            settings = settings,
                            coverDigest = decoded.digest,
                        )
                        if (!gate.shouldPublish(fingerprint, false)) return@withLock
                    }
                }

                val renderSettings = system.readSettings()
                if (!isCapable(renderSettings) || renderSettings.mode != mode) {
                    gate.reset()
                    return@withLock
                }
                illustration = system.loadIllustration(renderSettings)
                bitmap = when (mode) {
                    ReaderScreensaverMode.COVER -> renderer.renderCover(
                        cover = requireNotNull(cover).bitmap,
                        illustration = illustration,
                    )

                    ReaderScreensaverMode.READING_PROGRESS -> renderer.render(
                        request = request,
                        illustration = illustration,
                    )
                }
                currentCoroutineContext().ensureActive()

                // Recheck immediately before encoding/writing: switching the
                // feature off while a frame is being rendered must not publish.
                val writeSettings = system.readSettings()
                if (!isCapable(writeSettings) || writeSettings.mode != mode) {
                    gate.reset()
                    return@withLock
                }
                // 渲染期间被更新调度超越（如换书后旧 flush 仍在跑）——放弃
                // 写入，避免旧文件覆盖新书并误删其壁纸
                if (!publishEpoch.isCurrent(epoch)) return@withLock

                val renderTimeMillis = System.currentTimeMillis()
                val encoded = RmbBitmapEncoder.encode(bitmap, renderTimeMillis)
                val published = writer.write(
                    encoded = encoded,
                    targetDirectory = java.io.File(mode.targetDirectory),
                )
                try {
                    ReaderScreensaverNotifier.notifyChanged(appContext, mode)
                } catch (throwable: Throwable) {
                    Log.w(TAG, "broadcast failed: ${throwable.javaClass.simpleName}")
                    return@withLock
                }
                writer.cleanupObsolete(published)
                gate.markPublished(fingerprint)
                Log.i(
                    TAG,
                    "published page_content_hash=${fingerprint.take(12)} " +
                        "mode=${mode.wallpaperType} " +
                        "bitmap=${encoded.width}x${encoded.height} " +
                        "config=${encoded.format.bitmapConfigName} " +
                        "bytes=${encoded.bytes.size}",
                )
            } catch (throwable: Throwable) {
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                Log.w(
                    TAG,
                    "publish failed: ${throwable.javaClass.simpleName}",
                )
            } finally {
                bitmap?.recycle()
                illustration?.recycle()
                cover?.bitmap?.recycle()
            }
        }
    }

    private fun isCapable(settings: ReaderScreensaverSettings): Boolean =
        resolveReaderScreensaverCapability(
            ReaderScreensaverCapabilityInput(
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                systemUiInstalled = targetPlatform,
                settings = settings,
            ),
        )

    companion object {
        private const val TAG = "ReaderScreensaver"
        internal const val DEBOUNCE_MILLIS = 300L

        /** settings 观察者 force 夺回的最小间隔：防固件回写观察键形成自激环。 */
        internal const val FORCE_RECLAIM_INTERVAL_MS = 60_000L

        /** 进程级：所有阅读组合共享的发布互斥与调度代数（跨实例仲裁）。 */
        private val publicationMutex = Mutex()
        internal val publishEpoch = ReaderScreensaverPublishEpoch()
    }
}
