package ru.sosiskibot.luckystar.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val DB_NAME = "offline_download.db"
private const val DOWNLOAD_WORK_NAME = "download_all_library"

@Serializable
private data class OfflineLibraryDto(
    val chapters: List<OfflineChapterDto> = emptyList(),
    val chapterIndex: Map<String, OfflineChapterDto> = emptyMap(),
)

@Serializable
private data class OfflineChapterDto(
    val id: String,
    val pages: List<OfflinePageDto> = emptyList(),
)

@Serializable
private data class OfflinePageDto(
    val index: Int,
    val url: String,
    val size: Long = 0,
)

@Entity(tableName = "download_state")
data class DownloadStateEntity(
    @PrimaryKey val id: Int = 1,
    val status: String,
    val downloadedPages: Int,
    val totalPages: Int,
    val bytesDownloaded: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
)

@Entity(tableName = "offline_page")
data class OfflinePageEntity(
    @PrimaryKey val pageId: String,
    val chapterId: String,
    val pageIndex: Int,
    val remoteUrl: String,
    val localPath: String,
    val sizeBytes: Long,
    val checksumSha256: String,
)

@Dao
interface OfflineDownloadDao {
    @Query("SELECT * FROM download_state WHERE id = 1")
    fun observeState(): Flow<DownloadStateEntity?>

    @Query("SELECT * FROM download_state WHERE id = 1")
    suspend fun getState(): DownloadStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: DownloadStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOfflinePage(entity: OfflinePageEntity)

    @Query("SELECT pageId FROM offline_page")
    suspend fun getAllPageIds(): List<String>

    @Query("DELETE FROM offline_page")
    suspend fun clearOfflinePages()
}

@Database(
    entities = [DownloadStateEntity::class, OfflinePageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OfflineDownloadDatabase : RoomDatabase() {
    abstract fun dao(): OfflineDownloadDao

    companion object {
        @Volatile
        private var INSTANCE: OfflineDownloadDatabase? = null

        fun get(context: Context): OfflineDownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineDownloadDatabase::class.java,
                    DB_NAME,
                ).build().also { INSTANCE = it }
            }
        }
    }
}

data class DownloadProgress(
    val status: String,
    val downloadedPages: Int,
    val totalPages: Int,
    val progressFraction: Float,
    val bytesDownloaded: Long,
    val updatedAt: Long,
    val errorMessage: String?,
)

class OfflineDownloadRepository(
    private val context: Context,
    private val dao: OfflineDownloadDao,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun observeProgress(): Flow<DownloadProgress> {
        return dao.observeState().map { entity ->
            entity?.toDomain() ?: DownloadProgress(
                status = "idle",
                downloadedPages = 0,
                totalPages = 0,
                progressFraction = 0f,
                bytesDownloaded = 0,
                updatedAt = System.currentTimeMillis(),
                errorMessage = null,
            )
        }
    }

    suspend fun markQueued() {
        dao.upsertState(
            DownloadStateEntity(
                status = "queued",
                downloadedPages = 0,
                totalPages = 0,
                bytesDownloaded = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun runDownloadAll(baseUrl: String, forceRedownload: Boolean = false) {
        val manifestUrl = normalizeBaseUrl(baseUrl) + "data/library.json"
        val manifestBody = httpGetBytes(manifestUrl)
        val manifest = json.decodeFromString<OfflineLibraryDto>(manifestBody.decodeToString())
        val chapterMap = manifest.chapterIndex.ifEmpty { manifest.chapters.associateBy { it.id } }

        val allPages = chapterMap.values
            .flatMap { ch -> ch.pages.map { pg -> Triple(ch.id, pg.index, pg) } }
            .sortedWith(compareBy({ it.first }, { it.second }))

        val totalPages = allPages.size
        var downloadedPages = 0
        var bytesDownloaded = 0L

        if (forceRedownload) {
            dao.clearOfflinePages()
            cacheRoot(context).deleteRecursively()
        }

        val existingIds = dao.getAllPageIds().toHashSet()
        writeState("running", downloadedPages, totalPages, bytesDownloaded, null)

        for ((chapterId, pageIndex, page) in allPages) {
            val pageId = "$chapterId:$pageIndex"
            if (!forceRedownload && existingIds.contains(pageId)) {
                downloadedPages += 1
                writeState("running", downloadedPages, totalPages, bytesDownloaded, null)
                continue
            }

            val localFile = localFileFor(context, chapterId, pageIndex, page.url)
            localFile.parentFile?.mkdirs()

            val bytes = httpGetBytes(resolveUrl(baseUrl, page.url))
            localFile.writeBytes(bytes)
            val checksum = sha256(bytes)
            bytesDownloaded += bytes.size.toLong()

            dao.upsertOfflinePage(
                OfflinePageEntity(
                    pageId = pageId,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    remoteUrl = page.url,
                    localPath = localFile.absolutePath,
                    sizeBytes = bytes.size.toLong(),
                    checksumSha256 = checksum,
                ),
            )

            downloadedPages += 1
            writeState("running", downloadedPages, totalPages, bytesDownloaded, null)
        }

        writeState("completed", downloadedPages, totalPages, bytesDownloaded, null)
    }

    suspend fun markFailed(message: String) {
        val prev = dao.getState()
        writeState(
            status = "failed",
            downloaded = prev?.downloadedPages ?: 0,
            total = prev?.totalPages ?: 0,
            bytes = prev?.bytesDownloaded ?: 0L,
            err = message,
        )
    }

    private suspend fun writeState(
        status: String,
        downloaded: Int,
        total: Int,
        bytes: Long,
        err: String?,
    ) {
        dao.upsertState(
            DownloadStateEntity(
                status = status,
                downloadedPages = downloaded,
                totalPages = total,
                bytesDownloaded = bytes,
                updatedAt = System.currentTimeMillis(),
                errorMessage = err,
            ),
        )
    }

    private fun httpGetBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw IOException("HTTP ${res.code} for $url")
            }
            return res.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun DownloadStateEntity.toDomain(): DownloadProgress {
        val fraction = if (totalPages <= 0) 0f else downloadedPages.toFloat() / totalPages.toFloat()
        return DownloadProgress(
            status = status,
            downloadedPages = downloadedPages,
            totalPages = totalPages,
            progressFraction = fraction.coerceIn(0f, 1f),
            bytesDownloaded = bytesDownloaded,
            updatedAt = updatedAt,
            errorMessage = errorMessage,
        )
    }
}

class DownloadAllWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return DownloadNotifier(applicationContext).asForegroundInfo(
            title = "Lucky Star",
            text = "Preparing offline download…",
            progress = 0,
        )
    }

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString(KEY_BASE_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing base url"))
        val force = inputData.getBoolean(KEY_FORCE, false)

        val db = OfflineDownloadDatabase.get(applicationContext)
        val repository = OfflineDownloadRepository(applicationContext, db.dao())

        return try {
            setForeground(getForegroundInfo())
            repository.runDownloadAll(baseUrl = baseUrl, forceRedownload = force)
            setProgress(workDataOf(KEY_PROGRESS to 100))
            DownloadNotifier(applicationContext).notifyCompleted()
            Result.success()
        } catch (t: Throwable) {
            repository.markFailed(t.message ?: "Download failed")
            DownloadNotifier(applicationContext).notifyFailed(t.message ?: "Download failed")
            Result.retry()
        }
    }

    companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_FORCE = "force"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
    }
}

class DownloadWorkScheduler(private val context: Context) {

    fun enqueueDownloadAll(baseUrl: String, forceRedownload: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<DownloadAllWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadAllWorker.KEY_BASE_URL, baseUrl)
                    .putBoolean(DownloadAllWorker.KEY_FORCE, forceRedownload)
                    .build(),
            )
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DOWNLOAD_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    suspend fun markQueuedInDb() {
        val db = OfflineDownloadDatabase.get(context)
        OfflineDownloadRepository(context, db.dao()).markQueued()
    }
}

fun cacheRoot(context: Context): File = File(context.filesDir, "library")

private fun localFileFor(context: Context, chapterId: String, pageIndex: Int, remoteUrl: String): File {
    val ext = remoteUrl.substringAfterLast('.', "jpg").substringBefore('?')
    return File(cacheRoot(context), "$chapterId/$pageIndex.$ext")
}

private fun normalizeBaseUrl(base: String): String {
    val trimmed = base.trim()
    return if (trimmed.endsWith('/')) trimmed else "$trimmed/"
}

private fun resolveUrl(baseUrl: String, raw: String): String {
    return when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("/") -> {
            val b = normalizeBaseUrl(baseUrl)
            val hostRoot = b.substringBefore("/luckystar/")
            "$hostRoot$raw"
        }
        else -> normalizeBaseUrl(baseUrl) + raw
    }
}

private fun sha256(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
