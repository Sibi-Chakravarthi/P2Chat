package com.p2chat.ui.onboarding

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Offline, standalone QR code bitmap generator using ZXing (ID-4, D-16).
 * Zero Google Play Services dependencies.
 */
object QrCodeGenerator {

    fun generate(content: String, sizePx: Int = 512): ImageBitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    // High-contrast off-white modules on dark slate canvas
                    pixels[y * width + x] = if (bitMatrix.get(x, y)) 0xFFF4F4F5.toInt() else 0xFF0E0E10.toInt()
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
}
