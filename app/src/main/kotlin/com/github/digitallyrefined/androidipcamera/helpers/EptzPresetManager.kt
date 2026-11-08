package com.github.digitallyrefined.androidipcamera.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.util.Locale

/**
 * Manages virtual cinematic ePTZ (Pan, Tilt, Zoom) presets and state.
 * Supports persistent slots (1-3) stored in SharedPreferences,
 * parsing of PTZ strings (e.g. "0.2,-0.1,1.5"), and status serialization for Stream Deck / Web UI.
 */
class EptzPresetManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(PreferenceManager.getDefaultSharedPreferences(context))

    data class EptzPreset(
        val pan: Float,
        val tilt: Float,
        val zoom: Float
    ) {
        fun toJsonObject(): JSONObject {
            return JSONObject().apply {
                put("pan", String.format(Locale.US, "%.3f", pan).toDouble())
                put("tilt", String.format(Locale.US, "%.3f", tilt).toDouble())
                put("zoom", String.format(Locale.US, "%.2f", zoom).toDouble())
            }
        }
    }

    companion object {
        const val MIN_INDEX = 1
        const val MAX_INDEX = 3

        const val MIN_PAN = -1.0f
        const val MAX_PAN = 1.0f
        const val MIN_TILT = -1.0f
        const val MAX_TILT = 1.0f
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 10.0f

        val DEFAULT_POSITION = EptzPreset(pan = 0.0f, tilt = 0.0f, zoom = 1.0f)

        private const val PREF_PREFIX = "eptz_preset_"
        private const val KEY_CURRENT_PAN = "eptz_current_pan"
        private const val KEY_CURRENT_TILT = "eptz_current_tilt"
        private const val KEY_CURRENT_ZOOM = "eptz_current_zoom"
        private const val KEY_ACTIVE_PRESET = "eptz_active_preset"

        fun clampPan(pan: Float): Float = pan.coerceIn(MIN_PAN, MAX_PAN)
        fun clampTilt(tilt: Float): Float = tilt.coerceIn(MIN_TILT, MAX_TILT)
        fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

        fun clampPreset(pan: Float, tilt: Float, zoom: Float): EptzPreset {
            return EptzPreset(
                pan = clampPan(pan),
                tilt = clampTilt(tilt),
                zoom = clampZoom(zoom)
            )
        }

        /**
         * Parses a comma-separated PTZ string in the format "pan,tilt,zoom" (e.g. "0.2,-0.1,1.5").
         * Returns clamped EptzPreset, or null if the string cannot be parsed into 3 numeric values.
         */
        fun parsePtz(ptzString: String?): EptzPreset? {
            if (ptzString.isNullOrBlank()) return null
            val parts = ptzString.split(",").map { it.trim() }
            if (parts.size != 3) return null

            val pan = parts[0].toFloatOrNull() ?: return null
            val tilt = parts[1].toFloatOrNull() ?: return null
            val zoom = parts[2].toFloatOrNull() ?: return null

            return clampPreset(pan, tilt, zoom)
        }

        /**
         * Parses separate pan, tilt, zoom strings with fallback values for omitted parameters.
         * Returns null if all input strings are null/empty or if any non-null parameter is not a valid float.
         */
        fun parseParams(
            panStr: String?,
            tiltStr: String?,
            zoomStr: String?,
            fallback: EptzPreset = DEFAULT_POSITION
        ): EptzPreset? {
            if (panStr.isNullOrBlank() && tiltStr.isNullOrBlank() && zoomStr.isNullOrBlank()) {
                return null
            }

            val pan = if (!panStr.isNullOrBlank()) panStr.toFloatOrNull() ?: return null else fallback.pan
            val tilt = if (!tiltStr.isNullOrBlank()) tiltStr.toFloatOrNull() ?: return null else fallback.tilt
            val zoom = if (!zoomStr.isNullOrBlank()) zoomStr.toFloatOrNull() ?: return null else fallback.zoom

            return clampPreset(pan, tilt, zoom)
        }
    }

    /**
     * Checks if a preset index is valid (1 to 3).
     */
    fun isValidIndex(index: Int): Boolean = index in MIN_INDEX..MAX_INDEX

    /**
     * Checks whether a preset exists in slot [index].
     */
    fun hasPreset(index: Int): Boolean {
        if (!isValidIndex(index)) return false
        return prefs.getBoolean("${PREF_PREFIX}${index}_saved", false)
    }

    /**
     * Retrieves the preset at slot [index], or null if not saved or invalid index.
     */
    fun getPreset(index: Int): EptzPreset? {
        if (!hasPreset(index)) return null

        val pan = prefs.getFloat("${PREF_PREFIX}${index}_pan", 0.0f)
        val tilt = prefs.getFloat("${PREF_PREFIX}${index}_tilt", 0.0f)
        val zoom = prefs.getFloat("${PREF_PREFIX}${index}_zoom", 1.0f)

        return clampPreset(pan, tilt, zoom)
    }

    /**
     * Saves a preset at slot [index] (1 to 3).
     * Automatically clamps pan (-1..1), tilt (-1..1) and zoom (1..10).
     * Returns true if saved, false if index is invalid.
     */
    fun savePreset(index: Int, pan: Float, tilt: Float, zoom: Float): Boolean {
        if (!isValidIndex(index)) return false

        val clamped = clampPreset(pan, tilt, zoom)
        prefs.edit()
            .putFloat("${PREF_PREFIX}${index}_pan", clamped.pan)
            .putFloat("${PREF_PREFIX}${index}_tilt", clamped.tilt)
            .putFloat("${PREF_PREFIX}${index}_zoom", clamped.zoom)
            .putBoolean("${PREF_PREFIX}${index}_saved", true)
            .apply()

        return true
    }

    /**
     * Saves the current position into slot [index].
     */
    fun saveCurrentToPreset(index: Int): Boolean {
        val current = getCurrentPosition()
        return savePreset(index, current.pan, current.tilt, current.zoom)
    }

    /**
     * Loads preset [index], updating current position and active preset pointer.
     * Returns the loaded preset, or null if it does not exist.
     */
    fun loadPreset(index: Int): EptzPreset? {
        val preset = getPreset(index) ?: return null
        setCurrentPosition(preset.pan, preset.tilt, preset.zoom, activePreset = index)
        return preset
    }

    /**
     * Clears preset [index].
     */
    fun clearPreset(index: Int): Boolean {
        if (!isValidIndex(index)) return false
        prefs.edit()
            .remove("${PREF_PREFIX}${index}_pan")
            .remove("${PREF_PREFIX}${index}_tilt")
            .remove("${PREF_PREFIX}${index}_zoom")
            .remove("${PREF_PREFIX}${index}_saved")
            .apply()
        return true
    }

    /**
     * Clears all saved presets (1-3) and resets the current position to default (pan=0, tilt=0, zoom=1.0x).
     */
    fun reset() {
        val editor = prefs.edit()
        for (i in MIN_INDEX..MAX_INDEX) {
            editor.remove("${PREF_PREFIX}${i}_pan")
            editor.remove("${PREF_PREFIX}${i}_tilt")
            editor.remove("${PREF_PREFIX}${i}_zoom")
            editor.remove("${PREF_PREFIX}${i}_saved")
        }
        editor.remove(KEY_CURRENT_PAN)
        editor.remove(KEY_CURRENT_TILT)
        editor.remove(KEY_CURRENT_ZOOM)
        editor.remove(KEY_ACTIVE_PRESET)
        editor.apply()
    }

    /**
     * Returns the current live ePTZ position.
     */
    fun getCurrentPosition(): EptzPreset {
        val pan = prefs.getFloat(KEY_CURRENT_PAN, DEFAULT_POSITION.pan)
        val tilt = prefs.getFloat(KEY_CURRENT_TILT, DEFAULT_POSITION.tilt)
        val zoom = prefs.getFloat(KEY_CURRENT_ZOOM, DEFAULT_POSITION.zoom)
        return clampPreset(pan, tilt, zoom)
    }

    /**
     * Updates the current live ePTZ position and optionally the active preset index.
     */
    fun setCurrentPosition(pan: Float, tilt: Float, zoom: Float, activePreset: Int? = null): EptzPreset {
        val clamped = clampPreset(pan, tilt, zoom)
        val editor = prefs.edit()
            .putFloat(KEY_CURRENT_PAN, clamped.pan)
            .putFloat(KEY_CURRENT_TILT, clamped.tilt)
            .putFloat(KEY_CURRENT_ZOOM, clamped.zoom)

        if (activePreset != null && isValidIndex(activePreset)) {
            editor.putInt(KEY_ACTIVE_PRESET, activePreset)
        } else {
            editor.remove(KEY_ACTIVE_PRESET)
        }
        editor.apply()
        return clamped
    }

    /**
     * Gets the currently active preset index (1 to 3), or null if custom position.
     */
    fun getActivePreset(): Int? {
        val active = prefs.getInt(KEY_ACTIVE_PRESET, -1)
        return if (isValidIndex(active)) active else null
    }

    /**
     * Resets current framing to default (pan=0, tilt=0, zoom=1.0x).
     */
    fun resetCurrentPosition(): EptzPreset {
        return setCurrentPosition(DEFAULT_POSITION.pan, DEFAULT_POSITION.tilt, DEFAULT_POSITION.zoom, null)
    }

    /**
     * Builds a JSON string representing the full ePTZ status:
     * current pan, tilt, zoom, active preset, and slots 1-3.
     */
    fun getStatusJson(): String {
        val current = getCurrentPosition()
        val active = getActivePreset()

        val json = JSONObject()
        json.put("pan", String.format(Locale.US, "%.3f", current.pan).toDouble())
        json.put("tilt", String.format(Locale.US, "%.3f", current.tilt).toDouble())
        json.put("zoom", String.format(Locale.US, "%.2f", current.zoom).toDouble())
        json.put("active_preset", if (active != null) active else JSONObject.NULL)
        json.put("is_transitioning", false)

        val presetsObj = JSONObject()
        for (i in MIN_INDEX..MAX_INDEX) {
            val p = getPreset(i)
            if (p != null) {
                presetsObj.put(i.toString(), p.toJsonObject())
            } else {
                presetsObj.put(i.toString(), JSONObject.NULL)
            }
        }
        json.put("presets", presetsObj)

        return json.toString()
    }
}
