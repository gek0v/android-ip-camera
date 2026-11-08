package com.github.digitallyrefined.androidipcamera.helpers

import android.os.Build

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Global/modern backend (CameraX). The live stream is fed from ImageAnalysis (YUV → encoder) — it
 * reliably honours the requested resolution (Preview-to-a-custom-Surface picks tiny sizes on some
 * legacy HALs, and Preview is screen-capped anyway). Full-res stills come from ImageCapture
 * (co-bound so no per-shot rebind); if the HAL never completes a takePicture, the freshest analysis
 * frame is encoded as a guaranteed fallback. Some HALs fail to configure any session containing a
 * still surface ("Unable to configure camera ... TimeoutException") — that wedge is detected by the
 * service via [hasProducedFrame] and it falls back to the Camera1 backend.
 * On a strong CPU this runs at the camera's frame rate; on weak/legacy chips the YUV→NV12 copy is
 * the bottleneck (~12fps) — which is why the service auto-prefers Camera1 on LEGACY hardware.
 *
 * [onFrame] is called for every analysis frame and MUST close the ImageProxy.
 */
class CameraXCapture(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val cameraId: String?,
    private val desired: Size,
    private val previewSurfaceProvider: Preview.SurfaceProvider? = null,
    private val encoderSurfaceProvider: Preview.SurfaceProvider? = null,
    private val onEncoderSurfaceFallback: (() -> Unit)? = null,
    private val targetFps: Int = 60,
    private val onFrame: (ImageProxy) -> Unit = {}
) : CaptureBackend {
    @Volatile override var width = desired.width; private set
    @Volatile override var height = desired.height; private set
    @Volatile override var ready = false; private set
    @Volatile var encoderSurfaceBound = false
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    // Always co-bound with the stream so a still snaps straight from the bound use case (no rebind).
    private var boundSelector: CameraSelector? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var secondaryAnalysisUseCase: ImageAnalysis? = null
    private var pipExec: ExecutorService? = null
    private var secNv21Buffer: ByteArray? = null
    private var secNv21RowBuffer: ByteArray? = null
    @Volatile private var onPipFrameCallback: ((ByteArray, Int, Int) -> Unit)? = null

    fun setPipFrameCallback(cb: ((ByteArray, Int, Int) -> Unit)?) {
        onPipFrameCallback = cb
    }

    private class Nv21BufferPool(private val poolSize: Int = 3) {
        private var buffers: Array<ByteArray>? = null
        private var bufferSize = 0
        private var nextIdx = 0

        @Synchronized
        fun acquire(size: Int): ByteArray {
            if (buffers == null || bufferSize != size) {
                buffers = Array(poolSize) { ByteArray(size) }
                bufferSize = size
                nextIdx = 0
            }
            val buf = buffers!![nextIdx]
            nextIdx = (nextIdx + 1) % poolSize
            return buf
        }
    }

    private val primaryBufferPool = Nv21BufferPool(3)
    private var primaryNv21RowBuffer: ByteArray? = null
    @Volatile private var onNv21FrameCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null

    fun setNv21FrameCallback(cb: ((ByteArray, Int, Int, Long) -> Unit)?) {
        onNv21FrameCallback = cb
    }

    fun hasConcurrentSupport(): Boolean = try {
        val available = provider?.availableConcurrentCameraInfos
        if (available != null) {
            available.isNotEmpty()
        } else {
            StreamingServerHelper.hasConcurrentCameraSupport(ctx)
        }
    } catch (_: Throwable) {
        StreamingServerHelper.hasConcurrentCameraSupport(ctx)
    }
    private var previewUseCase: Preview? = null
    private var encoderUseCase: Preview? = null
    @Volatile private var released = false
    /** True once the analysis stream has delivered at least one frame. The service uses this to spot
     *  a session that bound but never configured (no frames ever arrive) and fall back to Camera1. */
    @Volatile var hasProducedFrame = false
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var torchEnabled = false
    /** Whether the bound camera can drive the flash at all — auxiliary lenses (ultra-wide, depth)
     *  report FLASH_INFO_AVAILABLE=false on many multi-lens phones and enableTorch() then fails
     *  asynchronously, which a synchronous try/catch never sees. Lazy because it reads
     *  [logicalCameraId], declared below. */
    override val hasFlashUnit: Boolean by lazy {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            id != null && cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (_: Exception) { true }
    }
    // Cached camera controls, re-applied after a rebind (which replaces the camera control).
    @Volatile private var zoomRatio: Float? = null
    @Volatile private var exposureIndex: Int? = null
    // Cached manual focus (0f..1f) so it can be re-applied after an async (re)bind; null = autofocus.
    @Volatile private var manualFocus: Float? = null
    @Volatile private var cropRegion: android.graphics.Rect? = null
    override val sensorActiveArraySize: android.graphics.Rect? by lazy {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
                ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: cm.cameraIdList.firstOrNull()
            if (id != null) cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) else null
        } catch (_: Exception) { null }
    }
    private val main = ContextCompat.getMainExecutor(ctx)
    private val analysisExec = Executors.newSingleThreadExecutor()

    // Latest analysis frame in NV21, so a still can fall back to it when the HAL's takePicture()
    // stalls or the camera is mid-restart — a guaranteed JPEG beats a 503.
    private val nv21Lock = Any()
    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null
    @Volatile private var latestNv21: ByteArray? = null
    @Volatile private var latestNv21W = 0
    @Volatile private var latestNv21H = 0
    @Volatile private var latestNv21AtMs = 0L

    private val logicalCameraId: String? = cameraId?.substringBefore(':')
    private val physicalCameraId: String? = cameraId
        ?.substringAfter(':', "")
        ?.takeIf { it.isNotBlank() }

    @OptIn(ExperimentalCamera2Interop::class)
    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get(); provider = p
                // ImageAnalysis ignores a lone ResolutionStrategy unless a matching AspectRatioStrategy
                // is set too; PREFER_HIGHER lifts the analysis cap above the 640x480 default.
                val ratio = if (desired.width.toDouble() / desired.height >= 1.5) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                val isPixelOrTensor = Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                    Build.HARDWARE.contains("tensor", ignoreCase = true) ||
                    Build.HARDWARE.contains("gs201", ignoreCase = true) ||
                    Build.HARDWARE.contains("cloudripper", ignoreCase = true) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL.contains("tensor", ignoreCase = true))

                val selBuilder = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(desired, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                if (targetFps < 60 && !isPixelOrTensor) {
                    selBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                }
                val sel = selBuilder.build()
                // Pin every use case to a fixed rotation. Stream rotation comes only from the
                // per-camera rotate= control: the encoders ignore imageInfo.rotationDegrees and the
                // GL pipe ignores the surface's sensor transform, so this value must never follow
                // the display rotation.
                val aBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                val analysisInterop = Camera2Interop.Extender(aBuilder)
                applyCaptureOptimizations(analysisInterop)

                analysisUseCase = aBuilder.build()
                    .also { a -> a.setAnalyzer(analysisExec) { img ->
                        try {
                            val crop = try { img.cropRect } catch (_: Throwable) { null }
                            val cropW = if (crop != null && crop.right > crop.left) ((crop.right - crop.left) / 2) * 2 else img.width
                            val cropH = if (crop != null && crop.bottom > crop.top) ((crop.bottom - crop.top) / 2) * 2 else img.height
                            width = cropW; height = cropH
                            hasProducedFrame = true
                            val timestampNs = try { img.imageInfo.timestamp } catch (_: Throwable) { System.nanoTime() }

                            val cb = onNv21FrameCallback
                            if (cb != null) {
                                val reqSize = (cropW * cropH * 3) / 2
                                val nv21 = primaryBufferPool.acquire(reqSize)
                                convertYUV420toNV21(img, nv21, primaryNv21RowBuffer).also {
                                    if (primaryNv21RowBuffer == null || primaryNv21RowBuffer!!.size < img.planes[0].rowStride) {
                                        primaryNv21RowBuffer = ByteArray(img.planes[0].rowStride)
                                    }
                                }
                                synchronized(nv21Lock) {
                                    if (nv21Buffer == null || nv21Buffer!!.size != nv21.size) {
                                        nv21Buffer = ByteArray(nv21.size)
                                    }
                                    System.arraycopy(nv21, 0, nv21Buffer!!, 0, nv21.size)
                                    latestNv21 = nv21Buffer
                                    latestNv21W = cropW
                                    latestNv21H = cropH
                                    latestNv21AtMs = System.currentTimeMillis()
                                }
                                cb.invoke(nv21, cropW, cropH, timestampNs)
                            } else {
                                onFrame(img)
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "analyzer: ${e.message}")
                        } finally {
                            img.close()
                        }
                    } }
                // ImageCapture stays co-bound from start(): a still then snaps straight from the bound
                // use case, avoiding the per-shot unbind/rebind that hangs the session configuration on
                // some HALs. MINIMIZE_LATENCY captures from the repeating stream instead of asking the
                // HAL to reconfigure the session for a full-sensor still surface.
                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                physicalCameraId?.let { Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(it) }
                imageCapture = imageCaptureBuilder.build()
                previewSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    applyCaptureOptimizations(Camera2Interop.Extender(previewBuilder))
                    previewUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                encoderSurfaceProvider?.let { pv ->
                    val previewBuilder = Preview.Builder().setResolutionSelector(sel)
                        .setTargetRotation(Surface.ROTATION_0)
                    applyCaptureOptimizations(Camera2Interop.Extender(previewBuilder))
                    encoderUseCase = previewBuilder.build().also { it.setSurfaceProvider(pv) }
                }
                boundSelector = logicalCameraId?.let { requestedId ->
                    CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == requestedId }
                        }
                        .build()
                } ?: if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                rebind()
                ready = true
                Log.i(TAG, "bound desired ${desired.width}x${desired.height} front=$front cameraId=${cameraId ?: "default"}")
            } catch (e: Exception) { Log.e(TAG, "start: ${e.message}") }
        }, main)
    }

    /** (Re)bind the streaming use cases on the main thread. ImageCapture is always co-bound so a still
     *  can snap directly from the already-bound use case — never a per-shot unbind/rebind cycle (which
     *  hangs the session configuration on some HALs). */
    private fun rebind() {
        if (released) return
        val p = provider ?: return
        val sel = boundSelector ?: return
        val analysis = analysisUseCase ?: return

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val pipRequested = prefs.getBoolean("pip_enabled", false)
        val canDoConcurrent = pipRequested && hasConcurrentSupport()

        if (canDoConcurrent) {
            try {
                val availableCombos = p.availableConcurrentCameraInfos
                val chosenCombo = availableCombos.firstOrNull { combo ->
                    val hasFront = combo.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                    val hasBack = combo.any { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                    hasFront && hasBack
                } ?: availableCombos.firstOrNull()

                if (chosenCombo != null && chosenCombo.size >= 2) {
                    p.unbindAll()
                    val targetLensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    val primaryCamInfo = logicalCameraId?.let { reqId ->
                        chosenCombo.firstOrNull {
                            try { Camera2CameraInfo.from(it).cameraId == reqId } catch (_: Throwable) { false }
                        }
                    } ?: chosenCombo.firstOrNull { it.lensFacing == targetLensFacing }
                      ?: chosenCombo[0]

                    val secondaryCamInfo = chosenCombo.firstOrNull { it != primaryCamInfo }
                      ?: chosenCombo[1]

                    val primarySel = primaryCamInfo.cameraSelector
                    val secondarySel = secondaryCamInfo.cameraSelector

                    val primaryGroup = UseCaseGroup.Builder().addUseCase(analysis).apply {
                        previewUseCase?.let { addUseCase(it) }
                    }.build()

                    pipExec?.shutdown()
                    val newPipExec = Executors.newSingleThreadExecutor()
                    pipExec = newPipExec

                    val secAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                        .setTargetRotation(Surface.ROTATION_0)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also { sa ->
                            sa.setAnalyzer(newPipExec) { secImg ->
                                try {
                                    val crop = try { secImg.cropRect } catch (_: Throwable) { null }
                                    val cropW = if (crop != null && crop.right > crop.left) ((crop.right - crop.left) / 2) * 2 else secImg.width
                                    val cropH = if (crop != null && crop.bottom > crop.top) ((crop.bottom - crop.top) / 2) * 2 else secImg.height
                                    val nv21 = convertYUV420toNV21(secImg, secNv21Buffer, secNv21RowBuffer).also {
                                        secNv21Buffer = it
                                        if (secNv21RowBuffer == null || secNv21RowBuffer!!.size < secImg.planes[0].rowStride) {
                                            secNv21RowBuffer = ByteArray(secImg.planes[0].rowStride)
                                        }
                                    }
                                    onPipFrameCallback?.invoke(nv21, cropW, cropH)
                                } catch (e: Exception) {
                                    Log.e(TAG, "secAnalysis error: ${e.message}")
                                } finally {
                                    secImg.close()
                                }
                            }
                        }
                    secondaryAnalysisUseCase = secAnalysis
                    val secondaryGroup = UseCaseGroup.Builder().addUseCase(secAnalysis).build()

                    val primaryConfig = ConcurrentCamera.SingleCameraConfig(primarySel, primaryGroup, owner)
                    val secondaryConfig = ConcurrentCamera.SingleCameraConfig(secondarySel, secondaryGroup, owner)
                    val concurrentCamera = p.bindToLifecycle(listOf(primaryConfig, secondaryConfig))
                    camera = concurrentCamera.cameras.firstOrNull()
                    encoderSurfaceBound = false
                    Log.i(TAG, "Successfully bound Dual Concurrent Cameras for PiP mode: primary facing=${primaryCamInfo.lensFacing}, secondary facing=${secondaryCamInfo.lensFacing}")
                    reapplyControls()
                    return
                } else {
                    Log.w(TAG, "Concurrent camera combinations list is empty or insufficient: $availableCombos")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Concurrent camera binding failed, falling back to single camera: ${e.message}", e)
                try { pipExec?.shutdown() } catch (_: Exception) {}
                pipExec = null
                secondaryAnalysisUseCase = null
            }
        } else {
            try { pipExec?.shutdown() } catch (_: Exception) {}
            pipExec = null
            secondaryAnalysisUseCase = null
        }
        val cases = mutableListOf<androidx.camera.core.UseCase>()
        previewUseCase?.let { cases.add(it) }
        encoderUseCase?.let { cases.add(it) }
        cases.add(analysis)
        imageCapture?.let { cases.add(it) }
        p.unbindAll()
        // On slow HALs (e.g. Exynos 7420) the previous camera close() can take 400ms–6s.
        // bindToLifecycle() may fail if the HAL hasn't finished tearing down. Retry a few
        // times before falling back to degraded surface combinations.
        val maxRetries = 3
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                camera = p.bindToLifecycle(owner, sel, *cases.toTypedArray())
                encoderSurfaceBound = encoderUseCase != null && cases.any { it === encoderUseCase }
                lastException = null
                break
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    Log.w(TAG, "bindToLifecycle failed (attempt $attempt/$maxRetries): ${e.message}; retrying in 500ms...")
                    Thread.sleep(500)
                } else {
                    Log.e(TAG, "bindToLifecycle failed after $maxRetries attempts: ${e.message}")
                }
            }
        }
        if (lastException != null) {
            // Co-binding the encoder surface with a still stream can exceed some HALs' concurrent-stream
            // limit. Rather than lose the camera, shed the encoder surface / screen preview and keep
            // ImageAnalysis + ImageCapture (software YUV stream, full-res stills intact).
            if (encoderUseCase != null || previewUseCase != null) {
                Log.w(TAG, "Attempting fallback: dropping encoder surface / preview...")
                try {
                    encoderUseCase = null
                    previewUseCase = null
                    encoderSurfaceBound = false
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, analysis, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    onEncoderSurfaceFallback?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "fallback rebind failed: ${e.message}")
                    throw e
                }
            } else {
                // Last resort: a lone ImageCapture should always be bindable.
                Log.w(TAG, "Attempting fallback: binding ImageCapture alone...")
                try {
                    p.unbindAll()
                    camera = p.bindToLifecycle(owner, sel, imageCapture ?: throw RuntimeException("ImageCapture not initialized"))
                    encoderSurfaceBound = false
                } catch (e: Exception) {
                    Log.e(TAG, "ImageCapture-alone rebind failed: ${e.message}")
                    throw e
                }
            }
        }
        reapplyControls()
    }

    private fun reapplyControls() {
        val cc = camera?.cameraControl
        if (torchEnabled && hasFlashUnit) try { cc?.enableTorch(true) } catch (_: Exception) {}
        zoomRatio?.let { applyZoomWithRetry(it) }
        exposureIndex?.let { try { cc?.setExposureCompensationIndex(it) } catch (_: Exception) {} }
        applyCamera2Options()
    }

    /**
     * Apply a zoom ratio, retrying briefly on failure. Right after a (re)bind, CameraX's reported
     * zoom range (from CONTROL_ZOOM_RATIO_RANGE) can momentarily reject an otherwise-valid ratio —
     * this mostly bites sub-1.0x (ultra-wide) ratios, since >=1.0x is almost always within the
     * camera's default range and succeeds on the first try. Without this retry, a saved sub-1.0x
     * zoom would silently fail to restore on app startup.
     */
    private fun applyZoomWithRetry(ratio: Float, attemptsLeft: Int = 10) {
        val cc = camera?.cameraControl ?: return
        try {
            val future = cc.setZoomRatio(ratio)
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    if (attemptsLeft > 0) {
                        mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
                    } else {
                        Log.e(TAG, "zoom $ratio failed after retries: ${e.message}")
                    }
                }
            }, main)
        } catch (e: Exception) {
            if (attemptsLeft > 0) {
                mainHandler.postDelayed({ applyZoomWithRetry(ratio, attemptsLeft - 1) }, 150)
            } else {
                Log.e(TAG, "zoom $ratio rejected after retries: ${e.message}")
            }
        }
    }

    override fun captureStill(onJpeg: (ByteArray?) -> Unit) {
        val ic = imageCapture ?: return onJpeg(null)
        if (provider == null || released) return onJpeg(null)
        // ImageCapture stays bound from start(), so a still needs no rebind — per-shot unbind/rebind
        // cycles hang the session configuration on some HALs. Just snap from the bound use case.
        main.execute {
            if (released) return@execute onJpeg(null)
            var finished = false
            fun done(result: ByteArray?) {
                if (finished) return
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                onJpeg(result)
            }
            // Watchdog: if the HAL never calls back, fall back to the live analysis frame so the
            // snapshot still succeeds.
            mainHandler.postDelayed({
                if (!finished) {
                    Log.w(TAG, "capture timeout — falling back to analysis frame")
                    done(frameFallbackJpeg())
                }
            }, CAPTURE_TIMEOUT_MS)
            val shoot = Runnable {
                if (finished) return@Runnable
                ic.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val b = try {
                            val buf = image.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } catch (e: Exception) { Log.e(TAG, "read: ${e.message}"); null } finally { image.close() }
                        done(b)
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "capture: ${e.message}")
                        done(frameFallbackJpeg())
                    }
                })
            }
            // Wait for the re-applied zoom to actually take effect, else the still would be captured
            // at the reset (1.0x) zoom.
            val zoomFuture = zoomRatio?.let { r -> try { camera?.cameraControl?.setZoomRatio(r) } catch (_: Exception) { applyZoomWithRetry(r); null } }
            if (zoomFuture != null) zoomFuture.addListener(shoot, main) else shoot.run()
        }
    }

    /** JPEG encoded from the latest analysis frame (if still fresh), or null. Guarantees a snapshot
     *  whenever the camera is producing frames. */
    private fun frameFallbackJpeg(): ByteArray? {
        val nv: ByteArray; val w: Int; val h: Int
        synchronized(nv21Lock) {
            val cached = latestNv21 ?: run { Log.w(TAG, "fallback: no cached frame"); return null }
            val age = System.currentTimeMillis() - latestNv21AtMs
            if (age > FRAME_FALLBACK_MAX_AGE_MS) {
                Log.w(TAG, "fallback: cached frame stale (${age}ms)")
                return null
            }
            nv = cached.copyOf()   // copy under the lock so the analyzer can't overwrite mid-encode
            w = latestNv21W; h = latestNv21H
        }
        if (w == 0 || h == 0) return null
        return try {
            val quality = DeviceMemoryHelper.mjpegJpegQuality(ctx)
            convertNV21toJPEG(nv, w, h, quality)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "frame fallback OOM")
            try { System.gc() } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            Log.e(TAG, "frame fallback: ${e.message}")
            null
        }
    }

    override fun getTorch(): Boolean = torchEnabled
    override fun setTorch(on: Boolean) {
        // Lenses without a flash unit must report OFF even when asked for ON, so the service knows
        // to route the torch through a flash-capable camera instead.
        torchEnabled = on && hasFlashUnit
        if (on && !hasFlashUnit) return
        try {
            val future = camera?.cameraControl?.enableTorch(on) ?: return
            // enableTorch reports failure through the future, not by throwing — observe it or the
            // failure is completely silent and internal state drifts from the real LED.
            future.addListener({
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "enableTorch($on): ${e.message}")
                    if (on) torchEnabled = false
                }
            }, main)
        } catch (e: Exception) {
            Log.w(TAG, "enableTorch($on): ${e.message}")
            if (on) torchEnabled = false
        }
    }
    override fun setExposure(ev: Int) { exposureIndex = ev; try { camera?.cameraControl?.setExposureCompensationIndex(ev) } catch (_: Exception) {} }
    override fun setZoom(ratio: Float) { zoomRatio = ratio; applyZoomWithRetry(ratio) }
    @Volatile private var focusLocked: Boolean = false
    override fun isFocusLocked(): Boolean = focusLocked

    @OptIn(ExperimentalCamera2Interop::class)
    override fun triggerAutoFocus() {
        focusOnPoint(0.5f, 0.5f)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCamera2Options() {
        val cam = camera ?: return
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            var hasOptions = false
            val norm = manualFocus
            if (norm != null) {
                val minDist = try {
                    Camera2CameraInfo.from(cam.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                } catch (_: Exception) { 0f }
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE, norm * minDist)
                hasOptions = true
            }
            val crop = cropRegion
            if (crop != null) {
                builder.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, crop)
                hasOptions = true
            }
            if (hasOptions) {
                c2.captureRequestOptions = builder.build()
            } else {
                c2.clearCaptureRequestOptions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyCamera2Options: " + e.message)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun setCropRegion(rect: android.graphics.Rect?) {
        cropRegion = rect
        applyCamera2Options()
    }

    override fun focusOnPoint(x: Float, y: Float) {
        performFocusAndMetering(x, y, lock = false)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun focusAndLock(x: Float, y: Float) {
        performFocusAndMetering(x, y, lock = true)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun performFocusAndMetering(x: Float, y: Float, lock: Boolean) {
        try {
            val cam = camera ?: return
            manualFocus = null
            applyCamera2Options()
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            val builder = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            if (lock) {
                builder.disableAutoCancel()
                focusLocked = true
            } else {
                builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
                focusLocked = false
            }
            cam.cameraControl.startFocusAndMetering(builder.build())
        } catch (e: Exception) { Log.e(TAG, "performFocusAndMetering: ${e.message}") }
    }

    override fun unlockFocus() {
        focusLocked = false
        try {
            camera?.cameraControl?.cancelFocusAndMetering()
        } catch (e: Exception) { Log.e(TAG, "unlockFocus: ${e.message}") }
    }

    override fun setFocusMode(mode: String) {
        unlockFocus()
        when (mode.lowercase()) {
            "continuous" -> setManualFocus(-1f)
            "infinity" -> setManualFocus(0f)
            "macro" -> setManualFocus(1f)
            "auto" -> triggerAutoFocus()
        }
    }

    /**
     * Fixed manual focus via Camera2 interop. [distance] 0f..1f maps to LENS_FOCUS_DISTANCE
     * in diopters (0f = infinity, 1f = the lens' minimum focus distance / nearest). A negative
     * value clears the override and restores continuous autofocus.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    override fun setManualFocus(distance: Float) {
        val norm = if (distance < 0f) null else distance.coerceIn(0f, 1f)
        manualFocus = norm
        applyCamera2Options()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyCaptureOptimizations(interop: Camera2Interop.Extender<*>) {
        physicalCameraId?.let { interop.setPhysicalCameraId(it) }

        // 1. High-speed AE target FPS range targeting 60fps (or user target) when supported
        aeFpsRange(targetFps)?.let {
            interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            Log.i(TAG, "AE fps range $it (targetFps=$targetFps)")
        }

        // 2. Set capture intent to PREVIEW to eliminate exposure search delays & prevent HAL from dropping FPS
        interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW
        )

        // 3. Auto-exposure & auto-white-balance: lock into active auto modes without pre-flash sweeps
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // 4. Disable EIS to eliminate 60-120ms multi-frame queue delay and preserve native sensor FOV
        interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        try {
            interop.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        } catch (_: Exception) {}

        // 5. Fast single-pass ISP pipeline: disable multi-frame temporal noise reduction and face tracking DSP
        interop.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
        interop.setCaptureRequestOption(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED)

        // 6. Smooth continuous video auto-focus
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }

    /** Prefers AE target ranges with upper bound matching or exceeding targetFps (e.g. [30, 60] or [60, 60])
     *  so sensor operates at 60fps on supported hardware, falling back cleanly to 30fps. */
    private fun aeFpsRange(targetFps: Int = 60): Range<Int>? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = logicalCameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.first()
        val ranges = cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val target = targetFps.coerceAtLeast(30)
        val highFpsRanges = ranges.filter { it.upper >= target }
        if (highFpsRanges.isNotEmpty()) {
            highFpsRanges.firstOrNull { it.lower >= target }
                ?: highFpsRanges.maxByOrNull { it.lower }
        } else {
            ranges.firstOrNull { it.lower >= 30 && it.upper >= 30 }
                ?: ranges.filter { it.upper >= 30 }.maxByOrNull { it.lower }
                ?: ranges.maxWithOrNull(compareBy({ it.upper - it.lower }, { it.upper }))
        }
    } catch (e: Exception) { Log.e(TAG, "aeRange: ${e.message}"); null }

    override fun stop() {
        ready = false
        released = true               // block any in-flight capture callback from touching the camera
        mainHandler.removeCallbacksAndMessages(null)
        try { provider?.unbindAll() } catch (_: Exception) {}
        try { analysisExec.shutdown() } catch (_: Exception) {}
        try { pipExec?.shutdown() } catch (_: Exception) {}
        pipExec = null
        secondaryAnalysisUseCase = null
        provider = null; camera = null; imageCapture = null
    }

    companion object {
        private const val TAG = "CameraXCapture"
        /** Give a full-res takePicture this long before falling back to the analysis frame. */
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        /** Analysis-frame fallback is only served if the cached frame is at most this old. */
        private const val FRAME_FALLBACK_MAX_AGE_MS = 6_000L
    }
}
