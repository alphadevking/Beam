using Fleck;
using System;
using System.Collections.Generic;

namespace Beam.Windows.Services
{
    public class WebSocketHost
    {
        private WebSocketServer? _server;
        private List<IWebSocketConnection> _clients = new List<IWebSocketConnection>();

        public Action<string>? OnMessageReceived { get; set; }

        public void Start(int port = 8081)
        {
            _server = new WebSocketServer($"ws://0.0.0.0:{port}");
            _server.Start(socket =>
            {
                socket.OnOpen = () =>
                {
                    Console.WriteLine("Client connected!");
                    _clients.Add(socket);
                };
                socket.OnClose = () =>
                {
                    Console.WriteLine("Client disconnected!");
                    _clients.Remove(socket);
                };
                socket.OnMessage = message =>
                {
                    OnMessageReceived?.Invoke(message);
                };
            });
        }

        public void Broadcast(string message)
        {
            foreach (var client in _clients)
            {
                client.Send(message);
            }
        }
    }
}
