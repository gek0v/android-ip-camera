package com.github.digitallyrefined.androidipcamera.helpers

/** Timestamp-based frame pacing for camera surfaces that produce faster than the encoder target. */
internal class FrameRateLimiter(targetFps: Int) {
    @Volatile
    var targetFps: Int = targetFps.coerceIn(1, 120)
        set(value) {
            val coerced = value.coerceIn(1, 120)
            if (field != coerced) {
                field = coerced
                intervalNs = 1_000_000_000L / coerced
                reset()
            }
        }

    private var intervalNs = 1_000_000_000L / this.targetFps
    private var nextFrameNs = Long.MIN_VALUE
    private var lastTimestampNs = Long.MIN_VALUE

    fun shouldEmit(timestampNs: Long): Boolean {
        if (lastTimestampNs != Long.MIN_VALUE && timestampNs < lastTimestampNs) reset()
        lastTimestampNs = timestampNs

        if (nextFrameNs == Long.MIN_VALUE) {
            nextFrameNs = timestampNs + intervalNs
            return true
        }
        if (timestampNs < nextFrameNs) return false

        do {
            nextFrameNs += intervalNs
        } while (nextFrameNs <= timestampNs)
        return true
    }

    fun reset() {
        nextFrameNs = Long.MIN_VALUE
        lastTimestampNs = Long.MIN_VALUE
    }
}
