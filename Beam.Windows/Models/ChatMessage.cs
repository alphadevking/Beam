using System;

namespace Beam.Windows.Models
{
    public class ChatMessage
    {
        public string Content { get; set; } = string.Empty;
        public string Sender { get; set; } = string.Empty;
        public DateTime Timestamp { get; set; } = DateTime.Now;
        public bool IsMe { get; set; }
        public string Type { get; set; } = "text"; // text, file
        public string? FileName { get; set; }
    }
}
