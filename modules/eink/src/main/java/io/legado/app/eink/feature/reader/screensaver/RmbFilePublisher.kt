package io.legado.app.eink.feature.reader.screensaver

import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal data class PublishedRmbFile(
    val target: File,
    val obsolete: List<File>,
)

/**
 * Writes a complete BWAR image next to the old wallpaper and only swaps it in
 * after the temp file is durable and externally validated.
 */
internal class RmbFilePublisher {

    fun write(
        encoded: EncodedRmb,
        targetDirectory: File = TARGET_DIRECTORY,
    ): PublishedRmbFile {
        val header = RmbEncoder.inspectHeader(encoded.bytes)
            ?: error("Invalid BWAR header")
        require(header.width == encoded.width && header.height == encoded.height)
        require(header.format == encoded.format)
        val expectedLength = RmbEncoder.HEADER_SIZE.toLong() +
            encoded.width.toLong() * encoded.height.toLong() * encoded.format.bytesPerPixel
        require(encoded.bytes.size.toLong() == expectedLength)

        require(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Cannot create ${targetDirectory.absolutePath}"
        }

        val target = uniqueTarget(encoded, targetDirectory)
        val temp = File(
            targetDirectory,
            ".${target.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp",
        )
        var moved = false
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(encoded.bytes)
                stream.flush()
                stream.fd.sync()
            }
            Os.chmod(temp.absolutePath, 0x1FF)
            validateFile(temp, encoded)

            Os.rename(temp.absolutePath, target.absolutePath)
            moved = true
            check(target.setLastModified(encoded.renderTimeMillis)) {
                "Cannot set lastModified on ${target.name}"
            }
            validateFile(target, encoded)
        } catch (throwable: Throwable) {
            if (moved) target.delete()
            if (temp.exists()) temp.delete()
            throw throwable
        }

        return PublishedRmbFile(
            target = target,
            obsolete = targetDirectory.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it != target && it.extension == "rmb" }
                ?.toList()
                .orEmpty(),
        )
    }

    /**
     * Removes the previous wallpaper only after the replacement has been
     * validated and the SystemUI reload broadcast was sent.
     */
    fun cleanupObsolete(published: PublishedRmbFile) {
        published.obsolete.forEach { file ->
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "old rmb cleanup failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun uniqueTarget(encoded: EncodedRmb, targetDirectory: File): File {
        var suffix = encoded.renderTimeMillis
        while (true) {
            val replacement = encoded.copy(renderTimeMillis = suffix)
            val candidate = File(targetDirectory, replacement.fileName)
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun validateFile(file: File, encoded: EncodedRmb) {
        val expectedLength = RmbEncoder.HEADER_SIZE.toLong() +
            encoded.width.toLong() * encoded.height.toLong() * encoded.format.bytesPerPixel
        require(file.length() == expectedLength) {
            "RMB length ${file.length()} != $expectedLength"
        }
        val headerBytes = ByteArray(RmbEncoder.HEADER_SIZE)
        file.inputStream().use { input ->
            require(input.read(headerBytes) == RmbEncoder.HEADER_SIZE) {
                "Short RMB header"
            }
        }
        val header = RmbEncoder.inspectHeader(headerBytes)
            ?: error("Invalid persisted BWAR header")
        require(header.width == encoded.width)
        require(header.height == encoded.height)
        require(header.format == encoded.format)
    }

    companion object {
        private const val TAG = "ReaderScreensaver"
        internal val TARGET_DIRECTORY = File("/data/zhangyue/logo/reading_progress")
    }
}
