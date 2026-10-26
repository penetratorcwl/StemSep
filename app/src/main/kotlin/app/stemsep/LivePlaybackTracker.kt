package app.stemsep

object LivePlaybackTracker {
    @Volatile
    private var positionFrames = 0L

    @Volatile
    private var availableFrames = 0L

    @Volatile
    private var sampleRate = 0

    @Volatile
    private var playing = false

    fun reset() {
        positionFrames = 0L
        availableFrames = 0L
        sampleRate = 0
        playing = false
    }

    fun update(
        positionFrames: Long,
        availableFrames: Long,
        sampleRate: Int,
        playing: Boolean,
    ) {
        this.positionFrames = positionFrames
        this.availableFrames = availableFrames
        this.sampleRate = sampleRate
        this.playing = playing
    }

    fun bufferSeconds(): Float? {
        if (!playing || sampleRate <= 0) return null
        val buffered = (availableFrames - positionFrames).coerceAtLeast(0L)
        return buffered.toFloat() / sampleRate
    }
}
