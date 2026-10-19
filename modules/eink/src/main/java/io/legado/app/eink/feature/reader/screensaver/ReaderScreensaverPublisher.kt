package io.legado.app.eink.feature.reader.screensaver

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * - page/settings bursts are debounced, then serialized with a mutex;
 * - identical successful content is skipped unless a settings observer asks
 *   to reclaim the shared wallpaper after SystemUI reset it;
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
    private val publishMutex = Mutex()
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

    private var forceNextPublish = false
    private var started = false

    @Volatile
    private var targetPlatform = false

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // System settings may rewrite its default wallpaper after changing
            // these keys. Force one reclaim even when our fingerprint matches.
            requestPublish(immediate = false, force = true)
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
        publishMutex.withLock {
            val request = latestRequest ?: return@withLock
            val settings = system.readSettings()
            val mode = settings.mode
            if (!isCapable(settings) || mode == null) {
                gate.reset()
                return@withLock
            }

            var cover: ReaderScreensaverCover? = null
            var illustration: android.graphics.Bitmap? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                if (mode == ReaderScreensaverMode.COVER) {
                    cover = coverLoader.load(request.bookUrl)
                    if (cover == null) {
                        Log.i(TAG, "cover unavailable book_url=${request.bookUrl.take(24)}")
                        return@withLock
                    }
                }
                val fingerprint = ReaderScreensaverFingerprint.of(
                    request = request,
                    settings = settings,
                    coverDigest = cover?.digest,
                )
                val force = synchronized(this) {
                    val value = forceNextPublish
                    forceNextPublish = false
                    value
                }
                if (!gate.shouldPublish(fingerprint, force)) return@withLock

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
    }
}
