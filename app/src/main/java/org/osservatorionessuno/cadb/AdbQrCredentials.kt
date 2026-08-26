package org.osservatorionessuno.cadb

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

/**
 * Credentials + payload for Android Wireless Debugging "Pair device with QR code".
 *
 * Bugbane displays the QR; the target phone scans it. Payload format matches AOSP/adb:
 * `WIFI:T:ADB;S:<name>;P:<password>;;`
 */
data class AdbQrCredentials(
    val serviceName: String,
    val password: String,
) {
    val qrPayload: String
        get() = "WIFI:T:ADB;S:$serviceName;P:$password;;"

    fun toBitmap(sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(qrPayload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    companion object {
        private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
        private val random = SecureRandom()

        fun generate(): AdbQrCredentials =
            AdbQrCredentials(
                serviceName = randomString(length = 11),
                password = randomString(length = 15),
            )

        private fun randomString(length: Int): String {
            val chars = CharArray(length) { alphabet[random.nextInt(alphabet.size)] }
            return String(chars)
        }
    }
}
