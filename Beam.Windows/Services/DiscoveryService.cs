using System;
using System.Net;
using System.Net.NetworkInformation;
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
        private CancellationTokenSource? _cts;

        public void Start()
        {
            _cts = new CancellationTokenSource();
            Task.Run(() => BroadcastLoop(_cts.Token));
        }

        private async Task BroadcastLoop(CancellationToken token)
        {
            byte[] data = Encoding.UTF8.GetBytes(Message);
            var endpoint = new IPEndPoint(IPAddress.Broadcast, Port);

            while (!token.IsCancellationRequested)
            {
                try
                {
                    foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                    {
                        if (ni.OperationalStatus != OperationalStatus.Up) continue;
                        if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                        var props = ni.GetIPProperties();
                        foreach (var addr in props.UnicastAddresses)
                        {
                            if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                            {
                                try
                                {
                                    using var client = new UdpClient(new IPEndPoint(addr.Address, 0));
                                    client.EnableBroadcast = true;
                                    await client.SendAsync(data, data.Length, endpoint);
                                }
                                catch
                                {
                                    // Ignore bind/send errors for specific interfaces
                                }
                            }
                        }
                    }
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
        }
    }
}
