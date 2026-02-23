using System;
using System.Collections.ObjectModel;
using System.Net;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Input;
using Beam.Windows.Models;
using Beam.Windows.Services;
using Newtonsoft.Json;
using System.Linq;
using System.Windows.Controls;
using System.IO;
using System.Reflection;

namespace Beam.Windows
{
    public partial class MainWindow : Window
    {
        public ObservableCollection<ChatMessage> Messages { get; set; } = new ObservableCollection<ChatMessage>();

        private TcpHost? _tcpHost;
        private FileSender? _fileSender;
        private FileReceiver? _fileReceiver;
        private DiscoveryService? _discoveryService;
        private System.Threading.SemaphoreSlim _textQueue = new System.Threading.SemaphoreSlim(1, 1);


        public MainWindow()
        {
            InitializeComponent();
            DataContext = this;
            
            var rawVersion = Assembly.GetExecutingAssembly()
                .GetCustomAttribute<AssemblyInformationalVersionAttribute>()
                ?.InformationalVersion ?? "v0.0.0-unknown";
            
            // Clean up messy git-describe strings: v0.1.5-2-ge27... -> v0.1.5-2
            // Or if it contains a hash after a plus: v0.1.5+e27c... -> v0.1.5
            string displayVersion = rawVersion.Split('+')[0]; // Remove metadata
            if (displayVersion.Contains("-g")) 
                displayVersion = displayVersion.Substring(0, displayVersion.LastIndexOf("-g"));
            
            VersionText.Text = displayVersion;

            StartServices();
        }

        private void StartServices()
        {
            try
            {
                _tcpHost = new TcpHost();
                _fileSender = new FileSender(_tcpHost);
                _fileReceiver = new FileReceiver();
                _discoveryService = new DiscoveryService();

                _tcpHost.OnMessageReceived = (message) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        try
                        {
                            dynamic? msgData = JsonConvert.DeserializeObject(message);
                            if (msgData?.type == "text")
                            {
                                string senderName = msgData?.senderId != null ? (string)msgData.senderId : "Phone";
                                string messageId = msgData?.messageId != null ? (string)msgData.messageId : "";
                                string content = msgData?.content ?? "";
                                var msg = new ChatMessage { Content = content, IsMe = false, SenderName = senderName, DeliveryStatus = "" };
                                Messages.Add(msg);
                                ChatList.ScrollIntoView(msg);

                                // Send receipt
                                if (!string.IsNullOrEmpty(messageId))
                                {
                                    var receipt = new { type = "delivery_receipt", messageId = messageId, status = "delivered" };
                                    _tcpHost.Broadcast(JsonConvert.SerializeObject(receipt));
                                }
                            }
                            else if (msgData?.type == "stream_start" || msgData?.type == "file_metadata")
                            {
                                string name = (string)msgData.name;
                                long size = (long)msgData.size;

                                var existingMsg = Messages.FirstOrDefault(m => m.Type == "file" && m.FileName == name && !m.IsMe);
                                if (existingMsg == null)
                                {
                                    var newMsg = new ChatMessage
                                    {
                                        Type = "file",
                                        FileName = name,
                                        FileSize = size,
                                        Progress = 0,
                                        SenderName = msgData?.senderId != null ? (string)msgData.senderId : "Phone",
                                        DeliveryStatus = "receiving"
                                    };
                                    Messages.Add(newMsg);
                                    ChatList.ScrollIntoView(newMsg);
                                }
                                else
                                {
                                    existingMsg.DeliveryStatus = "receiving";
                                }
                            }
                            else if (msgData?.type == "file_complete")
                            {
                                string name = (string)msgData.name;
                                var msg = Messages.FirstOrDefault(m => m.Type == "file" && m.FileName == name && !m.IsMe);
                                if (msg != null)
                                {
                                    msg.DeliveryStatus = "delivered";
                                    msg.Progress = 100;
                                }
                            }
                            else if (msgData?.type == "delivery_receipt")
                            {
                                string messageId = (string)msgData.messageId;
                                string status = (string)msgData.status;
                                var msg = Messages.FirstOrDefault(m => m.MessageId == messageId);
                                if (msg != null) msg.DeliveryStatus = status;
                            }
                        }
                        catch (Exception ex)
                        {
                            Console.WriteLine($"JSON Parse Error: {ex.Message}");
                        }
                    });
                };

                _tcpHost.OnBinaryReceived = (data) =>
                {
                    // Only handle legacy chunks if 'data' is not null
                    if (data != null)
                    {
                        string? savedPath = _fileReceiver.HandleBinaryChunk(data, (fileName, progress) =>
                        {
                            Dispatcher.Invoke(() =>
                            {
                                var msg = Messages.FirstOrDefault(m => m.Type == "file" && m.FileName == fileName && !m.IsMe);
                                if (msg != null)
                                {
                                    msg.Progress = progress;
                                }
                            });
                        });

                        if (savedPath != null)
                        {
                            Dispatcher.Invoke(() =>
                            {
                                string fileName = System.IO.Path.GetFileName(savedPath);
                                var msg = Messages.FirstOrDefault(m => m.Type == "file" && m.FileName == fileName && !m.IsMe);
                                if (msg != null) msg.LocalFilePath = savedPath;
                            });
                        }
                    }
                };

                _tcpHost.OnFileProgress = (fileName, progress) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        var msg = Messages.FirstOrDefault(m => m.Type == "file" && m.FileName == fileName && !m.IsMe);
                        if (msg != null)
                        {
                            msg.Progress = progress;
                        }
                    });
                };

                _tcpHost.OnGetFileStream = (fileName) => 
                {
                    return _fileReceiver.GetStream(fileName);
                };

                _tcpHost.OnDeviceIdentified = (ip, name) => 
                {
                    Dispatcher.Invoke(() => 
                    {
                        ConnectionText.Text = $"📱 {name} ({ip})";
                    });
                };

                _tcpHost.OnClientConnected = (clientIp) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        ConnectionText.Text = $"📱 Connected ({clientIp})";
                        StatusBarConnectionText.Text = $"{_tcpHost.ClientCount} device(s) connected";
                    });
                };

                _tcpHost.OnClientDisconnected = (clientIp) =>
                {
                    Dispatcher.Invoke(() =>
                    {
                        if (_tcpHost.ClientCount == 0)
                            ConnectionText.Text = "Waiting for device...";
                        else
                            ConnectionText.Text = $"📱 {_tcpHost.ClientCount} device(s) connected";

                        StatusBarConnectionText.Text = $"{_tcpHost.ClientCount} device(s) connected";
                    });
                };

                _tcpHost.Start();
                ConnectionText.Text = "Waiting for device...";
                StatusBarConnectionText.Text = "0 device(s) connected";
                IPText.Text = $"Local IP: {GetLocalIPAddress()}";

                _discoveryService.Start();
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Startup error: {ex.Message}");
            }
        }

        private void SendMessage()
        {
            string text = InputBox.Text;
            if (string.IsNullOrWhiteSpace(text) || _tcpHost == null) return;

            var chatMsg = new ChatMessage { Content = text, IsMe = true, DeliveryStatus = "sending", SenderName = "PC" };
            Messages.Add(chatMsg);
            ChatList.ScrollIntoView(chatMsg);

            var msg = new { type = "text", content = text, messageId = chatMsg.MessageId, senderId = "PC" };
            
            Task.Run(async () =>
            {
                await _textQueue.WaitAsync();
                try
                {
                    while (_tcpHost.ClientCount == 0) await Task.Delay(500);
                    _tcpHost.Broadcast(JsonConvert.SerializeObject(msg));
                    
                    Dispatcher.Invoke(() => 
                    {
                        chatMsg.DeliveryStatus = "sent";
                        ChatList.ScrollIntoView(chatMsg);
                    });
                }
                finally 
                {
                    _textQueue.Release();
                }
            });

            InputBox.Clear();
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
                    var msg = new ChatMessage 
                    { 
                        Content = "", 
                        IsMe = true, 
                        Type = "file", 
                        LocalFilePath = file,
                        FileName = System.IO.Path.GetFileName(file),
                        FileSize = new System.IO.FileInfo(file).Length,
                        Progress = 0
                    };
                    Messages.Add(msg);
                    
                    await _fileSender!.SendFile(file, progress => 
                    {
                        Dispatcher.Invoke(() => 
                        {
                            msg.Progress = progress;
                        });
                    });
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
                    var msg = new ChatMessage 
                    { 
                        Content = "", 
                        IsMe = true, 
                        Type = "file", 
                        LocalFilePath = file,
                        FileName = System.IO.Path.GetFileName(file),
                        FileSize = new System.IO.FileInfo(file).Length,
                        Progress = 0
                    };
                    Messages.Add(msg);
                    
                    await _fileSender!.SendFile(file, progress => 
                    {
                        Dispatcher.Invoke(() => 
                        {
                            msg.Progress = progress;
                        });
                    });
                }
            }
        }

        private void Border_DragOver(object sender, DragEventArgs e)
        {
            e.Effects = DragDropEffects.Copy;
            e.Handled = true;
        }

        private void Copy_Click(object sender, RoutedEventArgs e)
        {
            ChatMessage? msg = null;
            if (sender is MenuItem menuItem && menuItem.DataContext is ChatMessage mm) msg = mm;
            else if (sender is Button button && button.DataContext is ChatMessage bm) msg = bm;

            if (msg != null && !string.IsNullOrEmpty(msg.Content))
            {
                Clipboard.SetText(msg.Content);
            }
        }
 
        private void OpenLocation_Click(object sender, RoutedEventArgs e)
        {
            ChatMessage? msg = null;
            if (sender is MenuItem menuItem && menuItem.DataContext is ChatMessage mm) msg = mm;
            else if (sender is Button button && button.DataContext is ChatMessage bm) msg = bm;

            if (msg != null && !string.IsNullOrEmpty(msg.LocalFilePath))
            {
                string argument = $"/select,\"{msg.LocalFilePath}\"";
                System.Diagnostics.Process.Start("explorer.exe", argument);
            }
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
