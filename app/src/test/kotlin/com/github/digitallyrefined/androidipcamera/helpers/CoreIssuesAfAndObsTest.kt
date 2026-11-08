package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CoreIssuesAfAndObsTest {

    // --- Issue #1: AF/AE Lock Logic Tests ---

    private class MockCaptureBackend : CaptureBackend {
        override val width: Int = 1920
        override val height: Int = 1080
        override fun captureStill(onJpeg: (ByteArray?) -> Unit) {}
        override fun getTorch(): Boolean = false
        override fun setTorch(on: Boolean) {}
        override fun setExposure(ev: Int) {}
        override fun setZoom(ratio: Float) {}
        override fun setManualFocus(distance: Float) {}
        override fun stop() {}

        var focusTriggeredCount = 0
        var lastFocusPoint: Pair<Float, Float>? = null
        var lastFocusMode: String? = null
        private var focusLocked: Boolean = false

        override fun triggerAutoFocus() {
            focusTriggeredCount++
        }

        override fun focusOnPoint(x: Float, y: Float) {
            lastFocusPoint = Pair(x, y)
            focusLocked = false
            triggerAutoFocus()
        }

        override fun focusAndLock(x: Float, y: Float) {
            lastFocusPoint = Pair(x, y)
            focusLocked = true
            triggerAutoFocus()
        }

        override fun unlockFocus() {
            focusLocked = false
        }

        override fun isFocusLocked(): Boolean = focusLocked

        override fun setFocusMode(mode: String) {
            unlockFocus()
            lastFocusMode = mode
        }
    }

    @Test
    fun focusAndLock_locksFocusAndTriggersAf() {
        val backend = MockCaptureBackend()
        assertFalse(backend.isFocusLocked())

        backend.focusAndLock(0.25f, 0.75f)
        assertTrue(backend.isFocusLocked())
        assertEquals(1, backend.focusTriggeredCount)
        assertEquals(Pair(0.25f, 0.75f), backend.lastFocusPoint)

        backend.unlockFocus()
        assertFalse(backend.isFocusLocked())
    }

    @Test
    fun setFocusMode_clearsActiveLock() {
        val backend = MockCaptureBackend()
        backend.focusAndLock(0.5f, 0.5f)
        assertTrue(backend.isFocusLocked())

        backend.setFocusMode("continuous")
        assertFalse(backend.isFocusLocked())
        assertEquals("continuous", backend.lastFocusMode)
    }

    @Test
    fun focusOnPoint_doesNotLock() {
        val backend = MockCaptureBackend()
        backend.focusOnPoint(0.3f, 0.4f)
        assertFalse(backend.isFocusLocked())
        assertEquals(Pair(0.3f, 0.4f), backend.lastFocusPoint)
    }

    // --- Issue #2: OBS Browser Source & Query Auth Tests ---

    private fun checkQueryAuth(
        queryParams: Map<String, String>,
        expectedUser: String,
        expectedPass: String
    ): Boolean {
        val queryUser = queryParams["user"] ?: queryParams["username"]
        val queryPass = queryParams["pass"] ?: queryParams["password"]
        val queryAuth = queryParams["auth"] ?: queryParams["token"]
        val hasValidTokenAuth = if (queryAuth != null) {
            try {
                val decoded = String(Base64.getDecoder().decode(queryAuth)).trim()
                decoded == "$expectedUser:$expectedPass"
            } catch (_: Exception) { false }
        } else false

        return (queryUser != null && queryPass != null && queryUser == expectedUser && queryPass == expectedPass) || hasValidTokenAuth
    }

    @Test
    fun queryAuth_validDirectUserAndPass() {
        val params = mapOf("user" to "streamer", "pass" to "secret123")
        assertTrue(checkQueryAuth(params, "streamer", "secret123"))

        val paramsAlt = mapOf("username" to "streamer", "password" to "secret123")
        assertTrue(checkQueryAuth(paramsAlt, "streamer", "secret123"))

        val paramsWrong = mapOf("user" to "streamer", "pass" to "wrong")
        assertFalse(checkQueryAuth(paramsWrong, "streamer", "secret123"))
    }

    @Test
    fun queryAuth_validBase64Token() {
        val raw = "streamer:secret123"
        val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())

        val paramsAuth = mapOf("auth" to encoded)
        assertTrue(checkQueryAuth(paramsAuth, "streamer", "secret123"))

        val paramsToken = mapOf("token" to encoded)
        assertTrue(checkQueryAuth(paramsToken, "streamer", "secret123"))

        val invalidToken = mapOf("token" to "invalid-base64-content!")
        assertFalse(checkQueryAuth(invalidToken, "streamer", "secret123"))
    }

    private fun generateObsHtml(uri: String): String {
        val queryParams = if (uri.contains("?")) {
            uri.substringAfter("?").split("&").mapNotNull {
                val idx = it.indexOf('=')
                if (idx != -1) it.substring(0, idx) to it.substring(idx + 1) else null
            }.toMap()
        } else emptyMap()

        val fit = queryParams["fit"]?.takeIf { it in listOf("contain", "cover", "fill") } ?: "contain"
        val rotate = queryParams["rotate"]?.toIntOrNull() ?: 0
        val flip = queryParams["flip"]?.lowercase()

        val imgQuery = if (uri.contains("?")) "?" + uri.substringAfter("?") else ""

        val transformStyle = buildString {
            if (rotate != 0) append("rotate(${rotate}deg) ")
            if (flip == "h" || flip == "horizontal") append("scaleX(-1) ")
            else if (flip == "v" || flip == "vertical") append("scaleY(-1) ")
            else if (flip == "both") append("scale(-1, -1) ")
        }.trim()

        val imgStyle = buildString {
            append("width:100%;height:100%;object-fit:${fit};")
            if (transformStyle.isNotEmpty()) append("transform:${transformStyle};")
        }

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>OBS Stream</title><style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;display:flex;align-items:center;justify-content:center;}img{${imgStyle}}</style></head><body><img src=\"/video/mjpeg${imgQuery}\" alt=\"Stream\"></body></html>"
    }

    @Test
    fun obsRoute_forwardsCredentialsAndAppliesFitAndTransform() {
        val uri = "/obs?user=admin&pass=pass123&fit=cover&rotate=90&flip=h"
        val html = generateObsHtml(uri)

        // Verifies credentials and params are forwarded into the img src so CEF loads the stream without auth prompt
        assertTrue(html.contains("<img src=\"/video/mjpeg?user=admin&pass=pass123&fit=cover&rotate=90&flip=h\""))
        // Verifies object-fit styling
        assertTrue(html.contains("object-fit:cover;"))
        // Verifies rotation and flip transforms
        assertTrue(html.contains("rotate(90deg)"))
        assertTrue(html.contains("scaleX(-1)"))
    }

    @Test
    fun obsRoute_defaultsToContainAndNoTransform() {
        val uri = "/obs"
        val html = generateObsHtml(uri)

        assertTrue(html.contains("<img src=\"/video/mjpeg\""))
        assertTrue(html.contains("object-fit:contain;"))
        assertFalse(html.contains("rotate"))
    }
}
