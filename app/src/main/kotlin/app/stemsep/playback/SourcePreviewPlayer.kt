package app.stemsep.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.io.Closeable

class SourcePreviewPlayer(
    context: Context,
    uri: Uri,
    private val onPrepared: (durationMs: Int) -> Unit,
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit,
) : Closeable {
    private val player = MediaPlayer()
    @Volatile
    private var closed = false
    @Volatile
    private var prepared = false

    init {
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(context, uri)
            player.setOnPreparedListener { mediaPlayer ->
                if (closed) return@setOnPreparedListener
                prepared = true
                onPrepared(mediaPlayer.duration.coerceAtLeast(0))
            }
            player.setOnCompletionListener {
                if (!closed) onCompletion()
            }
            player.setOnErrorListener { _, what, extra ->
                if (!closed) onError("Source preview failed ($what/$extra)")
                true
            }
            player.prepareAsync()
        }.onFailure { error ->
            onError(error.message ?: "Could not prepare source preview")
        }
    }

    fun play(): Boolean = runCatching {
        if (prepared && !closed) {
            player.start()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    fun pause() {
        runCatching {
            if (prepared && !closed && player.isPlaying) player.pause()
        }
    }

    fun seekTo(positionMs: Long) {
        runCatching {
            if (prepared && !closed) {
                player.seekTo(positionMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
            }
        }
    }

    fun durationMs(): Int = runCatching {
        if (prepared && !closed) player.duration else 0
    }.getOrDefault(0)

    fun positionMs(): Int = runCatching {
        if (prepared && !closed) player.currentPosition else 0
    }.getOrDefault(0)

    fun isPlaying(): Boolean = runCatching {
        prepared && !closed && player.isPlaying
    }.getOrDefault(false)

    override fun close() {
        if (closed) return
        closed = true
        prepared = false
        runCatching { player.setOnPreparedListener(null) }
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.reset() }
        runCatching { player.release() }
    }
}
