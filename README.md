# 🚫 NoSlop — The Unfiltered Pulse of the Mesh 🕸️

<p align="center">
  <em>"Your feed. Your identity. Zero Slop. Zero Algorithms. 100% Freedom."</em>
</p>

<p align="center">
  <img alt="Build Status" src="https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge">
  <img alt="Status" src="https://img.shields.io/badge/Status-Iteration_3_Live-orange?style=for-the-badge">
  <img alt="Network" src="https://img.shields.io/badge/Network-HAI--Net_Mesh-blue?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0-purple?style=for-the-badge">
</p>

---

## 🌪️ What is NoSlop?

**NoSlop** is more than just an app; it's your **personal sovereign wedge** into the [HAI-Net](https://hai-net.com) ecosystem. It's a serverless, local-first powerhouse designed to strip away the algorithmic "slop" of the modern internet and put you back in the driver's seat.

Imagine a world where your feed isn't manipulated by billionaires, where your data never touches a central server, and where your identity is a mathematical fortress. **That world is NoSlop.** 🚀

---

## ✨ Killer Features

### 📺 Immersive Snapping Feed (TikTok-Style!)
Experience content like never before. Vertical snapping navigation ensures you focus on one piece of high-quality content at a time.
- **🖼️ Blurred Media Fill**: Uncropped images with beautiful blurred backgrounds. No more ugly black bars!
- **📖 Segmented Article Reader**: Long-form articles are automatically broken into bite-sized segments. Side-swipe to page through like a digital book.
- **🎬 Pro Video Playback**: Seamless HLS/m3u8 and MP4 streaming from clearnet sources (YouTube, etc.) without the tracking.

### 🕸️ Serverless Social Mesh
Connect directly to your peers on the **HAI-Net** gossip network. 
- **🔐 E2EE DMs**: Secure, peer-to-peer direct messaging that stays between you and your contact.
- **📣 Signed Mesh Posts**: Broadcast your voice globally without a central authority. Every post is cryptographically signed.
- **🤝 QR Pairing**: Frictionless companion node pairing. Scan, handshake, and join the trust web.

### 🎭 Sovereign Identity
- **☁️ BIP39 Word Cloud**: Your identity is secured by a 12-word recovery phrase. Tap to copy, write it down, and own your digital life forever.
- **🛡️ Hardware Isolation**: Private keys are locked inside Android's hardware-backed Keystore. Even NoSlop can't "see" them raw.
- **📦 Data Portability**: Export your entire local existence into an AES-encrypted backup. Move your data, your way.

### 🕵️ Tracker-Free Aggregator
Follow your favorite creators from YouTube, TikTok, and across the clearnet without ever signing in.
- **🔍 Smart Search**: Find channels and creators directly during onboarding.
- **📂 Interest-Based Filtering**: Choose from 14+ categories (Gaming, Science, Art, etc.) and let NoSlop suggest the best tracker-free feeds.

---

## 🛠️ Under the Hood

NoSlop is built on a brutalist, high-performance stack:
- **UI**: Jetpack Compose (Modern, Reactive, Beautiful)
- **Networking**: Tor SOCKS5 (Onion-routed privacy by default)
- **Crypto**: Ed25519 (Signatures), X25519 (Key Exchange), ChaCha20-Poly1305 (DMs)
- **Storage**: Room SQLite (Public data) + EncryptedSharedPreferences (Hardware keys)

---

## 🚀 Getting Started

1. **Download & Install**: Build from source using [docs/BUILD.md](docs/BUILD.md).
2. **Onboarding**: Launch the 6-step tutorial. Generate your Word Cloud, pick your interests, and scan a friend's QR.
3. **Enjoy the Silence**: No ads. No trackers. No slop. Just the content you chose.

---

## 📚 Documentation Deep Dive

- 🏗️ **[BUILD.md](docs/BUILD.md)**: Compile and run NoSlop on your device.
- 📉 **[PROJECT_STATUS.md](docs/PROJECT_STATUS.md)**: See the latest technical milestones.
- 📦 **[PACKET_SCHEMA.md](docs/PACKET_SCHEMA.md)**: Deep dive into the HAI-Net wire protocol.
- 🧪 **[TEST_PROTOCOL.md](docs/TEST_PROTOCOL.md)**: How we verify the mesh.
- 🐞 **[DEBUG.md](docs/DEBUG.md)**: How to extract and read system logs.

---

## 🌟 Join the Revolution

NoSlop is part of the [People Power Initiative](https://pplpwr.me). We are building a future where AI works for humanity, not corporations.

> *"The galley is on top, but the water flows below. The water is the master."* 🌊

---

<p align="center">
  <strong>NoSlop is open-source (AGPL-3.0). Fork it. Run it. Be free.</strong>
</p>
