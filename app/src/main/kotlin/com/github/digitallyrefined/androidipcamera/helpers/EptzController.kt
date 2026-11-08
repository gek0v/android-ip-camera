package com.github.digitallyrefined.androidipcamera.helpers

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure JVM data class representing a crop rectangle, convertible to [android.graphics.Rect].
 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toAndroidRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)
}

/**
 * Normalized bounding box where coordinates are in [0.0f, 1.0f].
 * (0, 0) is top-left, (1, 1) is bottom-right.
 */
data class NormalizedBoundingBox(
    val ymin: Float,
    val xmin: Float,
    val ymax: Float,
    val xmax: Float
) {
    val width: Float get() = (xmax - xmin).coerceAtLeast(0f)
    val height: Float get() = (ymax - ymin).coerceAtLeast(0f)
    val centerX: Float get() = xmin + width / 2f
    val centerY: Float get() = ymin + height / 2f
}

/**
 * Cinematic ePTZ Controller for Android Camera2 / CameraX with:
 * - Normalized pan (-1.0f..1.0f), tilt (-1.0f..1.0f), zoom (1.0f..4.0f).
 * - Exact aspect ratio preservation and active-array boundary clamping (supporting 64MP IMX787).
 * - 60 FPS LERP smoothing for cinematic ease-in/ease-out camera moves without sudden jumps.
 * - Hysteresis deadband (default 5% / 0.05f) to eliminate micro-jitter and detector oscillations.
 * - Intelligent single/multi-subject auto-framing with golden ratio headroom (~15-20%).
 */
class EptzController(
    var lerpFactor: Float = DEFAULT_LERP_FACTOR,
    var deadbandThreshold: Float = DEFAULT_DEADBAND_THRESHOLD,
    var headroomFraction: Float = DEFAULT_HEADROOM_FRACTION,
    var targetFill: Float = DEFAULT_TARGET_FILL
) {
    companion object {
        const val MIN_PAN = -1.0f
        const val MAX_PAN = 1.0f
        const val MIN_TILT = -1.0f
        const val MAX_TILT = 1.0f
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 4.0f

        const val DEFAULT_LERP_FACTOR = 0.10f
        const val DEFAULT_DEADBAND_THRESHOLD = 0.05f
        const val DEFAULT_HEADROOM_FRACTION = 0.18f
        const val DEFAULT_TARGET_FILL = 0.55f

        private const val CONVERGENCE_EPSILON = 0.0005f

        fun computeCropRect(
            sensorWidth: Int,
            sensorHeight: Int,
            pan: Float,
            tilt: Float,
            zoom: Float,
            targetAspectRatio: Float = 16f / 9f
        ): CropRect {
            require(sensorWidth > 0 && sensorHeight > 0) { "Sensor dimensions must be positive" }
            require(targetAspectRatio > 0f) { "Target aspect ratio must be positive" }

            val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val clampedPan = pan.coerceIn(MIN_PAN, MAX_PAN)
            val clampedTilt = tilt.coerceIn(MIN_TILT, MAX_TILT)

            val sensorAspect = sensorWidth.toFloat() / sensorHeight.toFloat()

            val baseWidth: Float
            val baseHeight: Float
            if (sensorAspect > targetAspectRatio) {
                baseHeight = sensorHeight.toFloat()
                baseWidth = baseHeight * targetAspectRatio
            } else {
                baseWidth = sensorWidth.toFloat()
                baseHeight = baseWidth / targetAspectRatio
            }

            val cropWidth = (baseWidth / clampedZoom).roundToInt().coerceIn(1, sensorWidth)
            val cropHeight = (baseHeight / clampedZoom).roundToInt().coerceIn(1, sensorHeight)

            val maxOffsetX = (sensorWidth - cropWidth).coerceAtLeast(0)
            val maxOffsetY = (sensorHeight - cropHeight).coerceAtLeast(0)

            val normX = (clampedPan + 1.0f) / 2.0f
            val left = (normX * maxOffsetX).roundToInt().coerceIn(0, maxOffsetX)
            val right = left + cropWidth

            val normY = (1.0f - clampedTilt) / 2.0f
            val top = (normY * maxOffsetY).roundToInt().coerceIn(0, maxOffsetY)
            val bottom = top + cropHeight

            return CropRect(left, top, right, bottom)
        }
    }

    var currentPan: Float = 0.0f
        private set
    var currentTilt: Float = 0.0f
        private set
    var currentZoom: Float = 1.0f
        private set

    var targetPan: Float = 0.0f
        private set
    var targetTilt: Float = 0.0f
        private set
    var targetZoom: Float = 1.0f
        private set

    val isAnimating: Boolean
        get() = currentPan != targetPan || currentTilt != targetTilt || currentZoom != targetZoom

    fun setTarget(
        pan: Float = targetPan,
        tilt: Float = targetTilt,
        zoom: Float = targetZoom,
        applyDeadband: Boolean = true
    ): Boolean {
        val clampedPan = pan.coerceIn(MIN_PAN, MAX_PAN)
        val clampedTilt = tilt.coerceIn(MIN_TILT, MAX_TILT)
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

        var changed = false
        if (!applyDeadband || abs(clampedPan - targetPan) >= deadbandThreshold) {
            targetPan = clampedPan
            changed = true
        }
        if (!applyDeadband || abs(clampedTilt - targetTilt) >= deadbandThreshold) {
            targetTilt = clampedTilt
            changed = true
        }
        if (!applyDeadband || abs(clampedZoom - targetZoom) >= deadbandThreshold) {
            targetZoom = clampedZoom
            changed = true
        }
        return changed
    }

    fun setPan(pan: Float, applyDeadband: Boolean = true): Boolean =
        setTarget(pan = pan, applyDeadband = applyDeadband)

    fun setTilt(tilt: Float, applyDeadband: Boolean = true): Boolean =
        setTarget(tilt = tilt, applyDeadband = applyDeadband)

    fun setZoom(zoom: Float, applyDeadband: Boolean = true): Boolean =
        setTarget(zoom = zoom, applyDeadband = applyDeadband)

    fun reset(pan: Float = 0.0f, tilt: Float = 0.0f, zoom: Float = 1.0f) {
        val clampedPan = pan.coerceIn(MIN_PAN, MAX_PAN)
        val clampedTilt = tilt.coerceIn(MIN_TILT, MAX_TILT)
        val clampedZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        targetPan = clampedPan
        targetTilt = clampedTilt
        targetZoom = clampedZoom
        currentPan = clampedPan
        currentTilt = clampedTilt
        currentZoom = clampedZoom
    }

    fun update(): Boolean {
        if (!isAnimating) return false

        currentPan = lerp(currentPan, targetPan, lerpFactor)
        currentTilt = lerp(currentTilt, targetTilt, lerpFactor)
        currentZoom = lerp(currentZoom, targetZoom, lerpFactor)

        if (abs(targetPan - currentPan) < CONVERGENCE_EPSILON) currentPan = targetPan
        if (abs(targetTilt - currentTilt) < CONVERGENCE_EPSILON) currentTilt = targetTilt
        if (abs(targetZoom - currentZoom) < CONVERGENCE_EPSILON) currentZoom = targetZoom

        return true
    }

    fun tick(): Boolean = update()

    fun calculateCropRect(
        sensorWidth: Int,
        sensorHeight: Int,
        targetAspectRatio: Float = 16f / 9f,
        useCurrentPosition: Boolean = true
    ): CropRect {
        val pan = if (useCurrentPosition) currentPan else targetPan
        val tilt = if (useCurrentPosition) currentTilt else targetTilt
        val zoom = if (useCurrentPosition) currentZoom else targetZoom
        return computeCropRect(sensorWidth, sensorHeight, pan, tilt, zoom, targetAspectRatio)
    }

    fun autoFrame(box: NormalizedBoundingBox, applyDeadband: Boolean = true): Boolean =
        autoFrame(listOf(box), applyDeadband)

    fun autoFrame(
        ymin: Float,
        xmin: Float,
        ymax: Float,
        xmax: Float,
        applyDeadband: Boolean = true
    ): Boolean = autoFrame(NormalizedBoundingBox(ymin, xmin, ymax, xmax), applyDeadband)

    fun autoFrame(boxes: List<NormalizedBoundingBox>, applyDeadband: Boolean = true): Boolean {
        if (boxes.isEmpty()) return false

        val unionXmin = boxes.minOf { it.xmin }.coerceIn(0.0f, 1.0f)
        val unionYmin = boxes.minOf { it.ymin }.coerceIn(0.0f, 1.0f)
        val unionXmax = boxes.maxOf { it.xmax }.coerceIn(0.0f, 1.0f)
        val unionYmax = boxes.maxOf { it.ymax }.coerceIn(0.0f, 1.0f)

        val boxWidth = (unionXmax - unionXmin).coerceAtLeast(0.01f)
        val boxHeight = (unionYmax - unionYmin).coerceAtLeast(0.01f)
        val boxCenterX = (unionXmin + unionXmax) / 2.0f

        val candidatePan = ((boxCenterX - 0.5f) * 2.0f).coerceIn(MIN_PAN, MAX_PAN)

        val desiredZoomX = targetFill / boxWidth
        val desiredZoomY = targetFill / boxHeight
        val candidateZoom = minOf(desiredZoomX, desiredZoomY).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val cropHeightNorm = 1.0f / candidateZoom
        val cropCenterY = unionYmin + (0.5f - headroomFraction) * cropHeightNorm
        val candidateTilt = ((0.5f - cropCenterY) * 2.0f).coerceIn(MIN_TILT, MAX_TILT)

        return setTarget(candidatePan, candidateTilt, candidateZoom, applyDeadband = applyDeadband)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)
}
