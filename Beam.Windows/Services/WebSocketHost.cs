using Fleck;
using System;
using System.Collections.Generic;
using System.Linq;

namespace Beam.Windows.Services
{
    public class WebSocketHost
    {
        private WebSocketServer? _server;
        private List<IWebSocketConnection> _clients = new List<IWebSocketConnection>();

        public Action<string>? OnMessageReceived { get; set; }
        public Action<byte[]>? OnBinaryReceived { get; set; }
        public Action<string>? OnClientConnected { get; set; }
        public Action<string>? OnClientDisconnected { get; set; }

        public int ClientCount => _clients.Count;
        public bool IsTransferActive { get; set; }

        public void Start(int port = 8081)
        {
            _server = new WebSocketServer($"ws://0.0.0.0:{port}");
            _server.Start(socket =>
            {
                socket.OnOpen = () =>
                {
                    lock (_clients) _clients.Add(socket);
                    var clientIp = socket.ConnectionInfo.ClientIpAddress;
                    OnClientConnected?.Invoke(clientIp);
                };
                socket.OnClose = () =>
                {
                    lock (_clients) _clients.Remove(socket);
                    var clientIp = socket.ConnectionInfo.ClientIpAddress;
                    OnClientDisconnected?.Invoke(clientIp);
                };
                socket.OnMessage = message =>
                {
                    OnMessageReceived?.Invoke(message);
                };
                socket.OnBinary = data =>
                {
                    OnBinaryReceived?.Invoke(data);
                };
            });
 
            var heartbeatTimer = new System.Timers.Timer(10000);
            heartbeatTimer.Elapsed += (s, e) =>
            {
                if (!IsTransferActive)
                    Broadcast(Newtonsoft.Json.JsonConvert.SerializeObject(new { type = "ping" }));
            };
            heartbeatTimer.Start();
        }

        public void Broadcast(string message)
        {
            List<IWebSocketConnection> clientsCopy;
            lock (_clients)
            {
                clientsCopy = _clients.ToList();
            }

            foreach (var client in clientsCopy)
            {
                try
                {
                    client.Send(message);
                }
                catch
                {
                    lock (_clients) _clients.Remove(client);
                }
            }
        }

        public void Broadcast(byte[] data)
        {
            List<IWebSocketConnection> clientsCopy;
            lock (_clients)
            {
                clientsCopy = _clients.ToList();
            }

            foreach (var client in clientsCopy)
            {
                try
                {
                    client.Send(data);
                }
                catch
                {
                    lock (_clients) _clients.Remove(client);
                }
            }
        }

        public async Task BroadcastAsync(byte[] data)
        {
            List<IWebSocketConnection> clientsCopy;
            lock (_clients)
            {
                clientsCopy = _clients.ToList();
            }

            var sendTasks = new List<Task>();
            foreach (var client in clientsCopy)
            {
                var currentClient = client;
                sendTasks.Add(Task.Run(async () =>
                {
                    try
                    {
                        await currentClient.Send(data);
                    }
                    catch
                    {
                        lock (_clients) _clients.Remove(currentClient);
                    }
                }));
            }
            await Task.WhenAll(sendTasks);
        }

        public async Task BroadcastAsync(string message)
        {
            List<IWebSocketConnection> clientsCopy;
            lock (_clients)
            {
                clientsCopy = _clients.ToList();
            }

            var sendTasks = new List<Task>();
            foreach (var client in clientsCopy)
            {
                var currentClient = client;
                sendTasks.Add(Task.Run(async () =>
                {
                    try
                    {
                        await currentClient.Send(message);
                    }
                    catch
                    {
                        lock (_clients) _clients.Remove(currentClient);
                    }
                }));
            }
            await Task.WhenAll(sendTasks);
        }
    }
}
