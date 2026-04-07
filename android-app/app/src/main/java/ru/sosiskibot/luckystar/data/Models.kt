package ru.sosiskibot.luckystar.data

import kotlinx.serialization.Serializable

@Serializable
data class LibraryResponse(
    val schemaVersion: Int = 0,
    val generatedAt: String = "",
    val series: List<SeriesDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
    val chapterIndex: Map<String, ChapterDto> = emptyMap(),
)

@Serializable
data class SeriesDto(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val banner: String = "",
    val releaseLabel: String = "",
    val chapterLabel: String = "",
    val releases: List<ReleaseDto> = emptyList(),
    val chapterCount: Int = 0,
)

@Serializable
data class ReleaseDto(
    val id: String,
    val title: String,
    val type: String,
    val number: Int? = null,
    val chapterIds: List<String> = emptyList(),
)

@Serializable
data class ChapterDto(
    val id: String,
    val seriesId: String,
    val releaseId: String,
    val volume: Int? = null,
    val chapter: String,
    val chapterSort: Double? = null,
    val title: String,
    val shortTitle: String = "",
    val sourceName: String = "",
    val thumb: String? = null,
    val pageCount: Int = 0,
    val pages: List<PageDto> = emptyList(),
)

@Serializable
data class PageDto(
    val index: Int,
    val name: String,
    val url: String,
    val size: Long = 0,
)

data class LibrarySeries(
    val id: String,
    val title: String,
    val releaseLabel: String,
    val chapterLabel: String,
    val bannerUrl: String,
    val releases: List<LibraryRelease>,
)

data class LibraryRelease(
    val id: String,
    val title: String,
    val type: String,
    val number: Int?,
    val chapters: List<LibraryChapter>,
)

data class LibraryChapter(
    val id: String,
    val seriesId: String,
    val seriesTitle: String,
    val releaseId: String,
    val releaseTitle: String,
    val chapter: String,
    val title: String,
    val shortTitle: String,
    val thumb: String?,
    val pages: List<String>,
)

data class ReaderProgress(
    val chapterId: String,
    val pageIndex: Int,
)
