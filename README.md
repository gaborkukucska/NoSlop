# 🚫 NoSlop — The Unfiltered Pulse of the Mesh 🕸️

<p align="center">
  <em>"Your feed. Your identity. Zero algorithms. 100% freedom."</em>
</p>

<p align="center">
  <img alt="Build Status" src="https://img.shields.io/badge/Build-Passing-brightgreen?style=for-the-badge">
  <img alt="Status" src="https://img.shields.io/badge/Status-Iteration_3_Live-orange?style=for-the-badge">
  <img alt="Network" src="https://img.shields.io/badge/Network-HUBs_/_HAI--Net-blue?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/License-AGPL--3.0-purple?style=for-the-badge">
</p>

---

## What is NoSlop?

**NoSlop** is a privacy-first Kotlin Multiplatform app (Android & iOS) for consuming content and communicating with people — without servers, trackers, or algorithmic manipulation.

It combines a **tracker-free content aggregator** (RSS/Atom from YouTube, TikTok, and the open web) with a **serverless encrypted social layer** powered by Tor and our daisy-chain-gossip framework. All mesh network traffic is routed through **Tor by default**. Your identity is a cryptographic keypair that lives only on your device — no account, no email, no phone number.

---

## Reasons to stop the slop with NoSlop

- **Background Playback** — Keep listening to content seamlessly without keeping the app open. NoSlop fully supports background playback out of the box.
- **No Advertisements** — Say goodbye to annoying banners and video interruptions. Your messaging and content experience on NoSlop is 100% ad-free, always.
- **Complete Feed Control** — No obscure algorithms. You curate your content, you organize your sources, you decide what you see. Total chronological freedom.
- **P2P Mesh Engagement** — Connect locally and globally with peers. Bypass central platforms and establish direct, censorship-resistant connections.
- **Secure Direct Messaging** — End-to-end encrypted communications over our peer-to-peer mesh. Your conversations are mathematically secure and truly private.

---

## Features

### In-App OTA Updates
NoSlop bypasses centralized app stores entirely. It includes a robust over-the-air (OTA) auto-update system that:
- Automatically detects new releases from GitHub.
- Prominently alerts you via a dedicated banner in the Settings tab, persisting until updated.
- Handles Android 8.0+ `REQUEST_INSTALL_PACKAGES` permissions natively, with fallback options to "Just Download APK" if system permission dialogs fail.
- Downloads the APK directly via a pure, native `HttpURLConnection` pipeline with live progress toasts, bypassing the unreliable Android `DownloadManager` and DoH CDN redirect issues.
- Prompts the user to install the update seamlessly from within the app, equipped with anti-corruption file size and content-type verification.

### Immersive Snapping Feed

A vertical feed purpose-built for signal-to-noise ratio.

- **Blurred media fill** — images display uncropped with a blurred background fill. No black bars, no letterboxing.
- **Segmented article reader** — long articles are automatically split into paged segments. Side-swipe to read like a book.
- **Media playback** — seamless native audio and video streaming (HLS/m3u8, MP4, MP3, Archive.org, etc.) from clearnet sources with dynamic ahead-of-time preloading for instant playback without signing in or being tracked.
- **Immersive landscape mode** — rotate your device horizontally to automatically hide the UI, allowing edge-to-edge viewing for video and image content.
- **3-Tier Priority Curation** — choose from 14+ categories (Technology, Science, Privacy & Security, Gaming, Art, Music, and more) and specify your favorite creators during onboarding. NoSlop pre-loads curated RSS/Atom feeds and strictly prioritizes your feed chronologically in three tiers: `Creators > Chosen Categories > Trending Fallback`, guaranteeing a perfectly tailored experience from the first swipe.

### Serverless Social Mesh

Direct peer-to-peer communication over the HUBs / HAI-Net gossip network. No central server is ever involved.

- **Home HUBs** — Your dedicated local home lab serves as the ultimate backup of your mesh Identity, all your data, and your media. It ensures your presence is maintained even when your mobile device is offline. Deploy a new Home Hub directly from the NoSlop app's HUBs tab over SSH, or seamlessly auto-discover and cryptographically link to an existing one on your local network. **Deploying via the NoSlop app is currently the recommended way to set up a single-device HAI-Net system** *(multi-device deployment support is coming later)*. Once linked, the Hub serves as your master SQLite database, bi-directionally syncing historical DMs, mesh broadcasts, and contacts to your device. **If your hardware permits, the Hub also provides a completely private LLM assistant (Admin AI) equipped with an almost working project and LLM management harness!** You can converse with this AI securely from NoSlop using standard End-to-End Encrypted DMs. NoSlop safely detects deployment collisions and provides options to sign in, reset identity, or wipe.
- **Discoverable Mode & Creator Nodes** — Toggle Discoverable Mode in Settings to broadcast your ephemeral identity (burnable onion address) across the mesh up to 6 hops away, allowing anyone to find you without a QR code. Enable Creator Node to automatically accept incoming connections, permanently lock your media from local cache purging, and optionally broadcast your donation link.
- **Cryptographically signed posts** — every mesh broadcast is signed with your Ed25519 key. The network rejects forgeries.
- **End-to-end encrypted DMs** — direct messages use X25519 key agreement, derived via SHA3-256 into a ChaCha20-Poly1305 key (see [TECHNICAL_REFERENCE.md §3.5](docs/TECHNICAL_REFERENCE.md#35-direct-message-encryption) for the exact derivation). Only you and your contact can read them.
- **Cryptographic QR authentication** — scan a contact's QR code to exchange public keys and onion addresses, or scan your Hub's Web UI QR code to instantly verify ownership and sign in securely via an Ed25519 challenge-response.
- **Gossip propagation with firewall** — packets carry a hop counter (TTL = 6) and the gossip engine enforces per-sender rate limits (20 packets per 10-second window). Duplicate packets are deduplicated by ID with an LRU cache. Spam and flood attacks don't propagate.
- **Granular mesh filters** — control exactly which content types are pushed to and pulled from the mesh. Toggle incoming and outgoing traffic independently for reactions, comments, text posts, clearnet shares, images, and videos. Filters are network-only — your local data is always preserved, and interactions on content you already track are never blocked.

### Clearnet-to-Mesh Broadcasts

NoSlop is the bridge between the open web and your private mesh. Consuming content from your aggregated clearnet feed isn't a passive act — it's a gateway into community.

When you **like**, **share**, or **comment** on any clearnet item in your feed, NoSlop transforms that interaction into a **mesh broadcast**. The original URL and title are signed with your Ed25519 key and gossiped to your peers as a `POST` packet with embedded `clearnet_url` and `clearnet_title` fields. From that moment, the content lives in two worlds simultaneously: on the clearnet where it originated, and on the mesh where it travels under your identity.

All subsequent interactions — reactions, comments, replies — happen entirely on the mesh between you and your connections. No clearnet platform sees the engagement. No algorithm counts the signal. The conversation belongs to your network.

This is how NoSlop unites entertainment, community, and communication in one place:

- **Entertainment** — your curated clearnet feed surfaces the best of the open web, tracker-free.
- **Community** — a single tap broadcasts that content into your mesh, making it a shared reference point for your circle.
- **Communication** — every reply, comment, and reaction threads through the gossip protocol, end-to-end encrypted where needed, and fully offline-capable.

> 🌉 **The Bridge in Action:** The clearnet interaction-to-broadcast pipeline is fully live. Liking, sharing, or commenting on a clearnet item creates a deterministic SHA3-256-derived mesh anchor post for that URL. `REACTION` packets are signed, gossiped, and toggleable. Peers see rich clearnet preview cards seamlessly mixed into their feed alongside native mesh posts, complete with live reaction counts and a "View on Clearnet" button.

---

### Sovereign Identity

Your identity is generated locally and never leaves your device unless you export it yourself.

- **Ed25519 + X25519 keypair** — one key for signing, one for encryption. Generated on-device using Lazysodium (libsodium) with a Bouncy Castle fallback for maximum compatibility across all Android versions (API 24+).
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
| UI | Compose Multiplatform (Material Design 3) |
| Media | ExoPlayer (Android), AVPlayer (iOS), WKWebView/WebView, Coil |
| Content | Ktor, Kotlinx-serialization, xmlutil, RSS/Atom parser |
| Networking | Embedded Tor SOCKS5 daemon (onion-routed), Ktor + OkHttp/Darwin |
| Signing | Ed25519 (Bouncy Castle lightweight API / Lazysodium key generation) |
| Key exchange | X25519 |
| Encryption | ChaCha20-Poly1305 (DMs), AES-256-CBC (backup) |
| Storage | SQLDelight SQLite + native key-value stores |
| Background sync | WorkManager (Android) |
| Camera | CameraX (Android) + AVFoundation (iOS) |

---

## Getting Started

1. **Build from source** — follow [docs/BUILD.md](docs/BUILD.md). The canonical codebase is now the `mvp/` (Kotlin Multiplatform) directory.
2. **Run the onboarding flow** — 6 steps: generate your Word Cloud, pick your interests, optionally scan a friend's QR to add your first contact.
3. **Browse** — your feed populates immediately from the curated sources matching your interests. No account, no wait.

---

## Your Responsibilities

- **Open Source & Your Responsibility** — NoSlop is well-built open-source software with all functionalities in open code. Therefore, all responsibilities for its use fall entirely on you, the user.
- **No Server & No Automatic Backups** — Because there is no central server, there is NO cloud data backup. You must back up your identity and data yourself using the built-in export function.
- **Content Filtering** — While we do compile with some negative keywords to avoid certain content (see the repo), you should also set up your own negative keywords to avoid unwanted content in your feed.
- **Bring Your Own Network** — NoSlop is much better with friends, HOWEVER, it holds no user directory whatsoever. You must manually add peers to build your mesh. It is entirely up to you.
- **Installing the APK** — Android will likely show security warnings about installing apps from unknown sources since this is downloaded directly and not from the Play Store. You will likely need to search your phone's settings for `unknown` to find the 'Install unknown apps' section and allow installing from unknown sources to be able to install this app.

---

## 💖 Support the Vision

NoSlop is entirely free, open-source, and devoid of trackers and advertisements. If you find value in a private, serverless communication tool, please consider supporting the sole developer!

🪙 ☕ 🍱 [Toss me a coin, buy me a chai or even a meal](https://donate.stripe.com/dRmfZae1F0jNfPNfFC9fW00)

---

## Documentation

- 🏗️ **[BUILD.md](docs/BUILD.md)** — how to compile and install NoSlop.
- 🌍 **[TRANSLATION_GUIDE.md](docs/TRANSLATION_GUIDE.md)** — how to translate the app to your language.
- 📉 **[PROJECT_STATUS.md](docs/PROJECT_STATUS.md)** — latest technical milestones and known issues.
- 📡 **[WIRE_PROTOCOL_REFERENCE.md](docs/WIRE_PROTOCOL_REFERENCE.md)** — the complete HAI-Net wire protocol reference (supersedes the old packet schema docs).
- 🔬 **[TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md)** — deep technical reference: crypto derivations, gossip pipeline internals, media/Tor internals, build config.
- 🏠 **[HUB_INTEGRATION_PLAN.md](docs/HUB_INTEGRATION_PLAN.md)** — phased plan for the upcoming Home HUB architecture.
- 🔀 **[MIGRATION.md](docs/MIGRATION.md)** & **[KMP_PARITY_PLAN.md](docs/KMP_PARITY_PLAN.md)** — architecture shift and parity details for the Kotlin Multiplatform migration.
- 🛡️ **[PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)** — clear breakdown of data sovereignty and privacy expectations.
- 🐞 **[DEBUG.md](docs/DEBUG.md)** — how to extract and read system logs.
- 🛠️ **[SUPPORT.md](docs/SUPPORT.md)** — operations guide, backup/restore, and troubleshooting.
- 🔭 **[GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md)** — feature gaps vs. gChat/HAI-Net (presence, group chats, hash-based sync, etc.) and a backlog checklist.

---

## About

NoSlop is part of the [HAI-Net Initiative](https://hai-net.com) — building tools where AI and open networks work for people, not corporations.

Licensed under **AGPL-3.0**. Fork it. Run it. Own it.

<p align="center">
  ✨ <em><a href="https://gaborkukucska.com">Dreamed up by Gabby</a></em>
</p>
