package io.legado.app.eink.feature.reader.screensaver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RmbEncoderTest {

    @Test
    fun encodePacked_writesBwarHeaderAndExactPayload() {
        val pixels = ByteArray(2 * 2 * 2) { it.toByte() }
        val encoded = RmbEncoder.encodePacked(
            packedPixels = pixels,
            width = 2,
            height = 2,
            format = RmbPixelFormat.RGB_565,
            renderTimeMillis = 1234L,
        )

        assertEquals("BWAR", encoded.bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(RmbEncoder.HEADER_SIZE + pixels.size, encoded.bytes.size)
        assertEquals(
            RmbHeader(width = 2, height = 2, format = RmbPixelFormat.RGB_565),
            RmbEncoder.inspectHeader(encoded.bytes),
        )
        assertArrayEquals(
            pixels,
            encoded.bytes.copyOfRange(RmbEncoder.HEADER_SIZE, encoded.bytes.size),
        )
        assertEquals("2&2&RGB_565&1234.rmb", encoded.fileName)
    }

    @Test
    fun packRmbRows_removesPaddingAndKeepsPixelsInRowOrder() {
        val source = byteArrayOf(
            1, 2, 3, 4, 90, 91,
            5, 6, 7, 8, 92, 93,
        )

        val packed = packRmbRows(
            source = source,
            rowBytes = 6,
            width = 2,
            height = 2,
            bytesPerPixel = 2,
            swapPairsForLittleEndian = false,
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), packed)
    }

    @Test
    fun packRmbRows_swapsRgb565WordsForLittleEndianTarget() {
        val packed = packRmbRows(
            source = byteArrayOf(0x12, 0x34, 0x56, 0x78),
            rowBytes = 4,
            width = 2,
            height = 1,
            bytesPerPixel = 2,
            swapPairsForLittleEndian = true,
        )

        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), packed)
    }
}
