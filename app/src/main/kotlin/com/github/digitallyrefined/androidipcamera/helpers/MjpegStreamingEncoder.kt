package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.preference.PreferenceManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra-low-latency MJPEG streaming encoder.
 * Handles JPEG compression, adaptive bitrate control, image transformations,
 * and immediate non-blocking distribution to HTTP MJPEG clients.
 */
class MjpegStreamingEncoder(
    private val context: Context,
    private var streamingServerHelper: StreamingServerHelper?,
    private val onLog: (String) -> Unit = {}
) : StreamingEncoder {
    companion object {
        private const val TAG = "MjpegStreamingEncoder"
        private val FRAME_HEADER_PREFIX = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".toByteArray(StandardCharsets.US_ASCII)
        private val CRLF_CRLF = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
    }

    // Keep capacity tight (1 or 2 max) for ultra-low latency - never accumulate stale frames
    private val writerQueueCapacity = DeviceMemoryHelper.mjpegWriterQueueCapacity(context)
    private val writerGeneration = AtomicLong()
    private val networkWriter = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(writerQueueCapacity),
        { runnable -> Thread(runnable, "MjpegNetworkWriter").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    private var nv21Buffer: ByteArray? = null
    private var nv21RowBuffer: ByteArray? = null
    private var rotatedNv21Buffer: ByteArray? = null
    private var mirroredNv21Buffer: ByteArray? = null

    // Closed-loop rate control for bitrate limit
    @Volatile private var dynamicQuality = 85
    @Volatile private var lastFrameBytes = 0

    private fun invalidatePendingWrites() {
        writerGeneration.incrementAndGet()
        networkWriter.queue.clear()
    }

    private fun prefStringWithFallback(prefs: android.content.SharedPreferences, vararg keys: String): String? {
        for (k in keys) {
            if (prefs.contains(k)) return prefs.getString(k, null)
        }
        return null
    }

    private var lastFrameTime = 0L
    private var captureRunning = false

    /**
     * Resolves JPEG quality based on user preferences and target bitrate.
     * When bitrate limit is set (in kbps), adjusts dynamic quality adaptively to stay within budget.
     */
    private fun resolveQuality(prefs: android.content.SharedPreferences, fps: Int): Int {
        val configuredQuality = prefs.getString("stream_quality", "85")?.toIntOrNull()
            ?: DeviceMemoryHelper.defaultJpegQuality(context)
        val bitrateKbps = prefs.getString("stream_bitrate", "0")?.toIntOrNull() ?: 0

        if (bitrateKbps <= 0) {
            // Unlimited bitrate: strictly honor configured quality (e.g. 95% for maximum quality)
            return configuredQuality.coerceIn(10, 100)
        }

        // Target bytes per frame = (bitrateKbps * 1000 / 8) / fps
        val targetFrameBytes = (bitrateKbps * 1000L / 8L) / fps.coerceAtLeast(1)

        if (lastFrameBytes > 0) {
            if (lastFrameBytes > targetFrameBytes * 1.05) {
                val step = if (lastFrameBytes > targetFrameBytes * 1.3) 4 else 2
                dynamicQuality = (dynamicQuality - step).coerceAtLeast(15)
            } else if (lastFrameBytes < targetFrameBytes * 0.85 && dynamicQuality < configuredQuality) {
                dynamicQuality = (dynamicQuality + 1).coerceAtMost(configuredQuality)
            }
        } else {
            dynamicQuality = configuredQuality
        }

        return dynamicQuality.coerceIn(10, 100)
    }

    override fun processFrame(image: ImageProxy) {
        if (!hasClients()) return
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
            val fpsCoerced = fps.coerceIn(1, 60)
            val delay = try { 1000L / fpsCoerced } catch (_: Exception) { 33L }
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastFrameTime < delay) return
            lastFrameTime = currentTime

            val camId = prefs.getString("camera_id", null)
            val rotation = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                when {
                    prefs.contains("camera_rotate_$camId") -> prefs.getInt("camera_rotate_$camId", 0)
                    prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
                    else -> 0
                }
            } else 0
            val totalRotation = ((rotation % 360) + 360) % 360
            val scaleFactor = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "stream_scale_$camId", "stream_scale_$phys")?.toFloatOrNull() ?: 1.0f
            } else 1.0f
            val contrastValue = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "camera_contrast_$camId", "camera_contrast_$phys")?.toIntOrNull() ?: 0
            } else 0
            val mirror = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "mirror_$camId", "mirror_$phys")?.toBoolean() ?: false
            } else false

            val nv21 = convertYUV420toNV21(image, nv21Buffer, nv21RowBuffer).also {
                nv21Buffer = it
                if (nv21RowBuffer == null || nv21RowBuffer!!.size < image.planes[0].rowStride) {
                    nv21RowBuffer = ByteArray(image.planes[0].rowStride)
                }
            }

            var workingNv21 = nv21
            var curW = image.width
            var curH = image.height

            // Native zero-bitmap rotation (saves ~25ms CPU time & 8MB RAM per frame, keeping Tensor cool)
            if (totalRotation != 0) {
                if (rotatedNv21Buffer == null || rotatedNv21Buffer!!.size != nv21.size) {
                    rotatedNv21Buffer = ByteArray(nv21.size)
                }
                val (newW, newH) = rotateNV21(workingNv21, curW, curH, totalRotation, rotatedNv21Buffer!!)
                workingNv21 = rotatedNv21Buffer!!
                curW = newW
                curH = newH
            }

            if (mirror) {
                if (mirroredNv21Buffer == null || mirroredNv21Buffer!!.size != nv21.size) {
                    mirroredNv21Buffer = ByteArray(nv21.size)
                }
                mirrorNV21(workingNv21, curW, curH, mirroredNv21Buffer!!)
                workingNv21 = mirroredNv21Buffer!!
            }

            val quality = resolveQuality(prefs, fpsCoerced)
            var jpegBytes = convertNV21toJPEG(workingNv21, curW, curH, quality)
            if (scaleFactor != 1.0f || contrastValue != 0) {
                jpegBytes = applyTransformations(jpegBytes, 0, scaleFactor, contrastValue, quality, false)
            }
            lastFrameBytes = jpegBytes.size
            broadcastJpeg(jpegBytes)
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("processFrame")
        } catch (e: Exception) {
            Log.e(TAG, "processFrame: ${e.message}")
        }
    }

    /** Camera1 preview callback path (NV21 bytes, no ImageProxy). */
    fun processNv21Frame(nv21: ByteArray, width: Int, height: Int) {
        if (!hasClients()) return
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val fps = prefs.getString("stream_fps", "30")?.toIntOrNull() ?: 30
            val fpsCoerced = fps.coerceIn(1, 60)
            val delay = try { 1000L / fpsCoerced } catch (_: Exception) { 33L }
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < delay) return
            lastFrameTime = currentTime

            val camId = prefs.getString("camera_id", null)
            val rotation = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                when {
                    prefs.contains("camera_rotate_$camId") -> prefs.getInt("camera_rotate_$camId", 0)
                    prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
                    else -> 0
                }
            } else 0
            val totalRotation = ((rotation % 360) + 360) % 360
            val scaleFactor = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "stream_scale_$camId", "stream_scale_$phys")?.toFloatOrNull() ?: 1.0f
            } else 1.0f
            val contrastValue = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "camera_contrast_$camId", "camera_contrast_$phys")?.toIntOrNull() ?: 0
            } else 0
            val mirror = if (camId != null) {
                val phys = camId.substringAfter(':', camId)
                prefStringWithFallback(prefs, "mirror_$camId", "mirror_$phys")?.toBoolean() ?: false
            } else false

            var workingNv21 = nv21
            var curW = width
            var curH = height

            if (totalRotation != 0) {
                if (rotatedNv21Buffer == null || rotatedNv21Buffer!!.size != nv21.size) {
                    rotatedNv21Buffer = ByteArray(nv21.size)
                }
                val (newW, newH) = rotateNV21(workingNv21, curW, curH, totalRotation, rotatedNv21Buffer!!)
                workingNv21 = rotatedNv21Buffer!!
                curW = newW
                curH = newH
            }

            if (mirror) {
                if (mirroredNv21Buffer == null || mirroredNv21Buffer!!.size != nv21.size) {
                    mirroredNv21Buffer = ByteArray(nv21.size)
                }
                mirrorNV21(workingNv21, curW, curH, mirroredNv21Buffer!!)
                workingNv21 = mirroredNv21Buffer!!
            }

            val quality = resolveQuality(prefs, fpsCoerced)
            var jpegBytes = convertNV21toJPEG(workingNv21, curW, curH, quality)
            if (scaleFactor != 1.0f || contrastValue != 0) {
                jpegBytes = applyTransformations(jpegBytes, 0, scaleFactor, contrastValue, quality, false)
            }
            lastFrameBytes = jpegBytes.size
            broadcastJpeg(jpegBytes)
        } catch (e: OutOfMemoryError) {
            handleOutOfMemory("processNv21Frame")
        } catch (e: Exception) {
            Log.e(TAG, "processNv21Frame: ${e.message}")
        }
    }

    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastJpegAtMs: Long = 0L
    fun lastFrame(): ByteArray? = lastJpeg

    fun lastFrameFresh(maxAgeMs: Long): ByteArray? {
        val jpeg = lastJpeg ?: return null
        val age = System.currentTimeMillis() - lastJpegAtMs
        return if (age in 0..maxAgeMs) jpeg else null
    }

    private fun broadcastJpeg(jpegBytes: ByteArray) {
        lastJpeg = jpegBytes
        lastJpegAtMs = System.currentTimeMillis()
        val helper = streamingServerHelper ?: return
        val clients = helper.getClients()
        if (clients.isEmpty()) return
        val generation = writerGeneration.get()

        // Ultra-low latency guarantee: drop stale waiting frame if writer is busy
        while (networkWriter.queue.remainingCapacity() == 0) {
            networkWriter.queue.poll()
        }

        try {
            networkWriter.execute {
                if (generation != writerGeneration.get()) return@execute
                val toRemove = mutableListOf<StreamingServerHelper.Client>()
                val lengthBytes = jpegBytes.size.toString().toByteArray(StandardCharsets.US_ASCII)

                clients.forEach { client ->
                    if (generation != writerGeneration.get()) return@forEach
                    try {
                        val os = client.outputStream
                        os.write(FRAME_HEADER_PREFIX)
                        os.write(lengthBytes)
                        os.write(CRLF_CRLF)
                        os.write(jpegBytes)
                        os.write(CRLF)
                        os.flush()
                        client.markWriteSuccess()
                    } catch (e: Exception) {
                        try { client.socket.close() } catch (_: Exception) {}
                        toRemove.add(client)
                    }
                }
                toRemove.forEach { helper.removeClient(it) }
            }
        } catch (_: RejectedExecutionException) {
            // Drop frame on backpressure to prevent latency build up
        }
    }

    override fun handleRemoteControl(key: String, value: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        return when (key) {
            "scale" -> {
                val scale = value.toFloatOrNull() ?: return false
                if (scale in 0.5f..2.0f) {
                    val camId = prefs.getString("camera_id", null)
                    camId?.let {
                        prefs.edit().putString("stream_scale_$it", value).apply()
                        val phys = it.substringAfter(':', it)
                        if (phys.isNotBlank() && phys != it) prefs.edit().putString("stream_scale_$phys", value).apply()
                    }
                    true
                } else false
            }
            "fps" -> {
                val fps = value.toIntOrNull() ?: return false
                if (fps in 1..60) {
                    prefs.edit().putString("stream_fps", value).apply()
                    true
                } else false
            }
            "quality" -> {
                val q = value.toIntOrNull() ?: return false
                if (q in 10..100) {
                    prefs.edit().putString("stream_quality", value).apply()
                    dynamicQuality = q
                    true
                } else false
            }
            "bitrate" -> {
                val b = value.toIntOrNull() ?: return false
                if (b >= 0) {
                    prefs.edit().putString("stream_bitrate", value).apply()
                    true
                } else false
            }
            "audio_gain" -> {
                val gain = value.toFloatOrNull() ?: return false
                if (gain in 0.5f..3.0f) {
                    prefs.edit().putString("audio_gain", gain.toString()).apply()
                    true
                } else false
            }
            "contrast" -> {
                val contrast = value.toIntOrNull() ?: return false
                val camId = prefs.getString("camera_id", null)
                camId?.let {
                    prefs.edit().putString("camera_contrast_$it", contrast.toString()).apply()
                    val phys = it.substringAfter(':', it)
                    if (phys.isNotBlank() && phys != it) prefs.edit().putString("camera_contrast_$phys", contrast.toString()).apply()
                }
                true
            }
            "mirror" -> {
                if (value in listOf("true", "false")) {
                    val camId = prefs.getString("camera_id", null)
                    camId?.let {
                        prefs.edit().putString("mirror_$it", value).apply()
                        val phys = it.substringAfter(':', it)
                        if (phys.isNotBlank() && phys != it) prefs.edit().putString("mirror_$phys", value).apply()
                    }
                    true
                } else false
            }
            else -> false
        }
    }

    override fun start() {
        captureRunning = true
    }

    override fun stop() {
        captureRunning = false
        invalidatePendingWrites()
    }

    override fun hasClients(): Boolean {
        return streamingServerHelper?.getClients()?.isNotEmpty() == true
    }

    fun onMemoryPressure(severe: Boolean) {
        invalidatePendingWrites()
        lastJpeg = null
        nv21Buffer = null
        nv21RowBuffer = null
        rotatedNv21Buffer = null
        mirroredNv21Buffer = null
        if (severe) {
            DeviceMemoryHelper.updateMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
    }

    private fun handleOutOfMemory(source: String) {
        Log.e(TAG, "Out of memory in $source")
        onMemoryPressure(severe = true)
        try {
            System.gc()
        } catch (_: Exception) {}
    }

    private fun applyTransformations(
        jpegBytes: ByteArray,
        rotation: Int,
        scaleFactor: Float,
        contrastValue: Int,
        jpegQuality: Int,
        mirror: Boolean = false
    ): ByteArray {
        var bitmap: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options) ?: return jpegBytes

            val matrix = Matrix()
            if (mirror) matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            if (scaleFactor != 1.0f) matrix.postScale(scaleFactor, scaleFactor)

            var transformedBitmap = if (!matrix.isIdentity) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            if (contrastValue != 0 && !DeviceMemoryHelper.isUnderMemoryPressure()) {
                val contrastFactor = 1.0f + (contrastValue / 100.0f)
                val colorMatrix = android.graphics.ColorMatrix().apply {
                    set(floatArrayOf(
                        contrastFactor, 0f, 0f, 0f, 0f,
                        0f, contrastFactor, 0f, 0f, 0f,
                        0f, 0f, contrastFactor, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }
                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
                }
                val contrasted = Bitmap.createBitmap(
                    transformedBitmap.width, transformedBitmap.height, Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(contrasted)
                canvas.drawBitmap(transformedBitmap, 0f, 0f, paint)
                if (transformedBitmap !== bitmap) transformedBitmap.recycle()
                transformedBitmap = contrasted
            }

            val outStream = ByteArrayOutputStream()
            transformedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outStream)
            val result = outStream.toByteArray()

            if (transformedBitmap !== bitmap) transformedBitmap.recycle()
            bitmap.recycle()
            return result
        } catch (e: OutOfMemoryError) {
            bitmap?.recycle()
            handleOutOfMemory("applyTransformations")
            return jpegBytes
        } catch (e: Exception) {
            bitmap?.recycle()
            Log.e(TAG, "applyTransformations error: ${e.message}")
            return jpegBytes
        }
    }
}
