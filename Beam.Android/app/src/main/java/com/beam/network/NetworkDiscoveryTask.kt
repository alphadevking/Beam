package com.beam.network

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class NetworkDiscoveryTask(private val context: Context, private val onServerFound: (String) -> Unit) {
    private val PORT = 8888
    private val EXPECTED_MESSAGE = "I_AM_THE_HOST"
    private var socket: DatagramSocket? = null
    private var isListening = false

    fun startListening() {
        isListening = true
        Thread {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifiManager.createMulticastLock("BeamDiscoveryLock")
            lock.setReferenceCounted(true)
            
            try {
                lock.acquire()
                socket = DatagramSocket(PORT)
                socket?.broadcast = true
                val buffer = ByteArray(1024)
                
                Log.d("Beam", "Listening for UDP on port $PORT...")

                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    
                    if (message == EXPECTED_MESSAGE) {
                        val serverIp = packet.address.hostAddress
                        Log.d("Beam", "Server found at: $serverIp")
                        onServerFound(serverIp!!)
                    }
                }
            } catch (e: Exception) {
                Log.e("Beam", "UDP Error: ${e.message}")
            } finally {
                if (lock.isHeld) lock.release()
                socket?.close()
            }
        }.start()
    }

    fun stopListening() {
        isListening = false
        socket?.close()
    }
}
