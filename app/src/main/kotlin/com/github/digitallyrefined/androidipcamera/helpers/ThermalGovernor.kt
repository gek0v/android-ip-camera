package com.github.digitallyrefined.androidipcamera.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dynamic Thermal Governor optimized for Google Tensor G2 (Pixel 7/7a series)
 * and multi-core heterogeneous SoCs.
 *
 * Tensor G2 features 2x Cortex-X1 (prime), 2x Cortex-A78 (mid), and 4x Cortex-A55 (efficiency).
 * Under sustained 1080p MJPEG workloads, proactive battery temperature monitoring and
 * PowerManager thermal status throttling prevent thermal throttling cliffs and maintain
 * stream stability.
 *
 * 4 Thermal States:
 * - NORMAL (< 39.0°C / THERMAL_STATUS_NONE): Maximum performance (60/30 FPS, quality q=85).
 * - LIGHT_WARM (39.0°C - 41.0°C / THERMAL_STATUS_LIGHT): Dynamic quantization (q=75 max),
 *   display dimming suggestion, background thread priority to steer writes to Cortex-A55.
 * - MODERATE_WARM (41.0°C - 44.0°C / THERMAL_STATUS_MODERATE): Cadence reduction (50->30 FPS,
 *   30->24 FPS), motion-aware frame skipping (skip 1 of 2 frames on static scenes).
 * - SEVERE (> 44.0°C / THERMAL_STATUS_SEVERE+): Emergency throttling (24/15 FPS, q=65 max),
 *   thermal warning logged.
 */
class ThermalGovernor(private val appContext: Context? = null) {

    enum class ThermalState {
        NORMAL,
        LIGHT_WARM,
        MODERATE_WARM,
        SEVERE
    }

    companion object {
        private const val TAG = "ThermalGovernor"

        // PowerManager.THERMAL_STATUS_* mirror constants for universal API compatibility
        const val THERMAL_STATUS_NONE = 0
        const val THERMAL_STATUS_LIGHT = 1
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
        const val THERMAL_STATUS_CRITICAL = 4
        const val THERMAL_STATUS_EMERGENCY = 5
        const val THERMAL_STATUS_SHUTDOWN = 6

        // Temperature thresholds in Celsius
        const val TEMP_LIGHT_WARM_THRESHOLD = 39.0f
        const val TEMP_MODERATE_WARM_THRESHOLD = 41.0f
        const val TEMP_SEVERE_THRESHOLD = 44.0f

        fun stateFromTemperature(tempCelsius: Float): ThermalState = when {
            tempCelsius >= TEMP_SEVERE_THRESHOLD -> ThermalState.SEVERE
            tempCelsius >= TEMP_MODERATE_WARM_THRESHOLD -> ThermalState.MODERATE_WARM
            tempCelsius >= TEMP_LIGHT_WARM_THRESHOLD -> ThermalState.LIGHT_WARM
            else -> ThermalState.NORMAL
        }

        fun stateFromPowerManagerStatus(status: Int): ThermalState = when (status) {
            THERMAL_STATUS_NONE -> ThermalState.NORMAL
            THERMAL_STATUS_LIGHT -> ThermalState.LIGHT_WARM
            THERMAL_STATUS_MODERATE -> ThermalState.MODERATE_WARM
            THERMAL_STATUS_SEVERE,
            THERMAL_STATUS_CRITICAL,
            THERMAL_STATUS_EMERGENCY,
            THERMAL_STATUS_SHUTDOWN -> ThermalState.SEVERE
            else -> ThermalState.NORMAL
        }
    }

    @Volatile
    var currentState: ThermalState = ThermalState.NORMAL
        private set

    @Volatile
    var currentBatteryTempCelsius: Float? = null
        private set

    @Volatile
    var currentThermalStatus: Int? = null
        private set

    @Volatile
    private var simulatedState: ThermalState? = null

    @Volatile
    private var staticFrameCount: Long = 0L

    var onThermalStateChanged: ((ThermalState) -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<(ThermalState) -> Unit>()

    private var registeredContext: Context? = null
    private var isRegistered = false
    private var batteryReceiverRegistered = false
    private var thermalStatusListener: Any? = null

    val isScreenDimmingSuggested: Boolean
        get() = currentState != ThermalState.NORMAL

    val isScreenSaverSuggested: Boolean
        get() = currentState != ThermalState.NORMAL

    val isEfficiencyModeActive: Boolean
        get() = currentState != ThermalState.NORMAL

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempTenths > 0) {
                    updateBatteryTemperature(tempTenths)
                }
            }
        }
    }

    @Synchronized
    fun register(ctx: Context? = appContext) {
        val target = ctx ?: return
        if (isRegistered) return
        registeredContext = target
        isRegistered = true

        // 1. Proactive battery temperature monitoring
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = target.registerReceiver(batteryReceiver, filter)
            sticky?.let { intent ->
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                if (tempTenths > 0) {
                    updateBatteryTemperature(tempTenths)
                }
            }
            batteryReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }

        // 2. PowerManager thermal status listener on Android Q+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = target.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.let { pm ->
                    val listener = PowerManager.OnThermalStatusChangedListener { status ->
                        updateThermalStatus(status)
                    }
                    pm.addThermalStatusListener(target.mainExecutor, listener)
                    thermalStatusListener = listener
                    updateThermalStatus(pm.currentThermalStatus)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register thermal status listener: ${e.message}")
            }
        }
    }

    @Synchronized
    fun unregister() {
        if (!isRegistered) return
        val ctx = registeredContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalStatusListener != null) {
            try {
                val powerManager = ctx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                (thermalStatusListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                    powerManager?.removeThermalStatusListener(it)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to remove thermal status listener: ${e.message}")
            }
            thermalStatusListener = null
        }

        if (batteryReceiverRegistered && ctx != null) {
            try {
                ctx.unregisterReceiver(batteryReceiver)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to unregister battery receiver: ${e.message}")
            }
            batteryReceiverRegistered = false
        }

        registeredContext = null
        isRegistered = false
    }

    fun addThermalStateListener(listener: (ThermalState) -> Unit) {
        listeners.add(listener)
    }

    fun removeThermalStateListener(listener: (ThermalState) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Updates temperature from BatteryManager tenths of degree Celsius (e.g. 395 = 39.5°C).
     */
    fun updateBatteryTemperature(temperatureTenths: Int) {
        updateBatteryTemperatureCelsius(temperatureTenths / 10.0f)
    }

    /**
     * Updates temperature in degrees Celsius and updates calculated state.
     */
    fun updateBatteryTemperatureCelsius(tempCelsius: Float) {
        simulatedState = null
        currentBatteryTempCelsius = tempCelsius
        updateCalculatedState()
    }

    /**
     * Updates PowerManager thermal status code (0..6) and updates calculated state.
     */
    fun updateThermalStatus(status: Int) {
        simulatedState = null
        currentThermalStatus = status
        updateCalculatedState()
    }

    /**
     * Injects an explicit simulated state for testing or diagnostic overrides.
     */
    fun setSimulatedState(state: ThermalState?) {
        simulatedState = state
        updateCalculatedState()
    }

    /**
     * Resets any simulated override.
     */
    fun resetSimulation() {
        simulatedState = null
        staticFrameCount = 0L
        updateCalculatedState()
    }

    @Synchronized
    private fun updateCalculatedState() {
        val newState = simulatedState ?: calculateThermalState(currentBatteryTempCelsius, currentThermalStatus)
        if (newState != currentState) {
            val previousState = currentState
            currentState = newState
            notifyStateChanged(previousState, newState)
        }
    }

    private fun calculateThermalState(tempCelsius: Float?, powerManagerStatus: Int?): ThermalState {
        val tempState = tempCelsius?.let { stateFromTemperature(it) } ?: ThermalState.NORMAL
        val statusState = powerManagerStatus?.let { stateFromPowerManagerStatus(it) } ?: ThermalState.NORMAL
        return if (tempState.ordinal >= statusState.ordinal) tempState else statusState
    }

    private fun notifyStateChanged(previousState: ThermalState, newState: ThermalState) {
        if (newState == ThermalState.SEVERE) {
            try {
                Log.w(TAG, "Thermal emergency! State is SEVERE (battery: ${currentBatteryTempCelsius ?: 0f}°C). Throttling pipeline to protect Tensor SoC.")
            } catch (_: Throwable) {}
        }
        try {
            onThermalStateChanged?.invoke(newState)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onThermalStateChanged callback", e)
        }
        listeners.forEach { listener ->
            try {
                listener.invoke(newState)
            } catch (e: Exception) {
                Log.e(TAG, "Error in thermal state listener", e)
            }
        }
    }

    /**
     * Adapts configured FPS based on thermal level:
     * - NORMAL: configuredFps
     * - LIGHT_WARM: 60->50, <=30 unchanged
     * - MODERATE_WARM: >=50->30, >=30->24
     * - SEVERE: >=50->24, >=30->15, clamped to 15 max
     */
    fun effectiveFps(configuredFps: Int): Int {
        val fps = configuredFps.coerceIn(1, 120)
        return when (currentState) {
            ThermalState.NORMAL -> fps
            ThermalState.LIGHT_WARM -> when {
                fps >= 60 -> 50
                else -> fps
            }
            ThermalState.MODERATE_WARM -> when {
                fps >= 50 -> 30
                fps >= 30 -> 24
                else -> fps.coerceAtMost(24)
            }
            ThermalState.SEVERE -> when {
                fps >= 50 -> 24
                fps >= 30 -> 15
                else -> fps.coerceAtMost(15)
            }
        }
    }

    /**
     * Adapts JPEG quality based on thermal level:
     * - NORMAL: configuredQuality (e.g. 85)
     * - LIGHT_WARM: max 75
     * - MODERATE_WARM: max 75
     * - SEVERE: max 65
     */
    fun effectiveQuality(configuredQuality: Int): Int {
        val q = configuredQuality.coerceIn(10, 100)
        return when (currentState) {
            ThermalState.NORMAL -> q
            ThermalState.LIGHT_WARM -> q.coerceAtMost(75)
            ThermalState.MODERATE_WARM -> q.coerceAtMost(75)
            ThermalState.SEVERE -> q.coerceAtMost(65)
        }
    }

    /**
     * Motion-aware frame skipping:
     * When the scene is static and thermal level is MODERATE_WARM or SEVERE,
     * skips 1 of every 2 frames (50% reduction in encoding load).
     */
    @Synchronized
    fun shouldSkipStaticFrame(isStaticScene: Boolean): Boolean {
        if (!isStaticScene) {
            staticFrameCount = 0L
            return false
        }
        return when (currentState) {
            ThermalState.MODERATE_WARM, ThermalState.SEVERE -> {
                staticFrameCount++
                // Alternate: frame 1 (false -> encode), frame 2 (true -> skip), frame 3 (false), frame 4 (true)
                (staticFrameCount % 2L == 0L)
            }
            else -> {
                staticFrameCount = 0L
                false
            }
        }
    }

    /**
     * Resolves the target nice value / thread priority for background workers.
     * Maps warm/severe states to THREAD_PRIORITY_BACKGROUND (nice +10)
     * which guides Linux/Android scheduler onto LITTLE Cortex-A55 cores.
     */
    fun getTargetThreadPriority(): Int = if (currentState != ThermalState.NORMAL) {
        Process.THREAD_PRIORITY_BACKGROUND
    } else {
        Process.THREAD_PRIORITY_DEFAULT
    }

    /**
     * Applies efficiency thread priority to the calling thread.
     * Accepts an optional custom prioritySetter for testability.
     */
    fun applyEfficiencyThreadPriority(prioritySetter: ((Int) -> Unit)? = null) {
        val targetPriority = getTargetThreadPriority()
        if (prioritySetter != null) {
            prioritySetter(targetPriority)
        } else {
            try {
                Process.setThreadPriority(targetPriority)
            } catch (_: Throwable) {
                // Graceful fallback on non-Android or unit test environments
            }
        }
    }
}
