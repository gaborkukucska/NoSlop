# NoSlop — Serverless Feed Reader & Secure social Mesh Node

NoSlop is a serverless, local-first Jetpack Compose application designed to act as an unfilterable personal feed reader and decentralised social networking client on the **HAI-Net** mesh communication network.

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

## Features

- **Personal Free Feed Aggregator**: Aggregate and read your standard RSS and Atom feeds completely locally, free of trackers, telemetry, algorithms, and sponsored content.
- **Dynamic Cryptographic Verification**: Native **Ed25519** signature schemes verify every single inbound mesh post so that malicious actors cannot forge peer handles.
- **Double-Envelope E2EE**: Standalone **X25519** key agreements establish secure channels for **ChaCha20-Poly1305** Direct Messaging.
- **Hardware-Isolated Credential Storage**: Private keys never touch standard databases or unsecure caches; instead, they are compartmented securely inside Android's hardware `EncryptedSharedPreferences`.
- **Intrusive Firewall**: Incoming packets from unregistered/non-trusted senders are immediately discarded at the socket boundary, shielding the user from SPAM.
- **Non-Blocking Debug Logger**: Log operations are pushed synchronously to an in-memory queue for immediate visual debugging, while a background coroutine pool flushes log files to disk.

## Security Controls

1. **Cleartext Hardening**: Out-of-the-box support for strict cleartext rules, preventing accidental leaks of clearnet packets.
2. **Key Separation**: Distinct keypairs are generated upon onboarding: Ed25519 for integrity signatures and ECDH for messaging encryption.
3. **Local-First Design**: Total absence of centralized servers or backends. Your data stays on your hardware.

## License
Fully open-source under the GNU Affero General Public License (AGPL-3.0). See [LICENSE.md](LICENSE.md) for details.
