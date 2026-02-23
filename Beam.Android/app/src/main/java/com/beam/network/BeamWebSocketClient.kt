package com.beam.network

import android.util.Log
import okhttp3.*
import okio.ByteString

import java.util.concurrent.TimeUnit
 
class BeamWebSocketClient(private val listener: BeamSocketListener) {
    private var client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null

    interface BeamSocketListener {
        fun onMessage(text: String)
        fun onMessage(byteString: ByteString)
        fun onConnected()
        fun onDisconnected()
    }

    private var isConnecting = false
    @Volatile var isConnected: Boolean = false
        private set

    @Synchronized
    fun connect(ip: String, port: Int = 8081) {
        if (isConnecting) return
        isConnecting = true
        
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()

        val request = Request.Builder().url("ws://$ip:$port").build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnecting = false
                isConnected = true
                Log.d("Beam", "Connected to WebSocket")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != this@BeamWebSocketClient.webSocket) return
                isConnected = false
                webSocket.close(1000, null)
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != this@BeamWebSocketClient.webSocket) return
                isConnecting = false
                isConnected = false
                Log.e("Beam", "WebSocket Failure: ${t.message}. Retrying in 5s...")
                listener.onDisconnected()
                
                // Auto-reconnect logic
                Thread {
                    Thread.sleep(5000)
                    if (webSocket == this@BeamWebSocketClient.webSocket) {
                        connect(ip, port)
                    }
                }.start()
            }
        })
        webSocket = newSocket
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    fun send(bytes: ByteArray): Boolean {
        return webSocket?.send(ByteString.of(*bytes)) ?: false
    }

    fun close() {
        isConnected = false
        webSocket?.close(1000, "App closing")
    }
}
