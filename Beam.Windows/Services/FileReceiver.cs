using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using Newtonsoft.Json;

namespace Beam.Windows.Services
{
    public class FileReceiver
    {
        private readonly string _downloadPath;
        private readonly Dictionary<string, FileTransferState> _activeTransfers = new Dictionary<string, FileTransferState>();

        private class FileTransferState
        {
            public string FileName { get; set; } = string.Empty;
            public int TotalChunks { get; set; }
            public int ReceivedChunks { get; set; }
            public string TempPath { get; set; } = string.Empty;
            public FileStream? Stream { get; set; }
            public bool[] ChunkMask { get; set; } = Array.Empty<bool>();
        }

        public FileReceiver()
        {
            _downloadPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Beam");
            if (!Directory.Exists(_downloadPath))
            {
                Directory.CreateDirectory(_downloadPath);
            }
        }

        public Stream GetStream(string fileName)
        {
            string path = Path.Combine(_downloadPath, fileName);
            // Ensure any partial files or existing files are handled
            if (File.Exists(path)) File.Delete(path);
            return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.ReadWrite, 4 * 1024 * 1024, true);
        }

        public string? HandleBinaryChunk(byte[] data, Action<string, int> onProgress)
        {
            using var ms = new MemoryStream(data);
            using var reader = new BinaryReader(ms);
            
            byte type = reader.ReadByte();
            if (type != 1) return null; // Not a chunk

            int chunkIndex = IPAddress.NetworkToHostOrder(reader.ReadInt32());
            int totalChunks = IPAddress.NetworkToHostOrder(reader.ReadInt32());
            short nameLen = IPAddress.NetworkToHostOrder(reader.ReadInt16());
            string fileName = System.Text.Encoding.UTF8.GetString(reader.ReadBytes(nameLen));
            byte[] fileData = reader.ReadBytes((int)(ms.Length - ms.Position));

            FileTransferState? state;
            lock (_activeTransfers)
            {
                if (!_activeTransfers.TryGetValue(fileName, out state))
                {
                    string tempFile = Path.Combine(_downloadPath, fileName + ".part");
                    state = new FileTransferState
                    {
                        FileName = fileName,
                        TotalChunks = totalChunks,
                        TempPath = tempFile,
                        Stream = new FileStream(tempFile, FileMode.Create, FileAccess.Write, FileShare.ReadWrite, 4096, true),
                        ChunkMask = new bool[totalChunks]
                    };
                    _activeTransfers[fileName] = state;
                }
            }

            lock (state)
            {
                if (state.ChunkMask[chunkIndex]) return null;

                state.Stream!.Seek((long)chunkIndex * (256 * 1024), SeekOrigin.Begin);
                state.Stream.Write(fileData, 0, fileData.Length);
                state.ChunkMask[chunkIndex] = true;
                state.ReceivedChunks++;

                int progress = (int)((double)state.ReceivedChunks / totalChunks * 100);
                onProgress?.Invoke(fileName, progress);

                if (state.ReceivedChunks == totalChunks)
                {
                    state.Stream.Flush();
                    state.Stream.Close();
                    string finalPath = Path.Combine(_downloadPath, fileName);
                    if (File.Exists(finalPath)) File.Delete(finalPath);
                    File.Move(state.TempPath, finalPath);
                    lock (_activeTransfers)
                    {
                        _activeTransfers.Remove(fileName);
                    }
                    return finalPath;
                }
            }

            return null;
        }

        public void VerifyIntegrity(string fileName, string expectedMd5)
        {
            string filePath = Path.Combine(_downloadPath, fileName);
            if (!File.Exists(filePath))
            {
                Console.WriteLine($"[Beam] Integrity check SKIPPED — file not found: {fileName}");
                return;
            }

            using var md5 = MD5.Create();
            using var stream = File.OpenRead(filePath);
            byte[] hash = md5.ComputeHash(stream);
            string actualMd5 = BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();

            if (actualMd5 == expectedMd5)
            {
                Console.WriteLine($"[Beam] ✓ Integrity OK: {fileName} (MD5: {actualMd5})");
            }
            else
            {
                Console.WriteLine($"[Beam] ✗ INTEGRITY MISMATCH: {fileName} — expected {expectedMd5}, got {actualMd5}");
            }
        }
    }
}
