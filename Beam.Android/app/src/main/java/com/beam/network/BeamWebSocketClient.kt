package com.beam.network

import android.util.Log
import okhttp3.*
import okio.ByteString

class BeamWebSocketClient(private val listener: BeamSocketListener) {
    private var client: OkHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    interface BeamSocketListener {
        fun onMessage(text: String)
        fun onConnected()
        fun onDisconnected()
    }

    fun connect(ip: String, port: Int = 8081) {
        val request = Request.Builder().url("ws://$ip:$port").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("Beam", "Connected to WebSocket")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Beam", "WebSocket Failure: ${t.message}. Retrying in 5s...")
                listener.onDisconnected()
                
                // Auto-reconnect logic
                Thread {
                    Thread.sleep(5000)
                    connect(ip, port)
                }.start()
            }
        })
    }

    fun send(message: String) {
        webSocket?.send(message)
    }

    fun close() {
        webSocket?.close(1000, "App closing")
    }
}
