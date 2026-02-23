package com.beam.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.ceil
import java.util.concurrent.Semaphore

class FileSender(private val context: Context, private val tcpClient: BeamTcpClient) {
    private val gson = Gson()
    private val CHUNK_SIZE = 256 * 1024 // 256KB for higher throughput
    private val MAX_RETRIES = 5
    private val RETRY_DELAY_MS = 200L

    fun sendFile(uri: Uri, onProgress: (Int) -> Unit) {
        val fileName = getFileName(context, uri) ?: "unknown_file"
        
        Thread {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { 
                    it.statSize 
                } ?: 0L

                if (inputStream != null && fileSize > 0) {
                    waitForConnection()
                    val success = tcpClient.sendStream(inputStream, fileName, fileSize) { progress ->
                        onProgress(progress)
                    }
                    if (success) {
                        Log.d("Beam", "File sent successfully: $fileName")
                    } else {
                        Log.e("Beam", "Failed to send file: $fileName")
                    }
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e("Beam", "FileSender error: ${e.message}")
            }
        }.start()
    }

    private fun waitForConnection() {
        while (!tcpClient.isConnected) {
            Thread.sleep(500)
        }
    }

    private fun sendWithRetry(json: String) {
        var success = tcpClient.send(json)
        while (!success) {
            Thread.sleep(200)
            waitForConnection()
            success = tcpClient.send(json)
        }
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
