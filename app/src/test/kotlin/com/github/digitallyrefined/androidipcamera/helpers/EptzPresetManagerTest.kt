package com.github.digitallyrefined.androidipcamera.helpers

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EptzPresetManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var manager: EptzPresetManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        manager = EptzPresetManager(fakePrefs)
    }

    @Test
    fun parsePtz_validStrings_returnsClampedPreset() {
        val p1 = EptzPresetManager.parsePtz("0.2,-0.1,1.5")
        assertNotNull(p1)
        assertEquals(0.2f, p1!!.pan, 0.001f)
        assertEquals(-0.1f, p1.tilt, 0.001f)
        assertEquals(1.5f, p1.zoom, 0.001f)

        val p2 = EptzPresetManager.parsePtz("  0.5 ,  -0.3 , 2.5  ")
        assertNotNull(p2)
        assertEquals(0.5f, p2!!.pan, 0.001f)
        assertEquals(-0.3f, p2.tilt, 0.001f)
        assertEquals(2.5f, p2.zoom, 0.001f)
    }

    @Test
    fun parsePtz_outOfBoundsValues_clampsToLimits() {
        val p = EptzPresetManager.parsePtz("2.5,-3.0,15.0")
        assertNotNull(p)
        assertEquals(1.0f, p!!.pan, 0.001f)
        assertEquals(-1.0f, p.tilt, 0.001f)
        assertEquals(10.0f, p.zoom, 0.001f)

        val minP = EptzPresetManager.parsePtz("-5.0,5.0,0.2")
        assertNotNull(minP)
        assertEquals(-1.0f, minP!!.pan, 0.001f)
        assertEquals(1.0f, minP.tilt, 0.001f)
        assertEquals(1.0f, minP.zoom, 0.001f)
    }

    @Test
    fun parsePtz_invalidStrings_returnsNull() {
        assertNull(EptzPresetManager.parsePtz(null))
        assertNull(EptzPresetManager.parsePtz(""))
        assertNull(EptzPresetManager.parsePtz("   "))
        assertNull(EptzPresetManager.parsePtz("0.2,-0.1"))
        assertNull(EptzPresetManager.parsePtz("0.2,-0.1,1.0,extra"))
        assertNull(EptzPresetManager.parsePtz("abc,def,ghi"))
        assertNull(EptzPresetManager.parsePtz("0.2,invalid,1.0"))
    }

    @Test
    fun parseParams_validAndFallback() {
        val p1 = EptzPresetManager.parseParams("0.4", "-0.2", "1.8")
        assertNotNull(p1)
        assertEquals(0.4f, p1!!.pan, 0.001f)
        assertEquals(-0.2f, p1.tilt, 0.001f)
        assertEquals(1.8f, p1.zoom, 0.001f)

        val fallback = EptzPresetManager.EptzPreset(0.1f, 0.2f, 1.5f)
        val p2 = EptzPresetManager.parseParams("0.9", null, null, fallback)
        assertNotNull(p2)
        assertEquals(0.9f, p2!!.pan, 0.001f)
        assertEquals(0.2f, p2.tilt, 0.001f)
        assertEquals(1.5f, p2.zoom, 0.001f)

        assertNull(EptzPresetManager.parseParams(null, null, null))
        assertNull(EptzPresetManager.parseParams("invalid", "0.0", "1.0"))
    }

    @Test
    fun presets_saveGetHasAndClear() {
        assertFalse(manager.hasPreset(1))
        assertNull(manager.getPreset(1))

        assertTrue(manager.savePreset(1, 0.2f, -0.1f, 1.4f))
        assertTrue(manager.hasPreset(1))

        val p1 = manager.getPreset(1)
        assertNotNull(p1)
        assertEquals(0.2f, p1!!.pan, 0.001f)
        assertEquals(-0.1f, p1.tilt, 0.001f)
        assertEquals(1.4f, p1.zoom, 0.001f)

        assertTrue(manager.savePreset(2, -0.3f, 0.4f, 2.0f))
        assertTrue(manager.savePreset(3, 0.0f, 0.0f, 1.0f))

        assertFalse(manager.savePreset(0, 0f, 0f, 1f))
        assertFalse(manager.savePreset(4, 0f, 0f, 1f))
        assertFalse(manager.hasPreset(0))
        assertFalse(manager.hasPreset(4))
        assertNull(manager.getPreset(0))
        assertNull(manager.getPreset(4))

        assertTrue(manager.clearPreset(1))
        assertFalse(manager.hasPreset(1))
        assertNull(manager.getPreset(1))
        assertTrue(manager.hasPreset(2))
    }

    @Test
    fun currentPosition_and_loadPreset() {
        val def = manager.getCurrentPosition()
        assertEquals(0.0f, def.pan, 0.001f)
        assertEquals(0.0f, def.tilt, 0.001f)
        assertEquals(1.0f, def.zoom, 0.001f)
        assertNull(manager.getActivePreset())

        manager.setCurrentPosition(0.3f, -0.4f, 1.7f)
        val cur = manager.getCurrentPosition()
        assertEquals(0.3f, cur.pan, 0.001f)
        assertEquals(-0.4f, cur.tilt, 0.001f)
        assertEquals(1.7f, cur.zoom, 0.001f)

        manager.saveCurrentToPreset(2)
        assertTrue(manager.hasPreset(2))
        assertEquals(1.7f, manager.getPreset(2)!!.zoom, 0.001f)

        manager.resetCurrentPosition()
        assertEquals(1.0f, manager.getCurrentPosition().zoom, 0.001f)

        val loaded = manager.loadPreset(2)
        assertNotNull(loaded)
        assertEquals(2, manager.getActivePreset())
        assertEquals(1.7f, manager.getCurrentPosition().zoom, 0.001f)
    }

    @Test
    fun reset_clearsAllPresetsAndPosition() {
        manager.savePreset(1, 0.1f, 0.1f, 1.5f)
        manager.savePreset(2, 0.2f, 0.2f, 2.0f)
        manager.savePreset(3, 0.3f, 0.3f, 3.0f)
        manager.setCurrentPosition(0.5f, 0.5f, 4.0f, activePreset = 2)

        assertTrue(manager.hasPreset(1))
        assertTrue(manager.hasPreset(2))
        assertTrue(manager.hasPreset(3))
        assertEquals(2, manager.getActivePreset())

        manager.reset()

        assertFalse(manager.hasPreset(1))
        assertFalse(manager.hasPreset(2))
        assertFalse(manager.hasPreset(3))
        assertNull(manager.getActivePreset())
        val pos = manager.getCurrentPosition()
        assertEquals(0.0f, pos.pan, 0.001f)
        assertEquals(0.0f, pos.tilt, 0.001f)
        assertEquals(1.0f, pos.zoom, 0.001f)
    }

    @Test
    fun isValidIndex_boundsCheck() {
        assertFalse(manager.isValidIndex(0))
        assertTrue(manager.isValidIndex(1))
        assertTrue(manager.isValidIndex(2))
        assertTrue(manager.isValidIndex(3))
        assertFalse(manager.isValidIndex(4))
    }

    class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val backingMap: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = this
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) {
                    backingMap.clear()
                    clearFlag = false
                }
                for ((k, v) in pending) {
                    if (v === this || v == null) {
                        backingMap.remove(k)
                    } else {
                        backingMap[k] = v
                    }
                }
                pending.clear()
            }
        }
    }
}
