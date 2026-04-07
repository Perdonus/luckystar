package ru.sosiskibot.luckystar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL

class LibraryRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("luckystar_reader", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "offline-library")
    private val libraryCache = File(cacheDir, "library.json")

    suspend fun getLibrary(): List<LibrarySeries> = withContext(Dispatchers.IO) {
        val response = loadLibrary()
        val chapterMap = response.chapterIndex.ifEmpty { response.chapters.associateBy { it.id } }
        response.series.map { series ->
            val releases = series.releases.map { release ->
                LibraryRelease(
                    id = release.id,
                    title = release.title,
                    type = release.type,
                    number = release.number,
                    chapters = release.chapterIds.mapNotNull { chapterId ->
                        chapterMap[chapterId]?.toLibraryChapter(
                            seriesTitle = series.title,
                            releaseTitle = release.title,
                        )
                    },
                )
            }.filter { it.chapters.isNotEmpty() }
            LibrarySeries(
                id = series.id,
                title = series.title,
                releaseLabel = series.releaseLabel,
                chapterLabel = series.chapterLabel,
                bannerUrl = toAbsoluteUrl(series.banner),
                releases = releases,
            )
        }.filter { it.releases.isNotEmpty() }
    }

    suspend fun getChapter(chapterId: String): LibraryChapter? {
        return getLibrary().asSequence()
            .flatMap { it.releases.asSequence() }
            .flatMap { it.chapters.asSequence() }
            .firstOrNull { it.id == chapterId }
    }

    fun getProgress(chapterId: String): ReaderProgress =
        ReaderProgress(chapterId = chapterId, pageIndex = prefs.getInt("progress:$chapterId", 0))

    fun saveProgress(chapterId: String, pageIndex: Int) {
        prefs.edit().putInt("progress:$chapterId", pageIndex.coerceAtLeast(0)).apply()
        prefs.edit().putString("last_chapter_id", chapterId).apply()
    }

    fun getLastChapterId(): String? = prefs.getString("last_chapter_id", null)

    private suspend fun loadLibrary(): LibraryResponse = withContext(Dispatchers.IO) {
        if (libraryCache.exists()) {
            return@withContext json.decodeFromString(LibraryResponse.serializer(), libraryCache.readText())
        }
        val remote = fetchRemoteLibrary()
        cacheDir.mkdirs()
        libraryCache.writeText(json.encodeToString(LibraryResponse.serializer(), remote))
        remote
    }

    private suspend fun fetchRemoteLibrary(): LibraryResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(LibraryResponse.serializer(), URL("https://sosiskibot.ru/luckystar/data/library.json").readText())
    }

    private fun ChapterDto.toLibraryChapter(seriesTitle: String, releaseTitle: String): LibraryChapter {
        return LibraryChapter(
            id = id,
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            releaseId = releaseId,
            releaseTitle = releaseTitle,
            chapter = chapter,
            title = title,
            shortTitle = shortTitle.ifBlank { title },
            thumb = thumb?.let(::toAbsoluteUrl),
            pages = pages.map { toAbsoluteUrl(it.url) },
        )
    }

    private fun toAbsoluteUrl(path: String): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> "https://sosiskibot.ru$path"
            else -> "https://sosiskibot.ru/luckystar/$path"
        }
    }
}
