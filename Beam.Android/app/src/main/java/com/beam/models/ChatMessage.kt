package com.beam.models

import java.util.Date

data class ChatMessage(
    val content: String,
    val sender: String = "User",
    val timestamp: Long = System.currentTimeMillis(),
    val isMe: Boolean,
    val type: String = "text"
)
