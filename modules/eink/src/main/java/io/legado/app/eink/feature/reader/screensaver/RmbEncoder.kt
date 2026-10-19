package io.legado.app.eink.feature.reader.screensaver

internal enum class RmbPixelFormat(
    val code: Int,
    val bytesPerPixel: Int,
    val bitmapConfigName: String,
) {
    RGB_565(code = 1, bytesPerPixel = 2, bitmapConfigName = "RGB_565"),
    ARGB_8888(code = 3, bytesPerPixel = 4, bitmapConfigName = "ARGB_8888"),
}

internal data class RmbHeader(
    val width: Int,
    val height: Int,
    val format: RmbPixelFormat,
)

internal data class EncodedRmb(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: RmbPixelFormat,
    val renderTimeMillis: Long,
) {
    val fileName: String
        get() = "${width}&${height}&${format.bitmapConfigName}&$renderTimeMillis.rmb"
}

/**
 * Pure BWAR `.rmb` encoder.
 *
 * The Android bitmap adapter removes row stride before calling this function;
 * the payload therefore contains exactly `width * height * bytesPerPixel`
 * pixels and no compression.
 */
internal object RmbEncoder {

    const val HEADER_SIZE = 16

    fun encodePacked(
        packedPixels: ByteArray,
        width: Int,
        height: Int,
        format: RmbPixelFormat,
        renderTimeMillis: Long,
    ): EncodedRmb {
        require(width > 0 && height > 0) { "Invalid image size: ${width}x$height" }
        val expectedPixels = width.toLong() * height.toLong() * format.bytesPerPixel
        require(expectedPixels <= Int.MAX_VALUE) { "RMB image is too large" }
        require(packedPixels.size.toLong() == expectedPixels) {
            "Packed pixel size ${packedPixels.size} != $expectedPixels"
        }

        val bytes = ByteArray(HEADER_SIZE + packedPixels.size)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'W'.code.toByte()
        bytes[2] = 'A'.code.toByte()
        bytes[3] = 'R'.code.toByte()
        bytes.putLittleEndianInt(offset = 4, value = width)
        bytes.putLittleEndianInt(offset = 8, value = height)
        bytes.putLittleEndianInt(offset = 12, value = format.code)
        packedPixels.copyInto(bytes, destinationOffset = HEADER_SIZE)

        return EncodedRmb(
            bytes = bytes,
            width = width,
            height = height,
            format = format,
            renderTimeMillis = renderTimeMillis,
        )
    }

    fun inspectHeader(bytes: ByteArray): RmbHeader? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != 'B'.code.toByte() ||
            bytes[1] != 'W'.code.toByte() ||
            bytes[2] != 'A'.code.toByte() ||
            bytes[3] != 'R'.code.toByte()
        ) {
            return null
        }
        val width = bytes.readLittleEndianInt(4)
        val height = bytes.readLittleEndianInt(8)
        val formatCode = bytes.readLittleEndianInt(12)
        val format = RmbPixelFormat.entries.firstOrNull { it.code == formatCode } ?: return null
        if (width <= 0 || height <= 0) return null
        return RmbHeader(width = width, height = height, format = format)
    }
}

/**
 * Removes Android bitmap row stride and normalizes RGB565 pixel words to the
 * little-endian order required by the BWAR header.
 */
internal fun packRmbRows(
    source: ByteArray,
    rowBytes: Int,
    width: Int,
    height: Int,
    bytesPerPixel: Int,
    swapPairsForLittleEndian: Boolean,
): ByteArray {
    require(rowBytes >= width * bytesPerPixel)
    val packedRowBytes = width * bytesPerPixel
    require(source.size >= rowBytes * height)
    val packed = ByteArray(packedRowBytes * height)
    for (y in 0 until height) {
        val sourceOffset = y * rowBytes
        val targetOffset = y * packedRowBytes
        source.copyInto(
            destination = packed,
            destinationOffset = targetOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + packedRowBytes,
        )
        if (swapPairsForLittleEndian && bytesPerPixel == 2) {
            var index = targetOffset
            while (index < targetOffset + packedRowBytes) {
                val first = packed[index]
                packed[index] = packed[index + 1]
                packed[index + 1] = first
                index += 2
            }
        }
    }
    return packed
}

private fun ByteArray.putLittleEndianInt(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = (value ushr 8 and 0xff).toByte()
    this[offset + 2] = (value ushr 16 and 0xff).toByte()
    this[offset + 3] = (value ushr 24 and 0xff).toByte()
}

private fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
