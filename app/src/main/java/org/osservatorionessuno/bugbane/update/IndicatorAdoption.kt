package org.osservatorionessuno.bugbane.update

import java.io.OutputStream

/**
 * The single point where a candidate indicator set is verified and written to the store.
 *
 * Every source converges here — the online [IndicatorUpdater], the APK-bundled set
 * ([BundledIndicators]), and (future) a set loaded from disk — so nothing reaches the store
 * without passing verification, and the store is only ever written in one place.
 *
 * Signature / sigsum verification is intentionally NOT done yet; when it is added it goes next to
 * the hash check in [adopt], which is enough to cover every source at once.
 */
object IndicatorAdoption {

    /**
     * Stream a candidate set from [writeTo], verify it is exactly the set [meta] describes, and on
     * success atomically replace the stored set with it. The candidate goes through a scratch file
     * (never memory), and adoption — the rename plus state write — only happens after the SHA-256
     * computed over the exact staged bytes matches `meta.sha256`.
     *
     * Returns the adopted object count, or null if verification failed (the scratch file is
     * discarded and nothing is adopted).
     *
     * [checkedAtEpoch] / [updatedAtEpoch] record when this adoption happened; pass 0 for a source
     * that isn't an online check (e.g. the bundled set), so the UI still reads "not checked online".
     */
    fun adopt(
        store: IndicatorStore,
        meta: UpdateMetadata,
        checkedAtEpoch: Long,
        updatedAtEpoch: Long,
        writeTo: (OutputStream) -> Unit,
    ): Int? {
        val staged = store.stage(writeTo)
        if (staged.sha256 != meta.sha256) {
            staged.discard()
            return null
        }
        store.adoptStaged(staged)
        store.writeState(
            IndicatorStore.State(
                schema = meta.schema,
                version = meta.version,
                // Computed over the staged bytes, so `state.sha256 == hash(bundle on disk)`
                // holds by construction (and it equals meta.sha256, or we wouldn't be here).
                sha256 = staged.sha256,
                sunset = meta.sunset,
                buildDate = meta.buildDate,
                objectCount = staged.objectCount,
                lastCheckEpoch = checkedAtEpoch,
                lastUpdateEpoch = updatedAtEpoch,
            ),
        )
        return staged.objectCount
    }
}
