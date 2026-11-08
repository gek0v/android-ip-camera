package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.*
import org.junit.Test

class DualCameraPipTest {

    @Test
    fun testOverlayNv21PipBasicPlacement() {
        val mainW = 100
        val mainH = 100
        val mainSize = mainW * mainH * 3 / 2
        val mainNv21 = ByteArray(mainSize) { 10 } // Fill with dark gray

        val pipW = 20
        val pipH = 20
        val pipSize = pipW * pipH * 3 / 2
        val pipNv21 = ByteArray(pipSize) { 100 } // Secondary is lighter

        // Overlay with scale 0.20 (target 20x20) at top-right
        overlayNv21Pip(
            mainNv21 = mainNv21,
            mainW = mainW,
            mainH = mainH,
            pipNv21 = pipNv21,
            pipW = pipW,
            pipH = pipH,
            position = "top-right",
            scaleRatio = 0.20f,
            drawBorder = false
        )

        // Count how many pixels were changed to 100 in the Y plane
        var modifiedYCount = 0
        for (i in 0 until (mainW * mainH)) {
            if (mainNv21[i] == 100.toByte()) {
                modifiedYCount++
            }
        }

        // Target PiP size is 20x20 = 400 pixels
        assertEquals(400, modifiedYCount)
    }

    @Test
    fun testOverlayNv21PipAllPositions() {
        val mainW = 120
        val mainH = 120
        val pipW = 20
        val pipH = 20
        val positions = listOf("top-left", "top-right", "bottom-left", "bottom-right")

        for (pos in positions) {
            val mainNv21 = ByteArray(mainW * mainH * 3 / 2) { 0 }
            val pipNv21 = ByteArray(pipW * pipH * 3 / 2) { 50 }

            overlayNv21Pip(
                mainNv21 = mainNv21,
                mainW = mainW,
                mainH = mainH,
                pipNv21 = pipNv21,
                pipW = pipW,
                pipH = pipH,
                position = pos,
                scaleRatio = 0.25f, // 30x30
                drawBorder = true
            )

            // Verify that some pixels were replaced by pip and border
            val modified = mainNv21.count { it != 0.toByte() }
            assertTrue("Position $pos should have written inset pixels", modified > 0)
        }
    }

    @Test
    fun testOverlayNv21PipBoundaryConditions() {
        val mainW = 80
        val mainH = 60
        val mainNv21 = ByteArray(mainW * mainH * 3 / 2) { 5 }
        val pipNv21 = ByteArray(20 * 20 * 3 / 2) { 50 }

        // Negative or zero scale should safely do nothing
        overlayNv21Pip(mainNv21, mainW, mainH, pipNv21, 20, 20, "top-right", 0.0f)
        assertTrue(mainNv21.all { it == 5.toByte() })

        // Oversized scale (> 1.0f) should safely do nothing
        overlayNv21Pip(mainNv21, mainW, mainH, pipNv21, 20, 20, "top-right", 1.5f)
        assertTrue(mainNv21.all { it == 5.toByte() })
    }

    @Test
    fun testOverlayNv21PipDrawsBorder() {
        val mainW = 100
        val mainH = 100
        val pipW = 20
        val pipH = 20
        val mainNv21 = ByteArray(mainW * mainH * 3 / 2) { 0 }
        val pipNv21 = ByteArray(pipW * pipH * 3 / 2) { 40 }

        overlayNv21Pip(
            mainNv21 = mainNv21,
            mainW = mainW,
            mainH = mainH,
            pipNv21 = pipNv21,
            pipW = pipW,
            pipH = pipH,
            position = "top-left",
            scaleRatio = 0.30f,
            drawBorder = true
        )

        // White border pixels have value 240 (0xF0)
        val borderPixels = mainNv21.count { it == 240.toByte() }
        assertTrue("Border pixels should be drawn around the PiP inset", borderPixels > 0)
    }
}
