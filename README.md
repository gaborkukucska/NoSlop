# 🚫 NoSlop — The Unfiltered Pulse of the Mesh 🕸️

<p align="center">
  <em>"Your feed. Your identity. Zero algorithms. 100% freedom."</em>
</p>

<p align="center">
  <img alt="Build Status" src="https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge">
  <img alt="Status" src="https://img.shields.io/badge/Status-Iteration_3_Live-orange?style=for-the-badge">
  <img alt="Network" src="https://img.shields.io/badge/Network-HAI--Net_Mesh-blue?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0-purple?style=for-the-badge">
</p>

---

## What is NoSlop?

**NoSlop** is a privacy-first Android app for consuming content and communicating with people — without servers, trackers, or algorithmic manipulation.

It combines a **tracker-free content aggregator** (RSS/Atom from YouTube, TikTok, and the open web) with a **serverless encrypted social layer** powered by the [HAI-Net](https://hai-net.com) gossip mesh. All network traffic is routed through **Tor by default**. Your identity is a cryptographic keypair that lives only on your device — no account, no email, no phone number.

---

## Features

### Immersive Snapping Feed

A vertical feed purpose-built for signal-to-noise ratio.

- **Blurred media fill** — images display uncropped with a blurred background fill. No black bars, no letterboxing.
- **Segmented article reader** — long articles are automatically split into paged segments. Side-swipe to read like a book.
- **Video playback** — seamless HLS/m3u8 and MP4 streaming from clearnet sources (YouTube and others) without signing in or being tracked.
- **Interest-based curation** — choose from 14+ categories (Technology, Science, Privacy & Security, Gaming, Art, Music, and more) during onboarding. NoSlop pre-loads curated RSS/Atom feeds from sources like Hacker News, BBC World, NASA, EFF Deeplinks, and Krebs on Security — no account required.

### Serverless Social Mesh

Direct peer-to-peer communication over the HAI-Net gossip network. No central server is ever involved.

- **Cryptographically signed posts** — every mesh broadcast is signed with your Ed25519 key. The network rejects forgeries.
- **End-to-end encrypted DMs** — direct messages use ECDH (X25519) key agreement into AES-256-GCM. Only you and your contact can read them.
- **QR pairing** — scan a contact's QR code to exchange public keys and onion addresses. One scan, done.
- **Gossip propagation with firewall** — packets carry a hop counter (TTL = 6) and the gossip engine enforces per-sender rate limits (20 packets per 10-second window). Duplicate packets are deduplicated by ID with an LRU cache. Spam and flood attacks don't propagate.

### Sovereign Identity

Your identity is generated locally and never leaves your device unless you export it yourself.

- **Ed25519 + X25519 keypair** — one key for signing, one for encryption. Generated on-device using Android Keystore (API 33+) or Bouncy Castle (API 24–32 fallback).
- **Tor v3 onion address** — your identity includes a native `.onion` address derived from your Ed25519 key, making you directly reachable over Tor without a relay.
- **BIP39 Word Cloud** — your identity is backed up by a 12-word mnemonic phrase. Tap to copy, write it down, and you own your digital life permanently.
- **Tripcode** — a 6-character Base32 shortcode derived from SHA3-256 of your public key. A human-readable fingerprint that others can verify at a glance.
- **Hardware key isolation** — private keys are stored in Android's hardware-backed Keystore and never exposed in plaintext, even to NoSlop itself.
- **AES-encrypted backup** — export your entire identity and database into an encrypted archive. The encryption key is derived from your Word Cloud mnemonic. Move to a new device without losing anything.

### Tor-Routed Networking

All outbound traffic — feed fetches, mesh messages, media requests — is routed through an embedded Tor SOCKS5 proxy running locally on port 9050.

- Tor circuits are built before any data is sent. The app surfaces a clear status indicator so you always know if Tor is connected.
- Your real IP address is never exposed to feed servers, peers, or anyone on the network.
- Hidden service registration gives your node a stable `.onion` address for inbound peer connections.

---

## Tech Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose |
| Networking | Tor SOCKS5 (embedded, onion-routed) |
| Signing | Ed25519 (Android Keystore / Bouncy Castle) |
| Key exchange | X25519 |
| Encryption | AES-256-GCM (DMs), AES-CBC (backup) |
| Storage | Room SQLite (WAL mode) + EncryptedSharedPreferences |
| Background sync | WorkManager |

---

## Getting Started

1. **Build from source** — follow [docs/BUILD.md](docs/BUILD.md).
2. **Run the onboarding flow** — 6 steps: generate your Word Cloud, pick your interests, optionally scan a friend's QR to add your first contact.
3. **Browse** — your feed populates immediately from the curated sources matching your interests. No account, no wait.

---

## Documentation

- 🏗️ **[BUILD.md](docs/BUILD.md)** — how to compile and install NoSlop.
- 📉 **[PROJECT_STATUS.md](docs/PROJECT_STATUS.md)** — latest technical milestones and known issues.
- 📦 **[PACKET_SCHEMA.md](docs/PACKET_SCHEMA.md)** — HAI-Net wire protocol reference.
- 🐞 **[DEBUG.md](docs/DEBUG.md)** — how to extract and read system logs.

---

## About

NoSlop is part of the [People Power Initiative](https://pplpwr.me) — building tools where AI and open networks work for people, not corporations.

Licensed under **AGPL-3.0**. Fork it. Run it. Own it.

> *"The galley is on top, but the water flows below. The water is the master."* 🌊
