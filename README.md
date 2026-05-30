# NoSlop — Serverless Feed Reader & Secure social Mesh Node

**Status: v0.1**

NoSlop is a serverless, local-first Jetpack Compose application designed to act as an unfilterable personal feed reader and decentralised social networking client on the **HAI-Net** mesh communication network.

## Status: What Works (v0.1)

- **Feed Reader**: Aggregate and read RSS/Atom feeds completely locally, free of trackers, algorithms, and sponsored content.
- **Mesh Posts**: Decentralized peer-to-peer gossip network broadcasting timestamped posts.
- **E2EE DMs**: Secure direct messaging between peers.
- **QR Pairing**: Camera-based QR code scanning and sharing for frictionless companion node pairing.
- **Background Sync**: Automated periodic background syncing of feeds using Android WorkManager.
- **Tor Routing**: All mesh communication operates exclusively over Tor SOCKS5 hidden services.

## Architectural Architecture

```
                    ┌────────────────────────────┐
                    │     NoSlop Android Node    │
                    │   (Jetpack Compose UI)     │
                    └──────────────┬─────────────┘
                                   │
              ┌────────────────────┴────────────────────┐
              ▼                                         ▼
   ┌────────────────────┐                    ┌───────────────────┐
   │ NoSlopRepository   │                    │   Logger Daemon   │
   └──────────┬─────────┘                    │ (Async IO Writer) │
              │                              └──────────┬────────┘
     ┌────────┴──────────────┐                          │
     ▼                       ▼                          ▼
┌──────────────┐   ┌───────────────────┐     ┌───────────────────┐
│ Room SQLite  │   │ EncryptedPrefs    │     │  Local text file  │
│ (Public data)│   │ (Hardware Keys)   │     │ (noslop-debug.log)│
└──────────────┘   └─────────┬─────────┘     └───────────────────┘
                             │
                             ▼
                    ┌───────────────────┐
                    │    Tor/Orbot      │
                    │   SOCKS5 Proxy    │
                    └───────────────────┘
```

## Security Controls

1. **Dynamic Cryptographic Verification**: Native **Ed25519** signature schemes verify every single inbound mesh post so that malicious actors cannot forge peer handles.
2. **Double-Envelope E2EE**: Standalone **X25519** key agreements establish secure channels for **ChaCha20-Poly1305** Direct Messaging.
3. **Hardware-Isolated Credential Storage**: Private keys never touch standard databases or unsecure caches; instead, they are compartmented securely inside Android's hardware `EncryptedSharedPreferences`.
4. **Intrusive Firewall**: Incoming packets from unregistered/non-trusted senders are immediately discarded at the socket boundary, shielding the user from SPAM.
5. **Cleartext Hardening**: Out-of-the-box support for strict cleartext rules, preventing accidental leaks of clearnet packets.
6. **Key Separation**: Distinct keypairs are generated upon onboarding: Ed25519 for integrity signatures and ECDH for messaging encryption.
7. **Local-First Design**: Total absence of centralized servers or backends. Your data stays on your hardware.

## Requirements

- **Android 7.0+ (API 24 or higher)**
- **Orbot**: Must be installed and running (available via F-Droid or Google Play).

## How to Build

See [docs/BUILD.md](docs/BUILD.md) for detailed build instructions.

## License

Fully open-source under the GNU Affero General Public License (AGPL-3.0). See [LICENSE.md](LICENSE.md) for details.
