package com.github.digitallyrefined.androidipcamera.helpers

/**
 * A live camera backend feeding the MJPEG streaming pipeline, plus a full-resolution still and controls.
 *  - CameraXCapture  — modern default (ImageAnalysis + ImageCapture).
 *  - Camera1Capture  — legacy fallback reaching 1920×1080 on old HALs where CameraX caps at 720p.
 */
interface CaptureBackend {
    val width: Int
    val height: Int
    val ready: Boolean get() = true
    /** Full-resolution JPEG; concurrent with the live stream where the hardware allows. */
    fun captureStill(onJpeg: (ByteArray?) -> Unit)
    val hasFlashUnit: Boolean get() = true
    fun getTorch(): Boolean
    fun setTorch(on: Boolean)
    fun setExposure(ev: Int)
    fun setZoom(ratio: Float)
    fun triggerAutoFocus()
    fun focusOnPoint(x: Float, y: Float) { triggerAutoFocus() }
    fun setFocusMode(mode: String) {}
    /**
     * Manual focus. [distance] in 0f..1f is a normalized fixed focus distance
     * (0f = far/infinity, 1f = nearest). A negative value returns the camera to
     * continuous autofocus.
     */
    fun setManualFocus(distance: Float)
    fun stop()
}
