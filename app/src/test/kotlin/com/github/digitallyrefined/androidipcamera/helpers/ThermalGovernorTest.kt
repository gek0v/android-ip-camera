package com.github.digitallyrefined.androidipcamera.helpers

import android.os.Process
import com.github.digitallyrefined.androidipcamera.helpers.ThermalGovernor.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThermalGovernorTest {

    private lateinit var governor: ThermalGovernor

    @Before
    fun setUp() {
        governor = ThermalGovernor()
    }

    @Test
    fun `validates state transitions based on battery temperature`() {
        // NORMAL (< 39.0°C)
        governor.updateBatteryTemperatureCelsius(38.0f)
        assertEquals(ThermalState.NORMAL, governor.currentState)
        assertEquals(38.0f, governor.currentBatteryTempCelsius)

        // Using tenths of degree: 380 -> 38.0°C -> NORMAL
        governor.updateBatteryTemperature(380)
        assertEquals(ThermalState.NORMAL, governor.currentState)

        // LIGHT_WARM (39.0°C - 41.0°C)
        governor.updateBatteryTemperatureCelsius(40.0f)
        assertEquals(ThermalState.LIGHT_WARM, governor.currentState)

        governor.updateBatteryTemperature(405)
        assertEquals(ThermalState.LIGHT_WARM, governor.currentState)

        // MODERATE_WARM (41.0°C - 44.0°C)
        governor.updateBatteryTemperatureCelsius(42.5f)
        assertEquals(ThermalState.MODERATE_WARM, governor.currentState)

        governor.updateBatteryTemperature(430)
        assertEquals(ThermalState.MODERATE_WARM, governor.currentState)

        // SEVERE (> 44.0°C)
        governor.updateBatteryTemperatureCelsius(46.0f)
        assertEquals(ThermalState.SEVERE, governor.currentState)

        governor.updateBatteryTemperature(470)
        assertEquals(ThermalState.SEVERE, governor.currentState)

        // Cooldown back to NORMAL
        governor.updateBatteryTemperatureCelsius(37.5f)
        assertEquals(ThermalState.NORMAL, governor.currentState)
    }

    @Test
    fun `validates PowerManager thermal status transitions`() {
        governor.updateThermalStatus(ThermalGovernor.THERMAL_STATUS_NONE)
        assertEquals(ThermalState.NORMAL, governor.currentState)

        governor.updateThermalStatus(ThermalGovernor.THERMAL_STATUS_LIGHT)
        assertEquals(ThermalState.LIGHT_WARM, governor.currentState)

        governor.updateThermalStatus(ThermalGovernor.THERMAL_STATUS_MODERATE)
        assertEquals(ThermalState.MODERATE_WARM, governor.currentState)

        governor.updateThermalStatus(ThermalGovernor.THERMAL_STATUS_SEVERE)
        assertEquals(ThermalState.SEVERE, governor.currentState)

        governor.updateThermalStatus(ThermalGovernor.THERMAL_STATUS_CRITICAL)
        assertEquals(ThermalState.SEVERE, governor.currentState)
    }

    @Test
    fun `validates FPS adaptation across thermal states`() {
        // NORMAL: configured FPS unchanged
        governor.setSimulatedState(ThermalState.NORMAL)
        assertEquals(60, governor.effectiveFps(60))
        assertEquals(30, governor.effectiveFps(30))

        // LIGHT_WARM: 60 -> 50, 30 unchanged
        governor.setSimulatedState(ThermalState.LIGHT_WARM)
        assertEquals(50, governor.effectiveFps(60))
        assertEquals(30, governor.effectiveFps(30))

        // MODERATE_WARM: 60 -> 30, 30 -> 24
        governor.setSimulatedState(ThermalState.MODERATE_WARM)
        assertEquals(30, governor.effectiveFps(60))
        assertEquals(24, governor.effectiveFps(30))

        // SEVERE: 60 -> 24, 30 -> 15
        governor.setSimulatedState(ThermalState.SEVERE)
        assertEquals(24, governor.effectiveFps(60))
        assertEquals(15, governor.effectiveFps(30))
    }

    @Test
    fun `validates JPEG quality adaptation across thermal states`() {
        // NORMAL: configured quality preserved
        governor.setSimulatedState(ThermalState.NORMAL)
        assertEquals(85, governor.effectiveQuality(85))
        assertEquals(95, governor.effectiveQuality(95))
        assertEquals(60, governor.effectiveQuality(60))

        // LIGHT_WARM: max 75
        governor.setSimulatedState(ThermalState.LIGHT_WARM)
        assertEquals(75, governor.effectiveQuality(85))
        assertEquals(75, governor.effectiveQuality(95))
        assertEquals(75, governor.effectiveQuality(75))
        assertEquals(60, governor.effectiveQuality(60))

        // MODERATE_WARM: max 75
        governor.setSimulatedState(ThermalState.MODERATE_WARM)
        assertEquals(75, governor.effectiveQuality(85))
        assertEquals(75, governor.effectiveQuality(80))
        assertEquals(60, governor.effectiveQuality(60))

        // SEVERE: max 65
        governor.setSimulatedState(ThermalState.SEVERE)
        assertEquals(65, governor.effectiveQuality(85))
        assertEquals(65, governor.effectiveQuality(75))
        assertEquals(65, governor.effectiveQuality(65))
        assertEquals(50, governor.effectiveQuality(50))
    }

    @Test
    fun `validates motion-aware frame skipping in MODERATE_WARM and SEVERE`() {
        // NORMAL: no frame skipping even if scene is static
        governor.setSimulatedState(ThermalState.NORMAL)
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))

        // LIGHT_WARM: no frame skipping even if scene is static
        governor.setSimulatedState(ThermalState.LIGHT_WARM)
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))

        // MODERATE_WARM: alternates 1 of every 2 frames when static
        governor.setSimulatedState(ThermalState.MODERATE_WARM)
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true)) // Frame 1: keep
        assertTrue(governor.shouldSkipStaticFrame(isStaticScene = true))  // Frame 2: skip
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true)) // Frame 3: keep
        assertTrue(governor.shouldSkipStaticFrame(isStaticScene = true))  // Frame 4: skip

        // When motion is detected: never skips
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = false))
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = false))
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = false))

        // SEVERE: also alternates 1 of 2 frames when static
        governor.setSimulatedState(ThermalState.SEVERE)
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))
        assertTrue(governor.shouldSkipStaticFrame(isStaticScene = true))
        assertFalse(governor.shouldSkipStaticFrame(isStaticScene = true))
        assertTrue(governor.shouldSkipStaticFrame(isStaticScene = true))
    }

    @Test
    fun `validates listeners and state change callbacks`() {
        var propertyCallbackState: ThermalState? = null
        val listenerStates = mutableListOf<ThermalState>()

        governor.onThermalStateChanged = { propertyCallbackState = it }
        val listener: (ThermalState) -> Unit = { listenerStates.add(it) }
        governor.addThermalStateListener(listener)

        // State changes to LIGHT_WARM
        governor.updateBatteryTemperatureCelsius(40.0f)
        assertEquals(ThermalState.LIGHT_WARM, propertyCallbackState)
        assertEquals(listOf(ThermalState.LIGHT_WARM), listenerStates)

        // Same state: no extra invocation
        governor.updateBatteryTemperatureCelsius(40.5f)
        assertEquals(1, listenerStates.size)

        // State changes to MODERATE_WARM
        governor.updateBatteryTemperatureCelsius(42.0f)
        assertEquals(ThermalState.MODERATE_WARM, propertyCallbackState)
        assertEquals(listOf(ThermalState.LIGHT_WARM, ThermalState.MODERATE_WARM), listenerStates)

        // Remove listener
        governor.removeThermalStateListener(listener)
        governor.updateBatteryTemperatureCelsius(45.0f)
        assertEquals(ThermalState.SEVERE, propertyCallbackState)
        assertEquals(2, listenerStates.size) // Listener not called after removal
    }

    @Test
    fun `validates thread priority and screen dimming recommendations`() {
        // NORMAL
        governor.setSimulatedState(ThermalState.NORMAL)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, governor.getTargetThreadPriority())
        assertFalse(governor.isScreenDimmingSuggested)
        assertFalse(governor.isScreenSaverSuggested)
        assertFalse(governor.isEfficiencyModeActive)

        var appliedPriority = -1
        governor.applyEfficiencyThreadPriority { appliedPriority = it }
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, appliedPriority)

        // LIGHT_WARM
        governor.setSimulatedState(ThermalState.LIGHT_WARM)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, governor.getTargetThreadPriority())
        assertTrue(governor.isScreenDimmingSuggested)
        assertTrue(governor.isScreenSaverSuggested)
        assertTrue(governor.isEfficiencyModeActive)

        governor.applyEfficiencyThreadPriority { appliedPriority = it }
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, appliedPriority)

        // MODERATE_WARM
        governor.setSimulatedState(ThermalState.MODERATE_WARM)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, governor.getTargetThreadPriority())

        // SEVERE
        governor.setSimulatedState(ThermalState.SEVERE)
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, governor.getTargetThreadPriority())
        governor.applyEfficiencyThreadPriority { appliedPriority = it }
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, appliedPriority)
    }
}
