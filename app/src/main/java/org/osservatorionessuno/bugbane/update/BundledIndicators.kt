package org.osservatorionessuno.bugbane.update

import android.content.Context
import android.util.Log

/**
 * Seeds [IndicatorStore] from the IOC set bundled in the APK, so the app analyzes offline out of
 * the box.
 *
 * The snapshot is committed under `assets/[ASSET_DIR]/` (`update.json` + `indicators.json`,
 * refreshed by the `refreshBundledIndicators` Gradle task). Adopted when newer than the stored
 * set (fresh install, or an app update newer than the last online fetch); [IndicatorUpdater]
 * handles online updates.
 *
 * Cheap when unchanged (metadata read + version compare); the bundle is read only when adopted.
 */
object BundledIndicators {
    private const val TAG = "BundledIndicators"
    private const val ASSET_DIR = "bundled-indicators"
    private const val META_ASSET = "$ASSET_DIR/update.json"
    private const val BUNDLE_ASSET = "$ASSET_DIR/indicators.json"

    /** Adopt the bundled feed into [store] if it is present and newer than the stored version. */
    fun seedIfStale(context: Context, store: IndicatorStore = IndicatorStore(context)) {
        val assets = context.assets

        val metaBytes = runCatching { assets.open(META_ASSET).use { it.readBytes() } }.getOrNull()
            ?: return // no bundle shipped — nothing to seed
        val meta = runCatching { UpdateMetadata.parse(metaBytes) }.getOrNull() ?: run {
            Log.w(TAG, "Bundled update.json unreadable; skipping seed")
            return
        }
        if (meta.schema != IndicatorUpdater.SCHEMA) return // bundle targets another feed schema

        val local = store.readState()
        // Keep the stored set if it is the same schema and at least as new.
        if (local.schema == meta.schema && local.version >= meta.version) return

        val bundle = runCatching { assets.open(BUNDLE_ASSET).use { it.readBytes() } }.getOrNull() ?: run {
            Log.w(TAG, "Bundled indicators.json missing though update.json present; skipping seed")
            return
        }
        // Adopt via the shared gate with timestamps 0 (this set was never fetched online).
        val objectCount = IndicatorAdoption.adopt(store, meta, bundle, checkedAtEpoch = 0, updatedAtEpoch = 0)
        if (objectCount == null) {
            Log.w(TAG, "Bundled indicators failed verification (hash mismatch); skipping seed")
            return
        }
        Log.i(TAG, "Seeded indicators v${meta.version} from the bundled set ($objectCount objects)")
    }
}
