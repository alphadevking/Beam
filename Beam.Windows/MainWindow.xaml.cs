using System;
using System.Collections.ObjectModel;
using System.Net;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Input;
using Beam.Windows.Models;
using Beam.Windows.Services;
using Newtonsoft.Json;

namespace Beam.Windows
{
    public partial class MainWindow : Window
    {
        public ObservableCollection<ChatMessage> Messages { get; set; } = new ObservableCollection<ChatMessage>();

        private DiscoveryService _discoveryService = new DiscoveryService();
        private WebSocketHost _webSocketHost = new WebSocketHost();
        private FileSender? _fileSender;

        public MainWindow()
        {
            InitializeComponent();
            _fileSender = new FileSender(_webSocketHost);
            ChatList.ItemsSource = Messages;
            StartServices();
        }

        private void StartServices()
        {
            try 
            {
                _discoveryService.Start();
                _webSocketHost.OnMessageReceived = (json) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        try
                        {
                            var msgData = JsonConvert.DeserializeObject<dynamic>(json);
                            if (msgData?.type == "text")
                            {
                                var msg = new ChatMessage { Content = (string)msgData.content, IsMe = false };
                                Messages.Add(msg);
                                ChatList.ScrollIntoView(msg);
                            }
                        }
                        catch (Exception ex)
                        {
                            Console.WriteLine($"Error parsing message: {ex.Message}");
                        }
                    });
                };
                _webSocketHost.Start();
                StatusText.Text = "Beam Host Active";
                IpText.Text = $"Local IP: {GetLocalIPAddress()}";
            }
            catch (Exception ex)
            {
                StatusText.Text = $"Error: {ex.Message}";
            }
        }

        private void SendMessage()
        {
            if (string.IsNullOrWhiteSpace(InputBox.Text)) return;

            var msg = new ChatMessage { Content = InputBox.Text, IsMe = true };
            Messages.Add(msg);
            
            var json = JsonConvert.SerializeObject(new { type = "text", content = msg.Content });
            _webSocketHost.Broadcast(json);

            InputBox.Clear();
            ChatList.ScrollIntoView(msg);
        }

        private void Send_Click(object sender, RoutedEventArgs e) => SendMessage();

        private void InputBox_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) SendMessage();
        }

        private async void Attach_Click(object sender, RoutedEventArgs e)
        {
            var dialog = new Microsoft.Win32.OpenFileDialog
            {
                Multiselect = true,
                Title = "Select files to send"
            };

            if (dialog.ShowDialog() == true)
            {
                foreach (string file in dialog.FileNames)
                {
                    var msg = new ChatMessage { Content = $"Sending: {System.IO.Path.GetFileName(file)}", IsMe = true, Type = "file" };
                    Messages.Add(msg);
                    
                    await _fileSender!.SendFile(file, progress => 
                    {
                        Dispatcher.Invoke(() => 
                        {
                            msg.Content = $"Sending: {System.IO.Path.GetFileName(file)} ({progress:F0}%)";
                        });
                    });
                    
                    msg.Content = $"Sent: {System.IO.Path.GetFileName(file)}";
                }
            }
        }

        private async void Border_Drop(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                string[] files = (string[])e.Data.GetData(DataFormats.FileDrop);
                foreach (string file in files)
                {
                    var msg = new ChatMessage { Content = $"Sending: {System.IO.Path.GetFileName(file)}", IsMe = true, Type = "file" };
                    Messages.Add(msg);
                    
                    await _fileSender!.SendFile(file, progress => 
                    {
                        Dispatcher.Invoke(() => 
                        {
                            msg.Content = $"Sending: {System.IO.Path.GetFileName(file)} ({progress:F0}%)";
                            // We need InotifyPropertyChanged if we want this to update live
                            // For now, let's just update the list if needed or keep it simple
                        });
                    });
                    
                    msg.Content = $"Sent: {System.IO.Path.GetFileName(file)}";
                }
            }
        }

        private void Border_DragOver(object sender, DragEventArgs e)
        {
            e.Effects = DragDropEffects.Copy;
            e.Handled = true;
        }

        private string GetLocalIPAddress()
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    return ip.ToString();
                }
            }
            return "127.0.0.1";
        }
    }
}
