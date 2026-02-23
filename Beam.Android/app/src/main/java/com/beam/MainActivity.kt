package com.beam

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.beam.adapters.MessageAdapter
import com.beam.models.ChatMessage
import com.beam.network.BeamTcpClient
import com.beam.network.NetworkDiscoveryTask
import com.beam.network.FileReceiver
import com.beam.network.FileSender
import com.beam.services.BeamForegroundService
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import okio.ByteString
import java.io.File

class MainActivity : AppCompatActivity(), BeamTcpClient.BeamSocketListener, MessageAdapter.OnMessageActionListener {
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var attachButton: MaterialButton
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var connectedIp: String? = null
    
    private lateinit var discoveryTask: NetworkDiscoveryTask
    private lateinit var tcpClient: BeamTcpClient
    private lateinit var fileSender: FileSender
    private lateinit var fileReceiver: FileReceiver
    private val gson = Gson()
    private lateinit var toolbar: MaterialToolbar
    private val textExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var hostIp: String? = null // Added

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        
        fileReceiver = FileReceiver(this)
        tcpClient = BeamTcpClient(this)
        fileSender = FileSender(this, tcpClient)
        discoveryTask = NetworkDiscoveryTask(this) { ip ->
            runOnUiThread {
                connectedIp = ip
                hostIp = ip // Set hostIp
                toolbar.subtitle = "Connecting to $ip..."
                tcpClient.connect(ip)
            }
        }
        
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "v0.0.0" }
        
        toolbar.subtitle = "Searching for PC... $versionName"
        discoveryTask.startListening()

        sendButton.setOnClickListener {
            sendMessage()
        }

        attachButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 1001)
        }
    }

    private fun setupUI() {
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
        adapter.setOnMessageActionListener(this)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = adapter
    }

    // --- Manual IP Entry via Toolbar Menu ---
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Connect manually")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            showManualIpDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showManualIpDialog() {
        val input = EditText(this)
        input.hint = "e.g. 192.168.43.100"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        input.setPadding(60, 40, 60, 20)

        AlertDialog.Builder(this)
            .setTitle("Connect to PC")
            .setMessage("Enter the PC's IP address (shown at the top-right of the Beam window on your PC):")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotBlank()) {
                    // Stop auto-discovery
                    discoveryTask.stopListening()
                    connectedIp = ip
                    hostIp = ip // Set hostIp
                    toolbar.subtitle = "Connecting to $ip..."
                    tcpClient.connect(ip)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val fileName = com.beam.network.FileSender.getFileName(this, uri) ?: "unknown_file"
                val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                val msg = ChatMessage(content = "", isMe = true, type = "file", fileName = fileName, fileSize = fileSize, progress = 0)
                messages.add(msg)
                val msgIndex = messages.size - 1
                adapter.notifyItemInserted(msgIndex)
                chatRecyclerView.scrollToPosition(msgIndex)

                fileSender.sendFile(uri) { progress: Int ->
                    runOnUiThread {
                        val idx = messages.indexOfLast { it.type == "file" && it.fileName == fileName && it.isMe }
                        if (idx != -1) {
                            val oldMsg = messages[idx]
                            messages[idx] = oldMsg.copy(
                                progress = progress,
                                deliveryStatus = if (progress >= 100) "sent" else oldMsg.deliveryStatus
                            )
                            adapter.notifyItemChanged(idx)
                            if (progress >= 100) chatRecyclerView.scrollToPosition(idx)
                        }
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString()
        if (text.isNotBlank()) {
            val msg = ChatMessage(content = text, isMe = true, deliveryStatus = "sending", senderName = android.os.Build.MODEL)
            messages.add(msg)
            adapter.notifyItemInserted(messages.size - 1)
            chatRecyclerView.scrollToPosition(messages.size - 1)
            
            val json = gson.toJson(mapOf("type" to "text", "content" to text, "senderId" to android.os.Build.MODEL, "messageId" to msg.messageId))
            val msgId = msg.messageId
            
            textExecutor.execute {
                if (!tcpClient.isConnected) {
                    hostIp?.let { tcpClient.connect(it) }
                }
                var sendSuccess = tcpClient.send(json)
                while (!sendSuccess) {
                    Thread.sleep(200)
                    while (!tcpClient.isConnected) Thread.sleep(500)
                    sendSuccess = tcpClient.send(json)
                }
                
                runOnUiThread {
                    val idx = messages.indexOfFirst { it.messageId == msgId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(deliveryStatus = "sent")
                        adapter.notifyItemChanged(idx)
                        chatRecyclerView.scrollToPosition(idx)
                    }
                }
            }
            
            messageInput.text.clear()
        }
    }

    override fun getOutputFile(fileName: String): File {
        return fileReceiver.getOutputFile(fileName)
    }

    override fun onMessage(byteString: okio.ByteString) {
        // Legacy chunk handling
        fileReceiver.handleBinaryChunk(byteString.toByteArray()) { fileName, progress, savedPath ->
            runOnUiThread {
                val msgIndex = messages.indexOfLast { it.type == "file" && it.fileName == fileName && !it.isMe }
                if (msgIndex != -1) {
                    val oldMsg = messages[msgIndex]
                    messages[msgIndex] = oldMsg.copy(
                        progress = progress,
                        localFilePath = savedPath ?: oldMsg.localFilePath
                    )
                    adapter.notifyItemChanged(msgIndex)
                }
            }
        }
    }

    override fun onProgress(fileName: String, progress: Int) {
        runOnUiThread {
            val msgIndex = messages.indexOfLast { it.type == "file" && it.fileName == fileName && !it.isMe }
            if (msgIndex != -1) {
                messages[msgIndex] = messages[msgIndex].copy(progress = progress)
                adapter.notifyItemChanged(msgIndex)
            }
        }
    }

    override fun onMessage(text: String) {
        runOnUiThread {
            try {
                val data = gson.fromJson(text, Map::class.java)
                val type = data["type"] as String
                
                if (type == "text") {
                    val content = data["content"] as String
                    val messageId = data["messageId"] as? String ?: ""
                    val msg = ChatMessage(content = content, isMe = false, senderName = "PC", deliveryStatus = "delivered", messageId = messageId)
                    messages.add(msg)
                    adapter.notifyItemInserted(messages.size - 1)
                    chatRecyclerView.scrollToPosition(messages.size - 1)

                    // Send delivery receipt back
                    if (messageId.isNotBlank()) {
                        val receipt = mapOf("type" to "delivery_receipt", "messageId" to messageId, "status" to "delivered")
                        this.tcpClient.send(gson.toJson(receipt))
                    }
                } else if (type == "delivery_receipt") {
                    val messageId = data["messageId"] as? String
                    val fileName = data["fileName"] as? String
                    
                    if (messageId?.isNotBlank() == true) {
                        val idx = messages.indexOfFirst { it.messageId == messageId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(deliveryStatus = "delivered")
                            adapter.notifyItemChanged(idx)
                        }
                    } else if (fileName?.isNotBlank() == true) {
                        val idx = messages.indexOfLast { it.type == "file" && it.fileName == fileName && it.isMe }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(deliveryStatus = "delivered")
                            adapter.notifyItemChanged(idx)
                        }
                    }
                } else if (type == "stream_start" || type == "file_metadata") {
                    val name = data["name"] as String
                    val size = (data["size"] as Double).toLong()
                    
                    val senderName = (data["senderId"] as? String) ?: "PC"
                    val existingIdx = messages.indexOfLast { it.type == "file" && it.fileName == name && !it.isMe }
                    
                    if (existingIdx == -1) {
                        val msg = ChatMessage(
                            content = "",
                            isMe = false,
                            type = "file",
                            fileName = name,
                            fileSize = size,
                            progress = 0,
                            senderName = senderName,
                            deliveryStatus = "receiving"
                        )
                        messages.add(msg)
                        adapter.notifyItemInserted(messages.size - 1)
                        chatRecyclerView.scrollToPosition(messages.size - 1)
                    } else {
                        messages[existingIdx] = messages[existingIdx].copy(deliveryStatus = "receiving")
                        adapter.notifyItemChanged(existingIdx)
                    }

                } else if (type == "file_complete") {
                    val name = data["name"] as String
                    val localPath = data["localPath"] as? String
                    
                    val msgIndex = messages.indexOfLast { it.type == "file" && it.fileName == name && !it.isMe }
                    if (msgIndex != -1) {
                        val oldMsg = messages[msgIndex]
                        messages[msgIndex] = oldMsg.copy(
                            deliveryStatus = "delivered", 
                            progress = 100,
                            localFilePath = localPath ?: oldMsg.localFilePath
                        )
                        adapter.notifyItemChanged(msgIndex)
                    }
                } else if (type == "delivery_receipt") {
                    val messageId = data["messageId"] as String
                    val status = data["status"] as String
                    val msgIndex = messages.indexOfFirst { it.messageId == messageId }
                    if (msgIndex != -1) {
                        messages[msgIndex] = messages[msgIndex].copy(deliveryStatus = status)
                        adapter.notifyItemChanged(msgIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var wasDisconnected = true

    override fun onConnected() {
        runOnUiThread {
            toolbar.title = "Beam"
            toolbar.subtitle = "Connected to PC" + (if (connectedIp != null) " ($connectedIp)" else "")
            if (wasDisconnected) {
                Toast.makeText(this, "Connected to PC!", Toast.LENGTH_SHORT).show()
                wasDisconnected = false
            }
        }
    }

    override fun onDisconnected() {
        wasDisconnected = true
        runOnUiThread {
            toolbar.title = "Beam"
            toolbar.subtitle = "Disconnected — tap ⋮ to connect manually"
            if (connectedIp == null) {
                discoveryTask.startListening()
            }
        }
    }

    override fun onCopyMessage(text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Beam Message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
 
    override fun onOpenFile(path: String) {
        try {
            val file = java.io.File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveFile(path: String) {
        try {
            val sourceFile = java.io.File(path)
            if (!sourceFile.exists()) {
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val destFile = java.io.File(downloadsDir, sourceFile.name)
            sourceFile.copyTo(destFile, overwrite = true)
            Toast.makeText(this, "Saved to Downloads: ${sourceFile.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
 
    override fun onDestroy() {
        super.onDestroy()
        discoveryTask.stopListening()
        tcpClient.close()
    }
}
