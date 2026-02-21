package com.beam.adapters

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.beam.R
import com.beam.models.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(private val messages: List<ChatMessage>) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        holder.messageText.text = msg.content
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.timeText.text = sdf.format(Date(msg.timestamp))

        val params = holder.messageText.layoutParams as LinearLayout.LayoutParams
        if (msg.isMe) {
            params.gravity = Gravity.END
            holder.messageText.setBackgroundResource(R.drawable.bg_bubble_me)
            holder.messageText.setTextColor(android.graphics.Color.WHITE)
        } else {
            params.gravity = Gravity.START
            holder.messageText.setBackgroundResource(R.drawable.bg_bubble_them)
            holder.messageText.setTextColor(android.graphics.Color.WHITE)
        }
        holder.messageText.layoutParams = params
    }

    override fun getItemCount() = messages.size
}
