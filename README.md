# Beam 🚀

Beam is a cross-platform, local-network chat and file-sharing application. It allows you to seamlessly send text messages and files (of any size) between your Windows PC and Android phone over Wi-Fi, with zero configuration and automatic discovery.

## ✨ Features

- **Auto-Discovery**: Open the apps on both devices, and they find each other automatically via UDP broadcast.
- **Real-time Chat**: Low-latency text messaging using WebSockets.
- **Large File Beam**: Drag and drop files on Windows to send them to Android. Optimized with chunked transfers and disk-backed storage to handle GBs of data.
- **Modern UI**: Sleek dark-mode interface with color-coded chat bubbles.
- **Self-Healing**: Automatic reconnection logic if the network drops.
- **Privacy First**: All data stays on your local network. No cloud, no tracking.

## 🛠️ Quick Start

### 1. Download the Apps
You don't need IDEs installed! Get the latest built binaries from the [GitHub Releases](../../releases).

Every time code is pushed to the `main` branch, an automated GitHub Action builds the latest `.exe` and `.apk` files and creates a Release!

#### Versioning & Releases (For Developers)
Beam uses **Conventional Commits** for fully automated semantic versioning and releasing. When pushing changes to `main`, prefix your commits to control the next version jump:
- `fix: ...` drops a *Patch* release (e.g., `v1.0.1` ➔ `v1.0.2`)
- `feat: ...` drops a *Minor* release (e.g., `v1.0.1` ➔ `v1.1.0`)
- `feat!: ...` or `BREAKING CHANGE:` drops a *Major* release (e.g., `v1.1.0` ➔ `v2.0.0`)

### 2. Network Setup
Ensure your devices are on the same Wi-Fi. On Windows, you may need to allow the app through the firewall (see [Configuration](docs/CONFIGURATION.md)).

### 3. Beam!
1. Launch the Windows app.
2. Launch the Android app.
3. Once connected, type a message or drag a file into the Windows window.

## 📚 Documentation
- [Configuration Guide](docs/CONFIGURATION.md): Port details, Firewall setup, and Permissions.
- [Walkthrough](.gemini/antigravity/brain/18c0565c-9505-4723-90c5-e60199c0359e/walkthrough.md): Project development history and feature logs.

## ⚖️ License
MIT
