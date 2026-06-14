package org.osservatorionessuno.bugbane.update

import android.util.Log
import org.osservatorionessuno.libbhttp.BhttpRequest
import org.osservatorionessuno.libbhttp.BhttpResponse
import org.osservatorionessuno.libbhttp.decodeResponse
import org.osservatorionessuno.libbhttp.encodeRequest
import org.osservatorionessuno.libohttp.MEDIA_TYPE_REQUEST
import org.osservatorionessuno.libohttp.MEDIA_TYPE_RESPONSE
import org.osservatorionessuno.libohttp.OhttpClient
import org.osservatorionessuno.libohttp.bouncycastle.BouncyCastleHpkeBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * Oblivious HTTP (RFC 9458) transport for the indicator update feed.
 *
 * Every update request is encoded as BHTTP, encapsulated against the gateway's key config, and
 * POSTed through an Oblivious relay so the gateway never sees the client address.
 *
 * The key config is shipped with the app ([EMBEDDED_KEY_CONFIG]).
 */
class OhttpTransport : UpdateTransport {
    private val client: OhttpClient = OhttpClient.fromKeyConfigList(EMBEDDED_KEY_CONFIG, BouncyCastleHpkeBackend())

    /**
     * Issue an oblivious GET for [path] (an absolute path on the update origin, e.g. `/v1/update.json`)
     * and return the decoded inner BHTTP response.
     */
    override fun get(path: String): BhttpResponse {
        val bhttp = encodeRequest(
            BhttpRequest(
                method = "GET",
                scheme = TARGET_SCHEME,
                authority = TARGET_AUTHORITY,
                path = path,
                headers = listOf("accept" to "*/*"),
            ),
        )
        val encapsulated = client.encapsulateRequest(bhttp)
        val ohttpResponse = relayPost(encapsulated.bytes)
        val responseBytes = encapsulated.decapsulateResponse(ohttpResponse)
        val response = decodeResponse(responseBytes)
        Log.i(TAG, "GET $path -> inner status ${response.statusCode}, ${response.body.size} bytes")
        return response
    }

    /** POST the encapsulated request to the relay (the only host the client talks to) and return the
     * raw `message/ohttp-res` bytes. */
    private fun relayPost(body: ByteArray): ByteArray {
        val conn = (URL(RELAY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", MEDIA_TYPE_RESPONSE)
            setRequestProperty("Content-Type", MEDIA_TYPE_REQUEST)
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                throw IOException("POST $RELAY_URL -> HTTP $code (${err.size} bytes of error body)")
            }
            return conn.inputStream.use { readBounded(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Read the relay response with a hard ceiling. The relay is a separate, not-fully-trusted party,
     * so an unbounded read would let a rogue relay OOM the app; [MAX_RESPONSE_BYTES] bounds it while
     * leaving generous headroom over the real bundle size.
     */
    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_RESPONSE_BYTES) throw IOException("relay response exceeds $MAX_RESPONSE_BYTES bytes")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    companion object {
        const val TAG = "OhttpTransport"

        /**
         * Gateway key config (RFC 9458 §3, the `application/ohttp-keys` length-prefixed list format),
         * shipped with the app. On key rotation, regenerate from `https://update.bugbane.org/ohttp-keys`
         * and ship the new bytes in an app update.
         * sha256 = 58a88a2136d3871cc9ab78786bc04b3aa91eb27076e964f5c729070e5a8252cc (pinned 2026-06).
         */
        private val EMBEDDED_KEY_CONFIG: ByteArray =
            Base64.getDecoder().decode("AC0BACBD/fKo5k/IUJ08ZOvqwhq7bZYEO6noR2al+vbhKIsyEwAIAAEAAQABAAM=")

        /**
         * Oblivious relay resource that forwards encapsulated requests to our gateway. This relay
         * resource is configured to forward to the `/gateway` endpoint on [TARGET_AUTHORITY].
         */
        const val RELAY_URL = "https://relay.oblivious.network/moving-mist-26"

        /** Inner (gateway-side) request origin — where update.json / fulls / deltas live. */
        const val TARGET_SCHEME = "https"
        const val TARGET_AUTHORITY = "update.bugbane.org"

        private const val TIMEOUT_MS = 15_000

        /** Hard ceiling on a single relay response (128MB). */
        private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
