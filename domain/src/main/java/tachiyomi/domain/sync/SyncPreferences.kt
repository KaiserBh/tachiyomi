package tachiyomi.domain.sync

import tachiyomi.core.preference.PreferenceStore
import java.time.Instant

class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun syncHost() = preferenceStore.getString("sync_host", "https://sync.tachiyomi.org")
    fun syncAPIKey() = preferenceStore.getString("sync_api_key", "")
    fun syncLastSync() = preferenceStore.getInstant("sync_last_sync", Instant.EPOCH)

    fun syncInterval() = preferenceStore.getInt("sync_interval", 0)

    fun deviceName() = preferenceStore.getString("device_name", android.os.Build.MANUFACTURER + android.os.Build.PRODUCT)

    fun deviceID() = preferenceStore.getInt("device_id", 0)

    fun syncService() = preferenceStore.getInt("sync_service", 0)

    fun googleAccountJson() = preferenceStore.getString("google_account_json", "")

    fun setGoogleAccountJson(accountJson: String) {
        googleAccountJson().set(accountJson)
    }

    fun clearGoogleAccountJson() {
        googleAccountJson().delete()
    }
}
