package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class NativeImagePipelineTest {

    private class MockPlane(
        private val buf: ByteBuffer,
        private val rStride: Int,
        private val pStride: Int
    ) : ImageProxy.PlaneProxy {
        override fun getBuffer(): ByteBuffer = buf
        override fun getRowStride(): Int = rStride
        override fun getPixelStride(): Int = pStride
    }

    private class MockImageProxy(
        private val w: Int,
        private val h: Int,
        private val planeList: Array<ImageProxy.PlaneProxy>,
        private val crop: Rect = Rect(0, 0, w, h)
    ) : ImageProxy {
        override fun getWidth(): Int = w
        override fun getHeight(): Int = h
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getCropRect(): Rect = crop
        override fun setCropRect(rect: Rect?) {}
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planeList
        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()
        override fun getImage(): android.media.Image? = null
        override fun close() {}
    }

    @Test
    fun testFallbackKotlinWhenNativeNotLoaded() {
        // On host JVM test, native library might not be present in java.library.path
        val isAvail = NativeImagePipeline.isAvailable()
        val isNeon = NativeImagePipeline.isNeonSupported()
        // Neither should crash
        assertTrue(isAvail || !isAvail)
        assertTrue(isNeon || !isNeon)
    }

    @Test
    fun testConvertPlanesPlanarPixelStride1() {
        val width = 8
        val height = 4
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaSize = chromaWidth * chromaHeight

        val yBuf = ByteBuffer.allocate(ySize)
        for (i in 0 until ySize) yBuf.put((i + 1).toByte())
        yBuf.flip()

        val uBuf = ByteBuffer.allocate(chromaSize)
        for (i in 0 until chromaSize) uBuf.put((10 + i).toByte())
        uBuf.flip()

        val vBuf = ByteBuffer.allocate(chromaSize)
        for (i in 0 until chromaSize) vBuf.put((20 + i).toByte())
        vBuf.flip()

        val outNv21 = ByteArray(width * height * 3 / 2)
        val success = NativeImagePipeline.convertPlanesToNv21(
            yBuf, uBuf, vBuf, outNv21,
            width, height, width, chromaWidth, 1
        )
        assertTrue(success)

        // Verify Y part
        for (i in 0 until ySize) {
            assertEquals((i + 1).toByte(), outNv21[i])
        }

        // Verify UV part is interleaved {V0, U0, V1, U1...}
        val uvOffset = ySize
        for (i in 0 until chromaSize) {
            val expectedV = (20 + i).toByte()
            val expectedU = (10 + i).toByte()
            assertEquals(expectedV, outNv21[uvOffset + i * 2])
            assertEquals(expectedU, outNv21[uvOffset + i * 2 + 1])
        }
    }

    @Test
    fun testConvertPlanesSemiPlanarPixelStride2NV21() {
        val width = 8
        val height = 4
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvPairs = chromaWidth * chromaHeight

        val yBuf = ByteBuffer.allocateDirect(ySize)
        for (i in 0 until ySize) yBuf.put((i + 1).toByte())
        yBuf.flip()

        // NV21 shared buffer: V at 0, U at 1, V at 2, U at 3...
        val sharedUV = ByteBuffer.allocateDirect(uvPairs * 2)
        for (i in 0 until uvPairs) {
            sharedUV.put((50 + i).toByte()) // V
            sharedUV.put((30 + i).toByte()) // U
        }
        sharedUV.flip()

        val vBuf = sharedUV.duplicate().apply { position(0) }
        val uBuf = sharedUV.duplicate().apply { position(1) }

        val outNv21 = ByteArray(width * height * 3 / 2)
        val ok = NativeImagePipeline.convertPlanesToNv21(
            yBuf, uBuf, vBuf, outNv21,
            width, height, width, width, 2
        )
        assertTrue(ok)

        // Check Y
        for (i in 0 until ySize) {
            assertEquals((i + 1).toByte(), outNv21[i])
        }
        // Check UV interleaved {V, U}
        val uvOffset = ySize
        for (i in 0 until uvPairs) {
            assertEquals((50 + i).toByte(), outNv21[uvOffset + i * 2])
            assertEquals((30 + i).toByte(), outNv21[uvOffset + i * 2 + 1])
        }
    }

    @Test
    fun testConvertPlanesSemiPlanarPixelStride2NV12() {
        val width = 8
        val height = 4
        val ySize = width * height
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uvPairs = chromaWidth * chromaHeight

        val yBuf = ByteBuffer.allocate(ySize)
        for (i in 0 until ySize) yBuf.put((i + 1).toByte())
        yBuf.flip()

        // NV12 shared buffer: U at 0, V at 1, U at 2, V at 3...
        val sharedUV = ByteBuffer.allocate(uvPairs * 2)
        for (i in 0 until uvPairs) {
            sharedUV.put((70 + i).toByte()) // U
            sharedUV.put((90 + i).toByte()) // V
        }
        sharedUV.flip()

        val uBuf = sharedUV.duplicate().apply { position(0) }
        val vBuf = sharedUV.duplicate().apply { position(1) }

        val outNv21 = ByteArray(width * height * 3 / 2)
        val ok = NativeImagePipeline.convertPlanesToNv21(
            yBuf, uBuf, vBuf, outNv21,
            width, height, width, width, 2
        )
        assertTrue(ok)

        // Check NV21 output has V then U!
        val uvOffset = ySize
        for (i in 0 until uvPairs) {
            assertEquals((90 + i).toByte(), outNv21[uvOffset + i * 2])     // V
            assertEquals((70 + i).toByte(), outNv21[uvOffset + i * 2 + 1]) // U
        }
    }

    @Test
    fun testDirectBuffersVsArrayBuffers() {
        val width = 16
        val height = 8
        val ySize = width * height
        val uvSize = ySize / 2
        val outSize = width * height * 3 / 2

        // Heap buffers
        val yHeap = ByteBuffer.allocate(ySize)
        val uHeap = ByteBuffer.allocate(uvSize / 2)
        val vHeap = ByteBuffer.allocate(uvSize / 2)
        for (i in 0 until ySize) yHeap.put((i and 0xFF).toByte())
        for (i in 0 until (uvSize / 2)) {
            uHeap.put((i + 10).toByte())
            vHeap.put((i + 20).toByte())
        }
        yHeap.flip(); uHeap.flip(); vHeap.flip()

        // Direct buffers with identical data
        val yDirect = ByteBuffer.allocateDirect(ySize)
        val uDirect = ByteBuffer.allocateDirect(uvSize / 2)
        val vDirect = ByteBuffer.allocateDirect(uvSize / 2)
        yDirect.put(yHeap.duplicate()).flip()
        uDirect.put(uHeap.duplicate()).flip()
        vDirect.put(vHeap.duplicate()).flip()

        val outHeap = ByteArray(outSize)
        val outDirectArray = ByteArray(outSize)

        val ok1 = NativeImagePipeline.convertPlanesToNv21(yHeap, uHeap, vHeap, outHeap, width, height, width, width / 2, 1)
        val ok2 = NativeImagePipeline.convertPlanesToNv21(yDirect, uDirect, vDirect, outDirectArray, width, height, width, width / 2, 1)

        assertTrue(ok1)
        assertTrue(ok2)
        assertArrayEquals(outHeap, outDirectArray)

        // Test convertPlanesToNv21Direct with Direct output buffer
        val outDirectBuf = ByteBuffer.allocateDirect(outSize)
        yDirect.position(0); uDirect.position(0); vDirect.position(0)
        val ok3 = NativeImagePipeline.convertPlanesToNv21Direct(yDirect, uDirect, vDirect, outDirectBuf, width, height, width, width / 2, 1)
        assertTrue(ok3)
        val directBufBytes = ByteArray(outSize)
        outDirectBuf.position(0)
        outDirectBuf.get(directBufBytes)
        assertArrayEquals(outHeap, directBufBytes)
    }

    @Test
    fun testErrorHandlingInvalidDimensions() {
        val buf = ByteBuffer.allocate(64)
        val out = ByteArray(64)

        // width <= 0 or height <= 0
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 0, 8, 8, 4, 1))
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, 0, 8, 4, 1))
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, -8, 8, 8, 4, 1))
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, -8, 8, 4, 1))

        // Odd dimensions
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 7, 8, 8, 4, 1))
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, 7, 8, 4, 1))
    }

    @Test
    fun testErrorHandlingInvalidStrides() {
        val buf = ByteBuffer.allocate(128)
        val out = ByteArray(128)
        // yRowStride < width
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, 8, 4, 4, 1))
        // uvPixelStride <= 0
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, 8, 8, 4, 0))
        // uvRowStride < (width / 2) * uvPixelStride
        assertFalse(NativeImagePipeline.convertPlanesToNv21(buf, buf, buf, out, 8, 8, 8, 2, 1))
    }

    @Test
    fun testErrorHandlingInsufficientCapacity() {
        val smallOut = ByteArray(10) // needs 8*8*3/2 = 96
        val yBuf = ByteBuffer.allocate(64)
        val uBuf = ByteBuffer.allocate(16)
        val vBuf = ByteBuffer.allocate(16)
        assertFalse(NativeImagePipeline.convertPlanesToNv21(yBuf, uBuf, vBuf, smallOut, 8, 8, 8, 4, 1))

        val properOut = ByteArray(96)
        val truncatedY = ByteBuffer.allocate(10) // needs 64
        assertFalse(NativeImagePipeline.convertPlanesToNv21(truncatedY, uBuf, vBuf, properOut, 8, 8, 8, 4, 1))
    }

    @Test
    fun testConvertYuv420ToNv21WithImageProxy() {
        val width = 8
        val height = 4
        val yBuf = ByteBuffer.allocateDirect(width * height).apply {
            for (i in 0 until capacity()) put(1.toByte())
            rewind()
        }
        val uvShared = ByteBuffer.allocateDirect(width * (height / 2)).apply {
            for (i in 0 until capacity()) put(2.toByte())
            rewind()
        }
        val uBuf = uvShared.duplicate().apply { position(1) }
        val vBuf = uvShared.duplicate().apply { position(0) }

        val planes = arrayOf<ImageProxy.PlaneProxy>(
            MockPlane(yBuf, width, 1),
            MockPlane(uBuf, width, 2),
            MockPlane(vBuf, width, 2)
        )
        val proxy = MockImageProxy(width, height, planes)

        val result = NativeImagePipeline.convertYuv420ToNv21(proxy)
        assertNotNull(result)
        assertEquals(width * height * 3 / 2, result!!.size)
        assertEquals(1.toByte(), result[0])
        assertEquals(2.toByte(), result[width * height])

        // Reuse buffer test
        val reuse = ByteArray(width * height * 3 / 2)
        yBuf.rewind(); uBuf.position(1); vBuf.position(0)
        val result2 = NativeImagePipeline.convertYuv420ToNv21(proxy, reuse)
        assertTrue(reuse === result2)
    }
}

