package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EptzControllerTest {

    private lateinit var controller: EptzController

    @Before
    fun setUp() {
        controller = EptzController()
    }

    // =========================================================================
    // 1. Matematicas de recorte, limites de contorno y preservacion de aspecto
    // =========================================================================

    @Test
    fun test64MpSensorWith16x9StreamPreservesAspectRatio() {
        val sensorW = 9248
        val sensorH = 6936
        val targetAspect = 16f / 9f

        // At zoom 1.0x (centered)
        val crop1x = controller.calculateCropRect(sensorW, sensorH, targetAspect)
        assertEquals(sensorW, crop1x.width)
        val expectedHeight1x = (sensorW / targetAspect).toInt()
        assertEquals(expectedHeight1x, crop1x.height)
        assertEquals(targetAspect, crop1x.width.toFloat() / crop1x.height.toFloat(), 0.005f)

        // At zoom 2.0x
        controller.reset(zoom = 2.0f)
        val crop2x = controller.calculateCropRect(sensorW, sensorH, targetAspect)
        assertEquals(sensorW / 2, crop2x.width)
        assertEquals(expectedHeight1x / 2, crop2x.height)
        assertEquals(targetAspect, crop2x.width.toFloat() / crop2x.height.toFloat(), 0.005f)

        // At zoom 4.0x (max zoom)
        controller.reset(zoom = 4.0f)
        val crop4x = controller.calculateCropRect(sensorW, sensorH, targetAspect)
        assertEquals(sensorW / 4, crop4x.width)
        assertEquals(targetAspect, crop4x.width.toFloat() / crop4x.height.toFloat(), 0.005f)
    }

    @Test
    fun test16x9SensorWith4x3StreamPillarboxAspectPreserved() {
        val sensorW = 1920
        val sensorH = 1080
        val targetAspect = 4f / 3f

        val crop = controller.calculateCropRect(sensorW, sensorH, targetAspect)
        assertEquals(sensorH, crop.height)
        val expectedWidth = (sensorH * targetAspect).toInt()
        assertEquals(expectedWidth, crop.width)
        assertEquals(targetAspect, crop.width.toFloat() / crop.height.toFloat(), 0.005f)
    }

    @Test
    fun testBoundaryClampingNeverExceedsSensor() {
        val sensorW = 9248
        val sensorH = 6936
        val targetAspect = 16f / 9f

        val testPositions = listOf(
            Triple(-1.0f, -1.0f, 1.0f),
            Triple(-1.0f, 1.0f, 1.0f),
            Triple(1.0f, -1.0f, 1.0f),
            Triple(1.0f, 1.0f, 1.0f),
            Triple(-2.5f, 5.0f, 0.5f),
            Triple(10.0f, -10.0f, 8.0f),
            Triple(-1.0f, 1.0f, 3.0f),
            Triple(1.0f, -1.0f, 4.0f)
        )

        for ((pan, tilt, zoom) in testPositions) {
            val crop = EptzController.computeCropRect(sensorW, sensorH, pan, tilt, zoom, targetAspect)
            assertTrue(crop.left >= 0)
            assertTrue(crop.top >= 0)
            assertTrue(crop.right <= sensorW)
            assertTrue(crop.bottom <= sensorH)
            assertTrue(crop.width > 0)
            assertTrue(crop.height > 0)
        }
    }

    @Test
    fun testCornerAlignmentsExact() {
        val sensorW = 9248
        val sensorH = 6936
        val targetAspect = 16f / 9f

        val leftCrop = EptzController.computeCropRect(sensorW, sensorH, pan = -1.0f, tilt = 0.0f, zoom = 2.0f, targetAspectRatio = targetAspect)
        assertEquals(0, leftCrop.left)

        val rightCrop = EptzController.computeCropRect(sensorW, sensorH, pan = 1.0f, tilt = 0.0f, zoom = 2.0f, targetAspectRatio = targetAspect)
        assertEquals(sensorW, rightCrop.right)

        val topCrop = EptzController.computeCropRect(sensorW, sensorH, pan = 0.0f, tilt = 1.0f, zoom = 2.0f, targetAspectRatio = targetAspect)
        assertEquals(0, topCrop.top)

        val bottomCrop = EptzController.computeCropRect(sensorW, sensorH, pan = 0.0f, tilt = -1.0f, zoom = 2.0f, targetAspectRatio = targetAspect)
        assertEquals(sensorH, bottomCrop.bottom)
    }

    // =========================================================================
    // 2. Convergencia LERP y Zona Muerta (Deadband)
    // =========================================================================

    @Test
    fun testDeadbandIgnoresChangesBelowThreshold() {
        controller.reset(pan = 0.0f, tilt = 0.0f, zoom = 1.0f)
        assertEquals(0.05f, controller.deadbandThreshold, 0.0001f)

        val changed = controller.setTarget(pan = 0.03f, tilt = 0.02f, zoom = 1.03f)
        assertFalse(changed)
        assertEquals(0.0f, controller.targetPan, 0.0001f)
        assertEquals(0.0f, controller.targetTilt, 0.0001f)
        assertEquals(1.0f, controller.targetZoom, 0.0001f)

        assertFalse(controller.update())
        assertEquals(0.0f, controller.currentPan, 0.0001f)
    }

    @Test
    fun testDeadbandAcceptsChangesAtOrAboveThreshold() {
        controller.reset(pan = 0.0f, tilt = 0.0f, zoom = 1.0f)

        val changed = controller.setTarget(pan = 0.06f, tilt = 0.10f, zoom = 1.20f)
        assertTrue(changed)
        assertEquals(0.06f, controller.targetPan, 0.0001f)
        assertEquals(0.10f, controller.targetTilt, 0.0001f)
        assertEquals(1.20f, controller.targetZoom, 0.0001f)
        assertTrue(controller.isAnimating)
    }

    @Test
    fun testLerpSmoothingConvergesSmoothly() {
        controller.reset(pan = 0.0f, tilt = 0.0f, zoom = 1.0f)
        controller.lerpFactor = 0.10f

        controller.setTarget(pan = 0.50f, tilt = -0.30f, zoom = 2.0f)
        assertTrue(controller.isAnimating)

        var previousPan = controller.currentPan
        var tickCount = 0

        while (controller.isAnimating && tickCount < 200) {
            val moved = controller.update()
            assertTrue(moved)
            assertTrue(controller.currentPan >= previousPan)
            previousPan = controller.currentPan
            tickCount++
        }

        assertFalse(controller.isAnimating)
        assertEquals(0.50f, controller.currentPan, 0.001f)
        assertEquals(-0.30f, controller.currentTilt, 0.001f)
        assertEquals(2.0f, controller.currentZoom, 0.001f)
    }

    @Test
    fun testDeadbandHysteresisWhileMoving() {
        controller.reset(pan = 0.0f, tilt = 0.0f, zoom = 1.0f)
        controller.setTarget(pan = 0.30f)

        controller.update()
        controller.update()

        val jitterChanged = controller.setTarget(pan = 0.32f)
        assertFalse(jitterChanged)
        assertEquals(0.30f, controller.targetPan, 0.0001f)

        val bigChanged = controller.setTarget(pan = 0.50f)
        assertTrue(bigChanged)
        assertEquals(0.50f, controller.targetPan, 0.0001f)
    }

    @Test
    fun testBypassDeadbandOption() {
        controller.reset(pan = 0.0f)
        val changed = controller.setTarget(pan = 0.02f, applyDeadband = false)
        assertTrue(changed)
        assertEquals(0.02f, controller.targetPan, 0.0001f)
    }

    // =========================================================================
    // 3. Auto-framing inteligente (Centro, Esquinas, Headroom aureo, Multi-sujeto)
    // =========================================================================

    @Test
    fun testAutoFramingCenteredSubject() {
        controller.reset()
        val subject = NormalizedBoundingBox(ymin = 0.30f, xmin = 0.40f, ymax = 0.70f, xmax = 0.60f)
        controller.autoFrame(subject, applyDeadband = false)

        assertEquals(0.0f, controller.targetPan, 0.01f)
        assertTrue(controller.targetZoom > 1.0f)
    }

    @Test
    fun testAutoFramingTopLeftSubject() {
        controller.reset()
        val subject = NormalizedBoundingBox(ymin = 0.05f, xmin = 0.05f, ymax = 0.25f, xmax = 0.25f)
        controller.autoFrame(subject, applyDeadband = false)

        assertTrue(controller.targetPan < -0.3f)
        assertTrue(controller.targetTilt > 0.3f)
    }

    @Test
    fun testAutoFramingBottomRightSubject() {
        controller.reset()
        val subject = NormalizedBoundingBox(ymin = 0.70f, xmin = 0.70f, ymax = 0.90f, xmax = 0.90f)
        controller.autoFrame(subject, applyDeadband = false)

        assertTrue(controller.targetPan > 0.3f)
        assertTrue(controller.targetTilt < -0.3f)
    }

    @Test
    fun testAutoFramingHeadroomGoldenRatio() {
        controller.reset()
        controller.headroomFraction = 0.18f
        val subject = NormalizedBoundingBox(ymin = 0.25f, xmin = 0.40f, ymax = 0.55f, xmax = 0.60f)
        controller.autoFrame(subject, applyDeadband = false)

        val cropH = 1.0f / controller.targetZoom
        val cropCenterY = 0.5f - controller.targetTilt / 2.0f
        val cropTop = cropCenterY - cropH / 2.0f

        val headroom = (subject.ymin - cropTop) / cropH
        assertEquals(0.18f, headroom, 0.02f)
    }

    @Test
    fun testMultiSubjectAutoFramingEnclosesAllSubjects() {
        controller.reset()
        val subject1 = NormalizedBoundingBox(ymin = 0.30f, xmin = 0.10f, ymax = 0.60f, xmax = 0.30f)
        val subject2 = NormalizedBoundingBox(ymin = 0.35f, xmin = 0.70f, ymax = 0.65f, xmax = 0.90f)

        val changed = controller.autoFrame(listOf(subject1, subject2), applyDeadband = false)
        assertTrue(changed)

        assertEquals(0.0f, controller.targetPan, 0.02f)
        assertTrue(controller.targetZoom <= 1.0f)
    }

    @Test
    fun testEmptyDetectionsReturnsFalse() {
        controller.reset()
        val changed = controller.autoFrame(emptyList())
        assertFalse(changed)
        assertFalse(controller.isAnimating)
    }
}
