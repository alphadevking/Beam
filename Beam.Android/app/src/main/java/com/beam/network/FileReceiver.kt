package com.beam.network

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

class FileReceiver(private val context: Context) {
    private val activeTransfers = mutableMapOf<String, FileTransferState>()
    private val CHUNK_SIZE = 512 * 1024

    data class FileTransferState(
        val fileName: String,
        val totalChunks: Int,
        var receivedChunks: Int = 0,
        val tempFile: File,
        val raf: RandomAccessFile
    )

    fun handleChunk(fileName: String, chunkIndex: Int, totalChunks: Int, base64Data: String, onProgress: (String, Int) -> Unit) {
        val state = try {
            activeTransfers.getOrPut(fileName) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                
                // QA: Check disk space (approximation)
                if (downloadsDir.usableSpace < (totalChunks.toLong() * CHUNK_SIZE)) {
                    Log.e("Beam", "Not enough disk space for $fileName")
                    return
                }

                val tempFile = File(downloadsDir, "$fileName.part")
                if (tempFile.exists()) tempFile.delete()
                
                FileTransferState(fileName, totalChunks, 0, tempFile, RandomAccessFile(tempFile, "rw"))
            }
        } catch (e: Exception) {
            Log.e("Beam", "Failed to initialize transfer: ${e.message}")
            return
        }

        try {
            val data = Base64.decode(base64Data, Base64.DEFAULT)
            state.raf.seek(chunkIndex.toLong() * CHUNK_SIZE)
            state.raf.write(data)
            
            state.receivedChunks++
            val progress = (state.receivedChunks.toDouble() / totalChunks * 100).toInt()
            onProgress(fileName, progress)

            if (state.receivedChunks == totalChunks) {
                finalizeFile(state)
                activeTransfers.remove(fileName)
                onProgress(fileName, 100)
            }
        } catch (e: Exception) {
            Log.e("Beam", "Error writing chunk: ${e.message}")
        }
    }

    private fun finalizeFile(state: FileTransferState) {
        try {
            state.raf.close()
            val finalFile = File(state.tempFile.parent, state.fileName)
            if (finalFile.exists()) finalFile.delete()
            state.tempFile.renameTo(finalFile)
            Log.d("Beam", "File saved to disk: ${finalFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("Beam", "Finalize error: ${e.message}")
        }
    }
}
