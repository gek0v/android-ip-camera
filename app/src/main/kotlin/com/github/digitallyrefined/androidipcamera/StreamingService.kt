package com.github.digitallyrefined.androidipcamera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.helpers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ultra-low-latency MJPEG Streaming Service.
 * Supports CameraX (default modern backend) and Camera1 (legacy hardware fallback),
 * full resolution still snapshots on demand, and immediate MJPEG streaming.
 */
@Suppress("DEPRECATION")
class StreamingService : LifecycleService() {

    private val binder = LocalBinder()
    var streamingServerHelper: StreamingServerHelper? = null

    /** Motion detection over the capture frames already delivered to the streaming pipeline. */
    private var motionMonitor: MotionMonitor? = null

    // Streaming encoder (MJPEG)
    private var mjpegStreamingEncoder: MjpegStreamingEncoder? = null
    private val encoders: List<StreamingEncoder>
        get() = listOfNotNull(mjpegStreamingEncoder)

    private fun motionEnabled(): Boolean = motionMonitor?.enabled == true

    @Volatile private var backend: CaptureBackend? = null
    @Volatile private var captureRunning = false
    @Volatile private var cameraHeldForTorch = false
    @Volatile private var deviceTorchOn = false

    @Volatile private var frontFacing = false
    @Volatile private var selectedCameraId: String? = null

    private var currentSurfaceProvider: Preview.SurfaceProvider? = null
    @Volatile private var snapshotInProgress = false

    private data class CachedSnapshot(val jpeg: ByteArray, val atMs: Long)
    private val snapCache = ConcurrentHashMap<String, CachedSnapshot>()
    private val snapLock = Any()
    private val cameraMutex = Mutex()
    private var pendingStartJob: kotlinx.coroutines.Job? = null
    private var torchVerifyJob: kotlinx.coroutines.Job? = null
    @Volatile private var cameraCloseStartTime: Long = 0L

    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val notificationChannelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                intent?.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED) {
                val channelId = intent.getStringExtra(NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID)
                if (channelId == CHANNEL_ID) {
                    val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
                    if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) handleStopService()
                }
            }
        }
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "streaming_service_channel"
        private const val PREF_CAMERA_ID = "camera_id"
        private const val PREF_CAMERA_TORCH = "camera_torch"
        private const val SNAPSHOT_DEADLINE_MS = 12_000L
        private const val SNAPSHOT_CACHE_MAX_AGE_MS = 5_000L
        private const val SNAPSHOT_STREAM_FRESH_MS = 3_000L
        private const val CAMERAX_FRAME_GRACE_MS = 8_000L
        const val ACTION_STOP_SERVICE = "com.github.digitallyrefined.androidipcamera.STOP_SERVICE"
        const val ACTION_START_SERVER = "com.github.digitallyrefined.androidipcamera.START_SERVER"
        const val ACTION_RESTART_NOTIFICATION = "com.github.digitallyrefined.androidipcamera.RESTART_NOTIFICATION"
        const val ACTION_RESTART_SERVER = "com.github.digitallyrefined.androidipcamera.RESTART_SERVER"
    }

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val filter = IntentFilter(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            registerReceiver(notificationChannelReceiver, filter)
        }
        startStreamingServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_SERVICE -> handleStopService()
            ACTION_START_SERVER -> startStreamingServer()
            ACTION_RESTART_NOTIFICATION -> startForegroundService()
            ACTION_RESTART_SERVER -> {
                Log.i(TAG, "Restarting server due to configuration change")
                lifecycleScope.launch(Dispatchers.IO) {
                    streamingServerHelper?.stopStreamingServer()
                    launchMain {
                        stopCamera()
                        bridgeTorchAcrossIdle()
                        startStreamingServer()
                    }
                }
            }
            "RESTART_STREAMING" -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    streamingServerHelper?.stopStreamingServer()
                    launchMain {
                        stopCamera()
                        bridgeTorchAcrossIdle()
                        startStreamingServer()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { unregisterReceiver(notificationChannelReceiver) } catch (_: Exception) {}
        }
        torchVerifyJob?.cancel(); torchVerifyJob = null
        pendingStartJob?.cancel(); pendingStartJob = null
        if (deviceTorchOn) setDeviceTorch(false)
        stopCamera()
        lifecycleScope.launch(Dispatchers.IO) {
            try { streamingServerHelper?.stopStreamingServer() } catch (e: Exception) { Log.e(TAG, "server stop: ${e.message}") }
            streamingServerHelper = null
        }
    }

    private fun handleStopService() {
        stopForeground(true)
        stopSelf()
        sendBroadcast(Intent("com.github.digitallyrefined.androidipcamera.CLOSE_APP"))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Camera Streaming Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps camera streaming active in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPI = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPI = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val restartIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_RESTART_NOTIFICATION }
        val restartPI = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android IP Camera Streaming")
            .setContentText("Camera server is running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(contentPI)
            .addAction(R.drawable.ic_notification, "Exit App", stopPI)
            .setDeleteIntent(restartPI)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    fun setPreviewSurface(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        val shouldRun = encoders.any { it.hasClients() } || currentSurfaceProvider != null || motionEnabled()
        if (shouldRun) {
            debouncedStartCamera()
        } else {
            launchMain {
                stopCamera()
                bridgeTorchAcrossIdle()
            }
        }
    }

    fun isCameraRunning() = captureRunning
    fun hasActiveClients() = encoders.any { it.hasClients() } || motionEnabled()

    fun switchCamera() {
        selectCamera(!frontFacing, null)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(PREF_CAMERA_ID, camId())
            .apply()
        if (captureRunning) debouncedStartCamera(force = true)
    }

    fun getLocalIpAddress(): String {
        ListenInterface.specificIpv4(this)?.let { return it }
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
                ni.inetAddresses.toList().forEach { a ->
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "unknown"
                }
            }
        } catch (_: Exception) {}
        return "unknown"
    }

    fun startStreamingServer() {
        try {
            val secureStorage = SecureStorage(this)
            if (CertificateHelper.certificateExists(this)) {
                if (secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, null).isNullOrEmpty()) {
                    File(filesDir, "personal_certificate.p12").let { if (it.exists()) it.delete() }
                    generateCertificateAndStart()
                } else initServer()
            } else generateCertificateAndStart()
        } catch (e: Exception) { Log.e(TAG, "start server: ${e.message}") }
    }

    private fun generateCertificateAndStart() {
        val pw = generateRandomPassword()
        lifecycleScope.launch(Dispatchers.IO) {
            val certFile = CertificateHelper.generateCertificate(this@StreamingService, pw)
            if (certFile != null) {
                SecureStorage(this@StreamingService).putSecureString(SecureStorage.KEY_CERT_PASSWORD, pw)
                PreferenceManager.getDefaultSharedPreferences(this@StreamingService).edit().remove("certificate_path").apply()
                kotlinx.coroutines.delay(100)
                initServer()
            } else launch(Dispatchers.Main) {
                Toast.makeText(this@StreamingService, "Failed to generate certificate", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initServer() {
        if (streamingServerHelper == null) {
            streamingServerHelper = StreamingServerHelper(
                this,
                onLog = { Log.i(TAG, "Server: $it"); onLog?.invoke(it) },
                onClientConnected = { handleClientConnected() },
                onClientDisconnected = { handleClientDisconnected() },
                onControlCommand = { key, value, ts -> handleRemoteControl(key, value, ts) },
                onSnapshot = { id -> snapshot(id) },
                onStreamingEnabledChanged = { enabled ->
                    if (!enabled) streamingServerHelper?.closeClientConnection()
                }
            )
            mjpegStreamingEncoder = MjpegStreamingEncoder(this, streamingServerHelper!!) { Log.i(TAG, "MJPEG: $it"); onLog?.invoke(it) }
            motionMonitor = MotionMonitor(this) { active ->
                Log.i(TAG, "Motion ${if (active) "started" else "ended"}")
            }
        }
        streamingServerHelper?.startStreamingServer()
        if (motionEnabled()) launchMain { startCameraIfNeeded() }
    }

    private fun generateRandomPassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun handleClientConnected() {
        onClientConnected?.invoke()
        launchMain { startCameraIfNeeded() }
    }

    private fun handleClientDisconnected() {
        onClientDisconnected?.invoke()
        launchMain {
            if (!hasActiveClients() && currentSurfaceProvider == null) {
                stopCamera()
                bridgeTorchAcrossIdle()
            }
        }
    }

    private fun launchMain(block: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isActive) block()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val baseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return baseGranted && postNotificationsGranted
    }

    private fun chooseApi(): String =
        when (val pref = PreferenceManager.getDefaultSharedPreferences(this).getString("capture_api", "auto") ?: "auto") {
            "camerax", "camera1" -> pref
            else -> if (isLegacy()) "camera1" else "camerax"
        }

    private fun isLegacy(): Boolean =
        cameraHardwareLevel(selectedCameraId) == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    private fun cameraHardwareLevel(cameraId: String?): Int? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (frontFacing) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cameraId?.takeIf { it in cm.cameraIdList }
            ?: cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: cm.cameraIdList.firstOrNull()
        id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) }
    } catch (_: Exception) { null }

    private fun desiredSize(): Size {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val streamRes = prefs.getString("stream_res", "auto") ?: "auto"
        if (streamRes == "auto") {
            val quality = prefs.getString("camera_resolution", "high") ?: "high"
            return when (quality) {
                "high" -> Size(1920, 1080)
                "medium" -> Size(1280, 720)
                "low" -> Size(800, 600)
                else -> Size(1280, 720)
            }
        }
        val m = Regex("(\\d+)x(\\d+)").find(streamRes)
        val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 1280
        val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 720
        return Size(w, h)
    }

    private suspend fun startCameraIfNeeded() { if (!captureRunning) startCamera() }

    private fun debouncedStartCamera(force: Boolean = false, delayMs: Long = 300) {
        if (force) {
            pendingStartJob?.cancel()
            pendingStartJob = null
            launchMain { startCamera(force = true) }
            return
        }
        pendingStartJob?.cancel()
        pendingStartJob = lifecycleScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(delayMs)
            startCamera()
        }
    }

    private suspend fun startCamera(force: Boolean = false) = cameraMutex.withLock {
        Log.i(TAG, "startCamera() called. force=$force, selectedCameraId=$selectedCameraId, frontFacing=$frontFacing, currentSurfaceProvider=$currentSurfaceProvider, captureRunning=$captureRunning")
        if (!allPermissionsGranted()) {
            Log.w(TAG, "startCamera bypassed: permissions not granted")
            return@withLock
        }
        val hasClients = encoders.any { it.hasClients() }
        if (!force && !hasClients && currentSurfaceProvider == null && !motionEnabled()) {
            Log.i(TAG, "startCamera bypassed: no force, no clients, and preview surface is null")
            return@withLock
        }

        val resumeTorchHold = cameraHeldForTorch
        cameraHeldForTorch = false
        stopCamera()

        try {
            val want = desiredSize()
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            if (selectedCameraId == null) {
                firstCameraIdForFacing(frontFacing)?.let { resolved ->
                    selectedCameraId = resolved
                    prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
                }
            }
            val camxUnusable = prefs.getBoolean("camera2_unusable", false)
            val needsFrames = snapshotInProgress || encoders.any { it.hasClients() } || motionEnabled()
            val useCamera1 = needsFrames && (chooseApi() == "camera1" || camxUnusable)

            if (useCamera1) {
                startCamera1Backend(want)
            } else {
                startCameraXBackend(want)
                if (needsFrames && !waitForCameraXFrame()) {
                    Log.w(TAG, "CameraX bound but no frames within ${CAMERAX_FRAME_GRACE_MS}ms — falling back to Camera1")
                    stopCamera()
                    prefs.edit().putBoolean("camera2_unusable", true).apply()
                    startCamera1Backend(want)
                }
            }

            try {
                prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
            } catch (_: Exception) {}

            captureRunning = true
            encoders.forEach { it.start() }

            if (!hasActiveClients() && currentSurfaceProvider == null && resumeTorchHold) {
                cameraHeldForTorch = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startCamera: ${e.message}")
            stopCamera()
        }
    }

    private suspend fun waitForCameraXFrame(): Boolean {
        val cx = backend as? CameraXCapture ?: return true
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < CAMERAX_FRAME_GRACE_MS) {
            if (cx.hasProducedFrame) return true
            kotlinx.coroutines.delay(100)
        }
        return cx.hasProducedFrame
    }

    private fun startCamera1Backend(want: Size) {
        val cap = Camera1Capture(camera1IndexForSelectedOrFacing(frontFacing), want.width, want.height)
        val dummyTexture = SurfaceTexture(0)
        cap.start(dummyTexture)
        val mjpeg = mjpegStreamingEncoder
        val motion = motionMonitor?.takeIf { it.enabled }
        cap.setPreviewFrameCallback { nv21 ->
            mjpeg?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
            motion?.processNv21Frame(nv21, cap.chosenW, cap.chosenH)
        }
        backend = cap
        applyStored(cap)
        Log.i(TAG, "stream ${camId()} api=camera1 ${cap.chosenW}x${cap.chosenH}")
    }

    private fun startCameraXBackend(want: Size) {
        val showScreenPreview = !encoders.any { it.hasClients() } && currentSurfaceProvider != null
        val cx = CameraXCapture(
            this, this, frontFacing, selectedCameraId, want,
            if (showScreenPreview) currentSurfaceProvider else null
        ) { img ->
            try {
                motionMonitor?.processFrame(img)
                if (mjpegStreamingEncoder?.hasClients() == true) {
                    mjpegStreamingEncoder?.processFrame(img)
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "ImageAnalysis OOM — dropping frame: ${e.message}")
                DeviceMemoryHelper.updateMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                try { System.gc() } catch (_: Exception) {}
            } finally {
                img.close()
            }
        }.also {
            it.setPipFrameCallback { nv21, w, h ->
                mjpegStreamingEncoder?.updatePipFrame(nv21, w, h)
            }
            it.start()
        }
        backend = cx
        applyStored(cx)
        Log.i(TAG, "stream ${camId()} api=camerax")
    }

    private fun stopCamera() {
        if (cameraHeldForTorch) return
        cameraCloseStartTime = System.currentTimeMillis()
        backend?.stop(); backend = null
        val closeDuration = System.currentTimeMillis() - cameraCloseStartTime
        if (closeDuration > 1000) {
            Log.w(TAG, "Camera close took ${closeDuration}ms")
        }
        encoders.forEach { it.stop() }
        motionMonitor?.reset()
        captureRunning = false
    }

    private fun readRotatePref(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val id = camId() ?: return 0
        val phys = id.substringAfter(':', id)
        return when {
            prefs.contains("camera_rotate_$id") -> prefs.getInt("camera_rotate_$id", 0)
            prefs.contains("camera_rotate_$phys") -> prefs.getInt("camera_rotate_$phys", 0)
            else -> 0
        }
    }

    private fun applyStored(b: CaptureBackend) {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        val wantTorch = p.getString(PREF_CAMERA_TORCH, "off") == "on"
        if (b.hasFlashUnit && wantTorch) b.setTorch(true)
        val id = camId() ?: return
        val phys = id.substringAfter(':', id)
        val zoom = when {
            p.contains("zoom_$id") -> p.getString("zoom_$id", null)
            p.contains("zoom_$phys") -> p.getString("zoom_$phys", null)
            else -> null
        }
        zoom?.toFloatOrNull()?.let { b.setZoom(it) }
        val exp = when {
            p.contains("exposure_$id") -> p.getString("exposure_$id", null)
            p.contains("exposure_$phys") -> p.getString("exposure_$phys", null)
            else -> null
        }
        exp?.toIntOrNull()?.let { b.setExposure(it) }
        val focusMode = p.getString("focus_mode", "continuous") ?: "continuous"
        b.setFocusMode(focusMode)
        if (focusMode == "manual") {
            val focus = when {
                p.contains("focus_$id") -> p.getString("focus_$id", null)
                p.contains("focus_$phys") -> p.getString("focus_$phys", null)
                else -> null
            }
            focus?.toFloatOrNull()?.let { b.setManualFocus(it) }
        }
    }

    private fun camId(): String? =
        selectedCameraId ?: if (frontFacing) "front" else "back"

    private fun selectCamera(front: Boolean, id: String?) {
        frontFacing = front
        selectedCameraId = id
    }

    private fun firstCameraIdForFacing(front: Boolean): String? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
    } catch (_: Exception) { null }

    private fun camera1IndexForSelectedOrFacing(front: Boolean): Int {
        val sel = selectedCameraId
        val physOrLogical = sel?.substringAfter(':', sel)?.toIntOrNull()
            ?: sel?.substringBefore(':')?.toIntOrNull()
        if (physOrLogical != null && physOrLogical in 0 until Camera.getNumberOfCameras()) return physOrLogical
        val info = Camera.CameraInfo()
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == want) return i
        }
        return 0
    }

    private fun setDeviceTorch(on: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val id = cm.cameraIdList.firstOrNull {
            try {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
            } catch (_: Exception) { false }
        } ?: return
        try {
            cm.setTorchMode(id, on)
            deviceTorchOn = on
        } catch (_: Exception) {}
    }

    private fun bridgeTorchAcrossIdle() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getString(PREF_CAMERA_TORCH, "off") == "on") {
            setDeviceTorch(true)
        }
    }

    fun snapshot(cameraId: String? = null): ByteArray? {
        val target = if (cameraId == null || cameraId == "current") {
            frontFacing to selectedCameraId
        } else {
            resolveCamera(cameraId) ?: (frontFacing to selectedCameraId)
        }
        val isCurrent = target.first == frontFacing && target.second == selectedCameraId
        val key = target.second ?: if (target.first) "front" else "back"

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val snapshotRes = prefs.getString("snapshot_res_$key", null)
            ?: prefs.getString("snapshot_res_${key.substringAfter(':', key)}", "max")

        if (snapshotRes == "stream" && isCurrent) {
            val freshJpeg = mjpegStreamingEncoder?.lastFrameFresh(SNAPSHOT_STREAM_FRESH_MS)
            if (freshJpeg != null) return freshJpeg
        }

        synchronized(snapLock) {
            val cached = snapCache[key]
            if (cached != null && (System.currentTimeMillis() - cached.atMs) <= SNAPSHOT_CACHE_MAX_AGE_MS) {
                return cached.jpeg
            }

            val savedFront = frontFacing
            val savedId = selectedCameraId
            snapshotInProgress = true
            val deadline = System.currentTimeMillis() + SNAPSHOT_DEADLINE_MS

            try {
                if (!isCurrent) {
                    selectCamera(target.first, target.second)
                    switchAndWait(force = true, deadline)
                } else if (!captureRunning) {
                    switchAndWait(force = true, deadline)
                }
                val b = backend ?: return null
                return captureFrom(b, key, deadline)
            } finally {
                snapshotInProgress = false
                if (!isCurrent) {
                    selectCamera(savedFront, savedId)
                    if (hasActiveClients() || currentSurfaceProvider != null) {
                        launchMain { startCamera(force = true) }
                    } else {
                        launchMain {
                            stopCamera()
                            bridgeTorchAcrossIdle()
                        }
                    }
                } else if (!hasActiveClients() && currentSurfaceProvider == null && !cameraHeldForTorch) {
                    launchMain {
                        stopCamera()
                        bridgeTorchAcrossIdle()
                    }
                }
            }
        }
    }

    private fun resolveCamera(token: String): Pair<Boolean, String?>? {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return when (token.lowercase()) {
            "back" -> false to firstCameraIdForFacing(false)
            "front" -> true to firstCameraIdForFacing(true)
            else -> try {
                val logicalId = token.substringBefore(':')
                val ch = cm.getCameraCharacteristics(logicalId)
                val facing = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                facing to token
            } catch (_: Exception) { null }
        }
    }

    private fun captureFrom(b: CaptureBackend, key: String, deadlineMs: Long): ByteArray? {
        var result: ByteArray? = null
        while (System.currentTimeMillis() < deadlineMs) {
            val target = backend ?: b
            val latch = CountDownLatch(1)
            val out = arrayOfNulls<ByteArray>(1)
            launchMain { target.captureStill { out[0] = it; latch.countDown() } }
            val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(100L)
            try { latch.await(remaining, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            result = out[0]?.let { rotateSnapshot(it, readRotatePref()) }
            if (result != null) break
            try { Thread.sleep(150) } catch (_: Exception) {}
        }
        result?.let {
            val maxSize = DeviceMemoryHelper.maxSnapshotBytes(this@StreamingService)
            if (it.size <= maxSize) {
                snapCache[key] = CachedSnapshot(it, System.currentTimeMillis())
            }
        }
        return result
    }

    private fun rotateSnapshot(jpeg: ByteArray, degrees: Int): ByteArray {
        val angle = ((degrees % 360) + 360) % 360
        if (angle == 0) return jpeg
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            try {
                val matrix = Matrix().apply { postRotate(angle.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                try {
                    if (rotated === bmp) return jpeg
                    val out = ByteArrayOutputStream(jpeg.size)
                    try {
                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        out.toByteArray()
                    } finally { out.close() }
                } finally { if (rotated !== bmp) rotated.recycle() }
            } finally { bmp.recycle() }
        } catch (_: Exception) { jpeg }
    }

    private fun switchAndWait(force: Boolean, deadlineMs: Long) {
        val l = CountDownLatch(1)
        launchMain { startCamera(force); l.countDown() }
        try { l.await((deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        var n = 0
        while (n < 40 && backend?.ready != true && System.currentTimeMillis() < deadlineMs) {
            try { Thread.sleep(100) } catch (_: Exception) {}
            n++
        }
        val convergeMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        if (convergeMs > 0) {
            try { Thread.sleep(minOf(convergeMs, if (frontFacing) 2000 else 600)) } catch (_: Exception) {}
        }
    }

    private fun acceptControl(key: String, ts: Long): Boolean {
        if (ts <= 0L) return true
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val last = prefs.getLong("last_ts_$key", 0L)
        if (ts <= last) return false
        prefs.edit().putLong("last_ts_$key", ts).apply()
        return true
    }

    private fun handleRemoteControl(key: String, value: String, ts: Long = 0L) {
        if (!acceptControl(key, ts)) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val storedCameraPref = prefs.getString(PREF_CAMERA_ID, null)
        val id = storedCameraPref ?: camId()
        val physicalId = id?.substringAfter(':', id) ?: ""

        when (key) {
            "torch" -> {
                val current = backend?.getTorch()
                    ?: (prefs.getString(PREF_CAMERA_TORCH, "off") == "on")
                val next = when (value.lowercase()) {
                    "on" -> true
                    "off" -> false
                    "toggle" -> !current
                    else -> return
                }
                prefs.edit().putString(PREF_CAMERA_TORCH, if (next) "on" else "off").apply()
                launchMain {
                    if (next && !captureRunning) {
                        cameraHeldForTorch = true
                        startCamera(force = true)
                    }
                    backend?.setTorch(next)
                    if (backend?.hasFlashUnit != true || !next) setDeviceTorch(next)
                    if (!next && cameraHeldForTorch) {
                        cameraHeldForTorch = false
                        if (!hasActiveClients() && currentSurfaceProvider == null) stopCamera()
                    }
                }
            }
            "exposure" -> {
                val ev = value.toIntOrNull() ?: return
                prefs.edit().putString("exposure_$id", ev.toString()).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("exposure_$physicalId", ev.toString()).apply()
                launchMain { backend?.setExposure(ev) }
            }
            "zoom" -> {
                val z = value.toFloatOrNull() ?: return
                val zStr = String.format(Locale.US, "%.1f", z)
                prefs.edit().putString("zoom_$id", zStr).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("zoom_$physicalId", zStr).apply()
                launchMain { backend?.setZoom(z) }
            }
            "focus_distance" -> {
                val f = value.toFloatOrNull() ?: return
                if (f < 0f) {
                    prefs.edit().remove("focus_$id").apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().remove("focus_$physicalId").apply()
                } else {
                    prefs.edit().putString("focus_$id", f.coerceIn(0f, 1f).toString()).apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("focus_$physicalId", f.coerceIn(0f, 1f).toString()).apply()
                }
                launchMain { backend?.setManualFocus(f) }
            }
            "focus_mode" -> {
                prefs.edit().putString("focus_mode", value).apply()
                launchMain { backend?.setFocusMode(value) }
            }
            "focus_trigger" -> {
                launchMain { backend?.triggerAutoFocus() }
            }
            "focus_point" -> {
                val parts = value.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    if (x != null && y != null) {
                        launchMain { backend?.focusOnPoint(x, y) }
                    }
                }
            }
            "camera" -> {
                val keepTorchOn = backend?.getTorch() == true
                val target = resolveCamera(value) ?: return
                selectCamera(target.first, target.second)
                prefs.edit().putString(PREF_CAMERA_ID, camId()).apply()
                launchMain {
                    if (captureRunning) {
                        debouncedStartCamera(force = true)
                        if (keepTorchOn) backend?.setTorch(true)
                    }
                }
            }
            "resolution" -> {
                if (value in listOf("low", "medium", "high")) {
                    prefs.edit().putString("camera_resolution", value).apply()
                    debouncedStartCamera()
                } else if (value == "auto" || value == "max" || Regex("\\d+x\\d+").matches(value)) {
                    prefs.edit().putString("stream_res", if (value == "max") "auto" else value).apply()
                    debouncedStartCamera()
                }
            }
            "rotate" -> {
                val angle = value.toIntOrNull() ?: return
                val norm = ((angle % 360) + 360) % 360
                prefs.edit().putInt("camera_rotate_$id", norm).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putInt("camera_rotate_$physicalId", norm).apply()
            }
            "scale" -> {
                prefs.edit().putString("stream_scale_$id", value).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("stream_scale_$physicalId", value).apply()
            }
            "contrast" -> {
                prefs.edit().putString("camera_contrast_$id", value).apply()
                if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("camera_contrast_$physicalId", value).apply()
            }
            "mirror" -> {
                if (value in listOf("true", "false")) {
                    prefs.edit().putString("mirror_$id", value).apply()
                    if (physicalId.isNotBlank() && physicalId != id) prefs.edit().putString("mirror_$physicalId", value).apply()
                }
            }
            "pip_enabled" -> {
                val enabled = value.equals("true", ignoreCase = true) || value == "1"
                prefs.edit().putBoolean("pip_enabled", enabled).apply()
                mjpegStreamingEncoder?.handleRemoteControl(key, value)
                launchMain { debouncedStartCamera(force = true) }
            }
            "pip_position", "pip_size" -> {
                prefs.edit().putString(key, value).apply()
                mjpegStreamingEncoder?.handleRemoteControl(key, value)
            }
            "quality" -> {
                val q = value.toIntOrNull()
                if (q != null && q in 10..100) {
                    prefs.edit().putString("stream_quality", q.toString()).apply()
                }
            }
            "bitrate" -> {
                val b = value.toIntOrNull()
                if (b != null && b >= 0) {
                    prefs.edit().putString("stream_bitrate", b.toString()).apply()
                }
            }
            "fps" -> {
                val fps = value.toIntOrNull()
                if (fps != null && fps in 1..60) {
                    prefs.edit().putString("stream_fps", fps.toString()).apply()
                }
            }
            "api" -> {
                if (value in listOf("auto", "camerax", "camera1")) {
                    prefs.edit().putString("capture_api", value).apply()
                    if (value != "camera1") prefs.edit().putBoolean("camera2_unusable", false).apply()
                    debouncedStartCamera()
                }
            }
            MotionMonitor.PREF_ENABLED -> {
                val enabled = when (value.lowercase()) {
                    "true", "on", "1" -> true
                    "false", "off", "0" -> false
                    else -> return
                }
                prefs.edit().putBoolean(MotionMonitor.PREF_ENABLED, enabled).apply()
                motionMonitor?.onSettingsChanged()
                launchMain {
                    if (enabled) {
                        if (captureRunning) debouncedStartCamera() else startCameraIfNeeded()
                    } else if (!encoders.any { it.hasClients() } && currentSurfaceProvider == null) {
                        stopCamera()
                        bridgeTorchAcrossIdle()
                    }
                }
            }
            MotionMonitor.PREF_SENSITIVITY -> {
                val sensitivity = value.toIntOrNull()?.coerceIn(1, 10) ?: return
                prefs.edit().putString(MotionMonitor.PREF_SENSITIVITY, sensitivity.toString()).apply()
            }
        }

        encoders.forEach { encoder ->
            encoder.handleRemoteControl(key, value)
        }
    }
}
