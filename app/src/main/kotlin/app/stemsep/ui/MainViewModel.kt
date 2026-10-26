package app.stemsep.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.stemsep.StemSepApp
import app.stemsep.playback.SourcePreviewPlayer
import app.stemsep.separation.CachedStems
import app.stemsep.separation.ComputeBackend
import app.stemsep.separation.ComputeCapabilities
import app.stemsep.separation.ComputeCapabilityDetector
import app.stemsep.separation.ExportFormat
import app.stemsep.separation.ModelCatalog
import app.stemsep.separation.SeparationPhase
import app.stemsep.separation.StemPlayer
import app.stemsep.work.SeparationWorker
import app.stemsep.dsp.StemMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class UiState(
    val selectedUri: Uri? = null,
    val selectedName: String? = null,
    val modelId: String = ModelCatalog.DEFAULT_MODEL_ID,
    val backend: ComputeBackend = ComputeBackend.CPU,
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
    val previewReady: Boolean = false,
    val previewPlaying: Boolean = false,
    val previewPositionMs: Int = 0,
    val previewDurationMs: Int = 0,
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
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var player: StemPlayer? = null
    private var previewPlayer: SourcePreviewPlayer? = null
    private var observeJob: Job? = null
    private var previewTickJob: Job? = null

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
        releasePlayer()
        releasePreview()
        _state.value = UiState(
            selectedUri = uri,
            selectedName = displayName(uri),
            modelId = _state.value.modelId,
            backend = _state.value.backend,
        )
    }

    fun selectModel(modelId: String) {
        if (modelId == _state.value.modelId) return
        releasePlayer()
        releasePreview()
        _state.update {
            it.copy(
                modelId = modelId,
                cached = null,
                playerReady = false,
                durationFrames = 0,
                positionFrames = 0,
                isPlaying = false,
                phase = null,
                progress = 0f,
                error = null,
                message = null,
            )
        }
    }

    fun selectBackend(backend: ComputeBackend) {
        if (backend == _state.value.backend) return
        if (!capabilities.supports(backend)) return
        releasePlayer()
        releasePreview()
        _state.update {
            it.copy(
                backend = backend,
                cached = null,
                playerReady = false,
                durationFrames = 0,
                positionFrames = 0,
                isPlaying = false,
                phase = null,
                progress = 0f,
                error = null,
                message = null,
            )
        }
    }

    fun start() {
        val current = _state.value
        val uri = current.selectedUri ?: return
        if (current.running) return
        releasePlayer()
        releasePreview()
        observeJob?.cancel()
        val model = ModelCatalog.byId(current.modelId)
        val request = OneTimeWorkRequestBuilder<SeparationWorker>()
            .setInputData(
                workDataOf(
                    SeparationWorker.KEY_INPUT_URI to uri.toString(),
                    SeparationWorker.KEY_MODEL_ID to model.id,
                    SeparationWorker.KEY_BACKEND to current.backend.cacheId,
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
                message = null,
            )
        }
        workManager.enqueueUniqueWork(
            SeparationWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        observeWork(request.id)
        startSourcePreview(uri)
    }

    fun cancel() {
        val id = _state.value.workId ?: return
        workManager.cancelWorkById(id)
        releasePreview()
        _state.update {
            it.copy(
                phase = "CANCELLED",
                previewReady = false,
                previewPlaying = false,
                previewPositionMs = 0,
            )
        }
    }

    fun togglePreview() {
        val currentPlayer = previewPlayer ?: return
        if (currentPlayer.isPlaying()) {
            currentPlayer.pause()
            previewTickJob?.cancel()
            _state.update {
                it.copy(
                    previewPlaying = false,
                    previewPositionMs = currentPlayer.positionMs(),
                )
            }
        } else {
            if (_state.value.previewPositionMs >= _state.value.previewDurationMs) {
                currentPlayer.seekTo(0)
                _state.update { it.copy(previewPositionMs = 0) }
            }
            if (currentPlayer.play()) {
                _state.update { it.copy(previewPlaying = true) }
                startPreviewTicker()
            }
        }
    }

    fun seekPreview(positionMs: Int) {
        val currentPlayer = previewPlayer ?: return
        val target = positionMs.coerceIn(0, _state.value.previewDurationMs)
        currentPlayer.seekTo(target.toLong())
        _state.update { it.copy(previewPositionMs = target) }
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
        releasePlayer()
        releasePreview()
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
                        _state.update {
                            it.copy(
                                running = true,
                                phase = info.progress.getString(SeparationWorker.KEY_PHASE)
                                    ?: it.phase,
                                progress = info.progress.getFloat(
                                    SeparationWorker.KEY_PROGRESS,
                                    0f,
                                ),
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        releasePreview()
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
                        releasePreview()
                        val message = info.outputData.getString(SeparationWorker.KEY_ERROR)
                            ?: "Separation failed"
                        _state.update { it.copy(running = false, error = message) }
                    }

                    WorkInfo.State.CANCELLED -> {
                        releasePreview()
                        _state.update { it.copy(running = false, phase = "CANCELLED") }
                    }
                }
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
        _state.update {
            it.copy(
                cached = stems,
                backend = ComputeBackend.fromId(stems.metadata.backend),
                playerReady = true,
                durationFrames = stems.metadata.frameCount,
                positionFrames = 0,
                isPlaying = false,
                mode = StemMode.MIX,
                vocalLevel = 1f,
            )
        }
    }

    private fun startSourcePreview(uri: Uri) {
        previewPlayer = SourcePreviewPlayer(
            context = getApplication(),
            uri = uri,
            onPrepared = { durationMs ->
                _state.update {
                    it.copy(
                        previewReady = true,
                        previewDurationMs = durationMs,
                        previewPositionMs = 0,
                    )
                }
            },
            onCompletion = {
                previewTickJob?.cancel()
                _state.update {
                    it.copy(
                        previewPlaying = false,
                        previewPositionMs = it.previewDurationMs,
                    )
                }
            },
            onError = { message ->
                _state.update {
                    it.copy(
                        error = message,
                        previewReady = false,
                        previewPlaying = false,
                    )
                }
            },
        )
    }

    private fun startPreviewTicker() {
        previewTickJob?.cancel()
        previewTickJob = viewModelScope.launch {
            while (isActive) {
                val currentPlayer = previewPlayer ?: break
                _state.update {
                    it.copy(
                        previewPositionMs = currentPlayer.positionMs(),
                        previewPlaying = currentPlayer.isPlaying(),
                    )
                }
                if (!currentPlayer.isPlaying()) break
                delay(250)
            }
        }
    }

    private fun releasePreview() {
        previewTickJob?.cancel()
        previewTickJob = null
        previewPlayer?.close()
        previewPlayer = null
        _state.update {
            it.copy(
                previewReady = false,
                previewPlaying = false,
                previewPositionMs = 0,
                previewDurationMs = 0,
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
