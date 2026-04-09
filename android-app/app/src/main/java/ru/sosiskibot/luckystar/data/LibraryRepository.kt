package ru.sosiskibot.luckystar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.sosiskibot.luckystar.diag.AppLogger
import ru.sosiskibot.luckystar.offline.OfflineDownloadDatabase
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.math.roundToInt

class LibraryRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("luckystar_reader", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "offline-library")
    private val libraryCache = File(cacheDir, "library.json")
    private val offlineDao by lazy { OfflineDownloadDatabase.get(context).dao() }

    @Volatile
    private var inMemoryLibrary: List<LibrarySeries>? = null

    @Volatile
    private var inMemoryLoadedAt: Long = 0L

    suspend fun getLibrary(forceRefresh: Boolean = false): List<LibrarySeries> = withContext(Dispatchers.IO) {
        val cached = inMemoryLibrary
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - inMemoryLoadedAt < MEMORY_CACHE_TTL_MS) {
            AppLogger.i("LibraryRepository", "Using in-memory library cache")
            return@withContext cached
        }

        val response = loadLibrary()
        val chapterMap = response.chapterIndex.ifEmpty { response.chapters.associateBy { it.id } }
        val offlinePageLookup = loadOfflinePageLookup()

        val mapped = response.series.map { series ->
            val releases = series.releases.map { release ->
                val chapters = release.chapterIds.mapNotNull { chapterId ->
                    chapterMap[chapterId]?.toLibraryChapter(
                        seriesTitle = series.title,
                        releaseTitle = release.title,
                        offlinePages = offlinePageLookup[chapterId].orEmpty(),
                    )
                }
                val releaseProgress = aggregateProgress(chapters.map { it.progress })
                LibraryRelease(
                    id = release.id,
                    title = release.title,
                    type = release.type,
                    number = release.number,
                    progress = releaseProgress,
                    chapters = chapters,
                )
            }.filter { it.chapters.isNotEmpty() }

            val seriesProgress = aggregateProgress(
                releases.flatMap { release -> release.chapters.map { it.progress } },
            )
            LibrarySeries(
                id = series.id,
                title = series.title,
                releaseLabel = series.releaseLabel,
                chapterLabel = series.chapterLabel,
                bannerUrl = toAbsoluteUrl(series.banner),
                progress = seriesProgress,
                releases = releases,
            )
        }.filter { it.releases.isNotEmpty() }

        inMemoryLibrary = mapped
        inMemoryLoadedAt = now
        AppLogger.i("LibraryRepository", "Library mapped: series=${mapped.size}")
        mapped
    }

    suspend fun getChapter(chapterId: String): LibraryChapter? {
        val chapter = getLibrary(forceRefresh = false).asSequence()
            .flatMap { it.releases.asSequence() }
            .flatMap { it.chapters.asSequence() }
            .firstOrNull { it.id == chapterId }
        if (chapter == null) {
            AppLogger.w("LibraryRepository", "Chapter not found: ${chapterId}")
        }
        return chapter
    }

    fun getProgress(chapterId: String): ReaderProgress {
        val pageIndex = prefs.getInt(progressKey(chapterId), 0).coerceAtLeast(0)
        val knownPageCount = prefs.getInt(progressPagesKey(chapterId), 0).coerceAtLeast(0)
        val summary = getChapterProgressSummary(chapterId, knownPageCount)
        return ReaderProgress(
            chapterId = chapterId,
            pageIndex = pageIndex,
            percent = summary.percent,
            completed = summary.completed,
        )
    }

    fun saveProgress(chapterId: String, pageIndex: Int) {
        prefs.edit().putInt(progressKey(chapterId), pageIndex.coerceAtLeast(0)).apply()
        prefs.edit().putString("last_chapter_id", chapterId).apply()
    }

    fun getLastChapterId(): String? = prefs.getString("last_chapter_id", null)

    private suspend fun loadLibrary(): LibraryResponse = withContext(Dispatchers.IO) {
        val remoteResult = runCatching { fetchRemoteLibrary() }
        remoteResult.getOrNull()?.let { remote ->
            AppLogger.i("LibraryRepository", "Remote library fetched successfully")
            persistLibraryCache(remote)
            return@withContext remote
        }

        remoteResult.exceptionOrNull()?.let { error ->
            AppLogger.w("LibraryRepository", "Remote library fetch failed, trying cache", error)
        }

        val cached = readCachedLibrary()
        if (cached != null) {
            AppLogger.i("LibraryRepository", "Using cached library manifest")
            return@withContext cached
        }

        val error = IOException("Library unavailable: remote fetch failed and no local cache")
        AppLogger.e("LibraryRepository", "Library unavailable", error)
        throw error
    }

    private suspend fun fetchRemoteLibrary(): LibraryResponse = withContext(Dispatchers.IO) {
        val body = URL(REMOTE_LIBRARY_URL)
            .openConnection()
            .apply {
                connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
                readTimeout = NETWORK_READ_TIMEOUT_MS
            }
            .getInputStream()
            .bufferedReader()
            .use { it.readText() }
        json.decodeFromString(LibraryResponse.serializer(), body)
    }

    private suspend fun loadOfflinePageLookup(): Map<String, Map<Int, String>> = withContext(Dispatchers.IO) {
        runCatching { offlineDao.getAllOfflinePages() }
            .getOrElse { emptyList() }
            .asSequence()
            .mapNotNull { entry ->
                val localFile = File(entry.localPath)
                if (!localFile.exists() || localFile.length() <= 0L) {
                    null
                } else {
                    Triple(entry.chapterId, entry.pageIndex, localFile.toURI().toString())
                }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second to it.third })
            .mapValues { (_, rows) -> rows.associate { (index, url) -> index to url } }
    }

    private fun ChapterDto.toLibraryChapter(
        seriesTitle: String,
        releaseTitle: String,
        offlinePages: Map<Int, String>,
    ): LibraryChapter {
        val pagesResolved = pages.mapIndexed { position, page ->
            offlinePages[page.index]
                ?: offlinePages[position]
                ?: offlinePages[position + 1]
                ?: toAbsoluteUrl(page.url)
        }
        val totalPages = pageCount.takeIf { it > 0 } ?: pages.size
        val progress = getChapterProgressSummary(id, totalPages)
        rememberChapterTotalPages(id, totalPages)

        return LibraryChapter(
            id = id,
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            releaseId = releaseId,
            releaseTitle = releaseTitle,
            chapter = chapter,
            title = title,
            shortTitle = shortTitle.ifBlank { title },
            thumb = resolveThumb(thumb, pagesResolved),
            progress = progress,
            pages = pagesResolved,
        )
    }

    fun getChapterProgressSummary(chapterId: String, totalPages: Int): ReadingProgressSummary {
        val normalizedTotal = totalPages.coerceAtLeast(0)
        if (normalizedTotal == 0) {
            return ReadingProgressSummary(
                percent = 0,
                completed = false,
                current = 0,
                total = 0,
                completedItems = 0,
                totalItems = 1,
            )
        }

        val progressKey = progressKey(chapterId)
        if (!prefs.contains(progressKey)) {
            return ReadingProgressSummary(
                percent = 0,
                completed = false,
                current = 0,
                total = normalizedTotal,
                completedItems = 0,
                totalItems = 1,
            )
        }

        val storedIndex = prefs.getInt(progressKey, 0).coerceAtLeast(0)
        val current = (storedIndex + 1).coerceIn(0, normalizedTotal)
        val completed = current >= normalizedTotal
        val percent = ((current.toFloat() / normalizedTotal.toFloat()) * 100f)
            .roundToInt()
            .coerceIn(0, 100)

        return ReadingProgressSummary(
            percent = percent,
            completed = completed,
            current = current,
            total = normalizedTotal,
            completedItems = if (completed) 1 else 0,
            totalItems = 1,
        )
    }

    private fun aggregateProgress(items: List<ReadingProgressSummary>): ReadingProgressSummary {
        if (items.isEmpty()) return ReadingProgressSummary()

        val total = items.sumOf { it.total.coerceAtLeast(0) }
        val current = items.sumOf { item ->
            item.current.coerceAtLeast(0).coerceAtMost(item.total.coerceAtLeast(0))
        }
        val totalItems = items.size
        val completedItems = items.count { it.completed }

        val percent = when {
            total > 0 -> ((current.toFloat() / total.toFloat()) * 100f).roundToInt()
            totalItems > 0 -> ((completedItems.toFloat() / totalItems.toFloat()) * 100f).roundToInt()
            else -> 0
        }.coerceIn(0, 100)

        return ReadingProgressSummary(
            percent = percent,
            completed = totalItems > 0 && completedItems == totalItems,
            current = current,
            total = total,
            completedItems = completedItems,
            totalItems = totalItems,
        )
    }

    private fun rememberChapterTotalPages(chapterId: String, totalPages: Int) {
        prefs.edit()
            .putInt(progressPagesKey(chapterId), totalPages.coerceAtLeast(0))
            .apply()
    }

    private fun resolveThumb(thumb: String?, pageUrls: List<String>): String? {
        return thumb
            ?.takeIf { it.isNotBlank() }
            ?.let(::toAbsoluteUrl)
            ?: pageUrls.firstOrNull()
    }

    private fun readCachedLibrary(): LibraryResponse? {
        if (!libraryCache.exists()) return null
        return runCatching {
            val raw = libraryCache.readText()
            if (raw.isBlank()) null else json.decodeFromString(LibraryResponse.serializer(), raw)
        }.getOrNull()
    }

    private fun persistLibraryCache(payload: LibraryResponse) {
        cacheDir.mkdirs()
        val serialized = json.encodeToString(LibraryResponse.serializer(), payload)
        val tmp = File(cacheDir, "${libraryCache.name}.tmp")
        tmp.writeText(serialized)
        if (libraryCache.exists()) {
            libraryCache.delete()
        }
        if (!tmp.renameTo(libraryCache)) {
            libraryCache.writeText(serialized)
            tmp.delete()
        }
    }

    private fun progressKey(chapterId: String): String = "progress:$chapterId"
    private fun progressPagesKey(chapterId: String): String = "progress_pages:$chapterId"

    private fun toAbsoluteUrl(path: String): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "https://sosiskibot.ru$path"
            else -> "https://sosiskibot.ru/luckystar/$path"
        }
    }

    companion object {
        private const val REMOTE_LIBRARY_URL = "https://sosiskibot.ru/luckystar/data/library.json"
        private const val MEMORY_CACHE_TTL_MS = 15_000L
        private const val NETWORK_CONNECT_TIMEOUT_MS = 8_000
        private const val NETWORK_READ_TIMEOUT_MS = 15_000
    }
}
