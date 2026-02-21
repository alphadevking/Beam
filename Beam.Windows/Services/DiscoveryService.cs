using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Beam.Windows.Services
{
    public class DiscoveryService
    {
        private const int Port = 8888;
        private const string Message = "I_AM_THE_HOST";
        private UdpClient? _udpClient;
        private CancellationTokenSource? _cts;

        public void Start()
        {
            _cts = new CancellationTokenSource();
            _udpClient = new UdpClient();
            _udpClient.EnableBroadcast = true;

            Task.Run(() => BroadcastLoop(_cts.Token));
        }

        private async Task BroadcastLoop(CancellationToken token)
        {
            var endpoint = new IPEndPoint(IPAddress.Broadcast, Port);
            byte[] data = Encoding.UTF8.GetBytes(Message);

            while (!token.IsCancellationRequested)
            {
                try
                {
                    await _udpClient!.SendAsync(data, data.Length, endpoint);
                    await Task.Delay(2000, token);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Broadcast error: {ex.Message}");
                    await Task.Delay(5000, token);
                }
            }
        }

        public void Stop()
        {
            _cts?.Cancel();
            _udpClient?.Close();
        }
    }
}
