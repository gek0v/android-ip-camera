package com.github.digitallyrefined.androidipcamera.helpers

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Data class representing the 18-byte video header preceding raw JPEG payloads
 * in RFC 6455 binary frames (/ws/video, /ws/obs).
 * Format: [Timestamp 8B][FrameIndex 4B][Flags 2B][PayloadLength 4B]
 */
data class VideoHeader(
    val timestamp: Long,
    val frameIndex: Int,
    val flags: Short,
    val payloadLength: Int
)

/**
 * Parsed RFC 6455 client WebSocket frame.
 */
data class WebSocketFrame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebSocketFrame) return false
        return fin == other.fin && opcode == other.opcode && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = fin.hashCode()
        result = 31 * result + opcode
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Represents an active WebSocket client connection.
 */
class WebSocketClient(
    val socket: Socket,
    val outputStream: OutputStream,
    val isAuthenticated: Boolean = false,
    val connectedAt: Long = System.currentTimeMillis(),
    val streamChannel: String = "primary"
) {
    val isWriting = AtomicBoolean(false)
    @Volatile var lastActiveMs: Long = System.currentTimeMillis()
    @Volatile var lastSuccessfulWriteMs: Long = System.currentTimeMillis()
    @Volatile var droppedFrames: Long = 0L
    @Volatile var isClosed: Boolean = false

    fun recordDroppedFrame() {
        droppedFrames++
    }

    fun sendFrame(frameBytes: ByteArray): Boolean {
        if (isClosed || socket.isClosed) return false
        return try {
            synchronized(outputStream) {
                outputStream.write(frameBytes)
                outputStream.flush()
            }
            lastSuccessfulWriteMs = System.currentTimeMillis()
            lastActiveMs = lastSuccessfulWriteMs
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    /**
     * Zero-copy transmission of video frames:
     * Writes frame header (22-28 bytes) and JPEG payload directly to outputStream
     * without allocating or copying an intermediate full-frame byte array.
     */
    fun sendVideoFrame(
        headerBytes: ByteArray,
        headerOffset: Int,
        headerLen: Int,
        jpegBytes: ByteArray,
        jpegOffset: Int = 0,
        jpegLen: Int = jpegBytes.size
    ): Boolean {
        if (isClosed || socket.isClosed) return false
        return try {
            synchronized(outputStream) {
                outputStream.write(headerBytes, headerOffset, headerLen)
                outputStream.write(jpegBytes, jpegOffset, jpegLen)
                outputStream.flush()
            }
            lastSuccessfulWriteMs = System.currentTimeMillis()
            lastActiveMs = lastSuccessfulWriteMs
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}

/**
 * Portable Base64 encoder compatible with JVM unit tests and Android runtimes.
 */
object WebSocketBase64 {
    private val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    fun encode(data: ByteArray): String {
        return try {
            // JVM 8+ and Android API 26+
            java.util.Base64.getEncoder().encodeToString(data)
        } catch (_: Throwable) {
            encodePure(data)
        }
    }

    fun encodePure(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i++].toInt() and 0xFF
            val b1 = if (i < data.size) data[i++].toInt() and 0xFF else -1
            val b2 = if (i < data.size) data[i++].toInt() and 0xFF else -1

            out.append(TABLE[b0 ushr 2])
            if (b1 != -1) {
                out.append(TABLE[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                if (b2 != -1) {
                    out.append(TABLE[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                    out.append(TABLE[b2 and 0x3F])
                } else {
                    out.append(TABLE[(b1 and 0x0F) shl 2])
                    out.append('=')
                }
            } else {
                out.append(TABLE[(b0 and 0x03) shl 4])
                out.append("==")
            }
        }
        return out.toString()
    }
}

/**
 * RFC 6455 Binary WebSocket Helper for ultra-low latency video streaming.
 * Handles handshake, framing, keepalive ping/pong, and non-blocking backpressure distribution.
 */
class BinaryWebSocketHelper(
    private val onLog: (String) -> Unit = {},
    private val onClientConnected: () -> Unit = {},
    private val onClientDisconnected: () -> Unit = {},
    private val onControlCommand: (String, String, Long) -> Unit = { _, _, _ -> }
) {
    companion object {
        const val MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0x00
        const val OPCODE_TEXT = 0x01
        const val OPCODE_BINARY = 0x02
        const val OPCODE_CLOSE = 0x08
        const val OPCODE_PING = 0x09
        const val OPCODE_PONG = 0x0A

        const val VIDEO_HEADER_SIZE = 18
        const val MAX_HEADER_SIZE = 28 // 10B WS header + 18B Video header

        /**
         * Computes the Sec-WebSocket-Accept key according to RFC 6455 section 1.3:
         * Base64(SHA-1(clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
         */
        fun computeAcceptKey(clientKey: String): String {
            val input = clientKey.trim() + MAGIC_GUID
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest(input.toByteArray(StandardCharsets.UTF_8))
            return WebSocketBase64.encode(digest)
        }

        /**
         * Detects if the HTTP headers describe a WebSocket upgrade request.
         */
        fun isWebSocketUpgrade(headers: List<String>): Boolean {
            var hasUpgrade = false
            var hasConnection = false
            for (header in headers) {
                val colon = header.indexOf(':')
                if (colon > 0) {
                    val name = header.substring(0, colon).trim()
                    val value = header.substring(colon + 1).trim()
                    if (name.equals("Upgrade", ignoreCase = true) && value.contains("websocket", ignoreCase = true)) {
                        hasUpgrade = true
                    }
                    if (name.equals("Connection", ignoreCase = true) && value.contains("upgrade", ignoreCase = true)) {
                        hasConnection = true
                    }
                }
            }
            return hasUpgrade && hasConnection
        }

        /**
         * Extracts the Sec-WebSocket-Key header value in a case-insensitive manner.
         */
        fun findWebSocketKey(headers: List<String>): String? {
            for (header in headers) {
                val colon = header.indexOf(':')
                if (colon > 0) {
                    val name = header.substring(0, colon).trim()
                    if (name.equals("Sec-WebSocket-Key", ignoreCase = true)) {
                        return header.substring(colon + 1).trim()
                    }
                }
            }
            return null
        }

        /**
         * Generates the standard HTTP 101 Switching Protocols response.
         */
        fun getObsWebSocketHtml(): String {
            val html = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>OBS WebSocket Stream</title>""" +
                """<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;display:flex;align-items:center;justify-content:center;}canvas{width:100%;height:100%;object-fit:contain;}</style></head>""" +
                """<body><canvas id="c"></canvas><script>""" +
                """const canvas=document.getElementById('c'),ctx=canvas.getContext('2d',{alpha:false,desynchronized:true});""" +
                """let ws,proto=location.protocol==='https:'?'wss:':'ws:';""" +
                """function connect(){""" +
                """const params=new URLSearchParams(location.search);""" +
                """const cam=(params.get('cam')||params.get('camera')||'').toLowerCase();""" +
                """const isSec=location.pathname.includes('front')||location.pathname.includes('secondary')||['front','secondary','sec','1'].includes(cam);""" +
                """const wsPath=isSec?'/ws/secondary':'/ws/video';""" +
                """ws=new WebSocket(proto+'//'+location.host+wsPath+location.search);ws.binaryType='arraybuffer';""" +
                """ws.onmessage=e=>{""" +
                """if(e.data.byteLength>18){""" +
                """const jpeg=e.data.slice(18);""" +
                """createImageBitmap(new Blob([jpeg],{type:'image/jpeg'}),{premultiplyAlpha:'none',colorSpaceConversion:'none'}).then(bmp=>{""" +
                """if(canvas.width!==bmp.width||canvas.height!==bmp.height){canvas.width=bmp.width;canvas.height=bmp.height;}""" +
                """ctx.drawImage(bmp,0,0);bmp.close();""" +
                """}).catch(()=>{});}};""" +
                """ws.onclose=()=>setTimeout(connect,1000);""" +
                """ws.onerror=()=>{try{ws.close();}catch(_){}};""" +
                """}connect();</script></body></html>"""
            return html
        }

        fun buildHandshakeResponse(acceptKey: String): String {
            return "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        }

        /**
         * Constructs an unmasked RFC 6455 frame header (Server -> Client) without ByteBuffer allocation.
         */
        fun buildFrameHeader(payloadLength: Long, opcode: Int = OPCODE_BINARY, fin: Boolean = true): ByteArray {
            val b0 = ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()
            return when {
                payloadLength <= 125L -> {
                    byteArrayOf(b0, (payloadLength and 0x7F).toByte())
                }
                payloadLength <= 65535L -> {
                    byteArrayOf(
                        b0,
                        126.toByte(),
                        (payloadLength ushr 8).toByte(),
                        (payloadLength and 0xFF).toByte()
                    )
                }
                else -> {
                    byteArrayOf(
                        b0,
                        127.toByte(),
                        (payloadLength ushr 56).toByte(),
                        (payloadLength ushr 48).toByte(),
                        (payloadLength ushr 40).toByte(),
                        (payloadLength ushr 32).toByte(),
                        (payloadLength ushr 24).toByte(),
                        (payloadLength ushr 16).toByte(),
                        (payloadLength ushr 8).toByte(),
                        (payloadLength and 0xFF).toByte()
                    )
                }
            }
        }

        /**
         * Builds the 18-byte video header:
         * [Timestamp 8B][FrameIndex 4B][Flags 2B][PayloadLength 4B]
         */
        fun buildVideoHeader(timestamp: Long, frameIndex: Int, flags: Short, payloadLength: Int): ByteArray {
            val out = ByteArray(VIDEO_HEADER_SIZE)
            out[0] = (timestamp ushr 56).toByte()
            out[1] = (timestamp ushr 48).toByte()
            out[2] = (timestamp ushr 40).toByte()
            out[3] = (timestamp ushr 32).toByte()
            out[4] = (timestamp ushr 24).toByte()
            out[5] = (timestamp ushr 16).toByte()
            out[6] = (timestamp ushr 8).toByte()
            out[7] = (timestamp and 0xFF).toByte()

            out[8] = (frameIndex ushr 24).toByte()
            out[9] = (frameIndex ushr 16).toByte()
            out[10] = (frameIndex ushr 8).toByte()
            out[11] = (frameIndex and 0xFF).toByte()

            out[12] = (flags.toInt() ushr 8).toByte()
            out[13] = (flags.toInt() and 0xFF).toByte()

            out[14] = (payloadLength ushr 24).toByte()
            out[15] = (payloadLength ushr 16).toByte()
            out[16] = (payloadLength ushr 8).toByte()
            out[17] = (payloadLength and 0xFF).toByte()
            return out
        }

        /**
         * Decodes an 18-byte video header from byte array using direct bit shifts.
         */
        fun parseVideoHeader(bytes: ByteArray, offset: Int = 0): VideoHeader {
            require(bytes.size - offset >= VIDEO_HEADER_SIZE) { "Buffer too short for video header" }
            var idx = offset
            val ts = ((bytes[idx++].toLong() and 0xFF) shl 56) or
                    ((bytes[idx++].toLong() and 0xFF) shl 48) or
                    ((bytes[idx++].toLong() and 0xFF) shl 40) or
                    ((bytes[idx++].toLong() and 0xFF) shl 32) or
                    ((bytes[idx++].toLong() and 0xFF) shl 24) or
                    ((bytes[idx++].toLong() and 0xFF) shl 16) or
                    ((bytes[idx++].toLong() and 0xFF) shl 8) or
                    (bytes[idx++].toLong() and 0xFF)

            val frameIndex = ((bytes[idx++].toInt() and 0xFF) shl 24) or
                    ((bytes[idx++].toInt() and 0xFF) shl 16) or
                    ((bytes[idx++].toInt() and 0xFF) shl 8) or
                    (bytes[idx++].toInt() and 0xFF)

            val flags = (((bytes[idx++].toInt() and 0xFF) shl 8) or
                    (bytes[idx++].toInt() and 0xFF)).toShort()

            val payloadLength = ((bytes[idx++].toInt() and 0xFF) shl 24) or
                    ((bytes[idx++].toInt() and 0xFF) shl 16) or
                    ((bytes[idx++].toInt() and 0xFF) shl 8) or
                    (bytes[idx++].toInt() and 0xFF)

            return VideoHeader(ts, frameIndex, flags, payloadLength)
        }

        /**
         * Writes both the RFC 6455 frame header and the 18-byte video header
         * into [out] at [offset] in a single pass with zero heap allocation.
         * Returns total header bytes written (20, 22, or 28 bytes).
         */
        fun writeVideoHeaderAndFrameHeader(
            out: ByteArray,
            offset: Int,
            jpegSize: Int,
            timestamp: Long,
            frameIndex: Int,
            flags: Short
        ): Int {
            val totalPayload = VIDEO_HEADER_SIZE.toLong() + jpegSize.toLong()
            var idx = offset
            out[idx++] = 0x82.toByte() // FIN | OPCODE_BINARY
            when {
                totalPayload <= 125L -> {
                    out[idx++] = totalPayload.toByte()
                }
                totalPayload <= 65535L -> {
                    out[idx++] = 126.toByte()
                    out[idx++] = (totalPayload ushr 8).toByte()
                    out[idx++] = (totalPayload and 0xFF).toByte()
                }
                else -> {
                    out[idx++] = 127.toByte()
                    out[idx++] = (totalPayload ushr 56).toByte()
                    out[idx++] = (totalPayload ushr 48).toByte()
                    out[idx++] = (totalPayload ushr 40).toByte()
                    out[idx++] = (totalPayload ushr 32).toByte()
                    out[idx++] = (totalPayload ushr 24).toByte()
                    out[idx++] = (totalPayload ushr 16).toByte()
                    out[idx++] = (totalPayload ushr 8).toByte()
                    out[idx++] = (totalPayload and 0xFF).toByte()
                }
            }

            // Video Header (18 bytes)
            out[idx++] = (timestamp ushr 56).toByte()
            out[idx++] = (timestamp ushr 48).toByte()
            out[idx++] = (timestamp ushr 40).toByte()
            out[idx++] = (timestamp ushr 32).toByte()
            out[idx++] = (timestamp ushr 24).toByte()
            out[idx++] = (timestamp ushr 16).toByte()
            out[idx++] = (timestamp ushr 8).toByte()
            out[idx++] = (timestamp and 0xFF).toByte()

            out[idx++] = (frameIndex ushr 24).toByte()
            out[idx++] = (frameIndex ushr 16).toByte()
            out[idx++] = (frameIndex ushr 8).toByte()
            out[idx++] = (frameIndex and 0xFF).toByte()

            out[idx++] = (flags.toInt() ushr 8).toByte()
            out[idx++] = (flags.toInt() and 0xFF).toByte()

            out[idx++] = (jpegSize ushr 24).toByte()
            out[idx++] = (jpegSize ushr 16).toByte()
            out[idx++] = (jpegSize ushr 8).toByte()
            out[idx++] = (jpegSize and 0xFF).toByte()

            return idx - offset
        }

        fun buildPongFrame(payload: ByteArray = ByteArray(0)): ByteArray {
            val header = buildFrameHeader(payload.size.toLong(), OPCODE_PONG, true)
            return if (payload.isEmpty()) header else header + payload
        }

        fun buildPingFrame(payload: ByteArray = ByteArray(0)): ByteArray {
            val header = buildFrameHeader(payload.size.toLong(), OPCODE_PING, true)
            return if (payload.isEmpty()) header else header + payload
        }

        fun buildCloseFrame(statusCode: Short = 1000): ByteArray {
            val payload = byteArrayOf((statusCode.toInt() ushr 8).toByte(), (statusCode.toInt() and 0xFF).toByte())
            val header = buildFrameHeader(2, OPCODE_CLOSE, true)
            return header + payload
        }

        /**
         * Encodes a complete RFC 6455 binary frame containing the video header and JPEG payload.
         * Single-array allocation with zero intermediate buffers.
         */
        fun encodeVideoFrameMessage(
            jpegBytes: ByteArray,
            timestamp: Long,
            frameIndex: Int,
            flags: Short = 0
        ): ByteArray {
            val totalPayload = VIDEO_HEADER_SIZE.toLong() + jpegBytes.size.toLong()
            val frameHeaderLen = when {
                totalPayload <= 125L -> 2
                totalPayload <= 65535L -> 4
                else -> 10
            }
            val result = ByteArray(frameHeaderLen + VIDEO_HEADER_SIZE + jpegBytes.size)
            val headerLen = writeVideoHeaderAndFrameHeader(
                out = result,
                offset = 0,
                jpegSize = jpegBytes.size,
                timestamp = timestamp,
                frameIndex = frameIndex,
                flags = flags
            )
            System.arraycopy(jpegBytes, 0, result, headerLen, jpegBytes.size)
            return result
        }

        /**
         * Parses and unmasks an incoming client frame per RFC 6455.
         */
        fun parseClientFrame(input: InputStream): WebSocketFrame? {
            val b0 = input.read()
            if (b0 == -1) return null
            val b1 = input.read()
            if (b1 == -1) return null

            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val len0 = input.read()
                val len1 = input.read()
                if (len0 == -1 || len1 == -1) return null
                payloadLen = ((len0 and 0xFF) shl 8 or (len1 and 0xFF)).toLong()
            } else if (payloadLen == 127L) {
                var l = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) return null
                    l = (l shl 8) or (b.toLong() and 0xFFL)
                }
                payloadLen = l
            }

            if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024) {
                // Reject payload > 10MB
                return null
            }

            val mask = if (masked) {
                val m = ByteArray(4)
                for (i in 0 until 4) {
                    val b = input.read()
                    if (b == -1) return null
                    m[i] = b.toByte()
                }
                m
            } else null

            val payload = ByteArray(payloadLen.toInt())
            var readTotal = 0
            while (readTotal < payload.size) {
                val count = input.read(payload, readTotal, payload.size - readTotal)
                if (count < 0) return null
                readTotal += count
            }

            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
                }
            }

            return WebSocketFrame(fin, opcode, payload)
        }
    }

    private val clients = CopyOnWriteArrayList<WebSocketClient>()
    private val frameCounter = AtomicInteger(0)
    private val secFrameCounter = AtomicInteger(0)

    private val writerExecutor: ExecutorService = ThreadPoolExecutor(
        4,
        16,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "WebSocketBroadcaster").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    fun getClients(): List<WebSocketClient> = clients.toList()
    fun hasClients(): Boolean = clients.isNotEmpty()
    fun hasClientsForChannel(channel: String): Boolean = clients.any { !it.isClosed && !it.socket.isClosed && it.streamChannel == channel }
    fun clientCount(): Int = clients.size

    fun handleHandshake(headers: List<String>, outputStream: OutputStream): Boolean {
        val key = findWebSocketKey(headers) ?: return false
        val accept = computeAcceptKey(key)
        val response = buildHandshakeResponse(accept)
        outputStream.write(response.toByteArray(StandardCharsets.US_ASCII))
        outputStream.flush()
        return true
    }

    fun registerClient(
        socket: Socket,
        outputStream: OutputStream,
        isAuthenticated: Boolean,
        channel: String = "primary"
    ): WebSocketClient {
        val client = WebSocketClient(socket, outputStream, isAuthenticated, streamChannel = channel)
        clients.add(client)
        onClientConnected()
        return client
    }

    fun removeClient(client: WebSocketClient) {
        if (clients.remove(client)) {
            client.close()
            onClientDisconnected()
        }
    }

    fun broadcastVideoFrame(
        jpegBytes: ByteArray,
        timestamp: Long = System.currentTimeMillis(),
        flags: Short = 0,
        channel: String = "primary"
    ) {
        val activeClients = clients.filter { !it.isClosed && !it.socket.isClosed && it.streamChannel == channel }
        if (activeClients.isEmpty()) return

        val frameIndex = if (channel == "primary") frameCounter.getAndIncrement() else secFrameCounter.getAndIncrement()

        // Zero-copy buffer reuse: write 22-28 byte header once into a compact byte array
        val tempHeader = ByteArray(MAX_HEADER_SIZE)
        val headerLen = writeVideoHeaderAndFrameHeader(
            out = tempHeader,
            offset = 0,
            jpegSize = jpegBytes.size,
            timestamp = timestamp,
            frameIndex = frameIndex,
            flags = flags
        )
        val headerBytes = tempHeader.copyOf(headerLen)

        for (client in activeClients) {
            if (client.isClosed || client.socket.isClosed) {
                removeClient(client)
                continue
            }
            if (SocketTuningHelper.shouldDropFrame(client)) {
                client.recordDroppedFrame()
                continue
            }
            if (!client.isWriting.compareAndSet(false, true)) {
                client.recordDroppedFrame()
                continue
            }
            try {
                writerExecutor.execute {
                    try {
                        if (!client.isClosed && !client.socket.isClosed) {
                            client.sendVideoFrame(headerBytes, 0, headerLen, jpegBytes, 0, jpegBytes.size)
                        }
                    } catch (e: Exception) {
                        client.close()
                        removeClient(client)
                    } finally {
                        client.isWriting.set(false)
                    }
                }
            } catch (e: RejectedExecutionException) {
                client.recordDroppedFrame()
                client.isWriting.set(false)
            }
        }
    }

    fun runClientReader(client: WebSocketClient) {
        val inputStream = client.socket.getInputStream()
        try {
            while (!client.isClosed && !client.socket.isClosed) {
                val frame = parseClientFrame(inputStream) ?: break
                client.lastActiveMs = System.currentTimeMillis()
                when (frame.opcode) {
                    OPCODE_CLOSE -> {
                        val closeCode = if (frame.payload.size >= 2) {
                            ByteBuffer.wrap(frame.payload, 0, 2).order(ByteOrder.BIG_ENDIAN).short
                        } else 1000.toShort()
                        val responseClose = buildCloseFrame(closeCode)
                        client.sendFrame(responseClose)
                        break
                    }
                    OPCODE_PING -> {
                        val pong = buildPongFrame(frame.payload)
                        client.sendFrame(pong)
                    }
                    OPCODE_PONG -> {
                    }
                    OPCODE_TEXT -> {
                        handleTextMessage(String(frame.payload, StandardCharsets.UTF_8))
                    }
                    OPCODE_BINARY -> {
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            removeClient(client)
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            if (text.startsWith("{")) {
                val json = JSONObject(text)
                val cmd = json.optString("command", json.optString("key", ""))
                val value = json.optString("value", "")
                if (cmd.isNotEmpty()) {
                    onControlCommand(cmd, value, System.currentTimeMillis())
                }
            }
        } catch (_: Exception) {}
    }

    fun closeAll() {
        val list = clients.toList()
        clients.clear()
        list.forEach { client ->
            try {
                client.close()
            } catch (_: Exception) {}
        }
        if (list.isNotEmpty()) {
            onClientDisconnected()
        }
    }

    fun stop() {
        closeAll()
        writerExecutor.shutdownNow()
    }
}
