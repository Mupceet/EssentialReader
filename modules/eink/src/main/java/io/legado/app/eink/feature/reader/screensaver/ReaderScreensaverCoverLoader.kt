package io.legado.app.eink.feature.reader.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import io.legado.app.eink.contract.BookDetailUiModel
import io.legado.app.eink.contract.EInkEngineRegistry
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReaderScreensaverCover(
    val bitmap: Bitmap,
    val digest: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * Loads the current Legado book cover through the same host cover pipeline used
 * by bookshelf/detail images.
 *
 * The fingerprint is calculated from the source bytes rather than the cover URL:
 * imported books and custom covers can keep the same path while their content
 * changes, and style 2 must republish in that case.
 */
internal class ReaderScreensaverCoverLoader(
    context: Context,
    private val loadBookDetail: suspend (String) -> BookDetailUiModel? = {
        EInkEngineRegistry.bookDetailEngine.loadBookDetail(it)
    },
    private val fetchRemoteCoverBytes: suspend (String, String?) -> ByteArray? = { url, origin ->
        EInkEngineRegistry.coverEngine.fetchCoverBytes(url, origin)
    },
    private val reloadRemoteCoverBytes: suspend (String, String?) -> ByteArray? = { url, origin ->
        EInkEngineRegistry.coverEngine.reloadCoverBytes(url, origin)
    },
) {
    private val appContext = context.applicationContext
    private val attemptedReloads = object : LinkedHashMap<String, Boolean>(8, 0.75f, true) {
        fun markAttempt(key: String): Boolean {
            if (containsKey(key)) return false
            put(key, true)
            while (size > MAX_RELOAD_ATTEMPTS) {
                remove(keys.first())
            }
            return true
        }
    }

    suspend fun load(bookUrl: String): ReaderScreensaverCover? {
        if (bookUrl.isBlank()) return null
        val detail = try {
            loadBookDetail(bookUrl)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "book detail load failed: ${throwable.javaClass.simpleName}")
            null
        } ?: return null
        val coverUrl = detail.displayCover?.trim()
        if (coverUrl.isNullOrEmpty()) return null

        val sourceBytes = readCoverBytes(
            url = coverUrl,
            sourceOrigin = detail.origin.takeIf { it.isNotBlank() },
        ) ?: return null
        if (!isValidCoverBytes(sourceBytes)) return null

        val sourceDigest = ReaderScreensaverFingerprint.sha256Hex(sourceBytes)
        var cover = decodeCover(sourceBytes, sourceDigest) ?: return null
        if (
            coverUrl.startsWith("http", ignoreCase = true) &&
            coverNeedsHighResolutionReload(
                width = cover.bitmap.width,
                height = cover.bitmap.height,
            )
        ) {
            val attemptKey = "$coverUrl|$sourceDigest"
            val shouldReload = synchronized(attemptedReloads) {
                attemptedReloads.markAttempt(attemptKey)
            }
            if (shouldReload) {
                val reloadedBytes = reloadRemoteCoverBytes(
                    coverUrl,
                    detail.origin.takeIf { it.isNotBlank() },
                )
                if (reloadedBytes != null && isValidCoverBytes(reloadedBytes)) {
                    val reloadedDigest = ReaderScreensaverFingerprint.sha256Hex(reloadedBytes)
                    synchronized(attemptedReloads) {
                        attemptedReloads.markAttempt("$coverUrl|$reloadedDigest")
                    }
                    if (reloadedDigest != sourceDigest) {
                        val reloaded = decodeCover(reloadedBytes, reloadedDigest)
                        if (reloaded != null) {
                            if (reloaded.pixelCount > cover.pixelCount) {
                                Log.i(
                                    TAG,
                                    "cover reload upgraded " +
                                        "${cover.bitmap.width}x${cover.bitmap.height} -> " +
                                        "${reloaded.bitmap.width}x${reloaded.bitmap.height}",
                                )
                                cover.bitmap.recycle()
                                cover = reloaded
                            } else {
                                reloaded.bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }

        Log.i(
            TAG,
            "cover source=${cover.sourceWidth}x${cover.sourceHeight} " +
                "decoded=${cover.bitmap.width}x${cover.bitmap.height} " +
                "needs_reload=${coverNeedsHighResolutionReload(
                    cover.bitmap.width,
                    cover.bitmap.height,
                )}",
        )
        return cover
    }

    private suspend fun readCoverBytes(url: String, sourceOrigin: String?): ByteArray? =
        try {
            when {
                url.startsWith("http", ignoreCase = true) -> {
                    fetchRemoteCoverBytes(url, sourceOrigin)
                }

                url.startsWith("data:", ignoreCase = true) -> decodeDataUri(url)

                else -> withContext(Dispatchers.IO) {
                    openLocalStream(url)?.use(InputStream::readBytes)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "cover bytes load failed: ${throwable.javaClass.simpleName}")
            null
        }

    private fun openLocalStream(url: String): InputStream? {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "content", "file" -> appContext.contentResolver.openInputStream(uri)
            null -> File(url).takeIf { it.isFile }?.let(::FileInputStream)
            else -> null
        }
    }

    private fun decodeDataUri(url: String): ByteArray? {
        val separator = url.indexOf(',')
        if (separator < 0) return null
        val metadata = url.substring(5, separator)
        val payload = url.substring(separator + 1)
        return if (metadata.contains(";base64", ignoreCase = true)) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            Uri.decode(payload).toByteArray(Charsets.UTF_8)
        }
    }

    private fun decodeCover(bytes: ByteArray, digest: String): ReaderScreensaverCover? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateCoverSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        return ReaderScreensaverCover(
            bitmap = bitmap,
            digest = digest,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
        )
    }

    private fun isValidCoverBytes(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.size <= MAX_COVER_BYTES

    private val ReaderScreensaverCover.pixelCount: Long
        get() = bitmap.width.toLong() * bitmap.height.toLong()

    companion object {
        private const val TAG = "ReaderScreensaver"
        private const val MAX_COVER_BYTES = 20 * 1024 * 1024
        private const val MAX_RELOAD_ATTEMPTS = 16
    }
}

/**
 * Samples only while both decoded dimensions stay at least as large as the
 * lock-screen target. Decoding below the target would force an avoidable
 * second upscale.
 */
internal fun calculateCoverSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int = ReaderScreensaverRenderer.TARGET_WIDTH,
    targetHeight: Int = ReaderScreensaverRenderer.TARGET_HEIGHT,
): Int {
    var sampleSize = 1
    while (
        sourceWidth / (sampleSize * 2) >= targetWidth &&
        sourceHeight / (sampleSize * 2) >= targetHeight
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun coverNeedsHighResolutionReload(
    width: Int,
    height: Int,
    targetWidth: Int = ReaderScreensaverRenderer.TARGET_WIDTH,
    targetHeight: Int = ReaderScreensaverRenderer.TARGET_HEIGHT,
): Boolean = width < targetWidth || height < targetHeight
