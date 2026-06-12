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

It combines a **tracker-free content aggregator** (RSS/Atom from YouTube, TikTok, and the open web) with a **serverless encrypted social layer** powered by Tor and our daisy-chain-gossip framework. All mesh network traffic is routed through **Tor by default**. Your identity is a cryptographic keypair that lives only on your device — no account, no email, no phone number.

---

## Features

### Immersive Snapping Feed

A vertical feed purpose-built for signal-to-noise ratio.

- **Blurred media fill** — images display uncropped with a blurred background fill. No black bars, no letterboxing.
- **Segmented article reader** — long articles are automatically split into paged segments. Side-swipe to read like a book.
- **Media playback** — seamless audio and video streaming (HLS/m3u8, MP4, MP3, etc.) from clearnet sources with dynamic ahead-of-time preloading for instant playback without signing in or being tracked.
- **Interest-based curation** — choose from 14+ categories (Technology, Science, Privacy & Security, Gaming, Art, Music, and more) during onboarding. NoSlop pre-loads curated RSS/Atom feeds from sources like Hacker News, BBC World, NASA, EFF Deeplinks, and Krebs on Security — no account required.

### Serverless Social Mesh

Direct peer-to-peer communication over the HAI-Net gossip network. No central server is ever involved.

- **Cryptographically signed posts** — every mesh broadcast is signed with your Ed25519 key. The network rejects forgeries.
- **End-to-end encrypted DMs** — direct messages use X25519 key agreement, derived via SHA3-256 into a ChaCha20-Poly1305 key. Only you and your contact can read them.
- **QR pairing** — scan a contact's QR code to exchange public keys and onion addresses. One scan, done.
- **Gossip propagation with firewall** — packets carry a hop counter (TTL = 6) and the gossip engine enforces per-sender rate limits (20 packets per 10-second window). Duplicate packets are deduplicated by ID with an LRU cache. Spam and flood attacks don't propagate.

### Clearnet-to-Mesh Broadcasts

NoSlop is the bridge between the open web and your private mesh. Consuming content from your aggregated clearnet feed isn't a passive act — it's a gateway into community.

When you **like**, **share**, or **comment** on any clearnet item in your feed, NoSlop transforms that interaction into a **mesh broadcast**. The original URL and title are signed with your Ed25519 key and gossiped to your peers as a `POST` packet with embedded `clearnet_url` and `clearnet_title` fields. From that moment, the content lives in two worlds simultaneously: on the clearnet where it originated, and on the mesh where it travels under your identity.

All subsequent interactions — reactions, comments, replies — happen entirely on the mesh between you and your connections. No clearnet platform sees the engagement. No algorithm counts the signal. The conversation belongs to your network.

This is how NoSlop unites entertainment, community, and communication in one place:

- **Entertainment** — your curated clearnet feed surfaces the best of the open web, tracker-free.
- **Community** — a single tap broadcasts that content into your mesh, making it a shared reference point for your circle.
- **Communication** — every reply, comment, and reaction threads through the gossip protocol, end-to-end encrypted where needed, and fully offline-capable.

> ✅ **Implemented** — The clearnet interaction-to-broadcast pipeline is live. The `PostPayload` schema carries `clearnet_url`, `clearnet_title`, and `clearnet_thumbnail_url`; liking, sharing, or commenting on a clearnet item creates (or reuses) a deterministic SHA3-256-derived mesh anchor post for that URL, and `REACTION` packets are signed, gossiped, and toggleable (add/remove). Peers can tap "View on Clearnet" to open shared links. Remaining polish work (richer clearnet preview cards, reaction-count UI on feed cards, hybrid feed mixing) is tracked in [PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

---

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
| UI | Jetpack Compose + Material Design 3 |
| Media | ExoPlayer (HLS/DASH/MP4), WebView (YouTube), Coil (images) |
| Content | RSS/Atom parser, Jamendo API, Archive.org, YouTube RSS |
| Networking | Embedded Tor SOCKS5 daemon (onion-routed), OkHttp + DNS-over-HTTPS |
| Signing | Ed25519 (Android Keystore / Bouncy Castle) |
| Key exchange | X25519 |
| Encryption | ChaCha20-Poly1305 (DMs), AES-256-CBC (backup) |
| Storage | Room SQLite (WAL mode) + EncryptedSharedPreferences |
| Background sync | WorkManager |
| Camera | CameraX + ML Kit (QR scanning) |

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
- 🛠️ **[SUPPORT.md](docs/SUPPORT.md)** — operations guide, backup/restore, and troubleshooting.
- 🔬 **[NOSLOP_TECHNICAL_REFERENCE.md](docs/NOSLOP_TECHNICAL_REFERENCE.md)** — deep technical reference: crypto derivations, gossip pipeline internals, wire protocol, media/Tor internals, build config.
- 🔭 **[NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md](docs/NOSLOP_GAP_ANALYSIS_AND_UPSTREAM_NOTES.md)** — feature gaps vs. gChat/HAI-Net (presence, group chats, hash-based sync, etc.) and a backlog checklist.

---

## About

NoSlop is part of the [HAI-Net Initiative](https://hai-net.com) — building tools where AI and open networks work for people, not corporations.

Licensed under **AGPL-3.0**. Fork it. Run it. Own it.
