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
 
    interface OnMessageActionListener {
        fun onCopyMessage(text: String)
        fun onOpenFile(path: String)
        fun onSaveFile(path: String)
    }
 
    private var actionListener: OnMessageActionListener? = null
 
    fun setOnMessageActionListener(listener: OnMessageActionListener) {
        this.actionListener = listener
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val openButton: View = view.findViewById(R.id.openButton)
        val saveButton: View = view.findViewById(R.id.saveButton)
        val fileActionLayout: View = view.findViewById(R.id.fileActionLayout)
        
        val fileAttachmentLayout: LinearLayout = view.findViewById(R.id.fileAttachmentLayout)
        val fileNameText: TextView = view.findViewById(R.id.fileNameText)
        val fileProgressBar: android.widget.ProgressBar = view.findViewById(R.id.fileProgressBar)
        val fileProgressText: TextView = view.findViewById(R.id.fileProgressText)
        val fileSizeText: TextView = view.findViewById(R.id.fileSizeText)
        val senderNameText: TextView = view.findViewById(R.id.senderNameText)
        val deliveryStatusText: TextView = view.findViewById(R.id.deliveryStatusText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        holder.timeText.text = sdf.format(Date(msg.timestamp))
 
        // Delivery status
        holder.deliveryStatusText.text = when (msg.deliveryStatus) {
            "sending" -> "⏳"
            "sent" -> "✓"
            "delivered" -> "✓✓"
            "verified" -> "✓✓✓"
            "failed" -> "✗"
            else -> ""
        }

        // Sender name (only for incoming messages)
        if (!msg.isMe && msg.senderName.isNotBlank()) {
            holder.senderNameText.visibility = View.VISIBLE
            holder.senderNameText.text = msg.senderName
        } else {
            holder.senderNameText.visibility = View.GONE
        }

        // Interactivity for text
        holder.messageText.setOnLongClickListener {
            actionListener?.onCopyMessage(msg.content)
            true
        }
 
        if (msg.type == "file") {
            holder.messageText.visibility = View.GONE
            holder.fileAttachmentLayout.visibility = View.VISIBLE
            
            holder.fileNameText.text = msg.fileName ?: msg.content
            holder.fileProgressBar.progress = msg.progress
            holder.fileProgressText.text = "${msg.progress}%"
            
            val totalSizeStr = android.text.format.Formatter.formatFileSize(holder.itemView.context, msg.fileSize)
            if (msg.progress < 100) {
                holder.fileSizeText.text = if (msg.isMe) "$totalSizeStr • Sending..." else "$totalSizeStr • Receiving..."
            } else {
                holder.fileSizeText.text = if (msg.isMe) "$totalSizeStr • Sent" else "$totalSizeStr • Downloaded"
            }
            
            if (msg.progress >= 100 && msg.localFilePath != null) {
                holder.fileActionLayout.visibility = View.VISIBLE
                holder.fileProgressText.visibility = View.GONE
                
                holder.openButton.setOnClickListener {
                    actionListener?.onOpenFile(msg.localFilePath)
                }
                holder.saveButton.setOnClickListener {
                    actionListener?.onSaveFile(msg.localFilePath)
                }
            } else {
                holder.fileActionLayout.visibility = View.GONE
                holder.fileProgressText.visibility = View.VISIBLE
            }

            val params = holder.fileAttachmentLayout.layoutParams as LinearLayout.LayoutParams
            if (msg.isMe) {
                params.gravity = Gravity.END
                holder.fileAttachmentLayout.setBackgroundResource(R.drawable.bg_bubble_me)
            } else {
                params.gravity = Gravity.START
                holder.fileAttachmentLayout.setBackgroundResource(R.drawable.bg_bubble_them)
            }
            holder.fileAttachmentLayout.layoutParams = params
            
        } else {
            holder.messageText.visibility = View.VISIBLE
            holder.fileAttachmentLayout.visibility = View.GONE
            holder.messageText.text = msg.content

            val params = holder.messageText.layoutParams as LinearLayout.LayoutParams
            if (msg.isMe) {
                params.gravity = Gravity.END
                holder.messageText.setBackgroundResource(R.drawable.bg_bubble_me)
            } else {
                params.gravity = Gravity.START
                holder.messageText.setBackgroundResource(R.drawable.bg_bubble_them)
            }
            holder.messageText.layoutParams = params
        }
    }

    override fun getItemCount() = messages.size
}
