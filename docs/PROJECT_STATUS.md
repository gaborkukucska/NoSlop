# NoSlop — Project Status

NoSlop is a serverless, offline-first personal RSS/Atom feed reader and private decentralized social mesh node configured for the **HAI-Net** decentralized initiative.

## Current Build & Integration Status
- **Android Compilation Target**: API Level 35 (compiled using JDK 11, standard Jetpack Compose UI)
- **Status**: **PASSING & FULLY COMPILED** (successfully verified via `compile_applet`)
- **Visual Identity**: Premium brutalist dark slate styling integrated with custom adaptive colors and Material Design 3 guidelines.

## Completed Milestones
1. **Package Re-alignment**: Restructured and renamed all packages from original placeholders to `com.noslop.app` dynamically across directories.
2. **Dynamic E2EE Messaging Routing**: Implemented elliptic-curve keys isolation (Ed25519 for post signatures, P-256 for ECDH key agreements, AES-256-GCM for direct messaging) coupled with on-the-fly dynamic decryption inside the Jetpack Compose layer.
3. **Hardware Storage Isolation (`IdentityRepository`)**: Migrated raw private keys into secure `EncryptedSharedPreferences` backed by the hardware-backed Android Keystore, keeping Room tables free of target exposure.
4. **Offline Logging Daemon (`Logger`)**: Developed thread-safe ring-buffer logging combined with non-blocking concurrent async file-write queues to context directory files.
5. **Cleartext Security Profiles (`network_security_config.xml`)**: Configured strict TLS requirements globally with explicit isolated cleartext exceptions for whitelisted local loopbacks and specific feed nodes.
6. **Orbot Tor Tunneling**: Implemented sockets polling and package queries to detect and bind to local Tor SOCKS5 proxies safely (`127.0.0.1:9050`).

## Cryptographic Specification Contract
| Function | Primitive | Format / Library | Storage Backend |
| :--- | :--- | :--- | :--- |
| **Post Signatures** | Ed25519 | Base64 strings (X.509/PKCS#8) | Android Keystore / `EncryptedSharedPreferences` |
| **Tripcode Derivation** | SHA3-256 | 6-char lowercase Base32 sequence | Database / Memory |
| **Onion Addressing** | SHA3-256 Tor v3 | 56-char `.onion` address | Database / Memory |
| **Key Agreement** | ECDH (P-256) | secp256r1 | `EncryptedSharedPreferences` |
| **Direct Message E2EE** | AES-256-GCM | 12-byte random nonce + payload | Local DB (Encrypted) |
