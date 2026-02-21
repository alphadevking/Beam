using System;
using System.IO;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace Beam.Windows.Services
{
    public class FileSender
    {
        private const int ChunkSize = 512 * 1024; // 512KB chunks for stability
        private readonly WebSocketHost _host;

        public FileSender(WebSocketHost host)
        {
            _host = host;
        }

        public async Task SendFile(string filePath, Action<double> onProgress)
        {
            if (!File.Exists(filePath)) return;

            string fileName = Path.GetFileName(filePath);
            long fileSize = new FileInfo(filePath).Length;
            int totalChunks = (int)Math.Ceiling((double)fileSize / ChunkSize);

            using var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, ChunkSize, true);
            byte[] buffer = new byte[ChunkSize];
            int bytesRead;
            int chunkIndex = 0;

            while ((bytesRead = await fs.ReadAsync(buffer, 0, buffer.Length)) > 0)
            {
                // Only payload creation is the significant allocation remaining
                var payload = new
                {
                    type = "file_chunk",
                    name = fileName,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    // Substring optimization if the buffer is larger than bytesRead
                    data = Convert.ToBase64String(buffer, 0, bytesRead)
                };

                _host.Broadcast(JsonConvert.SerializeObject(payload));
                
                chunkIndex++;
                onProgress?.Invoke((double)chunkIndex / totalChunks * 100);
                
                // Adaptive delay: wait longer for larger chunks to let the network clear
                await Task.Delay(5);
            }
        }
    }
}
