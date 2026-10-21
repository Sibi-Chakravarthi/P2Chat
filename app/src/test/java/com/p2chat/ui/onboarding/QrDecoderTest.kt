package com.p2chat.ui.onboarding

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

class QrDecoderTest {

    private val testPayload = "p2chat-invite:v1:testPayload1234567890abcdef"

    private fun generateQrGrayscale(content: String, size: Int = 200, inverted: Boolean = false): ByteArray {
        val writer = QRCodeWriter()
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isDark = bitMatrix.get(x, y)
                val pixel = if (inverted) {
                    if (isDark) 0xFE.toByte() else 0x10.toByte()
                } else {
                    if (isDark) 0x00.toByte() else 0xFF.toByte()
                }
                data[y * width + x] = pixel
            }
        }
        return data
    }

    @Test
    fun decode_upright_exactStride_success() {
        val size = 200
        val gray = generateQrGrayscale(testPayload, size)
        val buffer = ByteBuffer.wrap(gray)
        val reader = QrDecoder.createReader()

        val decoded = QrDecoder.decodePlane(
            buffer = buffer,
            width = size,
            height = size,
            rowStride = size,
            pixelStride = 1,
            rotationDegrees = 0,
            reader = reader
        )

        assertNotNull("Should decode upright exact stride QR", decoded)
        assertEquals(testPayload, decoded)
    }

    @Test
    fun decode_withRowStridePadding_success() {
        val width = 200
        val height = 200
        val rowStride = 256 // 56 bytes padding per row
        val gray = generateQrGrayscale(testPayload, width)

        val paddedBuffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            paddedBuffer.position(y * rowStride)
            paddedBuffer.put(gray, y * width, width)
        }

        val reader = QrDecoder.createReader()
        val decoded = QrDecoder.decodePlane(
            buffer = paddedBuffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 1,
            rotationDegrees = 0,
            reader = reader
        )

        assertNotNull("Should decode QR with row stride padding", decoded)
        assertEquals(testPayload, decoded)
    }

    @Test
    fun decode_rotated90Degrees_withStride_success() {
        val width = 200
        val height = 200
        val gray = generateQrGrayscale(testPayload, width)

        // Rotate the source 90 degrees counter-clockwise (so applying 90 deg clockwise will right it)
        // Camera sensor has 90 deg rotation relative to portrait
        // In CameraX: sensor image is sideways (e.g. 270 counter to portrait, so rotationDegrees is 90)
        // If image in buffer is rotated CCW by 90 deg, then QrDecoder.rotateGrayscale with 90 deg restores it!
        val ccw = ByteArray(width * height)
        var idx = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                ccw[idx++] = gray[y * width + x]
            }
        }

        val rowStride = 240
        val paddedBuffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            paddedBuffer.position(y * rowStride)
            paddedBuffer.put(ccw, y * width, width)
        }

        val reader = QrDecoder.createReader()
        val decoded = QrDecoder.decodePlane(
            buffer = paddedBuffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 1,
            rotationDegrees = 90,
            reader = reader
        )

        assertNotNull("Should decode 90-degree rotated QR", decoded)
        assertEquals(testPayload, decoded)
    }

    @Test
    fun decode_rotated270Degrees_withStride_success() {
        val width = 200
        val height = 200
        val gray = generateQrGrayscale(testPayload, width)

        // Rotate source by 90 deg CW, so rotationDegrees=270 restores it
        val cw = ByteArray(width * height)
        var idx = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                cw[idx++] = gray[y * width + x]
            }
        }

        val rowStride = 240
        val paddedBuffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            paddedBuffer.position(y * rowStride)
            paddedBuffer.put(cw, y * width, width)
        }

        val reader = QrDecoder.createReader()
        val decoded = QrDecoder.decodePlane(
            buffer = paddedBuffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = 1,
            rotationDegrees = 270,
            reader = reader
        )

        assertNotNull("Should decode 270-degree rotated QR", decoded)
        assertEquals(testPayload, decoded)
    }

    @Test
    fun decode_invertedQrCode_success() {
        val size = 200
        val gray = generateQrGrayscale(testPayload, size, inverted = true)
        val buffer = ByteBuffer.wrap(gray)
        val reader = QrDecoder.createReader()

        val decoded = QrDecoder.decodePlane(
            buffer = buffer,
            width = size,
            height = size,
            rowStride = size,
            pixelStride = 1,
            rotationDegrees = 0,
            reader = reader
        )

        assertNotNull("Should decode inverted QR code", decoded)
        assertEquals(testPayload, decoded)
    }
}
