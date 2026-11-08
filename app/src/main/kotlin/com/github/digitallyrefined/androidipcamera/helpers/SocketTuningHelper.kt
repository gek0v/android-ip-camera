package com.github.digitallyrefined.androidipcamera.helpers

import android.os.Build
import android.util.Log
import java.io.FileDescriptor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.Socket

object SocketTuningHelper {
    private const val TAG = "SocketTuningHelper"

    const val IPPROTO_TCP: Int = 6
    const val TCP_NOTSENT_LOWAT: Int = 25
    const val DEFAULT_NOTSENT_LOWAT_BYTES: Int = 16384
    const val HIGH_NOTSENT_LOWAT_BYTES: Int = 32768
    const val DEFAULT_CONGESTION_TIMEOUT_MS: Long = 250L

    @Volatile var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
    @Volatile var osSetsockoptIntInvoker: ((FileDescriptor, Int, Int, Int) -> Unit)? = null
    @Volatile var osPollOutChecker: ((FileDescriptor) -> Boolean)? = null
    @Volatile var fdExtractor: ((Socket) -> FileDescriptor?)? = null

    fun resetTestingHooks() {
        sdkIntProvider = { Build.VERSION.SDK_INT }
        osSetsockoptIntInvoker = null
        osPollOutChecker = null
        fdExtractor = null
    }

    data class TuningResult(
        val tcpNoDelayApplied: Boolean,
        val tcpNotSentLowatApplied: Boolean,
        val lowatBytes: Int = 0,
        val errorMessage: String? = null
    )

    fun extractFileDescriptor(socket: Socket): FileDescriptor? {
        if (socket.isClosed) return null
        val custom = fdExtractor
        if (custom != null) {
            val fd = custom(socket)
            return if (fd != null && fd.valid()) fd else null
        }
        try {
            val method: Method = socket.javaClass.getMethod("getFileDescriptor$")
            val fd = method.invoke(socket) as? FileDescriptor
            if (fd != null && fd.valid()) return fd
        } catch (_: Throwable) {}

        try {
            var sClass: Class<*>? = socket.javaClass
            var implField: Field? = null
            while (sClass != null && implField == null) {
                try {
                    implField = sClass.getDeclaredField("impl").apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    sClass = sClass.superclass
                }
            }
            val impl = implField?.get(socket)
            if (impl != null) {
                var implClass: Class<*>? = impl.javaClass
                var fdField: Field? = null
                while (implClass != null && fdField == null) {
                    try {
                        fdField = implClass.getDeclaredField("fd").apply { isAccessible = true }
                    } catch (_: NoSuchFieldException) {
                        implClass = implClass.superclass
                    }
                }
                val fd = fdField?.get(impl) as? FileDescriptor
                if (fd != null && fd.valid()) return fd
            }
        } catch (_: Throwable) {}
        return null
    }

    fun tuneStreamingSocket(
        socket: Socket,
        notsentLowatBytes: Int = DEFAULT_NOTSENT_LOWAT_BYTES
    ): TuningResult {
        var tcpNoDelaySuccess = false
        var tcpNotSentLowatSuccess = false
        var errorMsg: String? = null

        try {
            socket.tcpNoDelay = true
            tcpNoDelaySuccess = socket.tcpNoDelay
        } catch (e: Throwable) {
            errorMsg = "Failed to set TCP_NODELAY: " + e.message
            Log.w(TAG, errorMsg)
        }

        val sdkInt = try { sdkIntProvider() } catch (_: Throwable) { 0 }
        if (sdkInt >= 29) {
            val fd = extractFileDescriptor(socket)
            if (fd != null && fd.valid()) {
                val ok = applyTcpNotSentLowat(fd, notsentLowatBytes)
                tcpNotSentLowatSuccess = ok
                if (!ok && errorMsg == null) {
                    errorMsg = "setsockoptInt TCP_NOTSENT_LOWAT failed or unsupported"
                }
            } else {
                if (errorMsg == null) {
                    errorMsg = "Could not extract valid FileDescriptor from socket"
                }
            }
        }

        return TuningResult(
            tcpNoDelayApplied = tcpNoDelaySuccess,
            tcpNotSentLowatApplied = tcpNotSentLowatSuccess,
            lowatBytes = if (tcpNotSentLowatSuccess) notsentLowatBytes else 0,
            errorMessage = errorMsg
        )
    }

    fun applyTcpNotSentLowat(fd: FileDescriptor, lowatBytes: Int): Boolean {
        return try {
            val invoker = osSetsockoptIntInvoker
            if (invoker != null) {
                invoker(fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, lowatBytes)
                true
            } else {
                val osClass = Class.forName("android.system.Os")
                val setsockoptMethod = osClass.getMethod(
                    "setsockoptInt",
                    FileDescriptor::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setsockoptMethod.invoke(null, fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, lowatBytes)
                true
            }
        } catch (t: Throwable) {
            Log.d(TAG, "applyTcpNotSentLowat failed: " + t.message)
            false
        }
    }

    fun isSocketBufferReadyForWrite(socket: Socket): Boolean {
        if (!isSocketAlive(socket)) return false

        val checker = osPollOutChecker
        if (checker != null) {
            val fd = extractFileDescriptor(socket) ?: FileDescriptor()
            return checker(fd)
        }

        val fd = extractFileDescriptor(socket) ?: return true
        return try {
            val osClass = Class.forName("android.system.Os")
            val osConstantsClass = Class.forName("android.system.OsConstants")
            val polloutField = osConstantsClass.getField("POLLOUT")
            val polloutValue = (polloutField.get(null) as Number).toShort()

            val structPollfdClass = Class.forName("android.system.StructPollfd")
            val pfd = structPollfdClass.getDeclaredConstructor().newInstance()
            structPollfdClass.getField("fd").set(pfd, fd)
            structPollfdClass.getField("events").setShort(pfd, polloutValue)

            val pollArray = java.lang.reflect.Array.newInstance(structPollfdClass, 1)
            java.lang.reflect.Array.set(pollArray, 0, pfd)

            val pollMethod = osClass.getMethod("poll", pollArray.javaClass, Int::class.javaPrimitiveType)
            val rc = pollMethod.invoke(null, pollArray, 0) as Int
            if (rc > 0) {
                val revents = structPollfdClass.getField("revents").getShort(pfd).toInt()
                (revents and polloutValue.toInt()) != 0
            } else {
                false
            }
        } catch (_: Throwable) {
            true
        }
    }

    fun isSocketAlive(socket: Socket): Boolean {
        return try {
            socket.isConnected && !socket.isClosed && !socket.isOutputShutdown
        } catch (_: Throwable) {
            false
        }
    }

    fun isSocketCongested(
        socket: Socket,
        lastSuccessfulWriteMs: Long,
        isWriting: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        congestionThresholdMs: Long = DEFAULT_CONGESTION_TIMEOUT_MS
    ): Boolean {
        if (!isSocketAlive(socket)) return true

        if (isWriting && (nowMs - lastSuccessfulWriteMs) > congestionThresholdMs) {
            return true
        }

        if (!isSocketBufferReadyForWrite(socket)) {
            return true
        }

        return false
    }

    fun shouldDropFrame(
        socket: Socket,
        lastSuccessfulWriteMs: Long,
        isWriting: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        congestionThresholdMs: Long = DEFAULT_CONGESTION_TIMEOUT_MS
    ): Boolean {
        return isSocketCongested(
            socket = socket,
            lastSuccessfulWriteMs = lastSuccessfulWriteMs,
            isWriting = isWriting,
            nowMs = nowMs,
            congestionThresholdMs = congestionThresholdMs
        )
    }

    fun shouldDropFrame(
        client: StreamingServerHelper.Client,
        nowMs: Long = System.currentTimeMillis(),
        congestionThresholdMs: Long = DEFAULT_CONGESTION_TIMEOUT_MS
    ): Boolean {
        return shouldDropFrame(
            socket = client.socket,
            lastSuccessfulWriteMs = client.lastSuccessfulWriteMs,
            isWriting = client.isWriting,
            nowMs = nowMs,
            congestionThresholdMs = congestionThresholdMs
        )
    }
}
