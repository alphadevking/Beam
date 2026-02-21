# Configuration & Technical Manual

This document provides technical details for configuring and troubleshooting Beam.

## 📶 Network Protocol
Beam uses two main communication channels:
1. **UDP (Port 8888)**: Used for discovery. The Windows host broadcasts "I_AM_THE_HOST" every 2 seconds.
2. **WebSocket (Port 8081)**: Used for real-time chat and file transfer.

## 🛡️ Windows Firewall Setup
If the apps cannot find each other, you likely need to open the ports. Run these commands in an **Administrator PowerShell**:

```powershell
# Allow WebSocket Chat
netsh advfirewall firewall add rule name="Beam Chat" dir=in action=allow protocol=TCP localport=8081

# Allow UDP Discovery
netsh advfirewall firewall add rule name="Beam Discovery" dir=in action=allow protocol=UDP localport=8888
```

## 📱 Android Permissions
The app requires the following permissions (requested at runtime or in manifest):
- `INTERNET`: For WebSocket communication.
- `ACCESS_WIFI_STATE` & `CHANGE_WIFI_MULTICAST_STATE`: For UDP discovery.
- `FOREGROUND_SERVICE`: To maintain connection while the screen is off.
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`: To save files to the `Downloads` folder.

## 🏗️ Build System (CI/CD)
The project is configured with GitHub Actions. 
- **Trigger**: Every push to `main` branch.
- **Output**: 
  - `Beam.Windows.exe`: A single-file, self-contained executable for 64-bit Windows.
  - `app-debug.apk`: An installable Android package.

## 🚀 Performance Notes
- **Chunk Size**: Files are sent in **512KB chunks**. This balance provides high reliability on unstable Wi-Fi without overwhelming the UI thread.
- **Storage**: Android uses `RandomAccessFile` to stream received data directly to disk, allowing transfers larger than the device's RAM.
