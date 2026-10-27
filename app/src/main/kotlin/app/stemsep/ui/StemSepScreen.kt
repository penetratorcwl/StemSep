package app.stemsep.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StemSepScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedMetric by remember { mutableStateOf<StatMetric?>(null) }
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
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .animateContentSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SourceCard(
                selectedName = state.selectedName,
                enabled = !state.running && !state.exporting,
                onChoose = { audioPicker.launch(arrayOf("audio/*")) },
            )

            ModelCard(
                selectedModelId = state.modelId,
                enabled = !state.running && !state.exporting,
                onSelect = viewModel::selectModel,
            )

            ComputeCard(
                selectedBackend = state.backend,
                cpuThreads = state.cpuThreads,
                autoCpuThreads = state.autoCpuThreads,
                acceleratorMode = state.acceleratorMode,
                effectiveBackend = state.effectiveBackend,
                fallbackReason = state.fallbackReason,
                capabilities = viewModel.capabilities,
                enabled = !state.running && !state.exporting,
                onSelectBackend = viewModel::selectBackend,
                onCpuThreads = viewModel::setCpuThreads,
                onAutoCpuThreads = viewModel::setAutoCpuThreads,
                onAcceleratorMode = viewModel::setAcceleratorMode,
            )

            if (state.running || state.stems != null || state.inferenceChunks > 0) {
                StatsCard(
                    state = state,
                    capabilities = viewModel.capabilities,
                    onMetricClick = { selectedMetric = it },
                )
            }

            if (state.running) {
                ProgressCard(
                    phase = state.phase,
                    progress = state.progress,
                    livePreviewReady = state.livePreviewReady,
                    onCancel = viewModel::cancel,
                )
                AnimatedVisibility(visible = state.livePreviewReady) {
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
                    enabled = state.selectedUri != null && !state.exporting,
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

            val stems = state.stems
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

    selectedMetric?.let { metric ->
        StatsDetailSheet(
            metric = metric,
            state = state,
            onDismiss = { selectedMetric = null },
        )
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
    autoCpuThreads: Boolean,
    acceleratorMode: AcceleratorMode,
    effectiveBackend: ComputeBackend?,
    fallbackReason: String?,
    capabilities: ComputeCapabilities,
    enabled: Boolean,
    onSelectBackend: (ComputeBackend) -> Unit,
    onCpuThreads: (Int) -> Unit,
    onAutoCpuThreads: (Boolean) -> Unit,
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

            val hideThreads = selectedBackend == ComputeBackend.SNAPDRAGON_NNAPI &&
                acceleratorMode == AcceleratorMode.ACCELERATOR_ONLY
            if (!hideThreads) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (autoCpuThreads) {
                            "CPU worker threads: Auto"
                        } else {
                            "CPU worker threads: $cpuThreads of ${capabilities.cpuCores}"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FilterChip(
                        selected = autoCpuThreads,
                        onClick = { onAutoCpuThreads(!autoCpuThreads) },
                        enabled = enabled,
                        label = { Text("Auto") },
                    )
                }
                if (autoCpuThreads) {
                    Text(
                        text = "Auto starts at ${minOf(4, capabilities.cpuCores)} threads and adjusts during live playback to keep about 5 seconds buffered.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (capabilities.cpuCores > 1) {
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
            }

            if (selectedBackend == ComputeBackend.SNAPDRAGON_NNAPI) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow CPU fallback", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = if (acceleratorMode == AcceleratorMode.BALANCED) {
                                "NNAPI may use the CPU for operations the accelerator cannot run."
                            } else {
                                "Accelerator-only: the job fails instead of silently using the CPU."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = acceleratorMode == AcceleratorMode.BALANCED,
                        onCheckedChange = { allowFallback ->
                            onAcceleratorMode(
                                if (allowFallback) {
                                    AcceleratorMode.BALANCED
                                } else {
                                    AcceleratorMode.ACCELERATOR_ONLY
                                },
                            )
                        },
                        enabled = enabled,
                    )
                }
                Text(
                    text = "NNAPI delegates to the Qualcomm driver and does not expose GPU/NPU core counts or per-unit utilization.",
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

private enum class StatMetric(
    val title: String,
    val description: String,
    val unit: String,
) {
    CPU(
        title = "CPU",
        description = "This process's share of total CPU capacity across all reported cores.",
        unit = "%",
    ),
    GPU(
        title = "GPU",
        description = "Best-effort GPU busy time from Qualcomm sysfs. NNAPI chooses placement per operation.",
        unit = "%",
    ),
    NPU(
        title = "NPU",
        description = "NNAPI does not expose per-unit NPU utilization or a controllable NPU core count.",
        unit = "",
    ),
    INFERENCE(
        title = "Inference",
        description = "Average ONNX inference time for each completed audio chunk.",
        unit = "ms",
    ),
}

@Composable
private fun StatsCard(
    state: UiState,
    capabilities: ComputeCapabilities,
    onMetricClick: (StatMetric) -> Unit,
) {
    val active = state.effectiveBackend ?: state.backend
    val gpuPeak = state.gpuHistory.maxOrNull()?.roundToInt()
    val workerThreads = state.workerThreads.takeIf { it > 0 }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                detail = when {
                    state.autoCpuThreads && workerThreads != null ->
                        "Auto · $workerThreads of ${capabilities.cpuCores} worker threads"
                    state.autoCpuThreads -> "Auto · selects threads while running"
                    workerThreads != null ->
                        "$workerThreads of ${capabilities.cpuCores} worker threads"
                    else -> "${state.cpuThreads} of ${capabilities.cpuCores} worker threads"
                },
                chartValues = state.cpuHistory,
                chartColor = MaterialTheme.colorScheme.primary,
                chartMax = 100f,
                onClick = { onMetricClick(StatMetric.CPU) },
            )
            MetricRow(
                label = "GPU",
                value = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    state.gpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "driver-managed"
                } else {
                    "—"
                },
                detail = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    buildString {
                        append("NNAPI may place operations on the GPU")
                        if (gpuPeak != null) append(" · peak $gpuPeak%")
                    }
                } else {
                    "GPU acceleration not requested"
                },
                chartValues = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    state.gpuHistory
                } else {
                    emptyList()
                },
                chartColor = MaterialTheme.colorScheme.secondary,
                chartMax = 100f,
                onClick = { onMetricClick(StatMetric.GPU) },
            )
            MetricRow(
                label = "NPU",
                value = if (active == ComputeBackend.SNAPDRAGON_NNAPI) "driver-managed" else "off",
                detail = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    "NNAPI may place operations on the NPU"
                } else {
                    "QNN HTP requires quantized models"
                },
                onClick = { onMetricClick(StatMetric.NPU) },
            )
            MetricRow(
                label = "Inference",
                value = if (state.inferenceChunks > 0) {
                    "${state.inferenceAverageMillis} ms/chunk"
                } else {
                    "—"
                },
                detail = "${state.inferenceChunks} completed chunks",
                chartValues = state.inferenceHistory,
                chartColor = MaterialTheme.colorScheme.tertiary,
                onClick = { onMetricClick(StatMetric.INFERENCE) },
            )
            MetricRow(
                label = "Power",
                value = state.powerWatts?.let(::formatWatts) ?: "—",
                detail = "Battery current × voltage",
            )
            MetricRow(
                label = "Energy",
                value = if (state.batteryCharging) {
                    "charging"
                } else {
                    state.energyMilliAmpHours?.let(::formatEnergy) ?: "—"
                },
                detail = "Consumed by the device during this run",
            )
            Text(
                text = "Tap a metric for a larger chart and run summary. CPU is process share; GPU sysfs is best-effort.",
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
    chartValues: List<Float> = emptyList(),
    chartColor: Color = MaterialTheme.colorScheme.primary,
    chartMax: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall)
            if (chartValues.isNotEmpty()) {
                Sparkline(
                    values = chartValues,
                    color = chartColor,
                    maxValue = chartMax,
                    modifier = Modifier
                        .width(64.dp)
                        .height(28.dp),
                )
            }
        }
    }
}

@Composable
private fun Sparkline(
    values: List<Float>,
    color: Color,
    maxValue: Float? = null,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val scaleMax = maxValue ?: values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val step = if (values.size > 1) size.width / (values.size - 1) else 0f
        fun point(index: Int): Offset {
            val x = index * step
            val y = size.height - (values[index] / scaleMax).coerceIn(0f, 1f) * size.height
            return Offset(x, y)
        }

        val line = Path()
        values.forEachIndexed { index, _ ->
            val current = point(index)
            if (index == 0) line.moveTo(current.x, current.y)
            else line.lineTo(current.x, current.y)
        }
        val fill = Path()
        fill.moveTo(0f, size.height)
        values.forEachIndexed { index, _ ->
            val current = point(index)
            fill.lineTo(current.x, current.y)
        }
        fill.lineTo(size.width, size.height)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0.30f), Color.Transparent),
            ),
        )
        if (values.size == 1) {
            drawCircle(color = color, radius = 2.dp.toPx(), center = point(0))
        } else {
            drawPath(
                path = line,
                color = color,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsDetailSheet(
    metric: StatMetric,
    state: UiState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = metric.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = metric.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metric == StatMetric.NPU) {
                NpuDetails(state = state)
            } else {
                MetricDetails(metric = metric, state = state)
            }
            Text(
                text = "Swipe down to close",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MetricDetails(
    metric: StatMetric,
    state: UiState,
) {
    val values = metric.history(state)
    val color = when (metric) {
        StatMetric.CPU -> MaterialTheme.colorScheme.primary
        StatMetric.GPU -> MaterialTheme.colorScheme.secondary
        StatMetric.INFERENCE -> MaterialTheme.colorScheme.tertiary
        StatMetric.NPU -> MaterialTheme.colorScheme.outline
    }
    if (values.isEmpty()) {
        Text(
            text = "No samples yet. Start a separation to collect data.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val maxValue = if (metric == StatMetric.INFERENCE) {
        ((values.maxOrNull() ?: 1f) * 1.2f).coerceAtLeast(1f)
    } else {
        100f
    }
    MetricChart(
        values = values,
        maxValue = maxValue,
        unit = metric.unit,
        color = color,
        showPoints = metric == StatMetric.INFERENCE,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryTile(
            label = "Current",
            value = metric.currentValue(state),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Average",
            value = formatMetric(values.average().toFloat(), metric.unit),
            modifier = Modifier.weight(1f),
        )
        SummaryTile(
            label = "Peak",
            value = formatMetric(values.maxOrNull() ?: 0f, metric.unit),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "${values.size} samples · the chart keeps the latest 90 points",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun MetricChart(
    values: List<Float>,
    maxValue: Float,
    unit: String,
    color: Color,
    showPoints: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                for (index in 0..4) {
                    val y = top + (bottom - top) * index / 4f
                    drawLine(
                        color = color.copy(alpha = if (index == 4) 0.25f else 0.10f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (values.isEmpty()) return@Canvas
                val step = if (values.size > 1) {
                    size.width / (values.size - 1)
                } else {
                    0f
                }
                fun x(index: Int): Float = index * step
                fun y(value: Float): Float =
                    bottom - (value / maxValue).coerceIn(0f, 1f) * (bottom - top)

                val line = Path()
                values.forEachIndexed { index, value ->
                    if (index == 0) line.moveTo(x(index), y(value))
                    else line.lineTo(x(index), y(value))
                }
                val fill = Path()
                fill.moveTo(0f, bottom)
                values.forEachIndexed { index, value ->
                    fill.lineTo(x(index), y(value))
                }
                fill.lineTo(size.width, bottom)
                fill.close()
                drawPath(
                    path = fill,
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.28f), Color.Transparent),
                    ),
                )
                if (values.size > 1) {
                    drawPath(
                        path = line,
                        color = color,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                if (showPoints || values.size == 1) {
                    values.forEachIndexed { index, value ->
                        drawCircle(
                            color = color,
                            radius = 3.dp.toPx(),
                            center = Offset(x(index), y(value)),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "oldest",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "max ${maxValue.roundToInt()} $unit",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NpuDetails(state: UiState) {
    val active = state.effectiveBackend ?: state.backend
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("NNAPI status", style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (active == ComputeBackend.SNAPDRAGON_NNAPI) {
                    "Requested: ${state.backend.displayName}"
                } else {
                    "CPU backend selected"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.effectiveBackend?.let { effective ->
                Text(
                    text = "Active: ${effective.displayName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        text = "Android reports NNAPI placement only indirectly through the active backend. There is no public per-unit NPU utilization counter.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "QNN HTP requires a quantized model and calibration. The current UVR_MDXNET_9482 artifact is float32.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun StatMetric.history(state: UiState): List<Float> = when (this) {
    StatMetric.CPU -> state.cpuHistory
    StatMetric.GPU -> if (state.activeBackend() == ComputeBackend.SNAPDRAGON_NNAPI) {
        state.gpuHistory
    } else {
        emptyList()
    }
    StatMetric.INFERENCE -> state.inferenceHistory
    StatMetric.NPU -> emptyList()
}

private fun StatMetric.currentValue(state: UiState): String = when (this) {
    StatMetric.CPU -> state.cpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "—"
    StatMetric.GPU -> if (state.activeBackend() == ComputeBackend.SNAPDRAGON_NNAPI) {
        state.gpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "driver-managed"
    } else {
        "—"
    }
    StatMetric.INFERENCE -> if (state.inferenceChunks > 0) {
        "${state.inferenceAverageMillis} ms"
    } else {
        "—"
    }
    StatMetric.NPU -> if (state.activeBackend() == ComputeBackend.SNAPDRAGON_NNAPI) {
        "driver-managed"
    } else {
        "off"
    }
}

private fun UiState.activeBackend(): ComputeBackend = effectiveBackend ?: backend

private fun formatWatts(value: Float): String = String.format(Locale.US, "%.2f W", value)

private fun formatEnergy(value: Float): String = String.format(Locale.US, "%.1f mAh", value)

private fun formatMetric(value: Float, unit: String): String = when (unit) {
    "%" -> "${value.roundToInt()}%"
    "ms" -> "${value.roundToInt()} ms"
    else -> value.roundToInt().toString()
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Live ${modeLabel(state.liveMode).lowercase()}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
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
            }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StemMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.liveMode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(modeLabel(mode)) },
                        modifier = Modifier.weight(1f),
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
    var confirmDiscard by remember { mutableStateOf(false) }
    val duration = state.durationFrames
    val position = state.positionFrames

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                IconButton(onClick = viewModel::togglePlay) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StemMode.values().forEach { mode ->
                    FilterChip(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(modeLabel(mode)) },
                        modifier = Modifier.weight(1f),
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
            Text(
                text = "Stems are temporary. Export them to keep them; starting another " +
                    "separation or closing the app normally deletes them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { confirmDiscard = true },
                enabled = !state.exporting,
            ) {
                Text("Discard result")
            }
            if (confirmDiscard) {
                AlertDialog(
                    onDismissRequest = { confirmDiscard = false },
                    title = { Text("Discard result?") },
                    text = {
                        Text(
                            "The separated stems will be deleted. Export them first if " +
                                "you want to keep them.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDiscard = false
                                viewModel.discardResult()
                            },
                        ) {
                            Text("Discard")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDiscard = false }) {
                            Text("Keep")
                        }
                    },
                )
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
    "PREPARING" -> "Preparing..."
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
