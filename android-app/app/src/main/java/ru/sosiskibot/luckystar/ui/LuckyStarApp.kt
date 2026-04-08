package ru.sosiskibot.luckystar.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sosiskibot.luckystar.data.LibraryChapter
import ru.sosiskibot.luckystar.data.LibraryRepository
import ru.sosiskibot.luckystar.data.LibrarySeries
import ru.sosiskibot.luckystar.offline.DownloadProgress
import ru.sosiskibot.luckystar.offline.DownloadWorkScheduler
import ru.sosiskibot.luckystar.offline.OfflineDownloadDatabase
import ru.sosiskibot.luckystar.offline.OfflineDownloadRepository

data class LibraryUiState(
    val loading: Boolean = true,
    val series: List<LibrarySeries> = emptyList(),
    val selectedSeriesId: String? = null,
    val selectedReleaseId: String? = null,
)

data class ReaderUiState(
    val chapter: LibraryChapter? = null,
    val pageIndex: Int = 0,
    val controlsVisible: Boolean = true,
)

class LuckyStarViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val offlineRepository = OfflineDownloadRepository(
        application,
        OfflineDownloadDatabase.get(application).dao(),
    )
    private val downloadScheduler = DownloadWorkScheduler(application)
    private val _library = MutableStateFlow(LibraryUiState())
    private val _reader = MutableStateFlow(ReaderUiState())
    private val _download = MutableStateFlow(DownloadProgress("idle", 0, 0, 0f, 0L, System.currentTimeMillis(), null))

    val library: StateFlow<LibraryUiState> = _library.asStateFlow()
    val reader: StateFlow<ReaderUiState> = _reader.asStateFlow()
    val downloadState: StateFlow<DownloadProgress> = _download.asStateFlow()

    init {
        offlineRepository.observeProgress()
            .onEach { _download.value = it }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val series = repository.getLibrary()
            val firstSeries = series.firstOrNull()
            val firstRelease = firstSeries?.releases?.firstOrNull()
            _library.value = LibraryUiState(
                loading = false,
                series = series,
                selectedSeriesId = firstSeries?.id,
                selectedReleaseId = firstRelease?.id,
            )
            repository.getLastChapterId()?.let { openChapter(it) }
        }
    }

    fun selectSeries(seriesId: String) {
        val series = _library.value.series.firstOrNull { it.id == seriesId } ?: return
        _library.update {
            it.copy(
                selectedSeriesId = seriesId,
                selectedReleaseId = series.releases.firstOrNull()?.id,
            )
        }
    }

    fun selectRelease(releaseId: String) {
        _library.update { it.copy(selectedReleaseId = releaseId) }
    }

    fun openChapter(chapterId: String) {
        viewModelScope.launch {
            val chapter = repository.getChapter(chapterId) ?: return@launch
            val progress = repository.getProgress(chapterId)
            _reader.value = ReaderUiState(
                chapter = chapter,
                pageIndex = progress.pageIndex.coerceIn(0, chapter.pages.lastIndex.coerceAtLeast(0)),
                controlsVisible = true,
            )
            _library.update {
                it.copy(
                    selectedSeriesId = chapter.seriesId,
                    selectedReleaseId = chapter.releaseId,
                )
            }
        }
    }

    fun closeReader() {
        _reader.value = ReaderUiState()
    }

    fun setPage(index: Int) {
        val chapter = _reader.value.chapter ?: return
        val bounded = index.coerceIn(0, chapter.pages.lastIndex.coerceAtLeast(0))
        repository.saveProgress(chapter.id, bounded)
        _reader.update { it.copy(pageIndex = bounded) }
    }

    fun nextPage() = setPage(_reader.value.pageIndex + 1)
    fun prevPage() = setPage(_reader.value.pageIndex - 1)
    fun toggleControls() = _reader.update { it.copy(controlsVisible = !it.controlsVisible) }

    fun downloadAll() {
        viewModelScope.launch {
            downloadScheduler.markQueuedInDb()
            downloadScheduler.enqueueDownloadAll("https://sosiskibot.ru/luckystar/", forceRedownload = false)
        }
    }
}

@Composable
fun LuckyStarApp() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm = remember(app) { LuckyStarViewModel(app) }
    val library by vm.library.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()

    if (reader.chapter == null) {
        LibraryScreen(
            state = library,
            downloadState = download,
            onSelectSeries = vm::selectSeries,
            onSelectRelease = vm::selectRelease,
            onOpenChapter = vm::openChapter,
            onDownloadAll = vm::downloadAll,
        )
    } else {
        ReaderScreen(
            state = reader,
            downloadState = download,
            onBack = vm::closeReader,
            onPrev = vm::prevPage,
            onNext = vm::nextPage,
            onSetPage = vm::setPage,
            onToggleControls = vm::toggleControls,
            onDownloadAll = vm::downloadAll,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    downloadState: DownloadProgress,
    onSelectSeries: (String) -> Unit,
    onSelectRelease: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onDownloadAll: () -> Unit,
) {
    val selectedSeries = state.series.firstOrNull { it.id == state.selectedSeriesId }
    val selectedRelease = selectedSeries?.releases?.firstOrNull { it.id == state.selectedReleaseId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedSeries?.title ?: "Manga") },
                actions = {
                    IconButton(onClick = onDownloadAll) {
                        Icon(Icons.Outlined.Download, contentDescription = "Download all")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (downloadState.status != "idle") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = downloadState.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = when {
                            downloadState.totalPages > 0 -> "${downloadState.downloadedPages}/${downloadState.totalPages}"
                            downloadState.errorMessage != null -> downloadState.errorMessage
                            else -> downloadState.status
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.series.forEach { series ->
                    AssistChip(
                        onClick = { onSelectSeries(series.id) },
                        label = { Text(series.title) },
                        colors = if (series.id == state.selectedSeriesId) {
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                labelColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            AssistChipDefaults.assistChipColors()
                        },
                    )
                }
            }

            selectedSeries?.let { series ->
                AsyncImage(
                    model = series.bannerUrl,
                    contentDescription = series.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    contentScale = ContentScale.Crop,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    series.releases.forEach { release ->
                        AssistChip(
                            onClick = { onSelectRelease(release.id) },
                            label = { Text(release.title) },
                            colors = if (release.id == state.selectedReleaseId) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    labelColor = MaterialTheme.colorScheme.onSecondary,
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(selectedRelease?.chapters ?: emptyList(), key = { it.id }) { chapter ->
                    ChapterRow(chapter = chapter, onOpenChapter = onOpenChapter)
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: LibraryChapter,
    onOpenChapter: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenChapter(chapter.id) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chapter.thumb?.let { thumb ->
                AsyncImage(
                    model = thumb,
                    contentDescription = chapter.title,
                    modifier = Modifier
                        .fillMaxWidth(0.22f)
                        .height(72.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    chapter.releaseTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    state: ReaderUiState,
    downloadState: DownloadProgress,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSetPage: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val chapter = state.chapter ?: return
    val pageUrl = chapter.pages.getOrNull(state.pageIndex)
    var zoom by rememberSaveable(pageUrl) { mutableFloatStateOf(1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (pageUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.pageIndex) {
                        detectTapGestures { offset ->
                            val width = size.width.toFloat()
                            when {
                                offset.x < width * 0.28f -> onPrev()
                                offset.x > width * 0.72f -> onNext()
                                else -> onToggleControls()
                            }
                        }
                    }
                    .pointerInput(pageUrl) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = pageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            rotationY = if (zoom <= 1.05f) ((state.pageIndex % 2) - 0.5f) * 7f else 0f
                            cameraDistance = 22f * density
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }

        if (state.controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(chapter.seriesTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chapter.releaseTitle, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = onDownloadAll) {
                            Icon(Icons.Outlined.Download, contentDescription = "Download all")
                        }
                    },
                )
                if (downloadState.status != "idle") {
                    LinearProgressIndicator(
                        progress = downloadState.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Slider(
                    value = state.pageIndex.toFloat(),
                    onValueChange = { onSetPage(it.toInt()) },
                    valueRange = 0f..chapter.pages.lastIndex.coerceAtLeast(0).toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "${state.pageIndex + 1}/${chapter.pages.size}",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
