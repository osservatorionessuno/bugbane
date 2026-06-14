package org.osservatorionessuno.bugbane.update

import org.osservatorionessuno.libbhttp.BhttpResponse

/**
 * Minimal transport seam the updater fetches over. The production implementation is [OhttpTransport]
 * (Oblivious HTTP); tests substitute an in-memory fake so the update logic can be exercised without
 * a network or HPKE.
 */
interface UpdateTransport {
    fun get(path: String): BhttpResponse
}
