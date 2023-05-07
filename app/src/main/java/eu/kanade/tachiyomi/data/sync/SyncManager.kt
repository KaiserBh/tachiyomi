package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_ALL
import eu.kanade.tachiyomi.data.backup.BackupManager
import eu.kanade.tachiyomi.data.backup.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.models.SData
import eu.kanade.tachiyomi.data.sync.models.SyncDevice
import eu.kanade.tachiyomi.data.sync.models.SyncStatus
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.gzip
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.util.system.logcat
import tachiyomi.data.Chapters
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.manga.mangaMapper
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.sync.SyncPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val getFavorites: GetFavorites = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),

) {
    private val backupManager: BackupManager = BackupManager(context)
    private val notifier: SyncNotifier = SyncNotifier(context)

    enum class SyncService(val value: Int) {
        NONE(0),
        GOOGLE_DRIVE(1),
        SELF_HOSTED(2),
        ;

        companion object {
            fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with the remote server or Google Drive.
     *
     * This function retrieves local data (favorites, manga, extensions, and categories)
     * from the database using the BackupManager, then synchronizes the data with the remote
     * server or uploads the data to Google Drive.
     * The server URL, API key, or Google Drive are determined based on the sync preferences.
     * The data is sent to the server or uploaded to Google Drive by calling `sendSyncData()`.
     */
    suspend fun syncData() {
        val databaseManga = getAllMangaFromDB()

        logcat(LogPriority.DEBUG) { "Mangas to sync: $databaseManga" }
        val backup = Backup(
            backupManager.backupMangas(databaseManga, BACKUP_ALL),
            backupManager.backupCategories(BACKUP_ALL),
            emptyList(),
            backupManager.backupExtensionInfo(databaseManga),
        )

        when (val syncService = SyncService.fromInt(syncPreferences.syncService().get())) {
            SyncService.GOOGLE_DRIVE -> {
                syncDataWithServer(
                    SyncService.GOOGLE_DRIVE,
                    null,
                    null,
                    backup,
                )
            }

            SyncService.SELF_HOSTED -> {
                val host = syncPreferences.syncHost().get()
                val apiKey = syncPreferences.syncAPIKey().get()
                val url = "$host/api/sync/data"

                syncDataWithServer(
                    SyncService.SELF_HOSTED,
                    url,
                    apiKey,
                    backup,
                )
            }

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
            }
        }
    }

    /**
     * Sends a sync request to the server containing backup data.
     *
     * This function constructs the required objects (SyncStatus, SyncDevice, and SData)
     * using the given input parameters, converts the SData object to a JSON string,
     * and sends the JSON data to the server or uploads it to Google Drive depending on the sync service.
     *
     * @param syncService The sync service type (Google Drive or Self-hosted).
     * @param url The server URL to send the sync request to (only required for Self-hosted sync).
     * @param apiKey The API key for authentication (only required for Self-hosted sync).
     * @param backup A Backup object containing the manga, categories, and extensions data to be synced.
     */
    private suspend fun syncDataWithServer(
        syncService: SyncService,
        url: String?,
        apiKey: String?,
        backup: Backup,
    ) {
        // Create the SyncStatus object
        val syncStatus = SyncStatus(
            lastSynced = Instant.now().toString(),
            status = "completed",
        )

        // Create the Device object
        val device = SyncDevice(
            id = syncPreferences.deviceID().get(),
            name = syncPreferences.deviceName().get(),
        )

        // Create the SyncData object
        val syncData = SData(
            sync = syncStatus,
            backup = backup,
            device = device,
        )

        // Convert the SyncData object to a JSON string
        val jsonData = json.encodeToString(syncData)

        // Handle sync based on the selected service
        when (syncService) {
            SyncService.GOOGLE_DRIVE -> {
                sendSyncData(
                    url = "",
                    apiKey = "",
                    jsonData = jsonData,
                    storageType = "GoogleDrive",
                )
            }

            SyncService.SELF_HOSTED -> {
                if (url != null && apiKey != null) {
                    sendSyncData(url, apiKey, jsonData)
                }
            }

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
            }
        }
    }

    /**
     * Sends the sync data to the server as a compressed Gzip or uploads it to Google Drive.
     *
     * This function prepares an HTTP POST request with the given URL, API key, and JSON data for the server
     * or uploads the JSON data to Google Drive depending on the storageType.
     * The JSON data is sent in the request body, and the API key is added as a header for server requests.
     * After sending the data, the function updates the local data if required.
     *
     * If the storageType is "GoogleDrive", the function uploads the JSON data to Google Drive using the GoogleDriveSync class.
     * If the upload is successful, the function decodes the combined JSON data and updates the local data with the new backup.
     * The sync last sync preference is then updated with the current time.
     *
     * If the storageType is null (indicating a server sync), the function sends an HTTP POST request to the server with the given URL and API key.
     * The JSON data is sent as a compressed Gzip in the request body, and the API key is added as a header.
     * If the request is successful, the function decodes the sync data, updates the local data with the new backup, and updates the sync last sync preference with the current time.
     * If the device ID is 0 or different from the server device ID, the function also updates the local device ID preference.
     *
     * @param url The server URL to send the sync data to.
     * @param apiKey The API key for authentication.
     * @param jsonData The JSON string containing the sync data.
     * @param storageType The storage type to use for sync (e.g., "GoogleDrive"). Defaults to null for server sync.
     */
    private suspend fun sendSyncData(
        url: String,
        apiKey: String,
        jsonData: String,
        storageType: String? = null,
    ) {
        if (storageType == "GoogleDrive") {
            logcat(
                LogPriority.DEBUG,
            ) { "GoogleDrive sync started!" }
            val googleDriveSync = GoogleDriveSync(context)
            val combinedJsonData = googleDriveSync.uploadToGoogleDrive(jsonData)
            if (combinedJsonData != null) {
                val backup = decodeSyncBackup(combinedJsonData)
                val (filteredFavorites, nonFavorites) = filterFavoritesAndNonFavorites(backup)
                updateNonFavorites(nonFavorites)
                SyncHolder.backup = backup.copy(backupManga = filteredFavorites)
                BackupRestoreJob.start(context, "".toUri(), true)
                syncPreferences.syncLastSync().set(Instant.now())
                logcat(
                    LogPriority.DEBUG,
                ) { "GoogleDrive sync completed!" }
                return
            }
            logcat(
                LogPriority.DEBUG,
            ) { "GoogleDrive sync failed!" }
        } else {
            logcat(
                LogPriority.DEBUG,
            ) { "SyncYomi sync started!" }

            val client = OkHttpClient()
            val mediaType = "application/gzip".toMediaTypeOrNull()
            val body = jsonData.toRequestBody(mediaType).gzip()

            val headers = Headers.Builder()
                .add("Content-Type", "application/gzip")
                .add("Content-Encoding", "gzip")
                .add("X-API-Token", apiKey)
                .build()

            val request = POST(
                url = url,
                headers = headers,
                body = body,
            )

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()

                if (response.isSuccessful) {
                    val syncDataResponse: SData = json.decodeFromString(responseBody)

                    val backup = decodeSyncBackup(responseBody)
                    val (filteredFavorites, nonFavorites) = filterFavoritesAndNonFavorites(backup)
                    updateNonFavorites(nonFavorites)
                    SyncHolder.backup = backup.copy(backupManga = filteredFavorites)
                    BackupRestoreJob.start(context, "".toUri(), true)
                    syncPreferences.syncLastSync().set(Instant.now())

                    // If the device ID is 0 and not equal to the server device ID (this happens when the DB is fresh and the app is not), update it
                    if (syncPreferences.deviceID().get() == 0 || syncPreferences.deviceID()
                        .get() != syncDataResponse.device?.id
                    ) {
                        syncDataResponse.device?.id?.let { syncPreferences.deviceID().set(it) }
                    }

                    logcat(
                        LogPriority.DEBUG,
                    ) { "SyncYomi sync completed!" }
                } else {
                    notifier.showSyncError("Failed to sync: $responseBody")
                    responseBody.let { logcat(LogPriority.ERROR) { "SyncError:$it" } }
                }
            }
        }
    }

    /**
     * Decodes the given sync data string into a Backup object.
     *
     * @param data The sync data string to be decoded.
     * @return The decoded Backup object.
     */
    private fun decodeSyncBackup(data: String): Backup {
        val sData = json.decodeFromString(SData.serializer(), data)
        return sData.backup!!
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDB(): List<Manga> {
        return handler.awaitList { mangasQueries.getAllManga(mangaMapper) }
    }

    /**
     * Compares two Manga objects (one from the local database and one from the backup) to check if they are different.
     * @param localManga the Manga object from the local database.
     * @param remoteManga the BackupManga object from the backup.
     * @return true if the Manga objects are different, otherwise false.
     */
    private suspend fun isMangaDifferent(localManga: Manga, remoteManga: BackupManga): Boolean {
        val localChapters = handler.await { chaptersQueries.getChaptersByMangaId(localManga.id).executeAsList() }
        val localCategories = getCategories.await(localManga.id).map { it.order }

        return localManga.source != remoteManga.source ||
            localManga.url != remoteManga.url ||
            localManga.title != remoteManga.title ||
            localManga.artist != remoteManga.artist ||
            localManga.author != remoteManga.author ||
            localManga.description != remoteManga.description ||
            localManga.genre != remoteManga.genre ||
            localManga.status.toInt() != remoteManga.status ||
            localManga.thumbnailUrl != remoteManga.thumbnailUrl ||
            localManga.dateAdded != remoteManga.dateAdded ||
            localManga.chapterFlags.toInt() != remoteManga.chapterFlags ||
            localManga.favorite != remoteManga.favorite ||
            localManga.viewerFlags.toInt() != remoteManga.viewer_flags ||
            localManga.updateStrategy != remoteManga.updateStrategy ||
            areChaptersDifferent(localChapters, remoteManga.chapters) ||
            localCategories != remoteManga.categories
    }

    /**
     * Compares two lists of chapters (one from the local database and one from the backup) to check if they are different.
     * @param localChapters the list of chapters from the local database.
     * @param remoteChapters the list of BackupChapter objects from the backup.
     * @return true if the lists of chapters are different, otherwise false.
     */
    private fun areChaptersDifferent(localChapters: List<Chapters>, remoteChapters: List<BackupChapter>): Boolean {
        if (localChapters.size != remoteChapters.size) {
            return true
        }

        val localChapterMap = localChapters.associateBy { it.url }

        return remoteChapters.any { remoteChapter ->
            localChapterMap[remoteChapter.url]?.let { localChapter ->
                localChapter.name != remoteChapter.name ||
                    localChapter.scanlator != remoteChapter.scanlator ||
                    localChapter.read != remoteChapter.read ||
                    localChapter.bookmark != remoteChapter.bookmark ||
                    localChapter.last_page_read != remoteChapter.lastPageRead ||
                    localChapter.date_fetch != remoteChapter.dateFetch ||
                    localChapter.date_upload != remoteChapter.dateUpload ||
                    localChapter.chapter_number != remoteChapter.chapterNumber ||
                    localChapter.source_order != remoteChapter.sourceOrder
            } ?: true
        }
    }

    /**
     * Filters the favorite and non-favorite manga from the backup and checks if the favorite manga is different from the local database.
     * @param backup the Backup object containing the backup data.
     * @return a Pair of lists, where the first list contains different favorite manga and the second list contains non-favorite manga.
     */
    private suspend fun filterFavoritesAndNonFavorites(backup: Backup): Pair<List<BackupManga>, List<BackupManga>> {
        val databaseMangaFavorites = getFavorites.await()
        val localMangaMap = databaseMangaFavorites.associateBy { it.url }
        val favorites = mutableListOf<BackupManga>()
        val nonFavorites = mutableListOf<BackupManga>()

        backup.backupManga.forEach { remoteManga ->
            if (remoteManga.favorite) {
                localMangaMap[remoteManga.url]?.let { localManga ->
                    if (isMangaDifferent(localManga, remoteManga)) {
                        favorites.add(remoteManga)
                    }
                } ?: favorites.add(remoteManga)
            } else {
                nonFavorites.add(remoteManga)
            }
        }

        return Pair(favorites, nonFavorites)
    }

    /**
     * Updates the non-favorite manga in the local database with their favorite status from the backup.
     * @param nonFavorites the list of non-favorite BackupManga objects from the backup.
     */
    private suspend fun updateNonFavorites(nonFavorites: List<BackupManga>) {
        val localMangaList = getAllMangaFromDB()
        val localMangaMap = localMangaList.associateBy { it.url }

        nonFavorites.forEach { nonFavorite ->
            localMangaMap[nonFavorite.url]?.let { localManga ->
                if (localManga.favorite != nonFavorite.favorite) {
                    val updatedManga = localManga.copy(favorite = nonFavorite.favorite)
                    backupManager.updateManga(updatedManga)
                }
            }
        }
    }
}
