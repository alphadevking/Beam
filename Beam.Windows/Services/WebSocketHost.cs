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
        public Action<string>? OnClientConnected { get; set; }
        public Action<string>? OnClientDisconnected { get; set; }

        public int ClientCount => _clients.Count;

        public void Start(int port = 8081)
        {
            _server = new WebSocketServer($"ws://0.0.0.0:{port}");
            _server.Start(socket =>
            {
                socket.OnOpen = () =>
                {
                    _clients.Add(socket);
                    var clientIp = socket.ConnectionInfo.ClientIpAddress;
                    OnClientConnected?.Invoke(clientIp);
                };
                socket.OnClose = () =>
                {
                    var clientIp = socket.ConnectionInfo.ClientIpAddress;
                    _clients.Remove(socket);
                    OnClientDisconnected?.Invoke(clientIp);
                };
                socket.OnMessage = message =>
                {
                    OnMessageReceived?.Invoke(message);
                };
            });
        }

        public void Broadcast(string message)
        {
            foreach (var client in _clients.ToList())
            {
                try
                {
                    client.Send(message);
                }
                catch
                {
                    _clients.Remove(client);
                }
            }
        }
    }
}
