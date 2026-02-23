using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Beam.Windows.Services
{
    public class TcpHost
    {
        private TcpListener? _listener;
        private readonly Dictionary<string, TcpClientHandler> _clients = new Dictionary<string, TcpClientHandler>();
        private readonly List<TcpClientHandler> _unidentifiedClients = new List<TcpClientHandler>();
        private CancellationTokenSource? _cts;

        public Action<string>? OnMessageReceived { get; set; }
        public Action<byte[]>? OnBinaryReceived { get; set; }
        public Action<string>? OnClientConnected { get; set; }
        public Action<string>? OnClientDisconnected { get; set; }
        public Func<string, Stream>? OnGetFileStream { get; set; }
        public Action<string, int>? OnFileProgress { get; set; } // fileName, percent

        public Stream GetFileStream(string fileName) => OnGetFileStream?.Invoke(fileName) ?? Stream.Null;

        public int ClientCount
        {
            get
            {
                lock (_clients) return _clients.Count;
            }
        }

        public Action<string, string>? OnDeviceIdentified { get; set; } // IP, DeviceName

        public bool IsTransferActive { get; set; }

        public void Start(int port = 8081)
        {
            _cts = new CancellationTokenSource();
            _listener = new TcpListener(IPAddress.Any, port);
            _listener.Start();

            Task.Run(() => AcceptClientsAsync(_cts.Token));
        }

        private async Task AcceptClientsAsync(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var client = await _listener!.AcceptTcpClientAsync(token);
                    client.NoDelay = true;
                    client.ReceiveBufferSize = 4 * 1024 * 1024; // 4MB Lightning Buffer
                    client.SendBufferSize = 4 * 1024 * 1024;

                    var handler = new TcpClientHandler(client, this);
                    
                    lock (_unidentifiedClients) _unidentifiedClients.Add(handler);
                    
                    _ = Task.Run(() => handler.RunAsync(token), token);
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    Console.WriteLine($"Accept error: {ex.Message}");
                }
            }
        }

        public void Broadcast(string message)
        {
            byte[] payload = Encoding.UTF8.GetBytes(message);
            BroadcastInternal(0, payload);
        }

        public void Broadcast(byte[] data)
        {
            BroadcastInternal(1, data);
        }

        private void BroadcastInternal(byte type, byte[] payload)
        {
            List<TcpClientHandler> clientsCopy;
            lock (_clients) clientsCopy = _clients.Values.ToList();

            byte[] header = new byte[5];
            int length = payload.Length + 1; // +1 for type byte
            BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(0, 4), length);
            header[4] = type;

            foreach (var client in clientsCopy)
            {
                client.Send(header, payload);
            }
        }

        public async Task BroadcastAsync(byte[] data)
        {
            Broadcast(data); // Simple for now, can be optimized later if needed
            await Task.CompletedTask;
        }

        public async Task BroadcastAsync(string message)
        {
            Broadcast(message);
            await Task.CompletedTask;
        }

        public async Task BroadcastStreamAsync(Stream input, string fileName, long fileSize, Action<int> onProgress)
        {
            List<TcpClientHandler> clientsCopy;
            lock (_clients) clientsCopy = _clients.Values.ToList();

            if (clientsCopy.Count == 0) return;

            byte[] nameBytes = Encoding.UTF8.GetBytes(fileName);
            int headerLength = 1 + 4 + nameBytes.Length + 8;
            
            byte[] header = new byte[4 + headerLength];
            BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(0, 4), headerLength);
            header[4] = 2; // Type 2: Raw Stream

            BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(5, 4), nameBytes.Length);
            Array.Copy(nameBytes, 0, header, 9, nameBytes.Length);

            BinaryPrimitives.WriteInt64BigEndian(header.AsSpan(9 + nameBytes.Length, 8), fileSize);

            // Send header to all
            foreach (var client in clientsCopy)
            {
                client.SendRaw(header);
                client.Flush();
            }

            byte[] buffer = new byte[4 * 1024 * 1024]; // 4MB Buffer
            long totalSent = 0;
            int read;
            int lastReportedProgress = -1;

            while ((read = await input.ReadAsync(buffer, 0, buffer.Length)) > 0)
            {
                foreach (var client in clientsCopy)
                {
                    client.SendRaw(buffer, read);
                }
                totalSent += read;
                int currentProgress = (int)((double)totalSent / fileSize * 100);
                if (currentProgress != lastReportedProgress)
                {
                    lastReportedProgress = currentProgress;
                    onProgress?.Invoke(currentProgress);
                }
            }

            foreach (var client in clientsCopy) client.Flush();
        }

        public void Stop()
        {
            _cts?.Cancel();
            _listener?.Stop();
            lock (_clients)
            {
                foreach (var client in _clients.Values) client.Close();
                _clients.Clear();
            }
            lock (_unidentifiedClients)
            {
                foreach (var client in _unidentifiedClients) client.Close();
                _unidentifiedClients.Clear();
            }
        }

        private class TcpClientHandler
        {
            private readonly TcpClient _client;
            private readonly TcpHost _host;
            private readonly NetworkStream _stream;

            public TcpClientHandler(TcpClient client, TcpHost host)
            {
                _client = client;
                _host = host;
                _stream = client.GetStream();
            }

            public async Task RunAsync(CancellationToken token)
            {
                byte[] lenBuffer = new byte[4];
                try
                {
                    while (!token.IsCancellationRequested && _client.Connected)
                    {
                        // Read 4-byte length
                        if (!await ReadFullyAsync(_stream, lenBuffer, 4, token)) break;
                        int totalLength = BinaryPrimitives.ReadInt32BigEndian(lenBuffer);

                        if (totalLength <= 0) continue; 

                        // Read 1-byte type
                        int typeOrdinal = _stream.ReadByte();
                        if (typeOrdinal == -1) break;

                        if (typeOrdinal == 0) // JSON Control
                        {
                            byte[] payload = new byte[totalLength - 1];
                            if (!await ReadFullyAsync(_stream, payload, payload.Length, token)) break;
                            string json = Encoding.UTF8.GetString(payload);
                            HandleControlMessage(json);
                        }
                        else if (typeOrdinal == 2) // Raw Continuous Stream (Lightning Mode)
                        {
                            await HandleStreamAsync(token);
                        }
                        else if (typeOrdinal == 1) // Legacy/Chunk Binary (maintained for basic compatibility if needed)
                        {
                            byte[] payload = new byte[totalLength - 1];
                            if (!await ReadFullyAsync(_stream, payload, payload.Length, token)) break;
                            _host.OnBinaryReceived?.Invoke(payload);
                        }
                    }
                }
                catch { }
                finally
                {
                    Close();
                    lock (_host._clients)
                    {
                        if (!string.IsNullOrEmpty(DeviceId)) _host._clients.Remove(DeviceId);
                    }
                    lock (_host._unidentifiedClients) _host._unidentifiedClients.Remove(this);
                    
                    var clientIp = GetIp();
                    _host.OnClientDisconnected?.Invoke(clientIp);
                }
            }

            private void HandleControlMessage(string json)
            {
                try
                {
                    dynamic? data = Newtonsoft.Json.JsonConvert.DeserializeObject(json);
                    if (data?.type == "identify")
                    {
                        string id = (string)data.deviceId ?? "";
                        string name = (string)data.deviceName ?? "Phone";
                        DeviceId = id;
                        DeviceName = name;

                        lock (_host._clients)
                        {
                            if (_host._clients.TryGetValue(id, out var oldHandler))
                            {
                                oldHandler.Close();
                            }
                            _host._clients[id] = this;
                        }
                        lock (_host._unidentifiedClients) _host._unidentifiedClients.Remove(this);
                        
                        _host.OnDeviceIdentified?.Invoke(GetIp(), name);
                        _host.OnClientConnected?.Invoke(GetIp());
                    }
                    else
                    {
                        _host.OnMessageReceived?.Invoke(json);
                    }
                }
                catch { }
            }

            private async Task HandleStreamAsync(CancellationToken token)
            {
                string? fileName = null;
                try {
                    // Header: [4b NameLen][UTF-8 Name][8b FileSize]
                    byte[] nameLenBuf = new byte[4];
                    if (!await ReadFullyAsync(_stream, nameLenBuf, 4, token)) return;
                    int nameLen = BinaryPrimitives.ReadInt32BigEndian(nameLenBuf);

                    byte[] nameBuf = new byte[nameLen];
                    if (!await ReadFullyAsync(_stream, nameBuf, nameLen, token)) return;
                    fileName = Encoding.UTF8.GetString(nameBuf);

                    byte[] sizeBuf = new byte[8];
                    if (!await ReadFullyAsync(_stream, sizeBuf, 8, token)) return;
                    long fileSize = BinaryPrimitives.ReadInt64BigEndian(sizeBuf);

                    // Notify UI that a high-speed stream is starting
                    _host.OnMessageReceived?.Invoke(Newtonsoft.Json.JsonConvert.SerializeObject(new { 
                        type = "stream_start", 
                        name = fileName, 
                        size = fileSize,
                        senderId = string.IsNullOrEmpty(DeviceName) ? "Phone" : DeviceName
                    }));

                    long totalReceived = 0;
                    byte[] buffer = new byte[4 * 1024 * 1024]; // 4MB High-Speed Buffer
                    int lastReportedProgress = -1;
                    
                    using (var fs = _host.GetFileStream(fileName))
                    {
                        while (totalReceived < fileSize)
                        {
                            int toRead = (int)Math.Min(buffer.Length, fileSize - totalReceived);
                            int read = await _stream.ReadAsync(buffer, 0, toRead, token);
                            
                            if (read <= 0) 
                            {
                                Console.WriteLine($"[TcpHost] Warning: Stream EOF reached early. Expected {fileSize}, got {totalReceived}");
                                break; // Gracefully stop if the sender stopped sending bytes early!
                            }
                            
                            await fs.WriteAsync(buffer, 0, read, token);
                            totalReceived += read;
                            
                            // Frequent progress reporting for smooth UI
                            int currentProgress = (int)((double)totalReceived / fileSize * 100);
                            if (currentProgress != lastReportedProgress)
                            {
                                lastReportedProgress = currentProgress;
                                _host.OnFileProgress?.Invoke(fileName, currentProgress);
                            }
                        }
                        await fs.FlushAsync(token);
                    }

                    _host.OnMessageReceived?.Invoke(Newtonsoft.Json.JsonConvert.SerializeObject(new { 
                        type = "file_complete", 
                        name = fileName,
                        status = "received"
                    }));
                } catch (Exception ex) {
                    Console.WriteLine($"[TcpHost] Lightning Stream Error for {fileName ?? "unknown"}: {ex.Message}");
                    throw; // Throw to break the main RunAsync network loop
                }
            }

            public string DeviceId { get; private set; } = "";
            public string DeviceName { get; private set; } = "Phone";

            private string GetIp()
            {
                try {
                   return ((IPEndPoint)_client.Client.RemoteEndPoint!).Address.ToString();
                } catch { return "Unknown"; }
            }

            private async Task<bool> ReadFullyAsync(Stream stream, byte[] buffer, int count, CancellationToken token)
            {
                int totalRead = 0;
                while (totalRead < count)
                {
                    int read = await stream.ReadAsync(buffer, totalRead, count - totalRead, token);
                    if (read <= 0) return false;
                    totalRead += read;
                }
                return true;
            }

            public void Send(byte[] header, byte[] payload)
            {
                try
                {
                    lock (_stream)
                    {
                        _stream.Write(header, 0, header.Length);
                        _stream.Write(payload, 0, payload.Length);
                        _stream.Flush();
                    }
                }
                catch { }
            }

            public void SendRaw(byte[] data, int length = -1)
            {
                try {
                    lock (_stream) {
                        _stream.Write(data, 0, length == -1 ? data.Length : length);
                    }
                } catch { }
            }

            public void Flush()
            {
                try {
                    lock (_stream) {
                        _stream.Flush();
                    }
                } catch { }
            }

            public void Close()
            {
                _client.Close();
            }
        }
    }
}
