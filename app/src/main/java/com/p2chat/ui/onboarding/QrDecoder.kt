package com.p2chat.ui.onboarding

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Pure JVM QR code decoder and image frame transformer (ID-4, D-16).
 * Handles row-stride padding, sensor rotation (90/180/270), and dual normal/inverted binarization.
 * Zero Google Play Services dependencies.
 */
object QrDecoder {

    val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    fun createReader(): MultiFormatReader {
        val reader = MultiFormatReader()
        reader.setHints(DECODE_HINTS)
        return reader
    }

    /**
     * Extracts the Y (luminance) channel from a camera buffer,
     * safely discarding padding bytes caused by rowStride > width.
     */
    fun extractLuminance(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1
    ): ByteArray {
        val yBytes = ByteArray(width * height)
        (buffer as Buffer).rewind()

        if (rowStride == width && pixelStride == 1) {
            buffer.get(yBytes, 0, width * height)
            return yBytes
        }

        if (pixelStride == 1) {
            for (row in 0 until height) {
                (buffer as Buffer).position(row * rowStride)
                val remaining = buffer.remaining()
                val bytesToRead = minOf(width, remaining)
                if (bytesToRead > 0) {
                    buffer.get(yBytes, row * width, bytesToRead)
                }
            }
        } else {
            val rowData = ByteArray(rowStride)
            for (row in 0 until height) {
                (buffer as Buffer).position(row * rowStride)
                val bytesToRead = minOf(rowStride, buffer.remaining())
                buffer.get(rowData, 0, bytesToRead)
                for (col in 0 until width) {
                    if (col * pixelStride < bytesToRead) {
                        yBytes[row * width + col] = rowData[col * pixelStride]
                    }
                }
            }
        }
        return yBytes
    }

    /**
     * Rotates a packed grayscale byte array by the given degrees (0, 90, 180, 270 clockwise).
     * Returns Triple(rotatedBytes, rotatedWidth, rotatedHeight).
     */
    fun rotateGrayscale(
        data: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Triple<ByteArray, Int, Int> {
        return when (rotationDegrees) {
            90 -> {
                val rotated = ByteArray(width * height)
                var idx = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        rotated[idx++] = data[y * width + x]
                    }
                }
                Triple(rotated, height, width)
            }
            180 -> {
                val rotated = ByteArray(width * height)
                var idx = 0
                for (y in height - 1 downTo 0) {
                    for (x in width - 1 downTo 0) {
                        rotated[idx++] = data[y * width + x]
                    }
                }
                Triple(rotated, width, height)
            }
            270 -> {
                val rotated = ByteArray(width * height)
                var idx = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        rotated[idx++] = data[y * width + x]
                    }
                }
                Triple(rotated, height, width)
            }
            else -> Triple(data, width, height)
        }
    }

    /**
     * Attempts to decode a QR code from a packed upright grayscale buffer.
     * Tries standard binarization followed by inverted binarization.
     */
    fun decodeGrayscale(
        data: ByteArray,
        width: Int,
        height: Int,
        reader: MultiFormatReader
    ): String? {
        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )

        // Pass 1: Standard normal binarization
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            if (result != null && result.text.isNotEmpty()) {
                return result.text
            }
        } catch (_: NotFoundException) {
            // Not found in standard pass
        } catch (_: Exception) {
        } finally {
            reader.reset()
        }

        // Pass 2: Inverted binarization (for dark mode QR or reflective phone glass)
        try {
            val invertedSource = source.invert()
            val bitmap = BinaryBitmap(HybridBinarizer(invertedSource))
            val result = reader.decodeWithState(bitmap)
            if (result != null && result.text.isNotEmpty()) {
                return result.text
            }
        } catch (_: NotFoundException) {
            // Not found
        } catch (_: Exception) {
        } finally {
            reader.reset()
        }

        return null
    }

    /**
     * End-to-end decode from a camera frame plane buffer.
     */
    fun decodePlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
        reader: MultiFormatReader
    ): String? {
        val packed = extractLuminance(buffer, width, height, rowStride, pixelStride)
        val (rotated, rotWidth, rotHeight) = rotateGrayscale(packed, width, height, rotationDegrees)
        return decodeGrayscale(rotated, rotWidth, rotHeight, reader)
    }
}
