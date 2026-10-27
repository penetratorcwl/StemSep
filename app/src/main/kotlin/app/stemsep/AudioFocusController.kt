package app.stemsep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

class AudioFocusController(
    context: Context,
    private val onFocusLost: () -> Unit,
) {
    private val audioManager = requireNotNull(
        context.getSystemService(AudioManager::class.java),
    )
    private var focusRequest: AudioFocusRequest? = null
    @Volatile
    private var held = false

    @Synchronized
    fun request(): Boolean {
        if (held) return true
        val current = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                if (
                    change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    held = false
                    onFocusLost()
                }
            }
            .build()
            .also { focusRequest = it }
        held = audioManager.requestAudioFocus(current) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    @Synchronized
    fun abandon() {
        if (!held) return
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        held = false
    }
}
