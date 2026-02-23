package com.beam.network

import android.util.Log
import okio.ByteString
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class BeamTcpClient(private val listener: BeamSocketListener) {
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    
    interface BeamSocketListener {
        fun onMessage(text: String)
        fun onMessage(byteString: ByteString)
        fun onProgress(fileName: String, progress: Int)
        fun onConnected()
        fun onDisconnected()
        fun getOutputFile(fileName: String): File 
    }

    private val gson = com.google.gson.Gson()
    private var isConnecting = false
    @Volatile var isConnected: Boolean = false
        private set

    private var currentHost: String? = null
    private var currentPort: Int = 8081
    private var connectionThread: Thread? = null

    @Synchronized
    fun connect(ip: String, port: Int = 8081) {
        if (isConnecting || isConnected) return
        isConnecting = true
        currentHost = ip
        currentPort = port

        connectionThread?.interrupt()
        connectionThread = Thread {
            try {
                closeInternal()
                
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, port), 5000)
                socket?.tcpNoDelay = true
                socket?.sendBufferSize = 4 * 1024 * 1024 // 4MB Lightning Buffer
                socket?.receiveBufferSize = 4 * 1024 * 1024

                outputStream = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream(), 4 * 1024 * 1024))
                inputStream = DataInputStream(BufferedInputStream(socket!!.getInputStream(), 4 * 1024 * 1024))

                isConnected = true
                isConnecting = false
                Log.d("Beam", "Connected to TCP Server at $ip:$port")
                
                // Immediate Identify Handshake
                sendIdentify()
                
                listener.onConnected()
                startReadLoop()

            } catch (e: Exception) {
                isConnecting = false
                isConnected = false
                Log.e("Beam", "TCP Connection Failure: ${e.message}")
                listener.onDisconnected()
                
                if (currentHost != null) {
                    try { Thread.sleep(5000) } catch (ie: Exception) {}
                    connect(ip, port)
                }
            }
        }.apply { start() }
    }

    private fun sendIdentify() {
        val identity = mapOf(
            "type" to "identify",
            "deviceId" to android.os.Build.ID + "_" + android.os.Build.MODEL,
            "deviceName" to android.os.Build.MODEL
        )
        send(gson.toJson(identity))
    }

    private fun startReadLoop() {
        try {
            while (isConnected) {
                // Read 4-byte length
                val totalLength = inputStream!!.readInt() 
                
                if (totalLength <= 0) continue

                // Read 1-byte type
                val type = inputStream!!.readByte()
                
                if (type.toInt() == 0) {
                    val payload = ByteArray(totalLength - 1)
                    inputStream!!.readFully(payload)
                    listener.onMessage(String(payload, Charsets.UTF_8))
                } else if (type.toInt() == 1) {
                    val payload = ByteArray(totalLength - 1)
                    inputStream!!.readFully(payload)
                    listener.onMessage(ByteString.of(*payload))
                } else if (type.toInt() == 2) {
                    // Type 2: Raw Stream Mode
                    val nameLen = inputStream!!.readInt()
                    val nameBytes = ByteArray(nameLen)
                    inputStream!!.readFully(nameBytes)
                    val fileName = String(nameBytes, Charsets.UTF_8)
                    val fileSize = inputStream!!.readLong()
                    
                    listener.onMessage(gson.toJson(mapOf(
                        "type" to "stream_start", 
                        "name" to fileName, 
                        "size" to fileSize
                    )))
                    
                    val file = listener.getOutputFile(fileName)
                    val buffer = ByteArray(4 * 1024 * 1024)
                    var bytesReceived = 0L
                    
                    FileOutputStream(file).use { fos ->
                        while (bytesReceived < fileSize) {
                            val toRead = Math.min(buffer.size.toLong(), fileSize - bytesReceived).toInt()
                            val readSize = inputStream!!.read(buffer, 0, toRead)
                            if (readSize == -1) break
                            fos.write(buffer, 0, readSize)
                            bytesReceived += readSize
                            
                            listener.onProgress(fileName, (bytesReceived * 100 / fileSize).toInt())
                        }
                        fos.flush()
                    }
                    
                    listener.onMessage(gson.toJson(mapOf(
                        "type" to "file_complete", 
                        "name" to fileName,
                        "localPath" to file.absolutePath
                    )))
                }
            }
        } catch (e: Exception) {
            Log.e("Beam", "Read loop error: ${e.message}")
        } finally {
            closeInternal()
            if (isConnected) {
                isConnected = false
                listener.onDisconnected()
                // Reconnect if we lost connection unexpectedly
                currentHost?.let { connect(it, currentPort) }
            }
        }
    }

    @Synchronized
    fun send(message: String): Boolean {
        return try {
            val payload = message.toByteArray(Charsets.UTF_8)
            outputStream?.let { 
                it.writeInt(payload.size + 1)
                it.writeByte(0)
                it.write(payload)
                it.flush()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun send(bytes: ByteArray): Boolean {
        return try {
            outputStream?.let {
                it.writeInt(bytes.size + 1)
                it.writeByte(1)
                it.write(bytes)
                it.flush()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun sendStream(input: InputStream, fileName: String, fileSize: Long, onProgress: (Int) -> Unit): Boolean {
        return try {
            outputStream?.let { out ->
                val nameBytes = fileName.toByteArray(Charsets.UTF_8)
                
                // Type 2: [Int Length][Byte 2][Int NameLen][Name][Long Size][RAW DATA]
                val headerLength = 1 + 4 + nameBytes.size + 8
                out.writeInt(headerLength)
                out.writeByte(2) // Type 2: Raw Stream
                
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
                out.writeLong(fileSize)
                out.flush() // Send header first
                
                val buffer = ByteArray(4 * 1024 * 1024)
                var bytesSent = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    bytesSent += read
                    onProgress((bytesSent * 100 / fileSize).toInt())
                }
                out.flush()
                true
            } ?: false
        } catch (e: Exception) {
            Log.e("Beam", "Streaming error: ${e.message}")
            false
        }
    }

    private fun closeInternal() {
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }

    fun close() {
        currentHost = null 
        isConnected = false
        closeInternal()
    }
}
