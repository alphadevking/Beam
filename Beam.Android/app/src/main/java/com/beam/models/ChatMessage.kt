package com.beam.models

import java.util.Date
import java.util.UUID

data class ChatMessage(
    val content: String,
    val sender: String = "User",
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean,
    val type: String = "text",
    val localFilePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val progress: Int = 0,
    val messageId: String = UUID.randomUUID().toString().replace("-", ""),
    val deliveryStatus: String = "sending",  // sending, sent, delivered, failed
    val senderName: String = ""
)
