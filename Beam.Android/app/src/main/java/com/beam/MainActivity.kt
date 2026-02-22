package com.beam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.beam.adapters.MessageAdapter
import com.beam.models.ChatMessage
import com.beam.network.BeamWebSocketClient
import com.beam.network.NetworkDiscoveryTask
import com.beam.network.FileReceiver
import com.beam.services.BeamForegroundService
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.Gson

class MainActivity : AppCompatActivity(), BeamWebSocketClient.BeamSocketListener {
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private lateinit var discoveryTask: NetworkDiscoveryTask
    private lateinit var webSocketClient: BeamWebSocketClient
    private lateinit var fileReceiver: FileReceiver
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start Foreground Service
        val serviceIntent = Intent(this, BeamForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        adapter = MessageAdapter(messages)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = adapter

        fileReceiver = FileReceiver(this)
        webSocketClient = BeamWebSocketClient(this)
        discoveryTask = NetworkDiscoveryTask(this) { ip ->
            runOnUiThread {
                webSocketClient.connect(ip)
            }
        }
        discoveryTask.startListening()

        sendButton.setOnClickListener {
            sendMessage()
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString()
        if (text.isNotBlank()) {
            val msg = ChatMessage(content = text, isMe = true)
            messages.add(msg)
            adapter.notifyItemInserted(messages.size - 1)
            chatRecyclerView.scrollToPosition(messages.size - 1)
            
            val json = gson.toJson(mapOf("type" to "text", "content" to text))
            webSocketClient.send(json)
            
            messageInput.text.clear()
        }
    }

    override fun onConnected() {
        runOnUiThread {
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title = "Beam (Connected)"
            android.widget.Toast.makeText(this, "Connected to PC!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMessage(text: String) {
        runOnUiThread {
            try {
                val data = gson.fromJson(text, Map::class.java)
                val type = data["type"] as String
                
                if (type == "text") {
                    val msg = ChatMessage(content = data["content"] as String, isMe = false)
                    messages.add(msg)
                    adapter.notifyItemInserted(messages.size - 1)
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                } else if (type == "file_chunk") {
                    val name = data["name"] as String
                    val index = (data["chunkIndex"] as Double).toInt()
                    val total = (data["totalChunks"] as Double).toInt()
                    val base64 = data["data"] as String
                    
                    fileReceiver.handleChunk(name, index, total, base64) { fileName, progress ->
                        runOnUiThread {
                            val statusMsg = if (progress < 100) "Receiving: $fileName ($progress%)" else "Received: $fileName"
                            val lastMsg = messages.lastOrNull()
                            if (lastMsg != null && lastMsg.content.startsWith("Receiving: $fileName")) {
                                val idx = messages.size - 1
                                messages[idx] = ChatMessage(content = statusMsg, isMe = false, type = "file")
                                adapter.notifyItemChanged(idx)
                            } else {
                                val msg = ChatMessage(content = statusMsg, isMe = false, type = "file")
                                messages.add(msg)
                                adapter.notifyItemInserted(messages.size - 1)
                                chatRecyclerView.scrollToPosition(messages.size - 1)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title = "Beam (Disconnected)"
            // Restart discovery
            discoveryTask.startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryTask.stopListening()
        webSocketClient.close()
    }
}
