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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stemsep.dsp.StemMode
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("StemSep", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                selectedBackend = state.backend,
                capabilities = viewModel.capabilities,
                enabled = !state.running,
                onSelect = viewModel::selectModel,
                onSelectBackend = viewModel::selectBackend,
            )

            if (state.running) {
                ProgressCard(
                    phase = state.phase,
                    progress = state.progress,
                    onCancel = viewModel::cancel,
                )
                if (state.previewReady) {
                    SourcePreviewCard(
                        state = state,
                        onToggle = viewModel::togglePreview,
                        onSeek = viewModel::seekPreview,
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
    selectedBackend: ComputeBackend,
    capabilities: ComputeCapabilities,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onSelectBackend: (ComputeBackend) -> Unit,
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

            HorizontalDivider()

            Text("Compute", style = MaterialTheme.typography.labelLarge)
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
                        "Runs on the ARM CPU with ONNX Runtime (4 intra-op threads)."
                    capabilities.canUseSnapdragonNnapi ->
                        "Snapdragon ${capabilities.socLabel}: Android NNAPI may use the GPU/NPU. Unsupported operations fall back to CPU."
                    else ->
                        "Snapdragon acceleration is unavailable on this device; CPU is used."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourcePreviewCard(
    state: UiState,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    val duration = state.previewDurationMs
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Source preview", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${formatMs(state.previewPositionMs)} / ${formatMs(duration)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.previewPositionMs.coerceIn(0, duration).toFloat(),
                onValueChange = { onSeek(it.roundToInt()) },
                valueRange = 0f..max(1f, duration.toFloat()),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (state.previewPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.previewPlaying) "Pause source" else "Play source",
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text("Listen while the model works")
            }
        }
    }
}

@Composable
private fun ProgressCard(
    phase: String?,
    progress: Float,
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
                text = "Engine: ${state.backend.displayName}",
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

private fun formatMs(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
