# NoSlop — Architectural Findings & Open Technical Register

This document tracks active architectural enhancements and audit items for the NoSlop Android application (`app/`), superseding legacy registers and referenced by `MeshPacketVerifier.kt`.

---

## 1. Cryptographic Payload Canonicalization (Finding #4 / P1-7)
- **Current State**: Handlers and `MeshPacketVerifier` utilize length-prefixed encoding (`CryptoService.encodeForSigning`) across high-volume types (`POST`, `EDIT_POST`, `DELETE_POST`, `USER_HANDSHAKE`, `CONNECTION_REQUEST`, `CHAT_REACTION`, `GROUP_INVITE`, `GROUP_DELETE`). Legacy pipe-delimited strings (`|`) remain supported as fallback for `COMMENT`, `EDIT_COMMENT`, `IDENTITY_UPDATE`, and `FOLLOW`.
- **Roadmap**: Transition all packet types to a unified, versioned canonical encoder (e.g. sorted-key JSON with explicit null representations) emitting a protocol version header (`sigVersion = 2`) with a backward-compatible transition window.

## 2. Complete Database Encryption at Rest (Finding #11 / P0-2)
- **Current State**: Group message bodies are encrypted at rest using an app-scoped AES-256-GCM master key held in the Android Keystore (`GroupMessageCrypto`, Room migration 12→13). 1:1 Direct Messages store encrypted ciphertext and nonces from X25519/ChaCha20-Poly1305 key agreement.
- **Roadmap**: The underlying Room SQLite database (`mesh.db`) stores non-message entities (feed sources, viewed history, peers, and settings) in standard SQLite. Full-database encryption via SQLCipher is planned for post-v0.5 releases.

## 3. Direct Message Forward Secrecy & Ratchet
- **Current State**: Direct messaging uses static-static X25519 key agreement derived into a ChaCha20-Poly1305 key via SHA3-256.
- **Roadmap**: Implement Double Ratchet protocol (Signal / Olm style) to provide per-message ephemeral key exchanges and forward secrecy.

## 4. ProGuard Keep Surface Refactoring (P2-2)
- **Current State**: `proguard-rules.pro` preserves model classes and necessary reflection packages to guarantee Gson serialization stability and Tor daemon interoperability.
- **Roadmap**: Gradually replace package-level wildcard rules with granular `@Keep` annotations on model DTOs (`Packets.kt`, `UpdateChecker.kt`, `UserProfile.kt`).

## 5. Architectural Decomposition (P2-3 & P2-4)
- **Current State**: `NoSlopRepository` and `NoSlopViewModel` coordinate cross-domain flows (mesh, feeds, engagement, settings, and Hubs).
- **Roadmap**: Decompose large repository and ViewModel classes along existing domain boundaries (`HubRepository`, `ChatRepository`, `FeedViewModel`, `DMsViewModel`).

## 6. LAN Hub TLS Pinning (Finding #14)
- **Current State**: Because Android's Network Security Config enforces system-anchor TLS and restricts cleartext to loopback and `.onion`, Hub API interactions route reliably over the authenticated Tor hidden service (`.onion`) endpoint.
- **Roadmap**: Support direct LAN HTTP fast-path with Hub self-signed certificate generation and fingerprint pinning.


## 7. Client-Side Proxy Secret and Shared API Keys (P1-9 / D-2) ✅
- **Current State & Resolution**:
  1. `ProxyAuth.kt` dynamically accepts user-configured `custom_proxy_url` and `custom_proxy_secret` from `ApiKeyRepository`, allowing users to route through their own self-hosted Cloudflare Worker or reverse proxy endpoints while falling back safely to `BuildConfig` defaults.
  2. `JamendoApiClient.kt` accepts user-configured Jamendo Client IDs from `ApiKeyRepository`, falling back gracefully to the public CC client ID (`709fa152`).
  3. `ApiKeyRepository.SERVICES` exposes `jamendo`, `custom_proxy_url`, and `custom_proxy_secret` directly in `ApiKeysScreen.kt` for secure user configuration.

## 8. Sovereign Identity Recovery & Tiered Backup Architecture (Finding #2 / D-1)
- **Current State**: Restoring only from a 12-word mnemonic phrase cannot reconstruct a serverless node's peer connections, group chat states, secondary/burnable creator keys, or local preferences. Recovery is anchored on AES-256-GCM authenticated ZIP archives (`BackupManager.kt`).
- **Resolution**:
  1. Implemented tiered backup export (`BackupMediaOption.NONE` for lightweight ~100 KB IDs/keys/database archive vs. `BackupMediaOption.OWNED_ONLY` for owned media).
  2. Proactive advisory modals prompt users to back up their node upon onboarding completion and whenever secondary/creator hidden services or keys change (`NoSlopViewModel.BackupPromptReason`).
  3. Pruned dead derivation pathways and aligned test fakes.

