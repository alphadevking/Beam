package com.beam.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.google.gson.Gson
import java.io.InputStream
import kotlin.math.ceil

class FileSender(private val context: Context, private val webSocketClient: BeamWebSocketClient) {
    private val gson = Gson()
    private val CHUNK_SIZE = 512 * 1024 // 512KB chunks

    fun sendFile(uri: Uri, onProgress: (Int) -> Unit) {
        val fileName = getFileName(context, uri) ?: "unknown_file"
        
        Thread {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val availableBytes = inputStream.available()
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead: Int
                    var chunkIndex = 0
                    // approximate total chunks
                    val totalChunks = ceil(availableBytes.toDouble() / CHUNK_SIZE).toInt()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val base64Data = Base64.encodeToString(buffer.copyOfRange(0, bytesRead), Base64.NO_WRAP)
                        
                        val payload = mapOf(
                            "type" to "file_chunk",
                            "name" to fileName,
                            "chunkIndex" to chunkIndex,
                            "totalChunks" to totalChunks,
                            "data" to base64Data
                        )
                        
                        webSocketClient.send(gson.toJson(payload))
                        
                        chunkIndex++
                        val progress = ((chunkIndex.toDouble() / totalChunks) * 100).toInt()
                        onProgress(progress)

                        // brief sleep to prevent flooding the socket buffer
                        Thread.sleep(50)
                    }
                    inputStream.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    companion object {
        fun getFileName(context: Context, uri: Uri): String? {
            var result: String? = null
            if (uri.scheme == "content") {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = it.getString(index)
                    }
                }
            }
            if (result == null) {
                result = uri.path
                val cut = result?.lastIndexOf('/')
                if (cut != null && cut != -1) {
                    result = result?.substring(cut + 1)
                }
            }
            return result
        }
    }
}
