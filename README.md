# 🚫 NoSlop — The Unfiltered Pulse of the Mesh 🕸️

<p align="center">
  <em>"Your feed. Your identity. Zero algorithms. 100% freedom."</em>
</p>

<p align="center">
  <img alt="Build Status" src="https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge&logo=android">
  <img alt="Status" src="https://img.shields.io/badge/Status-v0.5.0--alpha-orange?style=for-the-badge">
  <img alt="Privacy" src="https://img.shields.io/badge/Privacy-100%25_Tor_Only-blueviolet?style=for-the-badge&logo=torproject">
  <img alt="Network" src="https://img.shields.io/badge/Network-HAI--Net_/_HUBs-blue?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0-purple?style=for-the-badge">
</p>

---

## 💡 What is NoSlop?

**NoSlop** is a sovereign, privacy-first Android application that combines a **tracker-free content aggregator** with a **serverless, end-to-end encrypted social mesh** running entirely over [Tor](https://www.torproject.org/).

There are **no accounts**, **no emails**, **no phone numbers**, and **zero algorithmic slop**. Your identity is an Ed25519/X25519 cryptographic keypair generated on your device. Consuming content from the open web and connecting with friends or creators happens directly through peer-to-peer Tor hidden services (`.onion`).

> 📖 **Deep Technical Details:** All cryptographic derivations, transport architectures, and protocol specifications live in [docs/TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md) and [docs/WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md).

---

## ⚡ Why Stop the Slop?

* 🎧 **Background Playback Out-of-the-Box** — Stream videos and audio seamlessly while browsing other tabs or with your screen locked.
* 🚫 **100% Ad & Tracker-Free** — Zero banners, zero tracking scripts, zero sponsored interruptions. Always.
* 🎯 **Total Feed Sovereignty** — Strict chronological curation. You decide your content mix, your sources, and your priorities.
* 🕸️ **Decentralized Social Mesh** — Direct peer-to-peer communication across Tor hidden services with multi-hop gossip relay.
* 🔒 **Mathematical Privacy** — E2EE Direct Messages (X25519 + ChaCha20-Poly1305) and multi-member mesh group chats.
* 🧅 **Strict Tor-Routed Anonymity** — All network traffic routes through an embedded Tor SOCKS5 daemon with dedicated per-stream circuit isolation.

---

## ✨ Core Features

### 📱 Immersive Snapping Feed

A vertically snapping feed purpose-built for clean signal-to-noise ratio:

* 🎬 **Universal Media Playback** — Native audio & video streaming (HLS, MP4, WebM, MP3, Archive.org, and direct streams) with dynamic ahead-of-time Tor preloading.
* 🖥️ **Immersive Landscape Mode** — Rotate your device horizontally to automatically hide navigation and overlays for edge-to-edge viewing.
* 📖 **Segmented Article Reader** — Multi-page horizontal book-style reader with asynchronous OpenGraph lead image resolution and full Markdown support.
* 🎛️ **3-Tier Priority Curation** — Strictly orders content chronologically in three tiers: `Favorite Creators > Selected Topics > Fallback Discoveries`.
* 🏷️ **3-Tier Nuanced Reactions** — 20 expressive reactions categorized into **Positive** (❤️ 👍 😂 🔥 😮 🎉 💡 👏 💎), **Expressive** (😢 😡 😱 🤔 🤯 🧘), and **Negative** (👎 💩 🤮 🤡 🚫).
* 🚫 **1-Tap Channel Banning** — React with 🚫 to immediately blacklist content creators and purge their slides from your feed.
* 📅 **Content Farm Cut-Off Filter** — Exclude automated channels created after a set date (e.g. drop post-2022 AI content farms).
* 🔖 **Saved & History Feeds** — Bookmark items with 1 tap; browse your full chronological history with instant full-text search.

---

### 🕸️ Serverless Social Mesh (HAI-Net)

Direct peer-to-peer communication over the HAI-Net gossip network — no central server ever exists:

* 📬 **End-to-End Encrypted DMs** — Mathematically secure messaging via X25519 key agreement and ChaCha20-Poly1305 AEAD.
* 👥 **Decentralized Group Chats** — End-to-end encrypted multi-member group conversations with audience controls (`🌐 All Members` vs `👥 Friends Only`), non-admin invites, and admin moderation.
* 🎬 **Creator Studio & Severable ID** — Dedicated Creator Mode equipped with an ephemeral/burnable secondary identity (`.onion`). Creators can share their **Creator ID 🪪**, receive followers, and publish broadcasts without leaking their personal identity.
* 🏠 **Home HUBs & Admin AI** — Link or auto-deploy an always-on home server over SSH as your sovereign master database and private LLM assistant.
* 🌉 **Clearnet-to-Mesh Bridge** — Liking, commenting, or sharing clearnet content instantly transforms it into a signed, deterministic SHA3-256 mesh anchor post.
* 🛡️ **Gossip Firewall & Mesh Filters** — 6-hop flood routing with LRU deduplication, packet verification, and granular toggles for incoming and outgoing media types.

---

### 🔑 Sovereign Cryptographic Identity

* 🔐 **Ed25519 + X25519 Keys** — Generated locally using Lazysodium (libsodium) with Bouncy Castle fallback.
* 🧅 **Native Tor v3 Onion Address** — Your node address is derived directly from your public key for direct peer reachability.
* 🪪 **Dual-Identity Separation** — Primary identity for trusted personal contacts; severable burnable identity for public creator broadcasts.
* ☁️ **Word Cloud Backup** — 12-word recovery mnemonic with AES-256-GCM authenticated encrypted zip backup and restore.
* 🪪 **Human-Readable Tripcode** — 6-character Base32 visual verification fingerprint (`@handle.tripcode`).

---

### 🧅 Tor-Routed Networking

* 🛡️ **Default Tor Routing** — Outbound feed fetches, media streams, API requests, and mesh packets route through an embedded local Tor daemon.
* 🔄 **SOCKS5 Stream Isolation** — Per-stream Tor isolation (`IsolateSOCKSAuth`) assigns unique circuits and exit nodes to distinct media streams, guaranteeing exit affinity and eliminating Google IP-lock stalls.
* 🔁 **Graceful Exit Hopping** — Nonce-bumping hops Tor exits upon provider blocks without process-wide circuit disruption.
* 📲 **Peerless OTA Updates** — Automated background update detection with SHA-256 cryptographic checksum verification before installation.

---

## 🛠️ Tech Stack

| Layer | Technologies |
|---|---|
| **UI** | Jetpack Compose (Material Design 3), Compose Animation, Markdown Spans |
| **Media** | Media3 / ExoPlayer, Coil, Android System WebView (fallback) |
| **Networking** | Embedded Tor daemon (`tor-android`), OkHttp, SOCKS5 Stream Isolation |
| **Cryptography** | Ed25519 (Lazysodium / Bouncy Castle), X25519, ChaCha20-Poly1305, SHA3-256, AES-256-GCM |
| **Persistence** | Room (SQLite), EncryptedSharedPreferences (Hardware Keystore backed) |
| **Hardware** | CameraX (QR scanning & media capture), ZXing |
| **Background** | Android WorkManager & Foreground Services |

---

## 🚀 Getting Started

1. **Build from source** — Follow the step-by-step compilation guide in [docs/BUILD.md](docs/BUILD.md).
2. **Complete Onboarding** — Set your language, record your 12-word Word Cloud mnemonic, select your favorite topics and creators, tune your content mix, and begin browsing!
3. **Connect with Peers** — Share your Contact Card QR code or Creator ID to start building your mesh circle.

---

## 📚 Documentation Index

* 🏗️ **[docs/BUILD.md](docs/BUILD.md)** — Compilation instructions and signing configuration.
* 📡 **[docs/WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md)** — Complete 24-packet HAI-Net wire protocol catalog and payload schemas.
* 🔬 **[docs/TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md)** — Deep technical specification (crypto derivations, Tor internals, SOCKS5 isolation, media pipelines).
* 📈 **[docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)** — Detailed technical milestone changelog and completed audit resolutions.
* 🌍 **[docs/TRANSLATION_GUIDE.md](docs/TRANSLATION_GUIDE.md)** — Guide for community localization and language JSON files.
* 🛡️ **[docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)** — User privacy expectations, data sovereignty, and security posture.
* 🏠 **[docs/HUB_INTEGRATION_PLAN.md](docs/HUB_INTEGRATION_PLAN.md)** — HAI-Net Home Hub integration blueprint and roadmap.
* 🛠️ **[docs/SUPPORT.md](docs/SUPPORT.md)** — Backup/restore guides, troubleshooting, and operations.
* 🐞 **[docs/DEBUG.md](docs/DEBUG.md)** — Extracting structured diagnostic logs.

---

## ⚖️ User Responsibilities

* 📦 **Decentralized & Serverless** — There is no central server, corporate account, or cloud backup. Always back up your 12-word Word Cloud.
* 🤝 **Bring Your Own Network** — NoSlop does not maintain public user directories. Direct peer connections are formed deliberately by exchanging QR codes or onion addresses.
* 📲 **Direct Installation** — Because NoSlop bypasses centralized app stores, you must allow installation of unknown apps on Android.

---

## 💖 Support the Vision

NoSlop is 100% free, open-source, tracker-free, and advertisement-free. If you find value in serverless, sovereign communication, consider supporting independent development:

🪙 ☕ 🍱 **[Toss me a coin or buy me a meal](https://donate.stripe.com/dRmfZae1F0jNfPNfFC9fW00)**

---

## 🌐 About

NoSlop is part of the **[HAI-Net Initiative](https://hai-net.com)** — building open networks where tools serve people, not corporate algorithms.

Licensed under **[AGPL-3.0](LICENSE.md)**. Fork it. Run it. Own it.

<p align="center">
  ✨ <em><a href="https://gaborkukucska.com">Dreamed up by Gabby</a></em>
</p>
