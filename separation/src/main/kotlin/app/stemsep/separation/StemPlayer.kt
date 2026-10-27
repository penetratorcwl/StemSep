package app.stemsep.separation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.stemsep.dsp.FloatPcmFileSource
import app.stemsep.dsp.StemMixer
import app.stemsep.dsp.StemMode
import java.io.Closeable
import java.io.File
import kotlin.math.max

/**
 * Plays the two raw stems through one AudioTrack and a software mixer.
 *
 * Playback position is tracked in source frames; seeking flushes the stream and
 * invalidates the in-flight write with a generation counter.
 */
class StemPlayer(
    vocalsFile: File,
    instrumentalFile: File,
    val frameCount: Long,
    val sampleRate: Int = 44_100,
    private val onPosition: (Long) -> Unit = {},
) : Closeable {
    private val vocals = FloatPcmFileSource(vocalsFile, frameCount)
    private val instrumental = FloatPcmFileSource(instrumentalFile, frameCount)
    private val lock = Object()
    private val chunkFrames = 2048
    private val vocalBuffer = FloatArray(chunkFrames * 2)
    private val instrumentalBuffer = FloatArray(chunkFrames * 2)
    private val mixBuffer = FloatArray(chunkFrames * 2)

    @Volatile
    private var mode: StemMode = StemMode.MIX

    @Volatile
    private var vocalLevel: Float = 1f

    private var playing = false
    private var released = false
    private var positionFrames = 0L
    private var generation = 0L
    private var audioTrack: AudioTrack? = null
    private var thread: Thread? = null

    val durationFrames: Long
        get() = frameCount

    fun setMode(mode: StemMode) {
        this.mode = mode
    }

    fun setVocalLevel(level: Float) {
        vocalLevel = level.coerceIn(0f, 1f)
    }

    fun currentFrame(): Long = synchronized(lock) { positionFrames }

    fun isPlaying(): Boolean = synchronized(lock) { playing }

    fun play() {
        synchronized(lock) {
            if (released) return
            if (positionFrames >= frameCount) positionFrames = 0
            if (audioTrack == null) {
                audioTrack = createAudioTrack()
            }
            playing = true
            val currentThread = thread
            if (currentThread == null || !currentThread.isAlive) {
                thread = Thread(::runPlayback, "stemsep-player").apply {
                    isDaemon = true
                    start()
                }
            }
            audioTrack?.play()
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            if (released) return
            playing = false
            runCatching { audioTrack?.pause() }
            lock.notifyAll()
        }
    }

    fun seekTo(frame: Long) {
        val target = frame.coerceIn(0L, frameCount)
        synchronized(lock) {
            if (released) return
            positionFrames = target
            generation++
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
                if (playing) audioTrack?.play()
            }
            lock.notifyAll()
        }
        onPosition(target)
    }

    override fun close() {
        synchronized(lock) {
            if (released) return
            released = true
            playing = false
            runCatching { audioTrack?.pause() }
            lock.notifyAll()
        }
        runCatching { thread?.join(2_000) }
        synchronized(lock) {
            runCatching {
                audioTrack?.flush()
                audioTrack?.release()
            }
            audioTrack = null
            thread = null
        }
        vocals.close()
        instrumental.close()
    }

    private fun runPlayback() {
        try {
            while (true) {
                val start: Long
                val writeGeneration: Long
                synchronized(lock) {
                    while (!playing && !released) lock.wait()
                    if (released) return
                    start = positionFrames
                    writeGeneration = generation
                }

                if (start >= frameCount) {
                    synchronized(lock) {
                        playing = false
                        runCatching { audioTrack?.pause() }
                    }
                    onPosition(frameCount)
                    continue
                }

                val requested = minOf(chunkFrames.toLong(), frameCount - start).toInt()
                val vocalRead = vocals.readFrames(start, requested, vocalBuffer)
                val instrumentalRead = instrumental.readFrames(start, requested, instrumentalBuffer)
                val frames = minOf(vocalRead, instrumentalRead)
                if (frames <= 0) {
                    synchronized(lock) { playing = false }
                    continue
                }

                val gains = StemMixer.gains(mode, vocalLevel)
                for (frame in 0 until frames) {
                    val base = frame * 2
                    mixBuffer[base] =
                        vocalBuffer[base] * gains.vocal +
                        instrumentalBuffer[base] * gains.instrumental
                    mixBuffer[base + 1] =
                        vocalBuffer[base + 1] * gains.vocal +
                        instrumentalBuffer[base + 1] * gains.instrumental
                }

                val track = synchronized(lock) { audioTrack } ?: return
                val written = track.write(mixBuffer, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    synchronized(lock) { playing = false }
                    continue
                }

                val framesWritten = written / 2
                val reachedEnd: Boolean
                synchronized(lock) {
                    if (writeGeneration == generation) {
                        positionFrames = (start + framesWritten).coerceAtMost(frameCount)
                    }
                    reachedEnd = positionFrames >= frameCount
                }
                onPosition(currentFrame())
                if (reachedEnd) {
                    synchronized(lock) {
                        playing = false
                        runCatching { audioTrack?.pause() }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            synchronized(lock) { playing = false }
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSize = max(minBuffer, 4096 * 2 * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Could not initialize the audio output")
        }
        return track
    }
}
