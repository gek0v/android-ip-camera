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
    val connectedAt: Long = System.currentTimeMillis()
) {
    val isWriting = AtomicBoolean(false)
    @Volatile var lastActiveMs: Long = System.currentTimeMillis()
    @Volatile var isClosed: Boolean = false

    fun sendFrame(frameBytes: ByteArray): Boolean {
        if (isClosed || socket.isClosed) return false
        return try {
            synchronized(outputStream) {
                outputStream.write(frameBytes)
                outputStream.flush()
            }
            lastActiveMs = System.currentTimeMillis()
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
                """ws=new WebSocket(proto+'//'+location.host+'/ws/video');ws.binaryType='arraybuffer';""" +
                """ws.onmessage=e=>{""" +
                """if(e.data.byteLength>18){""" +
                """const jpeg=e.data.slice(18);""" +
                """createImageBitmap(new Blob([jpeg],{type:'image/jpeg'})).then(bmp=>{""" +
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
         * Constructs an unmasked RFC 6455 frame header (Server -> Client).
         */
        fun buildFrameHeader(payloadLength: Long, opcode: Int = OPCODE_BINARY, fin: Boolean = true): ByteArray {
            val b0 = ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()
            return when {
                payloadLength <= 125L -> {
                    byteArrayOf(b0, (payloadLength and 0x7F).toByte())
                }
                payloadLength <= 65535L -> {
                    val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                    bb.put(b0)
                    bb.put(126.toByte())
                    bb.putShort(payloadLength.toShort())
                    bb.array()
                }
                else -> {
                    val bb = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
                    bb.put(b0)
                    bb.put(127.toByte())
                    bb.putLong(payloadLength)
                    bb.array()
                }
            }
        }

        /**
         * Builds the 18-byte video header:
         * [Timestamp 8B][FrameIndex 4B][Flags 2B][PayloadLength 4B]
         */
        fun buildVideoHeader(timestamp: Long, frameIndex: Int, flags: Short, payloadLength: Int): ByteArray {
            val bb = ByteBuffer.allocate(VIDEO_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            bb.putLong(timestamp)
            bb.putInt(frameIndex)
            bb.putShort(flags)
            bb.putInt(payloadLength)
            return bb.array()
        }

        /**
         * Decodes an 18-byte video header from byte array.
         */
        fun parseVideoHeader(bytes: ByteArray, offset: Int = 0): VideoHeader {
            require(bytes.size - offset >= VIDEO_HEADER_SIZE) { "Buffer too short for video header" }
            val bb = ByteBuffer.wrap(bytes, offset, VIDEO_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            val ts = bb.long
            val idx = bb.int
            val flags = bb.short
            val len = bb.int
            return VideoHeader(ts, idx, flags, len)
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
            val payload = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(statusCode).array()
            val header = buildFrameHeader(2, OPCODE_CLOSE, true)
            return header + payload
        }

        /**
         * Encodes a complete RFC 6455 binary frame containing the video header and JPEG payload.
         */
        fun encodeVideoFrameMessage(
            jpegBytes: ByteArray,
            timestamp: Long,
            frameIndex: Int,
            flags: Short = 0
        ): ByteArray {
            val totalPayloadSize = VIDEO_HEADER_SIZE + jpegBytes.size
            val frameHeader = buildFrameHeader(totalPayloadSize.toLong(), OPCODE_BINARY, true)
            val videoHeader = buildVideoHeader(timestamp, frameIndex, flags, jpegBytes.size)
            val result = ByteArray(frameHeader.size + VIDEO_HEADER_SIZE + jpegBytes.size)
            System.arraycopy(frameHeader, 0, result, 0, frameHeader.size)
            System.arraycopy(videoHeader, 0, result, frameHeader.size, VIDEO_HEADER_SIZE)
            System.arraycopy(jpegBytes, 0, result, frameHeader.size + VIDEO_HEADER_SIZE, jpegBytes.size)
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

    private val writerExecutor: ExecutorService = ThreadPoolExecutor(
        2,
        16,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        { runnable -> Thread(runnable, "WebSocketBroadcaster").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy()
    )

    fun getClients(): List<WebSocketClient> = clients.toList()
    fun hasClients(): Boolean = clients.isNotEmpty()
    fun clientCount(): Int = clients.size

    fun handleHandshake(headers: List<String>, outputStream: OutputStream): Boolean {
        val key = findWebSocketKey(headers) ?: return false
        val accept = computeAcceptKey(key)
        val response = buildHandshakeResponse(accept)
        outputStream.write(response.toByteArray(StandardCharsets.US_ASCII))
        outputStream.flush()
        return true
    }

    fun registerClient(socket: Socket, outputStream: OutputStream, isAuthenticated: Boolean): WebSocketClient {
        val client = WebSocketClient(socket, outputStream, isAuthenticated)
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
        flags: Short = 0
    ) {
        val activeClients = clients
        if (activeClients.isEmpty()) return

        val frameIndex = frameCounter.getAndIncrement()
        val messageBytes = encodeVideoFrameMessage(jpegBytes, timestamp, frameIndex, flags)

        for (client in activeClients) {
            if (client.isClosed || client.socket.isClosed) {
                removeClient(client)
                continue
            }
            if (!client.isWriting.compareAndSet(false, true)) {
                continue
            }
            try {
                writerExecutor.execute {
                    try {
                        if (!client.isClosed && !client.socket.isClosed) {
                            synchronized(client.outputStream) {
                                client.outputStream.write(messageBytes)
                                client.outputStream.flush()
                            }
                            client.lastActiveMs = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        client.close()
                        removeClient(client)
                    } finally {
                        client.isWriting.set(false)
                    }
                }
            } catch (e: RejectedExecutionException) {
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
