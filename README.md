# NoSlop: Serverless feed aggregator and secure social node

NoSlop is an offline-first, cryptographic feed reader and serverless social node designed for the decentralized **HAI-Net** mesh network. It rejects low quallity AI-generated content ("AI Slop") and centralized recommendation streams, in favor of direct peer signatures, local data sovereignty, and private Tor-routed direct messaging.

## 🚀 Key Features

*   **Self-Sovereign Cryptographic Identity**: Every user self-generates an Ed25519 keypair during onboarding. A 6-character tripcode (`display_name.tripcode`) is derived from the SHA-256 hash of your public key, verifying authorship with zero accounts, passwords, or central servers.
*   **Decentralized Feed Aggregation**: Unified in-app RSS/Atom feed reader. Strips all tracker scripts and presents content in a pure, distraction-free typography layout. Let's you read items in-app without loading third-party tracker browsers.
*   **P2P Mesh Gossip Gossip**: Sign and broadcast posts to local database stores. When connecting over Tor, nodes peer with friends to exchange, reconcile, and synchronize gossip posts securely.
*   **End-to-End Encrypted Messenger**: Peer-to-peer secure chat. Generates AES-256-GCM symmetric session keys using an ECDH (Elliptic Curve Diffie-Hellman) key agreement. Only you and the recipient companion hold the keys to decrypt the chat payload.
*   **Tor SOCKS5 Privacy Integration**: Native hooks connect to local Tor/Orbot hidden service ports, allowing direct socket messaging without exposing your physical location or IP address.

## 🛠 Architectural Blueprint (MVVM + Jetpack Compose)

```
        Onboarding UI  <--->   Primary Dashboard Compose UI (Tabs)
              ^                         ^
              |                         |
              +-----------+-------------+
                          |
                  NoSlopViewModel
                          |
              NoSlopRepository (Handles crypto, parsing, socks)
               /          |           \
      Room Database   TorService   FeedParser
      (Daos, Entities)
```

## 📂 Source Code Structure

*   `app/src/main/java/com/example/MainActivity.kt`: Starts the app, initializes global state, and navigates between the Onboarding screen and the Main Dashboard.
*   `app/src/main/java/com/example/ui/`: Contains all Compose layout and logic files:
    *   `OnboardingScreen.kt`: Beautiful onboarding wizard (identity generator, curate feeds, secure explanation).
    *   `MainScreen.kt`: Composes the Feed, Mesh, E2EE DMs, Profile, Settings, and Logs screens.
    *   `NoSlopViewModel.kt`: Core centralized StateFlow provider managing repository flows.
*   `app/src/main/java/com/example/data/`: Room configuration, Entities (`FeedItem`, `Peer`, `ChatMessage`), and companion DAO queries.
*   `app/src/main/java/com/example/crypto/`: Houses ECC keys, SHA-256 digests, and AES-256 GCM cipher operations.
*   `app/src/main/java/com/example/tor/`: Coordinates background Tor connection check and Orbot triggers.
*   `app/src/main/java/com/example/debug/`: Localized structured diagnostic logging.

## ⚖️ License
Licensed under the open-source GNU Affero General Public License (AGPL-3.0). See `/LICENSE.md` for details.
