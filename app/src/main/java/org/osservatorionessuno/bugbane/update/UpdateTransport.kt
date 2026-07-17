package org.osservatorionessuno.bugbane.update

import org.osservatorionessuno.libbhttp.BhttpResponse
import java.io.InputStream

/**
 * Minimal transport seam the updater fetches over. The production implementation is [OhttpTransport]
 * (Oblivious HTTP); tests substitute an in-memory fake so the update logic can be exercised without
 * a network or HPKE.
 */
interface UpdateTransport {
    /** Whole-body GET, for small resources (update.json, deltas). */
    fun get(path: String): BhttpResponse

    /**
     * GET whose response body [consume] reads as a stream while it downloads, for the full bundle —
     * it is never held in memory. A body read that throws means tampering or truncation; reaching
     * EOF means the response arrived complete.
     */
    fun <T> getStream(
        path: String,
        consume: (statusCode: Int, body: InputStream) -> T,
    ): T
}
