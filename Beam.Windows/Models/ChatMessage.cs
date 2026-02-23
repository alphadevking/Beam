using System;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Beam.Windows.Models
{
    public class ChatMessage : INotifyPropertyChanged
    {
        private string _content = string.Empty;
        public string Content 
        { 
            get => _content; 
            set { _content = value; OnPropertyChanged(); } 
        }

        public string Sender { get; set; } = string.Empty;
        public DateTime Timestamp { get; set; } = DateTime.Now;
        public bool IsMe { get; set; }
        
        private string _type = "text";
        public string Type 
        { 
            get => _type; 
            set { _type = value; OnPropertyChanged(); }
        }

        private string? _fileName;
        public string? FileName 
        { 
            get => _fileName; 
            set { _fileName = value; OnPropertyChanged(); }
        }

        private string? _localFilePath;
        public string? LocalFilePath 
        { 
            get => _localFilePath; 
            set { _localFilePath = value; OnPropertyChanged(); }
        }

        private double _progress;
        public double Progress
        {
            get => _progress;
            set { _progress = value; OnPropertyChanged(); OnPropertyChanged(nameof(ProgressText)); }
        }

        private long _fileSize;
        public long FileSize
        {
            get => _fileSize;
            set { _fileSize = value; OnPropertyChanged(); OnPropertyChanged(nameof(FileSizeFormatted)); }
        }

        // Delivery tracking
        public string MessageId { get; set; } = Guid.NewGuid().ToString("N");

        private string _deliveryStatus = "sending";  // sending, sent, delivered, failed
        public string DeliveryStatus
        {
            get => _deliveryStatus;
            set { _deliveryStatus = value; OnPropertyChanged(); OnPropertyChanged(nameof(DeliveryIcon)); }
        }

        public string DeliveryIcon => DeliveryStatus switch
        {
            "sending" => "⏳",
            "sent" => "✓",
            "delivered" => "✓✓",
            "failed" => "✗",
            _ => ""
        };

        public string FileSizeFormatted => FileSize switch
        {
            < 1024 => $"{FileSize} B",
            < 1024 * 1024 => $"{FileSize / 1024.0:F1} KB",
            _ => $"{FileSize / (1024.0 * 1024.0):F1} MB"
        };

        public string ProgressText => Progress >= 100 ? "Complete" : $"{Progress:F0}%";

        // Device identifier
        private string _senderName = string.Empty;
        public string SenderName
        {
            get => _senderName;
            set { _senderName = value; OnPropertyChanged(); }
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }
}
