package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object NativeImagePipeline {
    private var libraryLoaded: Boolean = false

    init {
        libraryLoaded = try {
            System.loadLibrary("neon_image_pipeline")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun isNeonSupported(): Boolean = if (libraryLoaded) {
        try {
            nativeIsNeonSupported()
        } catch (_: Throwable) {
            false
        }
    } else {
        false
    }

    @JvmStatic
    private external fun nativeIsNeonSupported(): Boolean

    @JvmStatic
    private external fun nativeConvertPlanesToNv21Direct(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        outNv21: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Boolean

    @JvmStatic
    private external fun nativeConvertPlanesToNv21ByteArray(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        outNv21: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Boolean

    fun convertPlanesToNv21Kotlin(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        outNv21: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) return false
        val requiredSize = width * height * 3 / 2
        if (outNv21.size < requiredSize) return false
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        if (yRowStride < width || uvPixelStride <= 0 || uvRowStride < chromaWidth * uvPixelStride) return false

        val yPos = yBuf.position()
        val uPos = uBuf.position()
        val vPos = vBuf.position()

        val yReq = (height - 1) * yRowStride + width
        if (yBuf.remaining() < yReq) return false
        val uvReq = (chromaHeight - 1) * uvRowStride + (chromaWidth - 1) * uvPixelStride + 1
        if (uBuf.remaining() < uvReq || vBuf.remaining() < uvReq) return false

        // 1. Copy Y plane
        if (yRowStride == width) {
            val oldPos = yBuf.position()
            yBuf.get(outNv21, 0, width * height)
            yBuf.position(oldPos)
        } else {
            val oldPos = yBuf.position()
            for (r in 0 until height) {
                yBuf.position(oldPos + r * yRowStride)
                yBuf.get(outNv21, r * width, width)
            }
            yBuf.position(oldPos)
        }

        // 2. Interleave UV planes into NV21 {V, U, V, U...}
        val uvOffset = width * height
        for (r in 0 until chromaHeight) {
            val uRowStart = uPos + r * uvRowStride
            val vRowStart = vPos + r * uvRowStride
            val dstRowStart = uvOffset + r * width
            for (c in 0 until chromaWidth) {
                outNv21[dstRowStart + c * 2] = vBuf.get(vRowStart + c * uvPixelStride)
                outNv21[dstRowStart + c * 2 + 1] = uBuf.get(uRowStart + c * uvPixelStride)
            }
        }
        return true
    }

    fun convertPlanesToNv21(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        outNv21: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Boolean {
        if (isAvailable()) {
            try {
                if (nativeConvertPlanesToNv21ByteArray(
                        yBuf, uBuf, vBuf, outNv21,
                        width, height, yRowStride, uvRowStride, uvPixelStride
                    )) {
                    return true
                }
            } catch (_: Throwable) {
                // Fallback to Kotlin
            }
        }
        return convertPlanesToNv21Kotlin(
            yBuf, uBuf, vBuf, outNv21,
            width, height, yRowStride, uvRowStride, uvPixelStride
        )
    }

    fun convertPlanesToNv21Direct(
        yBuf: ByteBuffer,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        outNv21: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): Boolean {
        if (isAvailable() && yBuf.isDirect && uBuf.isDirect && vBuf.isDirect && outNv21.isDirect) {
            try {
                if (nativeConvertPlanesToNv21Direct(
                        yBuf, uBuf, vBuf, outNv21,
                        width, height, yRowStride, uvRowStride, uvPixelStride
                    )) {
                    return true
                }
            } catch (_: Throwable) {
                // Fallback to Kotlin
            }
        }
        val requiredSize = width * height * 3 / 2
        if (outNv21.remaining() < requiredSize) return false
        val temp = ByteArray(requiredSize)
        if (convertPlanesToNv21Kotlin(
                yBuf, uBuf, vBuf, temp,
                width, height, yRowStride, uvRowStride, uvPixelStride
            )) {
            val oldPos = outNv21.position()
            outNv21.put(temp)
            outNv21.position(oldPos)
            return true
        }
        return false
    }

    fun convertYuv420ToNv21(
        image: ImageProxy,
        reuse: ByteArray? = null
    ): ByteArray? {
        try {
            val crop: Rect? = try { image.cropRect } catch (_: Throwable) { null }
            val width = if (crop != null && crop.right > crop.left) crop.right - crop.left else image.width
            val height = if (crop != null && crop.bottom > crop.top) crop.bottom - crop.top else image.height
            val cropTop = crop?.top ?: 0
            val cropLeft = crop?.left ?: 0

            if (width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) return null

            val planes = image.planes
            if (planes.size < 3) return null

            val requiredSize = (width * height * 3) / 2
            val data = if (reuse != null && reuse.size >= requiredSize) reuse else ByteArray(requiredSize)

            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val (adjY, adjU, adjV) = if (cropTop != 0 || cropLeft != 0) {
                val yDup = yBuf.duplicate()
                val uDup = uBuf.duplicate()
                val vDup = vBuf.duplicate()
                yDup.position(yDup.position() + yPlane.rowStride * cropTop + yPlane.pixelStride * cropLeft)
                uDup.position(uDup.position() + uPlane.rowStride * (cropTop shr 1) + uPlane.pixelStride * (cropLeft shr 1))
                vDup.position(vDup.position() + vPlane.rowStride * (cropTop shr 1) + vPlane.pixelStride * (cropLeft shr 1))
                Triple(yDup, uDup, vDup)
            } else {
                Triple(yBuf, uBuf, vBuf)
            }

            val success = convertPlanesToNv21(
                adjY, adjU, adjV, data,
                width, height,
                yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
            )

            return if (success) data else null
        } catch (_: Throwable) {
            return null
        }
    }
}

