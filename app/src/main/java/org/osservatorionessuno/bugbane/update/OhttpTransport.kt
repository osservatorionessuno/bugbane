package org.osservatorionessuno.bugbane.update

import android.util.Log
import org.osservatorionessuno.libbhttp.BhttpRequest
import org.osservatorionessuno.libbhttp.BhttpResponse
import org.osservatorionessuno.libbhttp.decodeResponseStream
import org.osservatorionessuno.libbhttp.encodeRequest
import org.osservatorionessuno.libohttp.MEDIA_TYPE_CHUNKED_REQUEST
import org.osservatorionessuno.libohttp.MEDIA_TYPE_CHUNKED_RESPONSE
import org.osservatorionessuno.libohttp.OhttpClient
import org.osservatorionessuno.libohttp.bouncycastle.BouncyCastleHpkeBackend
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Oblivious HTTP transport for the indicator update feed, using the chunked variant
 * (draft-ietf-ohai-chunked-ohttp) for every request: response chunks are decrypted and released
 * as they arrive — each only after its own AEAD tag verified — so even the full bundle is never
 * held in memory. Chunked-ness is part of the gateway configuration the client knows, and per the
 * draft's consistency considerations (§7) there is no fallback to the non-chunked variant.
 *
 * Every request is encoded as BHTTP, encapsulated against the gateway's key config, and POSTed
 * through an Oblivious relay so the gateway never sees the client address. The key config is
 * shipped with the app ([EMBEDDED_KEY_CONFIG]).
 */
class OhttpTransport : UpdateTransport {
    private val client: OhttpClient = OhttpClient.fromKeyConfigList(EMBEDDED_KEY_CONFIG, BouncyCastleHpkeBackend())

    override fun get(path: String): BhttpResponse = getStream(path) { status, body -> BhttpResponse(status, emptyList(), body.readBytes()) }

    /**
     * Issue an oblivious GET for [path] (an absolute path on the update origin, e.g.
     * `/v1/update.json`) and hand the inner status and body to [consume] while the response
     * streams from the wire.
     */
    override fun <T> getStream(
        path: String,
        consume: (Int, InputStream) -> T,
    ): T {
        val request = client.encapsulateChunkedRequest()
        val inner = encodeRequest(
            BhttpRequest(
                method = "GET",
                scheme = TARGET_SCHEME,
                authority = TARGET_AUTHORITY,
                path = path,
                headers = listOf("accept" to "*/*", "accept-encoding" to "gzip"),
            ),
        )
        val body = request.header + request.sealFinalChunk(inner)

        val conn = (URL(RELAY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", MEDIA_TYPE_CHUNKED_RESPONSE)
            setRequestProperty("Content-Type", MEDIA_TYPE_CHUNKED_REQUEST)
            // Ask intermediaries (the relay) to forward the body progressively instead of buffering
            setRequestProperty("Incremental", "?1")
            doOutput = true
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                // Only the size is reported, so drain counting bytes — buffering would hand the
                // untrusted relay an unbounded allocation on the error path.
                val errSize = conn.errorStream?.use { drainCounting(it) } ?: 0L
                throw IOException("POST $RELAY_URL -> HTTP $code ($errSize bytes of error body)")
            }
            if (conn.contentType?.contains(MEDIA_TYPE_CHUNKED_RESPONSE) != true) {
                throw IOException("unexpected relay content type ${conn.contentType}")
            }
            conn.inputStream.use { raw ->
                val plaintext = request.decapsulateChunkedResponse(BoundedInputStream(raw, MAX_RESPONSE_BYTES, "relay response"))
                val response = decodeResponseStream(plaintext)
                Log.i(TAG, "GET $path -> inner status ${response.statusCode}")
                return consume(response.statusCode, decoded(response.headers, response.body))
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Apply the response's content-encoding. Only gzip is offered ([accept-encoding]); the body is
     * streamed through the decompressor and bounded, since a small gzip can expand hugely and the
     * bytes are unverified until the hash check downstream. An unencoded body passes through.
     */
    internal fun decoded(
        headers: List<Pair<String, String>>,
        body: InputStream,
        maxBytes: Long = MAX_DECOMPRESSED_BYTES,
    ): InputStream {
        val gzip = headers.any { it.first.equals("content-encoding", ignoreCase = true) && it.second.trim().equals("gzip", ignoreCase = true) }
        return if (gzip) BoundedInputStream(GZIPInputStream(body), maxBytes, "decompressed response") else body
    }

    /** Count [input]'s bytes without retaining them, giving up at [MAX_RESPONSE_BYTES]. */
    private fun drainCounting(input: InputStream): Long {
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        while (total <= MAX_RESPONSE_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Caps how many bytes [input] can yield, throwing past [limit]. Used on the wire (a rogue relay
     * must not stream forever) and on the decompressed body (a small gzip must not expand without
     * bound). Both limits leave generous headroom over the real bundle size.
     */
    internal class BoundedInputStream(
        input: InputStream,
        private val limit: Long,
        private val what: String,
    ) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) count(it) }

        private fun count(n: Int) {
            total += n
            if (total > limit) throw IOException("$what exceeds $limit bytes")
        }
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

        /** Hard ceiling on the total wire bytes of a single relay response (128MB). */
        private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024

        /** Hard ceiling on a gzip-decompressed response body (128MB). */
        private const val MAX_DECOMPRESSED_BYTES = 128L * 1024 * 1024
    }
}
