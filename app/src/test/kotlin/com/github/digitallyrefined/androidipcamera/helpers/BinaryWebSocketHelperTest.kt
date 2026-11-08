package com.github.digitallyrefined.androidipcamera.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class BinaryWebSocketHelperTest {

    @Test
    fun testHandshakeKeyCalculation_rfc6455Example() {
        // RFC 6455 Section 1.3 standard test vector:
        val clientKey = "dGhlIHNhbXBsZSBub25jZQ=="
        val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

        val actualAccept = BinaryWebSocketHelper.computeAcceptKey(clientKey)
        assertEquals(expectedAccept, actualAccept)
    }

    @Test
    fun testHandshakeKeyCalculation_customKey() {
        val clientKey = "x3JJHMbDL1EzLkh9GBhXDw=="
        val accept = BinaryWebSocketHelper.computeAcceptKey(clientKey)
        assertNotNull(accept)
        assertEquals(28, accept.length)
        assertTrue(accept.endsWith("="))
    }

    @Test
    fun testIsWebSocketUpgrade_validHeaders() {
        val headers = listOf(
            "Host: 192.168.1.100:4444",
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version: 13"
        )
        assertTrue(BinaryWebSocketHelper.isWebSocketUpgrade(headers))
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", BinaryWebSocketHelper.findWebSocketKey(headers))
    }

    @Test
    fun testIsWebSocketUpgrade_caseInsensitive() {
        val headers = listOf(
            "host: 192.168.1.100:4444",
            "upgrade: WebSocket",
            "connection: keep-alive, upgrade",
            "sec-websocket-key: dGhlIHNhbXBsZSBub25jZQ=="
        )
        assertTrue(BinaryWebSocketHelper.isWebSocketUpgrade(headers))
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", BinaryWebSocketHelper.findWebSocketKey(headers))
    }

    @Test
    fun testIsWebSocketUpgrade_regularHttpHeader() {
        val headers = listOf(
            "Host: 192.168.1.100:4444",
            "Accept: text/html,application/xhtml+xml",
            "User-Agent: Mozilla/5.0"
        )
        assertFalse(BinaryWebSocketHelper.isWebSocketUpgrade(headers))
        assertNull(BinaryWebSocketHelper.findWebSocketKey(headers))
    }

    @Test
    fun testBuildHandshakeResponse() {
        val acceptKey = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        val response = BinaryWebSocketHelper.buildHandshakeResponse(acceptKey)

        assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
        assertTrue(response.contains("Upgrade: websocket\r\n"))
        assertTrue(response.contains("Connection: Upgrade\r\n"))
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"))
        assertTrue(response.endsWith("\r\n\r\n"))
    }

    @Test
    fun testBinaryFraming_shortPayload() {
        // Short: payload <= 125 bytes -> 2-byte frame header
        val len = 50L
        val header = BinaryWebSocketHelper.buildFrameHeader(len, BinaryWebSocketHelper.OPCODE_BINARY, fin = true)

        assertEquals(2, header.size)
        // 0x82 = FIN (0x80) | OPCODE_BINARY (0x02)
        assertEquals(0x82.toByte(), header[0])
        // Unmasked server frame -> bit 7 is 0, bits 0..6 = len
        assertEquals(50.toByte(), header[1])
    }

    @Test
    fun testBinaryFraming_mediumPayload() {
        // Medium: 126 <= payload <= 65535 bytes -> 4-byte frame header
        val len = 35000L
        val header = BinaryWebSocketHelper.buildFrameHeader(len, BinaryWebSocketHelper.OPCODE_BINARY, fin = true)

        assertEquals(4, header.size)
        assertEquals(0x82.toByte(), header[0])
        assertEquals(126.toByte(), header[1])

        val bb = ByteBuffer.wrap(header, 2, 2).order(ByteOrder.BIG_ENDIAN)
        val decodedLen = bb.short.toInt() and 0xFFFF
        assertEquals(35000, decodedLen)
    }

    @Test
    fun testBinaryFraming_largePayload() {
        // Large: payload > 65535 bytes (e.g. 1080p frame = 120KB) -> 10-byte frame header
        val len = 120000L
        val header = BinaryWebSocketHelper.buildFrameHeader(len, BinaryWebSocketHelper.OPCODE_BINARY, fin = true)

        assertEquals(10, header.size)
        assertEquals(0x82.toByte(), header[0])
        assertEquals(127.toByte(), header[1])

        val bb = ByteBuffer.wrap(header, 2, 8).order(ByteOrder.BIG_ENDIAN)
        val decodedLen = bb.long
        assertEquals(120000L, decodedLen)
    }

    @Test
    fun testVideoHeaderEncodingAndParsing() {
        val timestamp = 1700000000123L
        val frameIndex = 142
        val flags = 1.toShort()
        val payloadLength = 48500

        val headerBytes = BinaryWebSocketHelper.buildVideoHeader(timestamp, frameIndex, flags, payloadLength)
        assertEquals(BinaryWebSocketHelper.VIDEO_HEADER_SIZE, headerBytes.size)

        val parsed = BinaryWebSocketHelper.parseVideoHeader(headerBytes)
        assertEquals(timestamp, parsed.timestamp)
        assertEquals(frameIndex, parsed.frameIndex)
        assertEquals(flags, parsed.flags)
        assertEquals(payloadLength, parsed.payloadLength)
    }

    @Test
    fun testEncodeVideoFrameMessage() {
        val jpegData = ByteArray(300) { (it % 256).toByte() }
        val timestamp = 999999999L
        val frameIndex = 77
        val flags = 0.toShort()

        val fullMessage = BinaryWebSocketHelper.encodeVideoFrameMessage(jpegData, timestamp, frameIndex, flags)

        // Total payload = 18 + 300 = 318 bytes -> 4-byte frame header
        val expectedHeaderSize = 4
        val expectedTotalSize = expectedHeaderSize + BinaryWebSocketHelper.VIDEO_HEADER_SIZE + jpegData.size
        assertEquals(expectedTotalSize, fullMessage.size)

        assertEquals(0x82.toByte(), fullMessage[0])
        assertEquals(126.toByte(), fullMessage[1])

        val payloadLenDecoded = ByteBuffer.wrap(fullMessage, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(318, payloadLenDecoded)

        val parsedVideo = BinaryWebSocketHelper.parseVideoHeader(fullMessage, offset = expectedHeaderSize)
        assertEquals(timestamp, parsedVideo.timestamp)
        assertEquals(frameIndex, parsedVideo.frameIndex)
        assertEquals(flags, parsedVideo.flags)
        assertEquals(jpegData.size, parsedVideo.payloadLength)

        val extractedJpeg = fullMessage.copyOfRange(expectedHeaderSize + BinaryWebSocketHelper.VIDEO_HEADER_SIZE, fullMessage.size)
        assertArrayEquals(jpegData, extractedJpeg)
    }

    @Test
    fun testPingPongAndCloseFrames() {
        val pingPayload = "ping-123".toByteArray(StandardCharsets.UTF_8)
        val ping = BinaryWebSocketHelper.buildPingFrame(pingPayload)
        assertEquals(0x89.toByte(), ping[0]) // FIN | OPCODE_PING
        assertEquals(pingPayload.size.toByte(), ping[1])
        assertArrayEquals(pingPayload, ping.copyOfRange(2, ping.size))

        val pong = BinaryWebSocketHelper.buildPongFrame(pingPayload)
        assertEquals(0x8A.toByte(), pong[0]) // FIN | OPCODE_PONG
        assertEquals(pingPayload.size.toByte(), pong[1])
        assertArrayEquals(pingPayload, pong.copyOfRange(2, pong.size))

        val close = BinaryWebSocketHelper.buildCloseFrame(1000)
        assertEquals(0x88.toByte(), close[0]) // FIN | OPCODE_CLOSE
        assertEquals(2.toByte(), close[1])
        val code = ByteBuffer.wrap(close, 2, 2).order(ByteOrder.BIG_ENDIAN).short
        assertEquals(1000.toShort(), code)
    }

    @Test
    fun testParseClientFrame_maskedText() {
        val text = "test-message"
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val maskedBytes = ByteArray(textBytes.size) { i ->
            (textBytes[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        val frameBytes = ByteArray(2 + 4 + textBytes.size)
        frameBytes[0] = 0x81.toByte() // FIN | OPCODE_TEXT
        frameBytes[1] = (0x80 or textBytes.size).toByte() // MASK=1, len
        System.arraycopy(mask, 0, frameBytes, 2, 4)
        System.arraycopy(maskedBytes, 0, frameBytes, 6, textBytes.size)

        val frame = BinaryWebSocketHelper.parseClientFrame(ByteArrayInputStream(frameBytes))
        assertNotNull(frame)
        assertTrue(frame!!.fin)
        assertEquals(BinaryWebSocketHelper.OPCODE_TEXT, frame.opcode)
        assertEquals(text, String(frame.payload, StandardCharsets.UTF_8))
    }

    @Test
    fun testBase64EncodingCompatibility() {
        val testInputs = listOf(
            byteArrayOf(),
            byteArrayOf(1),
            byteArrayOf(1, 2),
            byteArrayOf(1, 2, 3),
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            "WebSocket streaming test vector 123456789".toByteArray(StandardCharsets.UTF_8)
        )

        for (input in testInputs) {
            val encodedStandard = WebSocketBase64.encode(input)
            val encodedPure = WebSocketBase64.encodePure(input)
            assertEquals(encodedStandard, encodedPure)
        }
    }

    @Test
    fun testChannelRegistrationAndSegregation() {
        val helper = BinaryWebSocketHelper()
        val mockSocketPrimary = java.net.Socket()
        val mockSocketSecondary = java.net.Socket()
        val outStreamPrimary = java.io.ByteArrayOutputStream()
        val outStreamSecondary = java.io.ByteArrayOutputStream()

        val primaryClient = helper.registerClient(mockSocketPrimary, outStreamPrimary, isAuthenticated = true, channel = "primary")
        val secondaryClient = helper.registerClient(mockSocketSecondary, outStreamSecondary, isAuthenticated = true, channel = "secondary")

        assertEquals("primary", primaryClient.streamChannel)
        assertEquals("secondary", secondaryClient.streamChannel)

        assertTrue(helper.hasClientsForChannel("primary"))
        assertTrue(helper.hasClientsForChannel("secondary"))
        assertFalse(helper.hasClientsForChannel("auxiliary"))

        helper.removeClient(primaryClient)
        assertFalse(helper.hasClientsForChannel("primary"))
        assertTrue(helper.hasClientsForChannel("secondary"))

        helper.removeClient(secondaryClient)
        assertFalse(helper.hasClientsForChannel("secondary"))
    }

    @Test
    fun testObsWebSocketHtmlSupportsDualCameras() {
        val html = BinaryWebSocketHelper.getObsWebSocketHtml()
        assertTrue(html.contains("/ws/secondary"))
        assertTrue(html.contains("/ws/video"))
        assertTrue(html.contains("cam"))
    }

    @Test
    fun testWriteVideoHeaderAndFrameHeader_matchesEncodeVideoFrameMessage() {
        val testSizes = listOf(50, 1000, 70000)
        for (size in testSizes) {
            val jpeg = ByteArray(size) { (it % 256).toByte() }
            val ts = 1710000000123L
            val frameIndex = 88
            val flags = 1.toShort()

            val expectedFull = BinaryWebSocketHelper.encodeVideoFrameMessage(jpeg, ts, frameIndex, flags)

            val headerBuf = ByteArray(BinaryWebSocketHelper.MAX_HEADER_SIZE)
            val headerLen = BinaryWebSocketHelper.writeVideoHeaderAndFrameHeader(
                out = headerBuf,
                offset = 0,
                jpegSize = jpeg.size,
                timestamp = ts,
                frameIndex = frameIndex,
                flags = flags
            )

            // Header part of full message must equal written header
            val expectedHeader = expectedFull.copyOfRange(0, headerLen)
            val actualHeader = headerBuf.copyOfRange(0, headerLen)
            assertArrayEquals(expectedHeader, actualHeader)

            // Length matches expected header size + video header size
            assertEquals(expectedFull.size - jpeg.size, headerLen)
        }
    }

    @Test
    fun testSendVideoFrameZeroCopyMatchesFullMessage() {
        val jpeg = ByteArray(500) { (it xor 0x55).toByte() }
        val ts = 1720000000456L
        val frameIndex = 120
        val flags = 0.toShort()

        val expectedFullMessage = BinaryWebSocketHelper.encodeVideoFrameMessage(jpeg, ts, frameIndex, flags)

        val mockSocket = java.net.Socket()
        val outStream = java.io.ByteArrayOutputStream()
        val helper = BinaryWebSocketHelper()
        val client = helper.registerClient(mockSocket, outStream, isAuthenticated = true)

        val headerBuf = ByteArray(BinaryWebSocketHelper.MAX_HEADER_SIZE)
        val headerLen = BinaryWebSocketHelper.writeVideoHeaderAndFrameHeader(
            out = headerBuf,
            offset = 0,
            jpegSize = jpeg.size,
            timestamp = ts,
            frameIndex = frameIndex,
            flags = flags
        )

        val ok = client.sendVideoFrame(headerBuf, 0, headerLen, jpeg, 0, jpeg.size)
        assertTrue(ok)
        assertArrayEquals(expectedFullMessage, outStream.toByteArray())
    }

    @Test
    fun testObsWebSocketHtml_includesFastBitmapDecodeOptions() {
        val html = BinaryWebSocketHelper.getObsWebSocketHtml()
        assertTrue(html.contains("premultiplyAlpha:'none'"))
        assertTrue(html.contains("colorSpaceConversion:'none'"))
    }
}
