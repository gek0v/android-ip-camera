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

    @JvmStatic
    private external fun nativeRotateNV21(
        src: ByteArray,
        dest: ByteArray,
        width: Int,
        height: Int,
        rotation: Int
    ): Boolean

    @JvmStatic
    private external fun nativeMirrorNV21(
        src: ByteArray,
        dest: ByteArray,
        width: Int,
        height: Int
    ): Boolean

    fun rotateNV21Kotlin(src: ByteArray, dest: ByteArray, width: Int, height: Int, rotation: Int): Boolean {
        val norm = ((rotation % 360) + 360) % 360
        val requiredSize = width * height * 3 / 2
        if (src.size < requiredSize || dest.size < requiredSize) return false
        if (norm == 0) {
            System.arraycopy(src, 0, dest, 0, requiredSize)
            return true
        }

        val frameSize = width * height
        when (norm) {
            90 -> {
                val dstWidth = height
                val tileSize = 32
                for (tj in 0 until height step tileSize) {
                    val tHeight = minOf(tileSize, height - tj)
                    for (ti in 0 until width step tileSize) {
                        val tWidth = minOf(tileSize, width - ti)
                        for (j in 0 until tHeight) {
                            val srcY = tj + j
                            val srcRowOffset = srcY * width + ti
                            val dstX = height - 1 - srcY
                            for (i in 0 until tWidth) {
                                val dstY = ti + i
                                dest[dstY * dstWidth + dstX] = src[srcRowOffset + i]
                            }
                        }
                    }
                }

                val uvWidth = width shr 1
                val uvHeight = height shr 1
                val uvTileSize = 16
                for (tj in 0 until uvHeight step uvTileSize) {
                    val tHeight = minOf(uvTileSize, uvHeight - tj)
                    for (ti in 0 until uvWidth step uvTileSize) {
                        val tWidth = minOf(uvTileSize, uvWidth - ti)
                        for (j in 0 until tHeight) {
                            val srcY = tj + j
                            val srcRowOffset = frameSize + (srcY * width) + (ti shl 1)
                            val dstX = (uvHeight - 1 - srcY) shl 1
                            for (i in 0 until tWidth) {
                                val dstY = ti + i
                                val dstOffset = frameSize + (dstY * height) + dstX
                                val srcOffset = srcRowOffset + (i shl 1)
                                dest[dstOffset] = src[srcOffset]
                                dest[dstOffset + 1] = src[srcOffset + 1]
                            }
                        }
                    }
                }
                return true
            }
            180 -> {
                var k = 0
                val end16 = frameSize and 15.inv()
                var srcIdx = frameSize - 1
                while (k < end16) {
                    dest[k] = src[srcIdx]
                    dest[k + 1] = src[srcIdx - 1]
                    dest[k + 2] = src[srcIdx - 2]
                    dest[k + 3] = src[srcIdx - 3]
                    dest[k + 4] = src[srcIdx - 4]
                    dest[k + 5] = src[srcIdx - 5]
                    dest[k + 6] = src[srcIdx - 6]
                    dest[k + 7] = src[srcIdx - 7]
                    dest[k + 8] = src[srcIdx - 8]
                    dest[k + 9] = src[srcIdx - 9]
                    dest[k + 10] = src[srcIdx - 10]
                    dest[k + 11] = src[srcIdx - 11]
                    dest[k + 12] = src[srcIdx - 12]
                    dest[k + 13] = src[srcIdx - 13]
                    dest[k + 14] = src[srcIdx - 14]
                    dest[k + 15] = src[srcIdx - 15]
                    k += 16
                    srcIdx -= 16
                }
                while (k < frameSize) {
                    dest[k++] = src[srcIdx--]
                }

                var uvK = frameSize
                val totalSize = frameSize + (frameSize shr 1)
                var i = totalSize - 2
                while (i >= frameSize) {
                    dest[uvK] = src[i]
                    dest[uvK + 1] = src[i + 1]
                    uvK += 2
                    i -= 2
                }
                return true
            }
            270 -> {
                val dstWidth = height
                val tileSize = 32
                for (tj in 0 until height step tileSize) {
                    val tHeight = minOf(tileSize, height - tj)
                    for (ti in 0 until width step tileSize) {
                        val tWidth = minOf(tileSize, width - ti)
                        for (j in 0 until tHeight) {
                            val srcY = tj + j
                            val srcRowOffset = srcY * width + ti
                            val dstX = srcY
                            for (i in 0 until tWidth) {
                                val dstY = width - 1 - (ti + i)
                                dest[dstY * dstWidth + dstX] = src[srcRowOffset + i]
                            }
                        }
                    }
                }

                val uvWidth = width shr 1
                val uvHeight = height shr 1
                val uvTileSize = 16
                for (tj in 0 until uvHeight step uvTileSize) {
                    val tHeight = minOf(uvTileSize, uvHeight - tj)
                    for (ti in 0 until uvWidth step uvTileSize) {
                        val tWidth = minOf(uvTileSize, uvWidth - ti)
                        for (j in 0 until tHeight) {
                            val srcY = tj + j
                            val srcRowOffset = frameSize + (srcY * width) + (ti shl 1)
                            val dstX = srcY shl 1
                            for (i in 0 until tWidth) {
                                val dstY = uvWidth - 1 - (ti + i)
                                val dstOffset = frameSize + (dstY * height) + dstX
                                val srcOffset = srcRowOffset + (i shl 1)
                                dest[dstOffset] = src[srcOffset]
                                dest[dstOffset + 1] = src[srcOffset + 1]
                            }
                        }
                    }
                }
                return true
            }
            else -> {
                System.arraycopy(src, 0, dest, 0, requiredSize)
                return true
            }
        }
    }

    fun rotateNV21(src: ByteArray, dest: ByteArray, width: Int, height: Int, rotation: Int): Boolean {
        if (isAvailable()) {
            try {
                if (nativeRotateNV21(src, dest, width, height, rotation)) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return rotateNV21Kotlin(src, dest, width, height, rotation)
    }

    fun mirrorNV21Kotlin(src: ByteArray, dest: ByteArray, width: Int, height: Int): Boolean {
        val requiredSize = width * height * 3 / 2
        if (src.size < requiredSize || dest.size < requiredSize) return false
        val frameSize = width * height

        for (row in 0 until height) {
            val rowOffset = row * width
            var left = 0
            var right = width - 1
            val end8 = width and 7.inv()
            while (left < end8) {
                dest[rowOffset + right] = src[rowOffset + left]
                dest[rowOffset + right - 1] = src[rowOffset + left + 1]
                dest[rowOffset + right - 2] = src[rowOffset + left + 2]
                dest[rowOffset + right - 3] = src[rowOffset + left + 3]
                dest[rowOffset + right - 4] = src[rowOffset + left + 4]
                dest[rowOffset + right - 5] = src[rowOffset + left + 5]
                dest[rowOffset + right - 6] = src[rowOffset + left + 6]
                dest[rowOffset + right - 7] = src[rowOffset + left + 7]
                left += 8
                right -= 8
            }
            while (left < width) {
                dest[rowOffset + right] = src[rowOffset + left]
                left++
                right--
            }
        }

        val uvHeight = height shr 1
        val uvWidth = width shr 1
        for (row in 0 until uvHeight) {
            val rowOffset = frameSize + (row * width)
            for (col in 0 until uvWidth) {
                val srcCol = col shl 1
                val destCol = (uvWidth - 1 - col) shl 1
                dest[rowOffset + destCol] = src[rowOffset + srcCol]
                dest[rowOffset + destCol + 1] = src[rowOffset + srcCol + 1]
            }
        }
        return true
    }

    fun mirrorNV21(src: ByteArray, dest: ByteArray, width: Int, height: Int): Boolean {
        if (isAvailable()) {
            try {
                if (nativeMirrorNV21(src, dest, width, height)) {
                    return true
                }
            } catch (_: Throwable) {}
        }
        return mirrorNV21Kotlin(src, dest, width, height)
    }

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

        // 1. Copy Y plane using fast bulk memory transfers
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

        // Fast Path 1: Semi-planar NV21 (pixelStride == 2)
        if (uvPixelStride == 2) {
            var isContiguousNv21 = false
            var isContiguousNv12 = false

            if (vBuf.hasArray() && uBuf.hasArray() && vBuf.array() === uBuf.array()) {
                val vOff = vBuf.arrayOffset() + vPos
                val uOff = uBuf.arrayOffset() + uPos
                if (vOff + 1 == uOff) isContiguousNv21 = true
                else if (uOff + 1 == vOff) isContiguousNv12 = true
            } else if (!vBuf.isReadOnly && vBuf.remaining() > 1 && uBuf.remaining() > 0) {
                try {
                    val origV = vBuf.get(vPos + 1)
                    val probe = (origV.toInt() xor 0x5A).toByte()
                    vBuf.put(vPos + 1, probe)
                    if (uBuf.get(uPos) == probe) {
                        isContiguousNv21 = true
                    }
                    vBuf.put(vPos + 1, origV)
                } catch (_: Throwable) {}
            }

            if (!isContiguousNv21 && !uBuf.isReadOnly && uBuf.remaining() > 1 && vBuf.remaining() > 0) {
                try {
                    val origU = uBuf.get(uPos + 1)
                    val probe = (origU.toInt() xor 0x5A).toByte()
                    uBuf.put(uPos + 1, probe)
                    if (vBuf.get(vPos) == probe) {
                        isContiguousNv12 = true
                    }
                    uBuf.put(uPos + 1, origU)
                } catch (_: Throwable) {}
            }

            if (isContiguousNv21) {
                val oldVPos = vBuf.position()
                val totalUvBytes = chromaWidth * chromaHeight * 2
                if (uvRowStride == width) {
                    vBuf.position(vPos)
                    vBuf.get(outNv21, uvOffset, totalUvBytes)
                    vBuf.position(oldVPos)
                } else {
                    val rowBytes = chromaWidth * 2
                    for (r in 0 until chromaHeight) {
                        vBuf.position(vPos + r * uvRowStride)
                        vBuf.get(outNv21, uvOffset + r * width, rowBytes)
                    }
                    vBuf.position(oldVPos)
                }
                return true
            }

            if (isContiguousNv12) {
                val oldUPos = uBuf.position()
                val rowBytes = chromaWidth * 2
                val rowBuf = ByteArray(rowBytes)
                for (r in 0 until chromaHeight) {
                    uBuf.position(uPos + r * uvRowStride)
                    uBuf.get(rowBuf, 0, rowBytes)
                    val dstRowOffset = uvOffset + r * width
                    var c = 0
                    val end8 = (rowBytes and 7.inv())
                    while (c < end8) {
                        outNv21[dstRowOffset + c] = rowBuf[c + 1]
                        outNv21[dstRowOffset + c + 1] = rowBuf[c]
                        outNv21[dstRowOffset + c + 2] = rowBuf[c + 3]
                        outNv21[dstRowOffset + c + 3] = rowBuf[c + 2]
                        outNv21[dstRowOffset + c + 4] = rowBuf[c + 5]
                        outNv21[dstRowOffset + c + 5] = rowBuf[c + 4]
                        outNv21[dstRowOffset + c + 6] = rowBuf[c + 7]
                        outNv21[dstRowOffset + c + 7] = rowBuf[c + 6]
                        c += 8
                    }
                    while (c < rowBytes) {
                        outNv21[dstRowOffset + c] = rowBuf[c + 1]
                        outNv21[dstRowOffset + c + 1] = rowBuf[c]
                        c += 2
                    }
                }
                uBuf.position(oldUPos)
                return true
            }
        }

        // Fast Path 2: Planar I420 (uvPixelStride == 1) with row block buffers
        if (uvPixelStride == 1) {
            val oldUPos = uBuf.position()
            val oldVPos = vBuf.position()
            val uRow = ByteArray(chromaWidth)
            val vRow = ByteArray(chromaWidth)
            for (r in 0 until chromaHeight) {
                uBuf.position(uPos + r * uvRowStride)
                vBuf.position(vPos + r * uvRowStride)
                uBuf.get(uRow, 0, chromaWidth)
                vBuf.get(vRow, 0, chromaWidth)
                val dstRowStart = uvOffset + r * width
                var c = 0
                val end8 = (chromaWidth and 7.inv())
                while (c < end8) {
                    outNv21[dstRowStart + (c shl 1)] = vRow[c]
                    outNv21[dstRowStart + (c shl 1) + 1] = uRow[c]
                    outNv21[dstRowStart + ((c + 1) shl 1)] = vRow[c + 1]
                    outNv21[dstRowStart + ((c + 1) shl 1) + 1] = uRow[c + 1]
                    outNv21[dstRowStart + ((c + 2) shl 1)] = vRow[c + 2]
                    outNv21[dstRowStart + ((c + 2) shl 1) + 1] = uRow[c + 2]
                    outNv21[dstRowStart + ((c + 3) shl 1)] = vRow[c + 3]
                    outNv21[dstRowStart + ((c + 3) shl 1) + 1] = uRow[c + 3]
                    outNv21[dstRowStart + ((c + 4) shl 1)] = vRow[c + 4]
                    outNv21[dstRowStart + ((c + 4) shl 1) + 1] = uRow[c + 4]
                    outNv21[dstRowStart + ((c + 5) shl 1)] = vRow[c + 5]
                    outNv21[dstRowStart + ((c + 5) shl 1) + 1] = uRow[c + 5]
                    outNv21[dstRowStart + ((c + 6) shl 1)] = vRow[c + 6]
                    outNv21[dstRowStart + ((c + 6) shl 1) + 1] = uRow[c + 6]
                    outNv21[dstRowStart + ((c + 7) shl 1)] = vRow[c + 7]
                    outNv21[dstRowStart + ((c + 7) shl 1) + 1] = uRow[c + 7]
                    c += 8
                }
                while (c < chromaWidth) {
                    outNv21[dstRowStart + (c shl 1)] = vRow[c]
                    outNv21[dstRowStart + (c shl 1) + 1] = uRow[c]
                    c++
                }
            }
            uBuf.position(oldUPos)
            vBuf.position(oldVPos)
            return true
        }

        // Fallback Path 3: General uvPixelStride with block transfers
        val oldUPos = uBuf.position()
        val oldVPos = vBuf.position()
        val rowLen = (chromaWidth - 1) * uvPixelStride + 1
        val uRow = ByteArray(rowLen)
        val vRow = ByteArray(rowLen)
        for (r in 0 until chromaHeight) {
            uBuf.position(uPos + r * uvRowStride)
            vBuf.position(vPos + r * uvRowStride)
            uBuf.get(uRow, 0, rowLen)
            vBuf.get(vRow, 0, rowLen)
            val dstRowStart = uvOffset + r * width
            for (c in 0 until chromaWidth) {
                val idx = c * uvPixelStride
                outNv21[dstRowStart + (c shl 1)] = vRow[idx]
                outNv21[dstRowStart + (c shl 1) + 1] = uRow[idx]
            }
        }
        uBuf.position(oldUPos)
        vBuf.position(oldVPos)
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
