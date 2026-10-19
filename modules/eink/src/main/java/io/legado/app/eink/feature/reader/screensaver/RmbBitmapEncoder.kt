package io.legado.app.eink.feature.reader.screensaver

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android bitmap adapter for [RmbEncoder]. */
internal object RmbBitmapEncoder {

    fun encode(bitmap: Bitmap, renderTimeMillis: Long): EncodedRmb {
        require(!bitmap.isRecycled)
        require(bitmap.config == Bitmap.Config.RGB_565) {
            "Reader screensaver must be RGB_565, was ${bitmap.config}"
        }
        val format = RmbPixelFormat.RGB_565
        val source = ByteArray(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(source).order(ByteOrder.nativeOrder()))
        val packed = packRmbRows(
            source = source,
            rowBytes = bitmap.rowBytes,
            width = bitmap.width,
            height = bitmap.height,
            bytesPerPixel = format.bytesPerPixel,
            swapPairsForLittleEndian = ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN,
        )
        return RmbEncoder.encodePacked(
            packedPixels = packed,
            width = bitmap.width,
            height = bitmap.height,
            format = format,
            renderTimeMillis = renderTimeMillis,
        )
    }
}
