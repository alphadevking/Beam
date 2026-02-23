using System;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace Beam.Windows.Services
{
    public class FileSender
    {
        private const int ChunkSize = 256 * 1024; // 256KB for higher throughput
        private const int MaxRetries = 5;

        private readonly TcpHost _host;

        public FileSender(TcpHost host)
        {
            _host = host;
        }



        public async Task SendFile(string filePath, Action<double> onProgress)
        {
            if (!File.Exists(filePath)) return;

            string fileName = Path.GetFileName(filePath);
            long fileSize = new FileInfo(filePath).Length;

            _host.IsTransferActive = true;

            using (var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 4 * 1024 * 1024, true))
            {
                await _host.BroadcastStreamAsync(fs, fileName, fileSize, progress => 
                {
                    onProgress?.Invoke(progress);
                });
            }

            _host.IsTransferActive = false;
        }

        private static void WriteInt32BE(Stream s, int value)
        {
            int be = IPAddress.HostToNetworkOrder(value);
            s.Write(BitConverter.GetBytes(be), 0, 4);
        }

        private static void WriteInt16BE(Stream s, short value)
        {
            short be = IPAddress.HostToNetworkOrder(value);
            s.Write(BitConverter.GetBytes(be), 0, 2);
        }
    }
}
