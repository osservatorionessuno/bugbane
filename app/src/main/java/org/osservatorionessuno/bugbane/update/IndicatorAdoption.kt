package org.osservatorionessuno.bugbane.update

/**
 * The single point where a candidate indicator set is verified and written to the store.
 *
 * Every source converges here — the online [IndicatorUpdater], the APK-bundled set
 * ([BundledIndicators]), and (future) a set loaded from disk — so nothing reaches the store
 * without passing [verify], and the store is only ever written in one place.
 *
 * Signature / sigsum verification is intentionally NOT done yet; when it is added it goes in
 * [verify], which is enough to cover every source at once.
 */
object IndicatorAdoption {

    /**
     * Whether [bundle] is exactly the set [meta] describes. Integrity only, for now — the feed
     * signature / sigsum proof over [meta] will also be checked here later.
     */
    fun verify(meta: UpdateMetadata, bundle: ByteArray): Boolean =
        OhttpTransport.sha256Hex(bundle) == meta.sha256

    /**
     * [verify] [bundle] against [meta] and, on success, atomically replace the stored set with it.
     * Returns the adopted object count, or null if verification failed (nothing is written).
     *
     * [checkedAtEpoch] / [updatedAtEpoch] record when this adoption happened; pass 0 for a source
     * that isn't an online check (e.g. the bundled set), so the UI still reads "not checked online".
     */
    fun adopt(
        store: IndicatorStore,
        meta: UpdateMetadata,
        bundle: ByteArray,
        checkedAtEpoch: Long,
        updatedAtEpoch: Long,
    ): Int? {
        if (!verify(meta, bundle)) return null
        val objectCount = store.writeBundle(bundle)
        store.writeState(
            IndicatorStore.State(
                schema = meta.schema,
                version = meta.version,
                // verify() passed, so this equals the hash of the bytes just written.
                sha256 = meta.sha256,
                sunset = meta.sunset,
                buildDate = meta.buildDate,
                objectCount = objectCount,
                lastCheckEpoch = checkedAtEpoch,
                lastUpdateEpoch = updatedAtEpoch,
            ),
        )
        return objectCount
    }
}
