package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

class SocketTuningHelperTest {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var acceptedSocket: Socket? = null
    private var tempRafFile: File? = null
    private var tempRaf: RandomAccessFile? = null
    private var validTestFd: FileDescriptor? = null

    @Before
    fun setUp() {
        SocketTuningHelper.resetTestingHooks()
        tempRafFile = File.createTempFile("test_fd", ".tmp")
        tempRaf = RandomAccessFile(tempRafFile, "rw")
        validTestFd = tempRaf!!.fd
        SocketTuningHelper.fdExtractor = { socket -> if (socket.isClosed) null else validTestFd }

        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        clientSocket = Socket("127.0.0.1", port)
        acceptedSocket = serverSocket!!.accept()
    }

    @After
    fun tearDown() {
        SocketTuningHelper.resetTestingHooks()
        try { clientSocket?.close() } catch (_: Throwable) {}
        try { acceptedSocket?.close() } catch (_: Throwable) {}
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { tempRaf?.close() } catch (_: Throwable) {}
        try { tempRafFile?.delete() } catch (_: Throwable) {}
    }

    @Test
    fun protocolConstantsMatchLinuxAndProposalSpecs() {
        assertEquals(6, SocketTuningHelper.IPPROTO_TCP)
        assertEquals(25, SocketTuningHelper.TCP_NOTSENT_LOWAT)
        assertEquals(16384, SocketTuningHelper.DEFAULT_NOTSENT_LOWAT_BYTES)
        assertEquals(32768, SocketTuningHelper.HIGH_NOTSENT_LOWAT_BYTES)
        assertEquals(250L, SocketTuningHelper.DEFAULT_CONGESTION_TIMEOUT_MS)
    }

    @Test
    fun extractFileDescriptorReturnsValidFdForConnectedSocket() {
        val socket = clientSocket!!
        val fd = SocketTuningHelper.extractFileDescriptor(socket)
        assertNotNull(fd)
        assertTrue(fd!!.valid())
    }

    @Test
    fun extractFileDescriptorReturnsNullForClosedSocket() {
        val socket = clientSocket!!
        socket.close()
        val fd = SocketTuningHelper.extractFileDescriptor(socket)
        assertNull(fd)
    }

    @Test
    fun tuneStreamingSocketEnablesTcpNoDelayOnAnyApi() {
        SocketTuningHelper.sdkIntProvider = { 24 }
        val socket = clientSocket!!
        socket.tcpNoDelay = false

        val result = SocketTuningHelper.tuneStreamingSocket(socket)

        assertTrue(socket.tcpNoDelay)
        assertTrue(result.tcpNoDelayApplied)
        assertFalse(result.tcpNotSentLowatApplied)
        assertEquals(0, result.lowatBytes)
    }

    @Test
    fun tuneStreamingSocketAppliesTcpNotSentLowatOnApi29Plus() {
        SocketTuningHelper.sdkIntProvider = { 29 }
        var invokedFd: FileDescriptor? = null
        var invokedLevel = -1
        var invokedOption = -1
        var invokedValue = -1

        SocketTuningHelper.osSetsockoptIntInvoker = { fd, level, opt, value ->
            invokedFd = fd
            invokedLevel = level
            invokedOption = opt
            invokedValue = value
        }

        val socket = clientSocket!!
        val result = SocketTuningHelper.tuneStreamingSocket(socket, SocketTuningHelper.DEFAULT_NOTSENT_LOWAT_BYTES)

        assertTrue(result.tcpNoDelayApplied)
        assertTrue(result.tcpNotSentLowatApplied)
        assertEquals(16384, result.lowatBytes)
        assertNull(result.errorMessage)

        assertNotNull(invokedFd)
        assertTrue(invokedFd!!.valid())
        assertEquals(SocketTuningHelper.IPPROTO_TCP, invokedLevel)
        assertEquals(SocketTuningHelper.TCP_NOTSENT_LOWAT, invokedOption)
        assertEquals(16384, invokedValue)
    }

    @Test
    fun tuneStreamingSocketSupportsCustomLowatLimit32KB() {
        SocketTuningHelper.sdkIntProvider = { 34 }
        var invokedValue = -1

        SocketTuningHelper.osSetsockoptIntInvoker = { _, _, _, value ->
            invokedValue = value
        }

        val socket = clientSocket!!
        val result = SocketTuningHelper.tuneStreamingSocket(socket, SocketTuningHelper.HIGH_NOTSENT_LOWAT_BYTES)

        assertTrue(result.tcpNotSentLowatApplied)
        assertEquals(32768, result.lowatBytes)
        assertEquals(32768, invokedValue)
    }

    @Test
    fun tuneStreamingSocketHandlesSetsockoptExceptionDefensively() {
        SocketTuningHelper.sdkIntProvider = { 30 }
        SocketTuningHelper.osSetsockoptIntInvoker = { _, _, _, _ ->
            throw RuntimeException("Simulated setsockopt failure / Os error")
        }

        val socket = clientSocket!!
        val result = SocketTuningHelper.tuneStreamingSocket(socket)

        assertTrue(result.tcpNoDelayApplied)
        assertFalse(result.tcpNotSentLowatApplied)
        assertEquals(0, result.lowatBytes)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun isSocketAliveReturnsFalseWhenClosedOrShutdown() {
        val socket = clientSocket!!
        assertTrue(SocketTuningHelper.isSocketAlive(socket))

        socket.shutdownOutput()
        assertFalse(SocketTuningHelper.isSocketAlive(socket))
    }

    @Test
    fun isSocketBufferReadyForWriteReflectsPollStatus() {
        val socket = clientSocket!!

        SocketTuningHelper.osPollOutChecker = { true }
        assertTrue(SocketTuningHelper.isSocketBufferReadyForWrite(socket))

        SocketTuningHelper.osPollOutChecker = { false }
        assertFalse(SocketTuningHelper.isSocketBufferReadyForWrite(socket))
    }

    @Test
    fun shouldDropFrameWhenKernelBufferIsSaturated() {
        val socket = clientSocket!!
        val now = 10_000L

        // Buffer ready -> do not drop
        SocketTuningHelper.osPollOutChecker = { true }
        assertFalse(SocketTuningHelper.shouldDropFrame(socket, lastSuccessfulWriteMs = now, isWriting = false, nowMs = now))

        // Buffer saturated (TCP_NOTSENT_LOWAT exceeded) -> drop proactively
        SocketTuningHelper.osPollOutChecker = { false }
        assertTrue(SocketTuningHelper.shouldDropFrame(socket, lastSuccessfulWriteMs = now, isWriting = false, nowMs = now))
    }

    @Test
    fun shouldDropFrameWhenInFlightWriteLagsBeyondThreshold() {
        val socket = clientSocket!!
        SocketTuningHelper.osPollOutChecker = { true }

        val startTime = 10_000L
        val withinThresholdTime = startTime + 100L
        val exceededThresholdTime = startTime + 300L

        // In flight but within 250ms threshold -> do not drop
        assertFalse(
            SocketTuningHelper.shouldDropFrame(
                socket = socket,
                lastSuccessfulWriteMs = startTime,
                isWriting = true,
                nowMs = withinThresholdTime,
                congestionThresholdMs = 250L
            )
        )

        // In flight and lagging 300ms (> 250ms threshold) -> drop proactively
        assertTrue(
            SocketTuningHelper.shouldDropFrame(
                socket = socket,
                lastSuccessfulWriteMs = startTime,
                isWriting = true,
                nowMs = exceededThresholdTime,
                congestionThresholdMs = 250L
            )
        )
    }

    @Test
    fun shouldDropFrameWithClientIntegrationAndTracksDroppedCount() {
        val socket = clientSocket!!
        val os = ByteArrayOutputStream()
        val writer = PrintWriter(os)
        val client = StreamingServerHelper.Client(
            socket = socket,
            outputStream = os,
            writer = writer,
            connectedAt = System.currentTimeMillis()
        )

        assertEquals(0L, client.droppedFrames)
        assertFalse(client.isWriting)

        // Simulate buffer saturation
        SocketTuningHelper.osPollOutChecker = { false }
        val shouldDrop = SocketTuningHelper.shouldDropFrame(client)
        assertTrue(shouldDrop)

        if (shouldDrop) {
            client.recordDroppedFrame()
        }
        assertEquals(1L, client.droppedFrames)

        // Simulate buffer cleared
        SocketTuningHelper.osPollOutChecker = { true }
        assertFalse(SocketTuningHelper.shouldDropFrame(client))

        // Simulate write cycle
        client.markWriteStart()
        assertTrue(client.isWriting)
        client.markWriteSuccess()
        assertFalse(client.isWriting)
    }
}
