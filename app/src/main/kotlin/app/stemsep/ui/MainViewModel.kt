package app.stemsep.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.stemsep.StemSepApp
import app.stemsep.dsp.StemMode
import app.stemsep.separation.AcceleratorMode
import app.stemsep.separation.CachedStems
import app.stemsep.separation.ComputeBackend
import app.stemsep.separation.ComputeCapabilities
import app.stemsep.separation.ComputeCapabilityDetector
import app.stemsep.separation.ExportFormat
import app.stemsep.separation.LiveStemPlayer
import app.stemsep.separation.ModelCatalog
import app.stemsep.separation.SeparationPhase
import app.stemsep.separation.StemPlayer
import app.stemsep.ui.theme.AppSettings
import app.stemsep.ui.theme.ThemeMode
import app.stemsep.ui.theme.ThemePalette
import app.stemsep.work.SeparationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val MAX_STATS_HISTORY = 90

data class UiState(
    val selectedUri: Uri? = null,
    val selectedName: String? = null,
    val modelId: String = ModelCatalog.DEFAULT_MODEL_ID,
    val backend: ComputeBackend = ComputeBackend.CPU,
    val cpuThreads: Int = 4,
    val acceleratorMode: AcceleratorMode = AcceleratorMode.BALANCED,
    val effectiveBackend: ComputeBackend? = null,
    val fallbackReason: String? = null,
    val workId: UUID? = null,
    val running: Boolean = false,
    val phase: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val cached: CachedStems? = null,
    val playerReady: Boolean = false,
    val durationFrames: Long = 0,
    val positionFrames: Long = 0,
    val isPlaying: Boolean = false,
    val livePreviewReady: Boolean = false,
    val livePreviewPlaying: Boolean = false,
    val livePositionFrames: Long = 0,
    val liveAvailableFrames: Long = 0,
    val liveTotalFrames: Long = 0,
    val liveSampleRate: Int = 44_100,
    val liveMode: StemMode = StemMode.VOCALS,
    val liveVocalLevel: Float = 1f,
    val cpuUsagePercent: Float? = null,
    val gpuUsagePercent: Float? = null,
    val cpuHistory: List<Float> = emptyList(),
    val gpuHistory: List<Float> = emptyList(),
    val inferenceHistory: List<Float> = emptyList(),
    val inferenceAverageMillis: Long = 0,
    val inferenceChunks: Int = 0,
    val workerThreads: Int = 0,
    val mode: StemMode = StemMode.MIX,
    val vocalLevel: Float = 1f,
    val exporting: Boolean = false,
    val exportProgress: Float = 0f,
    val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as StemSepApp).container
    private val workManager = WorkManager.getInstance(application)
    val capabilities: ComputeCapabilities = ComputeCapabilityDetector.detect()
    private val prefs = application.getSharedPreferences("stemsep-compute", Context.MODE_PRIVATE)
    private val settingsPrefs = application.getSharedPreferences("stemsep-settings", Context.MODE_PRIVATE)
    private val defaultCpuThreads = capabilities.cpuCores.coerceAtMost(4).coerceAtLeast(1)
    private var selectedCpuThreads = prefs
        .getInt("cpu_threads", defaultCpuThreads)
        .coerceIn(1, capabilities.cpuCores)
    private var selectedAcceleratorMode = AcceleratorMode.fromId(
        prefs.getString("accelerator_mode", null),
    )
    private val statsSampler = ComputeStatsSampler(capabilities.cpuCores)
    private val _state = MutableStateFlow(
        UiState(
            cpuThreads = selectedCpuThreads,
            acceleratorMode = selectedAcceleratorMode,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _settings = MutableStateFlow(
        AppSettings(
            themeMode = ThemeMode.fromId(settingsPrefs.getString("theme_mode", null)),
            themePalette = ThemePalette.fromId(settingsPrefs.getString("theme_palette", null)),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var player: StemPlayer? = null
    private var livePlayer: LiveStemPlayer? = null
    private var liveKey: String? = null
    private var observeJob: Job? = null
    private var statsJob: Job? = null

    fun selectAudio(uri: Uri?) {
        if (uri == null) return
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        workManager.cancelAllWorkByTag(SeparationWorker.WORK_TAG)
        observeJob?.cancel()
        stopStatsSampler()
        releasePlayer()
        releaseLivePreview()
        _state.value = UiState(
            selectedUri = uri,
            selectedName = displayName(uri),
            modelId = _state.value.modelId,
            backend = _state.value.backend,
            cpuThreads = _state.value.cpuThreads,
            acceleratorMode = _state.value.acceleratorMode,
            effectiveBackend = if (_state.value.backend == ComputeBackend.CPU) {
                ComputeBackend.CPU
            } else {
                null
            },
        )
    }

    fun selectModel(modelId: String) {
        if (modelId == _state.value.modelId) return
        releasePlayer()
        releaseLivePreview()
        _state.update {
            it.copy(
                modelId = modelId,
                cached = null,
                playerReady = false,
                durationFrames = 0,
                positionFrames = 0,
                isPlaying = false,
                effectiveBackend = if (it.backend == ComputeBackend.CPU) {
                    ComputeBackend.CPU
                } else {
                    null
                },
                fallbackReason = null,
                phase = null,
                progress = 0f,
                error = null,
                message = null,
                cpuUsagePercent = null,
                gpuUsagePercent = null,
                inferenceAverageMillis = 0,
                inferenceChunks = 0,
                workerThreads = 0,
                cpuHistory = emptyList(),
                gpuHistory = emptyList(),
                inferenceHistory = emptyList(),
            )
        }
    }

    fun selectBackend(backend: ComputeBackend) {
        if (backend == _state.value.backend) return
        if (!capabilities.supports(backend)) return
        releasePlayer()
        releaseLivePreview()
        _state.update {
            it.copy(
                backend = backend,
                effectiveBackend = if (backend == ComputeBackend.CPU) {
                    ComputeBackend.CPU
                } else {
                    null
                },
                fallbackReason = null,
                cached = null,
                playerReady = false,
                durationFrames = 0,
                positionFrames = 0,
                isPlaying = false,
                phase = null,
                progress = 0f,
                error = null,
                message = null,
                cpuUsagePercent = null,
                gpuUsagePercent = null,
                inferenceAverageMillis = 0,
                inferenceChunks = 0,
                workerThreads = 0,
                cpuHistory = emptyList(),
                gpuHistory = emptyList(),
                inferenceHistory = emptyList(),
            )
        }
    }

    fun setCpuThreads(threads: Int) {
        if (_state.value.running) return
        val value = threads.coerceIn(1, capabilities.cpuCores)
        selectedCpuThreads = value
        prefs.edit().putInt("cpu_threads", value).apply()
        _state.update { it.copy(cpuThreads = value) }
    }

    fun setAcceleratorMode(mode: AcceleratorMode) {
        if (_state.value.running) return
        selectedAcceleratorMode = mode
        prefs.edit().putString("accelerator_mode", mode.cacheId).apply()
        _state.update { it.copy(acceleratorMode = mode) }
    }

    fun setThemeMode(mode: ThemeMode) {
        if (_settings.value.themeMode == mode) return
        settingsPrefs.edit().putString("theme_mode", mode.name).apply()
        _settings.update { it.copy(themeMode = mode) }
    }

    fun setThemePalette(palette: ThemePalette) {
        if (_settings.value.themePalette == palette) return
        settingsPrefs.edit().putString("theme_palette", palette.name).apply()
        _settings.update { it.copy(themePalette = palette) }
    }

    fun start() {
        val current = _state.value
        val uri = current.selectedUri ?: return
        if (current.running) return
        releasePlayer()
        releaseLivePreview()
        observeJob?.cancel()
        val model = ModelCatalog.byId(current.modelId)
        val acceleratorMode = if (current.backend == ComputeBackend.CPU) {
            AcceleratorMode.BALANCED
        } else {
            current.acceleratorMode
        }
        val request = OneTimeWorkRequestBuilder<SeparationWorker>()
            .setInputData(
                workDataOf(
                    SeparationWorker.KEY_INPUT_URI to uri.toString(),
                    SeparationWorker.KEY_MODEL_ID to model.id,
                    SeparationWorker.KEY_BACKEND to current.backend.cacheId,
                    SeparationWorker.KEY_CPU_THREADS to current.cpuThreads,
                    SeparationWorker.KEY_ACCELERATOR_MODE to acceleratorMode.cacheId,
                ),
            )
            .addTag(SeparationWorker.WORK_TAG)
            .build()

        _state.update {
            it.copy(
                running = true,
                workId = request.id,
                phase = SeparationPhase.HASHING.name,
                progress = 0f,
                error = null,
                cached = null,
                playerReady = false,
                durationFrames = 0,
                positionFrames = 0,
                isPlaying = false,
                effectiveBackend = if (current.backend == ComputeBackend.CPU) {
                    ComputeBackend.CPU
                } else {
                    null
                },
                fallbackReason = null,
                cpuUsagePercent = null,
                gpuUsagePercent = null,
                inferenceAverageMillis = 0,
                inferenceChunks = 0,
                workerThreads = 0,
                cpuHistory = emptyList(),
                gpuHistory = emptyList(),
                inferenceHistory = emptyList(),
                message = null,
            )
        }
        startStatsSampler()
        workManager.enqueueUniqueWork(
            SeparationWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        observeWork(request.id)
    }

    fun cancel() {
        val id = _state.value.workId ?: return
        workManager.cancelWorkById(id)
        stopStatsSampler()
        releaseLivePreview()
        _state.update {
            it.copy(
                phase = "CANCELLED",
                running = false,
            )
        }
    }

    fun toggleLivePlay() {
        val currentPlayer = livePlayer ?: return
        runCatching {
            if (currentPlayer.isPlaying()) {
                currentPlayer.pause()
                _state.update {
                    it.copy(
                        livePreviewPlaying = false,
                        livePositionFrames = currentPlayer.currentFrame(),
                    )
                }
            } else {
                if (_state.value.livePositionFrames >= _state.value.liveTotalFrames) {
                    currentPlayer.seekTo(0)
                    _state.update { it.copy(livePositionFrames = 0) }
                }
                currentPlayer.play()
                _state.update { it.copy(livePreviewPlaying = true, error = null) }
            }
        }.onFailure { error ->
            _state.update {
                it.copy(
                    livePreviewPlaying = false,
                    error = error.message ?: "Live playback failed",
                )
            }
        }
    }

    fun seekLive(frame: Long) {
        val currentPlayer = livePlayer ?: return
        val total = _state.value.liveTotalFrames
        val target = frame.coerceIn(0L, total)
        currentPlayer.seekTo(target)
        if (target >= total) currentPlayer.pause()
        _state.update {
            it.copy(
                livePositionFrames = target,
                livePreviewPlaying = if (target >= total) false else it.livePreviewPlaying,
            )
        }
    }

    fun setLiveMode(mode: StemMode) {
        livePlayer?.setMode(mode)
        _state.update { it.copy(liveMode = mode) }
    }

    fun setLiveVocalLevel(level: Float) {
        val value = level.coerceIn(0f, 1f)
        livePlayer?.setVocalLevel(value)
        _state.update { it.copy(liveVocalLevel = value) }
    }

    fun togglePlay() {
        val currentPlayer = player ?: return
        runCatching {
            if (currentPlayer.isPlaying()) {
                currentPlayer.pause()
                _state.update { it.copy(isPlaying = false) }
            } else {
                if (_state.value.positionFrames >= _state.value.durationFrames) {
                    _state.update { it.copy(positionFrames = 0) }
                }
                currentPlayer.play()
                _state.update { it.copy(isPlaying = true, error = null) }
            }
        }.onFailure { error ->
            _state.update {
                it.copy(isPlaying = false, error = error.message ?: "Playback failed")
            }
        }
    }

    fun seekTo(frame: Long) {
        val duration = _state.value.durationFrames
        val target = frame.coerceIn(0L, duration)
        player?.seekTo(target)
        if (target >= duration) player?.pause()
        _state.update {
            it.copy(
                positionFrames = target,
                isPlaying = if (target >= duration) false else it.isPlaying,
            )
        }
    }

    fun setMode(mode: StemMode) {
        player?.setMode(mode)
        _state.update { it.copy(mode = mode) }
    }

    fun setVocalLevel(level: Float) {
        val value = level.coerceIn(0f, 1f)
        player?.setVocalLevel(value)
        _state.update { it.copy(vocalLevel = value) }
    }

    fun export(format: ExportFormat, treeUri: Uri) {
        val stems = _state.value.cached ?: return
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, exportProgress = 0f, error = null, message = null) }
            try {
                container.exporter.export(
                    stems = stems,
                    treeUri = treeUri,
                    format = format,
                    onProgress = { fraction ->
                        _state.update { it.copy(exportProgress = fraction) }
                    },
                )
                _state.update {
                    it.copy(exporting = false, exportProgress = 1f, message = "Exported")
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        exporting = false,
                        error = error.message ?: "Export failed",
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    override fun onCleared() {
        observeJob?.cancel()
        stopStatsSampler()
        releasePlayer()
        releaseLivePreview()
        super.onCleared()
    }

    private fun observeWork(id: UUID) {
        observeJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED -> {
                        updateLivePreview(info.progress)
                        val effective = info.progress
                            .getString(SeparationWorker.KEY_EFFECTIVE_BACKEND)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(ComputeBackend::fromId)
                        val fallback = info.progress
                            .getString(SeparationWorker.KEY_FALLBACK_REASON)
                            ?.takeIf { it.isNotBlank() }
                        _state.update {
                            val inferenceAverage = info.progress.getLong(
                                SeparationWorker.KEY_INFERENCE_MS,
                                it.inferenceAverageMillis,
                            )
                            val inferenceChunks = info.progress.getInt(
                                SeparationWorker.KEY_INFERENCE_CHUNKS,
                                it.inferenceChunks,
                            )
                            val inferenceHistory =
                                if (inferenceChunks > it.inferenceChunks && inferenceAverage > 0) {
                                    (it.inferenceHistory + inferenceAverage.toFloat())
                                        .takeLast(MAX_STATS_HISTORY)
                                } else {
                                    it.inferenceHistory
                                }
                            it.copy(
                                running = true,
                                phase = info.progress.getString(SeparationWorker.KEY_PHASE)
                                    ?: it.phase,
                                progress = info.progress.getFloat(
                                    SeparationWorker.KEY_PROGRESS,
                                    0f,
                                ),
                                effectiveBackend = effective ?: it.effectiveBackend,
                                fallbackReason = fallback ?: it.fallbackReason,
                                inferenceAverageMillis = inferenceAverage,
                                inferenceChunks = inferenceChunks,
                                inferenceHistory = inferenceHistory,
                                workerThreads = info.progress.getInt(
                                    SeparationWorker.KEY_WORKER_THREADS,
                                    it.workerThreads,
                                ),
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        stopStatsSampler()
                        releaseLivePreview()
                        _state.update {
                            it.copy(
                                running = false,
                                progress = 1f,
                                phase = SeparationPhase.FINALIZING.name,
                                error = null,
                            )
                        }
                        val key = info.outputData.getString(SeparationWorker.KEY_CACHE_KEY)
                        if (key != null) {
                            loadCached(key)
                        } else {
                            _state.update { it.copy(error = "The worker returned no cache key") }
                        }
                    }

                    WorkInfo.State.FAILED -> {
                        stopStatsSampler()
                        releaseLivePreview()
                        val message = info.outputData.getString(SeparationWorker.KEY_ERROR)
                            ?: "Separation failed"
                        _state.update { it.copy(running = false, error = message) }
                    }

                    WorkInfo.State.CANCELLED -> {
                        stopStatsSampler()
                        releaseLivePreview()
                        _state.update { it.copy(running = false, phase = "CANCELLED") }
                    }
                }
            }
        }
    }

    private fun updateLivePreview(data: Data) {
        val vocalsPath = data.getString(SeparationWorker.KEY_PREVIEW_VOCALS).orEmpty()
        val instrumentalPath = data.getString(SeparationWorker.KEY_PREVIEW_INSTRUMENTAL).orEmpty()
        val available = data.getLong(SeparationWorker.KEY_PREVIEW_AVAILABLE, 0L)
        val total = data.getLong(SeparationWorker.KEY_PREVIEW_TOTAL, 0L)
        val sampleRate = data.getInt(SeparationWorker.KEY_PREVIEW_SAMPLE_RATE, 0)
        if (vocalsPath.isBlank() || instrumentalPath.isBlank() || total <= 0 || sampleRate <= 0) {
            return
        }

        val vocalsFile = File(vocalsPath)
        val instrumentalFile = File(instrumentalPath)
        if (!vocalsFile.isFile || !instrumentalFile.isFile) return

        val boundedAvailable = available.coerceIn(0L, total)
        val key = "$vocalsPath|$instrumentalPath|$total|$sampleRate"
        if (livePlayer == null || liveKey != key) {
            if (boundedAvailable <= 0) {
                _state.update {
                    it.copy(
                        liveAvailableFrames = 0,
                        liveTotalFrames = total,
                        liveSampleRate = sampleRate,
                    )
                }
                return
            }
            releaseLivePreview()
            val created = runCatching {
                LiveStemPlayer(
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    totalFrames = total,
                    sampleRate = sampleRate,
                ) { frame ->
                    _state.update { state ->
                        state.copy(
                            livePositionFrames = frame,
                            livePreviewPlaying = livePlayer?.isPlaying() == true,
                        )
                    }
                }
            }.getOrElse { error ->
                _state.update {
                    it.copy(
                        liveAvailableFrames = boundedAvailable,
                        liveTotalFrames = total,
                        liveSampleRate = sampleRate,
                        message = "Live preview unavailable: ${error.message ?: "unknown error"}",
                    )
                }
                return
            }
            livePlayer = created
            liveKey = key
            _state.update {
                it.copy(
                    livePreviewReady = true,
                    livePreviewPlaying = false,
                    livePositionFrames = 0,
                    liveAvailableFrames = boundedAvailable,
                    liveTotalFrames = total,
                    liveSampleRate = sampleRate,
                    liveMode = StemMode.VOCALS,
                    liveVocalLevel = 1f,
                )
            }
        } else {
            _state.update {
                it.copy(
                    liveAvailableFrames = boundedAvailable,
                    liveTotalFrames = total,
                    liveSampleRate = sampleRate,
                )
            }
        }
    }

    private fun loadCached(key: String) {
        val stems = container.cache.find(key)
        if (stems == null) {
            _state.update { it.copy(error = "Cached stems are missing") }
            return
        }
        releasePlayer()
        player = StemPlayer(
            vocalsFile = stems.vocals,
            instrumentalFile = stems.instrumental,
            frameCount = stems.metadata.frameCount,
            sampleRate = stems.metadata.sampleRate,
        ) { frame ->
            _state.update { state ->
                state.copy(
                    positionFrames = frame,
                    isPlaying = if (frame >= state.durationFrames) false else state.isPlaying,
                )
            }
        }
        val acceleratorMode = AcceleratorMode.fromId(stems.metadata.acceleratorMode)
        selectedAcceleratorMode = acceleratorMode
        prefs.edit().putString("accelerator_mode", acceleratorMode.cacheId).apply()
        _state.update {
            it.copy(
                cached = stems,
                backend = ComputeBackend.fromId(stems.metadata.requestedBackend),
                effectiveBackend = ComputeBackend.fromId(stems.metadata.effectiveBackend),
                fallbackReason = stems.metadata.fallbackReason,
                acceleratorMode = acceleratorMode,
                playerReady = true,
                durationFrames = stems.metadata.frameCount,
                positionFrames = 0,
                isPlaying = false,
                mode = StemMode.MIX,
                vocalLevel = 1f,
            )
        }
    }

    private fun startStatsSampler() {
        statsJob?.cancel()
        statsSampler.reset()
        statsJob = viewModelScope.launch {
            while (isActive) {
                delay(750)
                val sample = withContext(Dispatchers.IO) { statsSampler.sample() }
                _state.update {
                    val cpu = sample.cpuPercent
                    val gpu = sample.gpuPercent
                    it.copy(
                        cpuUsagePercent = cpu ?: it.cpuUsagePercent,
                        gpuUsagePercent = gpu ?: it.gpuUsagePercent,
                        cpuHistory = if (cpu != null) {
                            (it.cpuHistory + cpu).takeLast(MAX_STATS_HISTORY)
                        } else {
                            it.cpuHistory
                        },
                        gpuHistory = if (gpu != null) {
                            (it.gpuHistory + gpu).takeLast(MAX_STATS_HISTORY)
                        } else {
                            it.gpuHistory
                        },
                    )
                }
            }
        }
    }

    private fun stopStatsSampler() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun releaseLivePreview() {
        livePlayer?.close()
        livePlayer = null
        liveKey = null
        _state.update {
            it.copy(
                livePreviewReady = false,
                livePreviewPlaying = false,
                livePositionFrames = 0,
                liveAvailableFrames = 0,
                liveTotalFrames = 0,
                liveSampleRate = 44_100,
            )
        }
    }

    private fun releasePlayer() {
        player?.close()
        player = null
    }

    private fun displayName(uri: Uri): String {
        val context = getApplication<Application>()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio"
    }
}
