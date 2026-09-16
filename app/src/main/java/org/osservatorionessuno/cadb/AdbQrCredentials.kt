package org.osservatorionessuno.cadb

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

/**
 * Wireless Debugging "Pair device with QR code" credentials. The target scans the
 * payload, advertises `_adb-tls-pairing._tcp` as [serviceName] and accepts [password].
 */
data class AdbQrCredentials(
    val serviceName: String,
    val password: String,
) {
    val qrPayload: String
        get() = "WIFI:T:ADB;S:$serviceName;P:$password;;"

    companion object {
        private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
        private val random = SecureRandom()

        fun generate(): AdbQrCredentials =
            AdbQrCredentials(
                serviceName = randomString(length = 11),
                password = randomString(length = 15),
            )

        fun randomString(length: Int): String {
            val chars = CharArray(length) { alphabet[random.nextInt(alphabet.size)] }
            return String(chars)
        }
    }
}

object QrCode {
    /** Wi-Fi join payload (WPA2) for the camera and Settings scanners. */
    fun wifiPayload(ssid: String, passphrase: String): String =
        "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

    private fun escape(s: String): String = s.replace(Regex("""[\;,:"]""")) { "\\" + it.value }

    fun bitmap(payload: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx) { i -> if (matrix[i % sizePx, i / sizePx]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
