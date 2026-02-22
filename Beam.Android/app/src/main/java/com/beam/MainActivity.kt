package com.beam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.Toast

class MainActivity : AppCompatActivity(), BeamWebSocketClient.BeamSocketListener {
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var attachButton: android.widget.ImageButton
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var connectedIp: String? = null
    
    private lateinit var discoveryTask: NetworkDiscoveryTask
    private lateinit var webSocketClient: BeamWebSocketClient
    private lateinit var fileReceiver: FileReceiver
    private lateinit var fileSender: com.beam.network.FileSender
    private val gson = Gson()
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Start Foreground Service
        val serviceIntent = Intent(this, BeamForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        attachButton = findViewById(R.id.attachButton)

        adapter = MessageAdapter(messages)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = adapter

        fileReceiver = FileReceiver(this)
        webSocketClient = BeamWebSocketClient(this)
        fileSender = com.beam.network.FileSender(this, webSocketClient)
        discoveryTask = NetworkDiscoveryTask(this) { ip ->
            runOnUiThread {
                connectedIp = ip
                toolbar.subtitle = "Connecting to $ip..."
                webSocketClient.connect(ip)
            }
        }
        
        toolbar.subtitle = "Searching for PC..."
        discoveryTask.startListening()

        sendButton.setOnClickListener {
            sendMessage()
        }

        attachButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            startActivityForResult(intent, 1001)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val fileName = com.beam.network.FileSender.getFileName(this, uri) ?: "unknown_file"
                // Inform UI
                val msg = ChatMessage(content = "Sending: $fileName", isMe = true, type = "file")
                messages.add(msg)
                val msgIndex = messages.size - 1
                adapter.notifyItemInserted(msgIndex)
                chatRecyclerView.scrollToPosition(msgIndex)

                fileSender.sendFile(uri) { progress ->
                    runOnUiThread {
                        if (progress < 100) {
                            messages[msgIndex] = ChatMessage(content = "Sending: $fileName ($progress%)", isMe = true, type = "file")
                        } else {
                            messages[msgIndex] = ChatMessage(content = "Sent: $fileName", isMe = true, type = "file")
                        }
                        adapter.notifyItemChanged(msgIndex)
                    }
                }
            }
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

    override fun onConnected() {
        runOnUiThread {
            toolbar.title = "Beam (Connected to PC" + (if (connectedIp != null) " - $connectedIp" else "") + ")"
            toolbar.subtitle = "Ready to send files"
            Toast.makeText(this, "Connected to PC!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            toolbar.title = "Beam (Disconnected)"
            toolbar.subtitle = "Searching for PC..."
            discoveryTask.startListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryTask.stopListening()
        webSocketClient.close()
    }
}
