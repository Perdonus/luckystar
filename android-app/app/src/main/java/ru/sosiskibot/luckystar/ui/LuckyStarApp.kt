package ru.sosiskibot.luckystar.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sosiskibot.luckystar.data.LibraryChapter
import ru.sosiskibot.luckystar.data.LibraryRelease
import ru.sosiskibot.luckystar.data.LibraryRepository
import ru.sosiskibot.luckystar.data.LibrarySeries
import ru.sosiskibot.luckystar.data.ReadingProgressSummary
import ru.sosiskibot.luckystar.offline.DownloadProgress
import ru.sosiskibot.luckystar.offline.DownloadWorkScheduler
import ru.sosiskibot.luckystar.offline.OfflineDownloadDatabase
import ru.sosiskibot.luckystar.offline.OfflineDownloadRepository
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class LibraryStage {
    SERIES,
    RELEASES,
    CHAPTERS,
}

private data class LibraryUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val series: List<LibrarySeries> = emptyList(),
    val selectedSeriesId: String? = null,
    val selectedReleaseId: String? = null,
    val stage: LibraryStage = LibraryStage.SERIES,
    val lastChapterId: String? = null,
)

private data class ReaderUiState(
    val chapter: LibraryChapter? = null,
    val pageIndex: Int = 0,
    val controlsVisible: Boolean = true,
)

private data class ProgressUi(
    val percent: Int,
    val done: Boolean,
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
    private val _download = MutableStateFlow(
        DownloadProgress(
            status = "idle",
            downloadedPages = 0,
            totalPages = 0,
            progressFraction = 0f,
            bytesDownloaded = 0L,
            updatedAt = System.currentTimeMillis(),
            errorMessage = null,
        ),
    )

    val library: StateFlow<LibraryUiState> = _library.asStateFlow()
    val reader: StateFlow<ReaderUiState> = _reader.asStateFlow()
    val downloadState: StateFlow<DownloadProgress> = _download.asStateFlow()

    init {
        offlineRepository.observeProgress()
            .onEach { _download.value = it }
            .launchIn(viewModelScope)

        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _library.update { it.copy(loading = true, errorMessage = null) }
            runCatching {
                repository.getLibrary(forceRefresh = true)
            }.onSuccess { series ->
                val lastChapterId = repository.getLastChapterId()
                val lastChapter = findChapter(series, lastChapterId)
                val selectedSeriesId = lastChapter?.seriesId ?: series.firstOrNull()?.id
                val selectedReleaseId = lastChapter?.releaseId
                    ?: series.firstOrNull()?.releases?.firstOrNull()?.id

                _library.value = LibraryUiState(
                    loading = false,
                    errorMessage = null,
                    series = series,
                    selectedSeriesId = selectedSeriesId,
                    selectedReleaseId = selectedReleaseId,
                    stage = LibraryStage.SERIES,
                    lastChapterId = lastChapterId,
                )
            }.onFailure { throwable ->
                _library.value = LibraryUiState(
                    loading = false,
                    errorMessage = throwable.message ?: "Не удалось загрузить библиотеку",
                )
            }
        }
    }

    fun openSeries(seriesId: String) {
        val current = _library.value
        val selectedSeries = current.series.firstOrNull { it.id == seriesId } ?: return
        val preferredReleaseId = selectedSeries.releases
            .firstOrNull { release -> release.chapters.any { it.id == current.lastChapterId } }
            ?.id
            ?: selectedSeries.releases.firstOrNull()?.id

        _library.update {
            it.copy(
                selectedSeriesId = seriesId,
                selectedReleaseId = preferredReleaseId,
                stage = LibraryStage.RELEASES,
            )
        }
    }

    fun openRelease(releaseId: String) {
        _library.update {
            it.copy(
                selectedReleaseId = releaseId,
                stage = LibraryStage.CHAPTERS,
            )
        }
    }

    fun openChapter(chapterId: String) {
        viewModelScope.launch {
            val chapter = repository.getChapter(chapterId) ?: return@launch
            val maxIndex = (chapter.pages.size - 1).coerceAtLeast(0)
            val progress = repository.getProgress(chapterId)
            _reader.value = ReaderUiState(
                chapter = chapter,
                pageIndex = progress.pageIndex.coerceIn(0, maxIndex),
                controlsVisible = true,
            )
            _library.update {
                it.copy(
                    selectedSeriesId = chapter.seriesId,
                    selectedReleaseId = chapter.releaseId,
                    stage = LibraryStage.CHAPTERS,
                    lastChapterId = chapter.id,
                )
            }
        }
    }

    fun setPage(index: Int) {
        val chapter = _reader.value.chapter ?: return
        val maxIndex = (chapter.pages.size - 1).coerceAtLeast(0)
        val bounded = index.coerceIn(0, maxIndex)

        repository.saveProgress(chapter.id, bounded)
        _reader.update { it.copy(pageIndex = bounded) }

        val summary = chapterSummaryForPage(bounded, chapter.pages.size)
        _library.update { state ->
            state.copy(
                series = state.series.updateProgress(chapter.id, summary),
                lastChapterId = chapter.id,
                selectedSeriesId = chapter.seriesId,
                selectedReleaseId = chapter.releaseId,
            )
        }
    }

    fun toggleReaderControls() {
        _reader.update { it.copy(controlsVisible = !it.controlsVisible) }
    }

    fun handleBack() {
        if (_reader.value.chapter != null) {
            _reader.value = ReaderUiState()
            return
        }

        _library.update { state ->
            when (state.stage) {
                LibraryStage.CHAPTERS -> state.copy(stage = LibraryStage.RELEASES)
                LibraryStage.RELEASES -> state.copy(stage = LibraryStage.SERIES)
                LibraryStage.SERIES -> state
            }
        }
    }

    fun downloadAll() {
        viewModelScope.launch {
            downloadScheduler.enqueueDownloadAll(
                baseUrl = "https://sosiskibot.ru/luckystar/",
                forceRedownload = false,
            )
        }
    }

    private fun findChapter(series: List<LibrarySeries>, chapterId: String?): LibraryChapter? {
        if (chapterId == null) return null
        return series.asSequence()
            .flatMap { it.releases.asSequence() }
            .flatMap { it.chapters.asSequence() }
            .firstOrNull { it.id == chapterId }
    }
}

@Composable
fun LuckyStarApp() {
    val context = LocalContext.current
    val vm: LuckyStarViewModel = viewModel()
    val library by vm.library.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()

    var pendingDownload by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingDownload) {
            pendingDownload = false
            if (granted) {
                vm.downloadAll()
            }
        }
    }

    fun triggerDownloadAll() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            pendingDownload = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.downloadAll()
        }
    }

    BackHandler(enabled = reader.chapter != null || library.stage != LibraryStage.SERIES) {
        vm.handleBack()
    }

    when {
        library.loading -> LoadingScreen()
        library.errorMessage != null -> ErrorScreen(
            message = library.errorMessage.orEmpty(),
            onRetry = vm::refreshLibrary,
        )
        reader.chapter != null -> ReaderScreen(
            state = reader,
            onBack = vm::handleBack,
            onSetPage = vm::setPage,
            onToggleControls = vm::toggleReaderControls,
        )
        else -> LibraryScreen(
            state = library,
            downloadState = download,
            onBack = vm::handleBack,
            onSelectSeries = vm::openSeries,
            onSelectRelease = vm::openRelease,
            onOpenChapter = vm::openChapter,
            onDownloadAll = ::triggerDownloadAll,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text("Повторить")
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    downloadState: DownloadProgress,
    onBack: () -> Unit,
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
                title = {
                    Text(
                        text = when (state.stage) {
                            LibraryStage.SERIES -> "Книги"
                            LibraryStage.RELEASES -> selectedSeries?.title ?: "Тома"
                            LibraryStage.CHAPTERS -> selectedRelease?.title ?: "Главы"
                        },
                    )
                },
                navigationIcon = {
                    if (state.stage != LibraryStage.SERIES) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (state.stage == LibraryStage.SERIES) {
                        IconButton(onClick = onDownloadAll) {
                            Icon(Icons.Outlined.Download, contentDescription = "Скачать всё")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DownloadBanner(downloadState = downloadState)

            when (state.stage) {
                LibraryStage.SERIES -> SeriesListScreen(
                    series = state.series,
                    onSelectSeries = onSelectSeries,
                )
                LibraryStage.RELEASES -> {
                    val series = selectedSeries
                    if (series != null) {
                        ReleaseListScreen(
                            series = series,
                            highlightedReleaseId = state.selectedReleaseId,
                            onSelectRelease = onSelectRelease,
                        )
                    }
                }
                LibraryStage.CHAPTERS -> {
                    val release = selectedRelease
                    if (release != null) {
                        ChapterListScreen(
                            release = release,
                            highlightedChapterId = state.lastChapterId,
                            onOpenChapter = onOpenChapter,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadBanner(downloadState: DownloadProgress) {
    if (downloadState.status == "idle") return

    val progressValue = when (downloadState.status) {
        "completed" -> 1f
        else -> downloadState.progressFraction
    }.coerceIn(0f, 1f)

    val statusText = when {
        downloadState.errorMessage != null -> downloadState.errorMessage
        downloadState.status == "completed" -> "Офлайн-библиотека готова"
        downloadState.totalPages > 0 -> "${downloadState.downloadedPages}/${downloadState.totalPages} страниц"
        else -> downloadState.status
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LinearProgressIndicator(
            progress = progressValue,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = statusText ?: downloadState.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (downloadState.errorMessage != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SeriesListScreen(
    series: List<LibrarySeries>,
    onSelectSeries: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItems(series, key = { it.id }) { item ->
            val progress = item.progress.toUi()
            val cover = seriesCover(item)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectSeries(item.id) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(228.dp),
                    ) {
                        AsyncImage(
                            model = cover,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        ) {
                            Text(
                                text = if (progress.done) "100%" else "${progress.percent}%",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(
                            progress = (progress.percent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseListScreen(
    series: LibrarySeries,
    highlightedReleaseId: String?,
    onSelectRelease: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(series.releases, key = { it.id }) { release ->
            val progress = release.progress.toUi()
            val isHighlighted = release.id == highlightedReleaseId
            val cover = release.chapters.firstOrNull()?.thumb ?: seriesCover(series)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectRelease(release.id) }
                    .border(
                        width = if (isHighlighted) 1.5.dp else 1.dp,
                        color = if (isHighlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(22.dp),
                    ),
                shape = RoundedCornerShape(22.dp),
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = cover,
                        contentDescription = release.title,
                        modifier = Modifier
                            .size(width = 70.dp, height = 100.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )

                    Text(
                        text = release.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    CompletionBadge(progress = progress)
                }
            }
        }
    }
}

@Composable
private fun ChapterListScreen(
    release: LibraryRelease,
    highlightedChapterId: String?,
    onOpenChapter: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(release.chapters, key = { it.id }) { chapter ->
            val progress = chapter.progress.toUi()
            val isHighlighted = chapter.id == highlightedChapterId

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenChapter(chapter.id) }
                    .border(
                        width = if (isHighlighted) 1.5.dp else 1.dp,
                        color = if (isHighlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = RoundedCornerShape(22.dp),
                    ),
                shape = RoundedCornerShape(22.dp),
                color = if (isHighlighted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chapter.shortTitle.ifBlank { chapter.title },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.width(12.dp))
                    CompletionBadge(progress = progress)
                }
            }
        }
    }
}

@Composable
private fun CompletionBadge(
    progress: ProgressUi,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (progress.done) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        if (progress.done) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = "Прочитано",
                modifier = Modifier.padding(8.dp),
                tint = Color(0xFF1F8E4D),
            )
        } else {
            Text(
                text = "${progress.percent}%",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onSetPage: (Int) -> Unit,
    onToggleControls: () -> Unit,
) {
    val chapter = state.chapter ?: return
    val pageCount = chapter.pages.size
    val currentUrl = chapter.pages.getOrNull(state.pageIndex)
    val previousUrl = chapter.pages.getOrNull(state.pageIndex - 1)
    val nextUrl = chapter.pages.getOrNull(state.pageIndex + 1)
    val coroutineScope = rememberCoroutineScope()

    var zoom by rememberSaveable(chapter.id) { mutableFloatStateOf(1f) }
    var panOffset by remember(chapter.id, state.pageIndex) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var flipFraction by remember(chapter.id, state.pageIndex) { mutableFloatStateOf(0f) }
    var animationDirection by remember(chapter.id, state.pageIndex) { mutableIntStateOf(0) }
    var animationJob by remember { mutableStateOf<Job?>(null) }

    val canTurnPage = zoom <= 1.01f
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4.5f)
        zoom = nextZoom
        panOffset = if (nextZoom <= 1.01f) {
            Offset.Zero
        } else {
            clampPan(panOffset + panChange, viewportSize, nextZoom)
        }
    }

    LaunchedEffect(chapter.id, state.pageIndex) {
        flipFraction = 0f
        animationDirection = 0
        if (zoom <= 1.01f) {
            panOffset = Offset.Zero
        }
    }

    fun resetZoom() {
        zoom = 1f
        panOffset = Offset.Zero
    }

    fun animateToFraction(target: Float, onEnd: (() -> Unit)? = null) {
        animationJob?.cancel()
        animationJob = coroutineScope.launch {
            val anim = Animatable(flipFraction)
            anim.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            ) {
                flipFraction = value
            }
            onEnd?.invoke()
        }
    }

    fun animatePageTurn(direction: Int) {
        if (!canTurnPage) return
        val targetIndex = state.pageIndex + direction
        if (targetIndex !in chapter.pages.indices) return
        animationDirection = direction
        animateToFraction(direction.toFloat()) {
            onSetPage(targetIndex)
            flipFraction = 0f
            animationDirection = 0
            panOffset = Offset.Zero
        }
    }

    fun settleDrag() {
        when {
            flipFraction > 0.18f && nextUrl != null -> animatePageTurn(1)
            flipFraction < -0.18f && previousUrl != null -> animatePageTurn(-1)
            else -> animateToFraction(0f) {
                flipFraction = 0f
                animationDirection = 0
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (state.controlsVisible) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = chapter.seriesTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = chapter.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.controlsVisible) {
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canTurnPage) {
                            OutlinedButton(
                                onClick = { animatePageTurn(-1) },
                                enabled = previousUrl != null,
                            ) {
                                Text("Назад")
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                text = if (zoom > 1.01f) {
                                    "${state.pageIndex + 1}/$pageCount · ${zoom.times(100).roundToInt()}%"
                                } else {
                                    "${state.pageIndex + 1}/$pageCount"
                                },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                            )
                        }

                        if (zoom > 1.01f) {
                            TextButton(onClick = { resetZoom() }) {
                                Text("100%")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { animatePageTurn(1) },
                                enabled = nextUrl != null,
                            ) {
                                Text("Дальше")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { viewportSize = it }
                .transformable(state = transformState)
                .pointerInput(chapter.id, state.pageIndex, canTurnPage, viewportSize, zoom) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (zoom > 1.01f) {
                                resetZoom()
                            } else {
                                val targetZoom = 2.4f
                                zoom = targetZoom
                                val focusedOffset = Offset(
                                    x = (viewportSize.width / 2f - tapOffset.x) * 0.85f,
                                    y = (viewportSize.height / 2f - tapOffset.y) * 0.85f,
                                )
                                panOffset = clampPan(focusedOffset, viewportSize, targetZoom)
                            }
                        },
                        onTap = { tapOffset ->
                            if (!canTurnPage) {
                                onToggleControls()
                                return@detectTapGestures
                            }

                            val width = viewportSize.width.toFloat().coerceAtLeast(1f)
                            when {
                                tapOffset.x <= width * 0.22f -> animatePageTurn(-1)
                                tapOffset.x >= width * 0.78f -> animatePageTurn(1)
                                else -> onToggleControls()
                            }
                        },
                    )
                }
                .pointerInput(chapter.id, state.pageIndex, canTurnPage, viewportSize, zoom) {
                    if (zoom > 1.01f) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                panOffset = clampPan(panOffset + dragAmount, viewportSize, zoom)
                            },
                        )
                    } else {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                animationJob?.cancel()
                                animationDirection = 0
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val width = viewportSize.width.toFloat().coerceAtLeast(1f)
                                flipFraction = (flipFraction - (dragAmount / width)).coerceIn(
                                    minimumValue = if (previousUrl != null) -1f else 0f,
                                    maximumValue = if (nextUrl != null) 1f else 0f,
                                )
                                animationDirection = when {
                                    flipFraction > 0f -> 1
                                    flipFraction < 0f -> -1
                                    else -> 0
                                }
                            },
                            onDragEnd = { settleDrag() },
                            onDragCancel = {
                                animateToFraction(0f) {
                                    flipFraction = 0f
                                    animationDirection = 0
                                }
                            },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            ReaderPageScene(
                currentUrl = currentUrl,
                adjacentUrl = when {
                    flipFraction > 0f || animationDirection > 0 -> nextUrl
                    flipFraction < 0f || animationDirection < 0 -> previousUrl
                    else -> null
                },
                zoom = zoom,
                panOffset = panOffset,
                flipFraction = flipFraction,
            )
        }
    }
}

@Composable
private fun ReaderPageScene(
    currentUrl: String?,
    adjacentUrl: String?,
    zoom: Float,
    panOffset: Offset,
    flipFraction: Float,
) {
    val absoluteFlip = abs(flipFraction).coerceIn(0f, 1f)
    val flippingForward = flipFraction >= 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (adjacentUrl != null && absoluteFlip > 0f) {
            PageSheet(
                imageUrl = adjacentUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .graphicsLayer {
                        alpha = (0.3f + absoluteFlip * 0.7f).coerceIn(0f, 1f)
                        scaleX = 0.96f + (absoluteFlip * 0.04f)
                        scaleY = 0.985f + (absoluteFlip * 0.015f)
                        translationX = if (flippingForward) {
                            size.width * 0.12f * (1f - absoluteFlip)
                        } else {
                            -size.width * 0.12f * (1f - absoluteFlip)
                        }
                    },
            )
        }

        PageSheet(
            imageUrl = currentUrl,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panOffset.x - (flipFraction * size.width * 0.14f)
                    translationY = panOffset.y
                    if (zoom <= 1.01f && absoluteFlip > 0f) {
                        transformOrigin = if (flippingForward) {
                            TransformOrigin(1f, 0.5f)
                        } else {
                            TransformOrigin(0f, 0.5f)
                        }
                        rotationY = if (flippingForward) {
                            -80f * absoluteFlip
                        } else {
                            80f * absoluteFlip
                        }
                    }
                    cameraDistance = 32f * density
                    alpha = 1f - (absoluteFlip * 0.08f)
                },
        )

        if (absoluteFlip > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 18.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = if (flippingForward) {
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.12f * absoluteFlip),
                                    Color.Black.copy(alpha = 0.25f * absoluteFlip),
                                )
                            } else {
                                listOf(
                                    Color.Black.copy(alpha = 0.25f * absoluteFlip),
                                    Color.Black.copy(alpha = 0.12f * absoluteFlip),
                                    Color.Transparent,
                                )
                            },
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun PageSheet(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun List<LibrarySeries>.updateProgress(
    chapterId: String,
    progress: ReadingProgressSummary,
): List<LibrarySeries> {
    return map { series ->
        var seriesChanged = false
        val updatedReleases = series.releases.map { release ->
            var releaseChanged = false
            val updatedChapters = release.chapters.map { chapter ->
                if (chapter.id == chapterId) {
                    releaseChanged = true
                    seriesChanged = true
                    chapter.copy(progress = progress)
                } else {
                    chapter
                }
            }

            if (releaseChanged) {
                release.copy(
                    chapters = updatedChapters,
                    progress = aggregateProgress(updatedChapters.map { it.progress }),
                )
            } else {
                release
            }
        }

        if (seriesChanged) {
            series.copy(
                releases = updatedReleases,
                progress = aggregateProgress(updatedReleases.map { it.progress }),
            )
        } else {
            series
        }
    }
}

private fun chapterSummaryForPage(
    pageIndex: Int,
    totalPages: Int,
): ReadingProgressSummary {
    val total = totalPages.coerceAtLeast(0)
    if (total == 0) {
        return ReadingProgressSummary(
            percent = 0,
            completed = false,
            current = 0,
            total = 0,
            completedItems = 0,
            totalItems = 1,
        )
    }

    val current = (pageIndex + 1).coerceIn(0, total)
    val completed = current >= total
    val percent = ((current.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    return ReadingProgressSummary(
        percent = percent,
        completed = completed,
        current = current,
        total = total,
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
    val completedItems = items.count { it.completed }
    val totalItems = items.size
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

private fun ReadingProgressSummary.toUi(): ProgressUi {
    return ProgressUi(
        percent = percent.coerceIn(0, 100),
        done = completed,
    )
}

private fun seriesCover(series: LibrarySeries): String? {
    val thumb = series.releases.asSequence()
        .flatMap { it.chapters.asSequence() }
        .mapNotNull { it.thumb }
        .firstOrNull { it.isNotBlank() }

    if (!thumb.isNullOrBlank()) return thumb
    return series.bannerUrl.takeUnless { it.endsWith(".svg", ignoreCase = true) || it.isBlank() }
}

private fun clampPan(
    offset: Offset,
    viewportSize: IntSize,
    zoom: Float,
): Offset {
    if (zoom <= 1.01f) return Offset.Zero
    val maxX = (viewportSize.width * (zoom - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (viewportSize.height * (zoom - 1f) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
