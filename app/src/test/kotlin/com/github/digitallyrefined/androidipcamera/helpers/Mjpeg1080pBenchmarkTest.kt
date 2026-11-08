package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Locale

class Mjpeg1080pBenchmarkTest {

    private class SyntheticPlane(
        private val byteBuffer: ByteBuffer,
        private val rStride: Int,
        private val pStride: Int
    ) : ImageProxy.PlaneProxy {
        override fun getBuffer(): ByteBuffer = byteBuffer
        override fun getRowStride(): Int = rStride
        override fun getPixelStride(): Int = pStride
    }

    private class SyntheticYuvImageProxy(
        private val w: Int,
        private val h: Int
    ) : ImageProxy {
        private val ySize = w * h
        private val uvSize = (w * h) / 2
        private val yBuffer = ByteBuffer.allocateDirect(ySize).apply {
            for (i in 0 until ySize) put((i and 0xFF).toByte())
            rewind()
        }
        // Emulate CameraX / Tensor layout where U and V are semi-planar with pixelStride 2
        private val uvShared = ByteBuffer.allocateDirect(w * (h / 2)).apply {
            for (i in 0 until (w * (h / 2))) put((i and 0xFF).toByte())
            rewind()
        }
        private val uBuffer = uvShared.duplicate().apply { position(1) }
        private val vBuffer = uvShared.duplicate().apply { position(0) }

        private val planeArray: Array<ImageProxy.PlaneProxy> = arrayOf(
            SyntheticPlane(yBuffer, w, 1),
            SyntheticPlane(uBuffer, w, 2),
            SyntheticPlane(vBuffer, w, 2)
        )

        override fun getWidth(): Int = w
        override fun getHeight(): Int = h
        override fun getFormat(): Int = ImageFormat.YUV_420_888
        override fun getCropRect(): Rect = Rect().apply { left = 0; top = 0; right = w; bottom = h }
        override fun setCropRect(rect: Rect?) {}
        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = planeArray
        override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()
        override fun getImage(): android.media.Image? = null
        override fun close() {}

        fun resetPositions() {
            yBuffer.position(0)
            yBuffer.limit(yBuffer.capacity())
            uBuffer.position(1)
            uBuffer.limit(uBuffer.capacity())
            vBuffer.position(0)
            vBuffer.limit(vBuffer.capacity())
        }
    }

    @Test
    fun benchmark1080pConversion() {
        val width = 1920
        val height = 1080
        val syntheticImage = SyntheticYuvImageProxy(width, height)

        var nv21Buffer: ByteArray? = null
        var nv21RowBuffer: ByteArray? = null

        // Warmup: 5 iterations
        for (i in 0 until 5) {
            syntheticImage.resetPositions()
            val nv21 = convertYUV420toNV21(syntheticImage, nv21Buffer, nv21RowBuffer)
            nv21Buffer = nv21
            val jpeg = convertNV21toJPEG(nv21, width, height, 85)
            if (nv21RowBuffer == null || nv21RowBuffer!!.size < width) {
                nv21RowBuffer = ByteArray(width)
            }
        }

        // Benchmark: 30 iterations at 1080p
        val iterations = 30
        val startTime = System.nanoTime()

        for (i in 0 until iterations) {
            syntheticImage.resetPositions()
            val nv21 = convertYUV420toNV21(syntheticImage, nv21Buffer, nv21RowBuffer)
            nv21Buffer = nv21
            val jpeg = convertNV21toJPEG(nv21, width, height, 85)
        }

        val totalTimeMs = (System.nanoTime() - startTime) / 1_000_000.0
        val avgLatencyMs = totalTimeMs / iterations
        val throughputFps = 1000.0 / avgLatencyMs

        println(String.format(Locale.US, "BENCHMARK_RESULT fps=%.2f latency_ms=%.2f", throughputFps, avgLatencyMs))
    }
}
