package app.stemsep.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stemsep.AppVisibilityTracker
import app.stemsep.LivePlaybackTracker
import app.stemsep.MainActivity
import app.stemsep.R
import app.stemsep.StemSepApp
import app.stemsep.separation.AcceleratorMode
import app.stemsep.separation.ComputeBackend
import app.stemsep.separation.LiveStemPreview
import app.stemsep.separation.ModelCatalog
import app.stemsep.separation.SeparationPhase
import app.stemsep.separation.SeparationProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SeparationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_INPUT_URI)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing input URI"))
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL_ID))
        val backend = ComputeBackend.fromId(inputData.getString(KEY_BACKEND))
        val cpuThreads = inputData.getInt(KEY_CPU_THREADS, 4).coerceAtLeast(0)
        val acceleratorMode = AcceleratorMode.fromId(
            inputData.getString(KEY_ACCELERATOR_MODE),
        )
        val pipeline = (applicationContext as StemSepApp).container.pipeline

        LivePlaybackTracker.reset()
        createChannel()
        val latestProgress = AtomicReference(
            SeparationProgress(SeparationPhase.HASHING, 0f),
        )
        val foregroundStarted = AtomicBoolean(false)

        var lastPhase: SeparationPhase? = null
        var lastPercent = -1
        var latestPreview: LiveStemPreview? = null
        var effectiveBackend: ComputeBackend? = null
        var fallbackReason: String? = null

        fun publish(progress: SeparationProgress, preview: LiveStemPreview?) {
            latestProgress.set(progress)
            progress.effectiveBackend?.let { effectiveBackend = it }
            progress.fallbackReason?.let { fallbackReason = it }
            if (preview != null) latestPreview = preview
            setProgressAsync(
                workDataOf(
                    KEY_PHASE to progress.phase.name,
                    KEY_PROGRESS to progress.fraction,
                    KEY_EFFECTIVE_BACKEND to (effectiveBackend?.cacheId ?: ""),
                    KEY_FALLBACK_REASON to (fallbackReason ?: ""),
                    KEY_PREVIEW_VOCALS to (latestPreview?.vocalsFile?.absolutePath ?: ""),
                    KEY_PREVIEW_INSTRUMENTAL to
                        (latestPreview?.instrumentalFile?.absolutePath ?: ""),
                    KEY_PREVIEW_AVAILABLE to (latestPreview?.availableFrames ?: 0L),
                    KEY_PREVIEW_TOTAL to (latestPreview?.totalFrames ?: 0L),
                    KEY_PREVIEW_SAMPLE_RATE to (latestPreview?.sampleRate ?: 0),
                    KEY_INFERENCE_MS to progress.inferenceAverageMillis,
                    KEY_INFERENCE_CHUNKS to progress.inferenceChunks,
                    KEY_WORKER_THREADS to progress.workerThreads,
                ),
            )
            val percent = (progress.fraction * 100).toInt()
            if (progress.phase != lastPhase || percent != lastPercent) {
                lastPhase = progress.phase
                lastPercent = percent
                if (foregroundStarted.get()) {
                    notifyProgress(progress.fraction, progress.phase)
                }
            }
        }

        return coroutineScope {
            val foregroundJob = launch {
                if (AppVisibilityTracker.foreground.value) {
                    AppVisibilityTracker.foreground.first { !it }
                }
                runCatching {
                    val current = latestProgress.get()
                    setForeground(foregroundInfo(current.fraction, current.phase))
                    foregroundStarted.set(true)
                    notifyProgress(current.fraction, current.phase)
                }.onFailure { error ->
                    Log.w(TAG, "Could not start foreground notification", error)
                }
            }
            try {
                val result = pipeline.separate(
                    uri = Uri.parse(uriString),
                    model = model,
                    backend = backend,
                    cpuThreads = cpuThreads,
                    acceleratorMode = acceleratorMode,
                    playbackLeadSeconds = { LivePlaybackTracker.bufferSeconds() },
                    onProgress = { progress ->
                        publish(progress, latestPreview)
                    },
                    onPreview = { preview ->
                        publish(latestProgress.get(), preview)
                    },
                    isCancelled = { isStopped },
                )
                Result.success(workDataOf(KEY_CACHE_KEY to result.stems.key))
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                if (isStopped) Result.failure() else Result.failure(errorData(error))
            } catch (error: Exception) {
                Result.failure(errorData(error))
            } finally {
                foregroundJob.cancel()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(0f, SeparationPhase.HASHING)

    private fun errorData(error: Exception) = workDataOf(
        KEY_ERROR to (error.message ?: error::class.java.simpleName),
    )

    private fun foregroundInfo(fraction: Float, phase: SeparationPhase): ForegroundInfo {
        val notification = buildNotification(fraction, phase)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(fraction: Float, phase: SeparationPhase) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(phaseText(phase))
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (fraction.coerceIn(0f, 1f) * 100).toInt(), false)
            .build()

    private fun notifyProgress(fraction: Float, phase: SeparationPhase) {
        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, buildNotification(fraction, phase))
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun phaseText(phase: SeparationPhase): String = when (phase) {
        SeparationPhase.HASHING -> applicationContext.getString(R.string.phase_hashing)
        SeparationPhase.DOWNLOADING_MODEL -> applicationContext.getString(R.string.phase_downloading)
        SeparationPhase.DECODING -> applicationContext.getString(R.string.phase_decoding)
        SeparationPhase.SEPARATING -> applicationContext.getString(R.string.phase_separating)
        SeparationPhase.FINALIZING -> applicationContext.getString(R.string.phase_finalizing)
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_separation),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = applicationContext.getString(
                R.string.notification_channel_separation_description,
            )
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BACKEND = "backend"
        const val KEY_CPU_THREADS = "cpu_threads"
        const val KEY_ACCELERATOR_MODE = "accelerator_mode"
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_EFFECTIVE_BACKEND = "effective_backend"
        const val KEY_FALLBACK_REASON = "fallback_reason"
        const val KEY_PREVIEW_VOCALS = "preview_vocals"
        const val KEY_PREVIEW_INSTRUMENTAL = "preview_instrumental"
        const val KEY_PREVIEW_AVAILABLE = "preview_available"
        const val KEY_PREVIEW_TOTAL = "preview_total"
        const val KEY_PREVIEW_SAMPLE_RATE = "preview_sample_rate"
        const val KEY_INFERENCE_MS = "inference_ms"
        const val KEY_INFERENCE_CHUNKS = "inference_chunks"
        const val KEY_WORKER_THREADS = "worker_threads"
        const val KEY_CACHE_KEY = "cache_key"
        const val KEY_ERROR = "error"

        const val WORK_NAME = "stemsep-separation"
        const val WORK_TAG = "stemsep-separation"
        const val NOTIFICATION_ID = 4101
        const val CHANNEL_ID = "stemsep-separation-v2"
        const val LEGACY_CHANNEL_ID = "stemsep-separation"
        const val TAG = "StemSepWorker"
    }
}
