package com.beam.network

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

class FileReceiver(private val context: Context) {
    private val activeTransfers = mutableMapOf<String, FileTransferState>()
    private val CHUNK_SIZE = 256 * 1024 // 256KB — matches FileSender

    data class FileTransferState(
        val fileName: String,
        val totalChunks: Int,
        var receivedChunks: Int = 0,
        val tempFile: File,
        val raf: RandomAccessFile,
        val chunkMask: BooleanArray
    )

    fun getOutputFile(fileName: String): File {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        return File(downloadsDir, fileName)
    }

    fun handleBinaryChunk(bytes: ByteArray, onProgress: (String, Int, String?) -> Unit) {
        try {
            val bis = java.io.ByteArrayInputStream(bytes)
            val dis = java.io.DataInputStream(bis)
            
            val type = dis.readByte()
            if (type.toInt() != 1) return

            val chunkIndex = dis.readInt()
            val totalChunks = dis.readInt()
            val nameLen = dis.readShort().toInt()
            val nameBytes = ByteArray(nameLen)
            dis.readFully(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)
            
            val state = activeTransfers.getOrPut(fileName) {
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val tempFile = File(downloadsDir, "$fileName.part")
                if (tempFile.exists()) tempFile.delete()
                FileTransferState(fileName, totalChunks, 0, tempFile, RandomAccessFile(tempFile, "rw"), BooleanArray(totalChunks))
            }

            if (state.chunkMask[chunkIndex]) return

            val dataSize = bytes.size - (1 + 4 + 4 + 2 + nameLen)
            val fileData = ByteArray(dataSize)
            dis.readFully(fileData)

            state.raf.seek(chunkIndex.toLong() * CHUNK_SIZE)
            state.raf.write(fileData)
            
            state.chunkMask[chunkIndex] = true
            state.receivedChunks++
            val progress = (state.receivedChunks.toDouble() / totalChunks * 100).toInt()
            onProgress(fileName, progress, null)

            if (state.receivedChunks == totalChunks) {
                val finalPath = finalizeFile(state)
                activeTransfers.remove(fileName)
                onProgress(fileName, 100, finalPath)
            }
        } catch (e: Exception) {
            Log.e("Beam", "Error writing binary chunk: ${e.message}")
        }
    }

    fun verifyIntegrity(fileName: String, expectedMd5: String) {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(downloadsDir, fileName)
        if (!file.exists()) {
            Log.w("Beam", "Integrity check SKIPPED — file not found: $fileName")
            return
        }

        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                md5.update(buffer, 0, bytesRead)
            }
        }

        val actualMd5 = md5.digest().joinToString("") { "%02x".format(it) }

        if (actualMd5 == expectedMd5) {
            Log.d("Beam", "✓ Integrity OK: $fileName (MD5: $actualMd5)")
        } else {
            Log.e("Beam", "✗ INTEGRITY MISMATCH: $fileName — expected $expectedMd5, got $actualMd5")
        }
    }

    private fun finalizeFile(state: FileTransferState): String? {
        return try {
            state.raf.close()
            val finalFile = File(state.tempFile.parent, state.fileName)
            if (finalFile.exists()) finalFile.delete()
            state.tempFile.renameTo(finalFile)
            Log.d("Beam", "File saved to disk: ${finalFile.absolutePath}")
            finalFile.absolutePath
        } catch (e: Exception) {
            Log.e("Beam", "Finalize error: ${e.message}")
            null
        }
    }
}
