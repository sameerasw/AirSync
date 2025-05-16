
# AirSync (Android Client)  

*Sync Android notifications to macOS and share your clipboard between both devices.*

---


## Table of Contents

- [About](#about)
- [Features](#features)
- [Architecture Overview](#architecture-overview)
- [Screenshots](#screenshots)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Building from Source](#building-from-source)
- [Configuration](#configuration)
- [Usage](#usage)
- [Security & Privacy](#security--privacy)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [License](#license)
- [Acknowledgements](#acknowledgements)
- [Contact](#contact)

---

## About

**AirSync** is an open-source Android application that allows users to:

- Seamlessly sync notifications from their Android device to their macOS computer (see [AirSyncMac](https://github.com/sameerasw/AirSyncMac)).
- Share clipboard content (text) between Android and macOS.
- Streamline cross-device productivity between Android and macOS ecosystems with the integration with scrcpy.

The project aims to fill the gap for Android users who also use macOS, offering a smooth, and efficient way to keep both devices in sync.

---

## Features

- 📲 **Notification Mirroring:** Forward Android notifications instantly to your Mac.
- 📋 **Clipboard Sync:** Share copied text and images seamlessly between Android and macOS.
- 🛠️ **Customizable:** Supports configuring which notifications to sync by apps.
- 🖥️ **No Cloud Dependency:** Direct device-to-device communication—no third-party servers and all happens on the local network.
- 🚀 **Lightweight:** Minimal battery and network usage.

---

## Architecture Overview

- **Language:** Kotlin jetpack Compose (100%)
- **Minimum Android Version:** Android 12L
- **Communication:** TCP over local Wi-Fi/network
- **Core Components:**
  - Notification Listener Service
  - Foreground Service for persistent connection
  - Clipboard Manager integration
- **Companion App:** [AirSyncMac](https://github.com/sameerasw/AirSyncMac) (Swift) for macOS

---

## Screenshots

<!-- Add screenshots here -->
<p align="center">
  
  ![Screenshot_20250515-190811](https://github.com/user-attachments/assets/8002d209-2d47-44db-9c31-8ffd4ef08385)
  
</p>

---

## Getting Started

### Prerequisites

- Android device (API level 32+)
- macOS device with [AirSyncMac](https://github.com/sameerasw/AirSyncMac) installed and running
- Both devices connected to the same Wi-Fi/local network

### Installation

#### From APK

1. Download the latest `.apk` from [Releases](https://github.com/sameerasw/AirSync/releases).
2. Install on your Android device. You may need to allow installation from unknown sources.

#### Building from Source

1. **Clone the Repository**
    ```sh
    git clone https://github.com/sameerasw/AirSync.git
    cd AirSync
    ```
2. **Open in Android Studio**
3. **Build and Run** on your device.

##### Required Permissions

- **Notification Access:** For mirroring notifications - Restricted by default if you sideload
- **Clipboard Access:** For clipboard sync
- **Network Access:** For communication with Mac

---

## Configuration
1. **Grant Wireless Debugging:**  
   - [optional] Enable Wireless Debugging from the developer options for app screen mirroring

2. **First Launch:**  
   - Grant notification and clipboard permissions as prompted.
       - If the settings infor that these are restricted, You will have to visit app info, click the 3 dot menu and allow restricted permissions with biometrics if requested and then try again
   - You might need to close and re-open the app after both permissions granted.
   - Ensure macOS app is running on the same network.

2. **Pairing Devices:**
   - Start the service. Optionally select which apps you want to include/ skip.
   - The Android app will display you the local ip address and the port it's active.
   - Enter the details in the AirSyncMac fields and continue.

4. **Setting Preferences:**  
   - Exclude apps from notification sync. You can change settings while the service is running.

---

## Usage

- **Notification Sync:**  
  - Notifications received on your Android device will appear on your Mac.
  - App icons will be used as the preview.

- **Clipboard Sync:**  
  - Currently, You only can send text to the mac by sharing the text and selecting AirSync from the share sheet target.
  - Once shared, it will be automatically copied to the macOS clipboard.

---

## Security & Privacy

- **Encryption:** nope, idk how to do, might discover in the future.
- **No Cloud Storage:** Data never leaves your local network.
- **Permissions:** Only requests permissions strictly required for functionality.
- **Open Source:** Reviewable code for full transparency.

---

## Troubleshooting & FAQ

- **Notifications not appearing on Mac?**
  - Ensure both devices are on the same Wi-Fi.
  - Check notification access permission.
  - Restart both apps.

- **Clipboard not syncing?**
  - Make sure the Mac app is running and paired.
  - Clipboard will not share during screen mirroring since scrcpy can handle that anyways.

- **Connection issues?**
  - Disable VPNs or firewalls that may block local network communication.

> For more help, please open an [issue](https://github.com/sameerasw/AirSync/issues).

---

## Contributing

Contributions are welcome!  
Please read [CONTRIBUTING.md](CONTRIBUTING.md) (no such thing yet) for guidelines.

- Fork the repo
- Create your feature branch (`git checkout -b feature/AmazingFeature`)
- Commit your changes (`git commit -am 'Add amazing feature'`)
- Push to the branch (`git push origin feature/AmazingFeature`)
- Open a pull request

---

## Roadmap

- [ ] Remote notification actions
- [ ] Cross-platform support (Windows/Linux), low priority
- [ ] User interface enhancements
- [ ] Better ways to share the clipboard

---

## License

[MIT](LICENSE)

---

## Acknowledgements

- Android Open Source Project
- Kotlin
- Vibe coding, because I'ma newb
- [AirSyncMac](https://github.com/sameerasw/AirSyncMac)

---

## Contact

- **Author:** [sameerasw.com](https://www.sameerasw.com) putanythinghere@sameerasw.com
- **Issues & Feedback:** [GitHub Issues](https://github.com/sameerasw/AirSync/issues)
- **Mac Client:** [AirSyncMac](https://github.com/sameerasw/AirSyncMac)
