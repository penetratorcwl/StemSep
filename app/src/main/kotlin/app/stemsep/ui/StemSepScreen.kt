package app.stemsep.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stemsep.dsp.StemMode
import app.stemsep.separation.AcceleratorMode
import app.stemsep.separation.ComputeBackend
import app.stemsep.separation.ComputeCapabilities
import app.stemsep.separation.ExportFormat
import app.stemsep.separation.ModelCatalog
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemSepScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        viewModel.selectAudio(uri)
    }
    var pendingFormat by remember { mutableStateOf(ExportFormat.WAV) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.export(pendingFormat, uri)
        }
    }
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { contentVisible = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0f,
        animationSpec = tween(450),
        label = "contentAlpha",
    )
    val contentOffset by animateFloatAsState(
        targetValue = if (contentVisible) 0f else 28f,
        animationSpec = tween(450),
        label = "contentOffset",
    )
    val logoScale by animateFloatAsState(
        targetValue = if (contentVisible) 1f else 0.7f,
        animationSpec = tween(600),
        label = "logoScale",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(30.dp)
                                .graphicsLayer {
                                    scaleX = logoScale
                                    scaleY = logoScale
                                },
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("StemSep", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "On-device vocal isolation",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset
                }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SourceCard(
                selectedName = state.selectedName,
                enabled = !state.running,
                onChoose = { audioPicker.launch(arrayOf("audio/*")) },
            )

            ModelCard(
                selectedModelId = state.modelId,
                enabled = !state.running,
                onSelect = viewModel::selectModel,
            )

            ComputeCard(
                selectedBackend = state.backend,
                cpuThreads = state.cpuThreads,
                acceleratorMode = state.acceleratorMode,
                effectiveBackend = state.effectiveBackend,
                fallbackReason = state.fallbackReason,
                capabilities = viewModel.capabilities,
                enabled = !state.running,
                onSelectBackend = viewModel::selectBackend,
                onCpuThreads = viewModel::setCpuThreads,
                onAcceleratorMode = viewModel::setAcceleratorMode,
            )

            if (state.running || state.cached != null || state.inferenceChunks > 0) {
                StatsCard(state = state, capabilities = viewModel.capabilities)
            }

            if (state.running) {
                ProgressCard(
                    phase = state.phase,
                    progress = state.progress,
                    livePreviewReady = state.livePreviewReady,
                    onCancel = viewModel::cancel,
                )
                if (state.livePreviewReady) {
                    LivePreviewCard(
                        state = state,
                        onToggle = viewModel::toggleLivePlay,
                        onSeek = viewModel::seekLive,
                        onMode = viewModel::setLiveMode,
                        onVocalLevel = viewModel::setLiveVocalLevel,
                    )
                }
            } else {
                Button(
                    onClick = viewModel::start,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selectedUri != null,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Separate vocals and instrumental")
                }
            }

            state.error?.let { message ->
                ErrorCard(message = message, onDismiss = viewModel::clearMessage)
            }
            state.message?.let { message ->
                MessageCard(message = message, onDismiss = viewModel::clearMessage)
            }

            val stems = state.cached
            if (state.playerReady && stems != null) {
                PlayerCard(
                    viewModel = viewModel,
                    state = state,
                    sourceName = stems.metadata.sourceName,
                    sampleRate = stems.metadata.sampleRate,
                    onExportClick = {
                        pendingFormat = it
                        exportPicker.launch(null)
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    selectedName: String?,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Source audio", style = MaterialTheme.typography.titleMedium)
            Text(
                text = selectedName ?: "Choose a song or recording.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onChoose, enabled = enabled) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (selectedName == null) "Choose audio" else "Change audio")
            }
        }
    }
}

@Composable
private fun ModelCard(
    selectedModelId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val selected = ModelCatalog.byId(selectedModelId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Model", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCatalog.all.forEach { model ->
                    FilterChip(
                        selected = model.id == selected.id,
                        onClick = { onSelect(model.id) },
                        enabled = enabled,
                        label = { Text(model.displayName) },
                    )
                }
            }
            Text(
                text = selected.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "The model is downloaded once and verified with SHA-256.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ComputeCard(
    selectedBackend: ComputeBackend,
    cpuThreads: Int,
    acceleratorMode: AcceleratorMode,
    effectiveBackend: ComputeBackend?,
    fallbackReason: String?,
    capabilities: ComputeCapabilities,
    enabled: Boolean,
    onSelectBackend: (ComputeBackend) -> Unit,
    onCpuThreads: (Int) -> Unit,
    onAcceleratorMode: (AcceleratorMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Compute", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComputeBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = selectedBackend == backend,
                        onClick = { onSelectBackend(backend) },
                        enabled = enabled && capabilities.supports(backend),
                        label = { Text(backend.displayName) },
                    )
                }
            }
            Text(
                text = when {
                    selectedBackend == ComputeBackend.CPU ->
                        "CPU inference uses ONNX Runtime. More worker threads can speed up separation but use more battery and heat."
                    capabilities.canUseSnapdragonNnapi ->
                        "Snapdragon ${capabilities.socLabel}: NNAPI is one backend. The Qualcomm driver chooses GPU, NPU or CPU per operation; GPU and NPU are not separate switches."
                    else ->
                        "Snapdragon acceleration is unavailable on this device; CPU is used."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = "CPU worker threads: $cpuThreads of ${capabilities.cpuCores}",
                style = MaterialTheme.typography.labelLarge,
            )
            if (capabilities.cpuCores > 1) {
                Slider(
                    value = cpuThreads.toFloat(),
                    onValueChange = { onCpuThreads(it.roundToInt()) },
                    valueRange = 1f..capabilities.cpuCores.toFloat(),
                    steps = (capabilities.cpuCores - 2).coerceAtLeast(0),
                    enabled = enabled,
                )
                Text(
                    text = "More threads use more CPU capacity; fewer threads leave more headroom for playback and the rest of the device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = "This device reports one CPU core.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (selectedBackend == ComputeBackend.SNAPDRAGON_NNAPI) {
                HorizontalDivider()
                Text("GPU / NPU policy", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AcceleratorMode.entries.forEach { mode ->
                        FilterChip(
                            selected = acceleratorMode == mode,
                            onClick = { onAcceleratorMode(mode) },
                            enabled = enabled,
                            label = { Text(mode.displayName) },
                        )
                    }
                }
                Text(
                    text = acceleratorMode.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "NNAPI does not expose a GPU/NPU core count or per-unit utilization. Android reports placement only indirectly through the active backend.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            Text(
                text = "Requested: ${selectedBackend.displayName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            effectiveBackend?.let { effective ->
                Text(
                    text = "Active: ${effective.displayName}",
                    color = if (effective != selectedBackend) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            fallbackReason?.let { reason ->
                Text(
                    text = "Fallback: $reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(
    state: UiState,
    capabilities: ComputeCapabilities,
) {
    val active = state.effectiveBackend ?: state.backend
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Compute stats", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (state.running) "live" else "last run",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            MetricRow(
                label = "CPU",
                value = state.cpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "—",
                detail = "${state.workerThreads.takeIf { it > 0 } ?: state.cpuThreads} of ${capabilities.cpuCores} worker threads",
            )
            MetricRow(
                label = "GPU",
                value = state.gpuUsagePercent?.let { "${it.roundToInt()}%" }
                    ?: if (active == ComputeBackend.SNAPDRAGON_NNAPI) "driver-managed" else "—",
                detail = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    "NNAPI may place operations on the GPU"
                } else {
                    "GPU acceleration not requested"
                },
            )
            MetricRow(
                label = "NPU",
                value = if (active == ComputeBackend.SNAPDRAGON_NNAPI) "driver-managed" else "off",
                detail = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    "NNAPI may place operations on the NPU"
                } else {
                    "QNN HTP requires quantized models"
                },
            )
            MetricRow(
                label = "Inference",
                value = if (state.inferenceChunks > 0) {
                    "${state.inferenceAverageMillis} ms/chunk"
                } else {
                    "—"
                },
                detail = "${state.inferenceChunks} completed chunks",
            )
            Text(
                text = "CPU is this process's share of total CPU capacity. Android does not provide public GPU/NPU utilization counters; NNAPI decides placement per operation.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun LivePreviewCard(
    state: UiState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onMode: (StemMode) -> Unit,
    onVocalLevel: (Float) -> Unit,
) {
    val total = state.liveTotalFrames
    val position = state.livePositionFrames.coerceIn(0L, total)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Live ${modeLabel(state.liveMode).lowercase()}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${formatTime(position, state.liveSampleRate)} / ${formatTime(total, state.liveSampleRate)} · buffered ${formatTime(state.liveAvailableFrames, state.liveSampleRate)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = {
                    if (total <= 0) 0f
                    else (state.liveAvailableFrames.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Slider(
                value = position.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..max(1f, total.toFloat()),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (state.livePreviewPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.livePreviewPlaying) {
                            "Pause live vocals"
                        } else {
                            "Play live vocals"
                        },
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text("Play while the model works")
            }
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StemMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.liveMode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
            if (state.liveMode == StemMode.MIX) {
                Text(
                    text = "Vocal level ${(state.liveVocalLevel * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = state.liveVocalLevel,
                    onValueChange = onVocalLevel,
                    valueRange = 0f..1f,
                )
            }
        }
    }
}

@Composable
private fun ProgressCard(
    phase: String?,
    progress: Float,
    livePreviewReady: Boolean,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(phaseLabel(phase), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%")
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (!livePreviewReady) {
                Text(
                    text = "Live vocals appear here after the first separated chunk.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PlayerCard(
    viewModel: MainViewModel,
    state: UiState,
    sourceName: String,
    sampleRate: Int,
    onExportClick: (ExportFormat) -> Unit,
) {
    var exportFormat by remember { mutableStateOf(ExportFormat.WAV) }
    val duration = state.durationFrames
    val position = state.positionFrames

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = sourceName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Engine: ${(state.effectiveBackend ?: state.backend).displayName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${formatTime(position, sampleRate)} / ${formatTime(duration, sampleRate)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..max(1f, duration.toFloat()),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::togglePlay) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(if (state.isPlaying) "Playing" else "Paused")
            }

            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StemMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }

            if (state.mode == StemMode.MIX) {
                Text(
                    text = "Vocal level ${(state.vocalLevel * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = state.vocalLevel,
                    onValueChange = viewModel::setVocalLevel,
                    valueRange = 0f..1f,
                )
            }

            HorizontalDivider()

            Text("Export", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExportFormat.values().forEach { format ->
                    FilterChip(
                        selected = exportFormat == format,
                        onClick = { exportFormat = format },
                        enabled = !state.exporting,
                        label = { Text(format.name) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onExportClick(exportFormat) },
                    enabled = !state.exporting,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }
            }
            if (state.exporting) {
                LinearProgressIndicator(
                    progress = { state.exportProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Writing files...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

private fun phaseLabel(phase: String?): String = when (phase) {
    "HASHING" -> "Reading the source..."
    "DOWNLOADING_MODEL" -> "Downloading the model..."
    "DECODING" -> "Decoding audio..."
    "SEPARATING" -> "Separating vocals..."
    "FINALIZING" -> "Saving the stems..."
    "CANCELLED" -> "Cancelled"
    else -> "Working..."
}

private fun modeLabel(mode: StemMode): String = when (mode) {
    StemMode.VOCALS -> "Vocals"
    StemMode.INSTRUMENTAL -> "Instrumental"
    StemMode.MIX -> "Mix"
}

private fun formatTime(frames: Long, sampleRate: Int): String {
    if (sampleRate <= 0) return "0:00"
    val totalSeconds = (frames / sampleRate).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
