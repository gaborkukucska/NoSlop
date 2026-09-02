# Privacy & Security Hardening Proposal — NoSlop

> [!NOTE]
> **Status: PARTIALLY IMPLEMENTED — reviewed 2026-09-01**
>
> **Landed and in `app/`:** AES-256-GCM backups (with a CBC read path for legacy
> archives), Tor circuit rotation, the AES-GCM fallback identity store, and
> exponential peer cooldown backoff.
>
> **Landed with a caveat:** the proxy secret has moved out of source into a
> Gradle property (`NOSLOP_PROXY_SECRET`) so the shipped value is no longer in
> the public repo and can be rotated without a code change, and the cleartext
> `X-Proxy-Secret` header is now behind `PROXY_SEND_LEGACY_SECRET`. That flag
> still defaults to true, so until the Worker is redeployed and the flag flipped,
> the secret is still crossing the wire. §1.1 is not closed.
>
> Be honest about the ceiling here: a signing key compiled into a public,
> downloadable APK is not a secret. The HMAC is abuse friction and a
> rate-limiting hook for the Worker, not authentication, and no amount of
> rotation changes that. The part of §1.2 that genuinely matters — letting users
> point at their own Worker — is still unimplemented and needs a Settings UI.
>
> **Not implemented:** release signature verification. Until 2026-09-01
> `UpdateManager` computed the APK's SHA-256 and logged it without comparing it
> to anything; the only real gates were a 2MB floor and a `text/html` check. A
> published-checksum comparison has now landed, but the Ed25519 release
> signature described in §2 has not. Do not treat OTA as MITM-resistant until it
> does.
>
> **Deliberately deferred:** the Double Ratchet in §5. DMs remain static-static
> X25519 with no forward secrecy, and the README says so.

## Local attack surface — added 2026-09-01

Three issues that were not in the original proposal at all, because the audit
that produced it looked at network traffic and never at what other apps on the
same handset can reach. On Android, loopback is shared between every installed
app; it is not a private namespace.

| Component | Issue | Status |
|---|---|---|
| **Tor control interface** | `ControlPort 9051` with `CookieAuthentication 0`. Any app holding INTERNET could open an unauthenticated Tor control connection and issue `GETINFO circuit-status` (deanonymisation), `ADD_ONION`, `SETCONF` or `SIGNAL`. | **PARTIAL.** All control access is centralised in `TorControlChannel`, which supports a private unix `ControlSocket`. The first `UNIX_ONLY` build could not open that socket — tor logs nothing to logcat, so it failed invisibly, taking `NEWNYM` and `ADD_ONION` with it. Currently `Mode.AUTO`: socket preferred, **TCP 9051 still declared as fallback**, so the hole is not yet closed. Cookie auth was rejected deliberately — it is global, and tor-android's own control connection authenticates with empty credentials. See TECHNICAL_REFERENCE §17.1. |
| **Media proxy** | `127.0.0.1:8080` served `/stream?id=…` with no authentication, so another app could pull cached mesh media, and the caller-supplied `onion=` parameter let it choose an arbitrary fetch target on the user's Tor circuits. | **Fixed.** Per-process token required on every request, verified in constant time; the onion parameter must match a well-formed v3 address. |
| **Mesh listener** | `readLine()` with no length bound — one peer sending bytes without a newline could exhaust the heap. | **Fixed.** Frames are capped and the connection is dropped past the ceiling. Raw frames are also no longer written to the exportable log. |

## Player URL IP lock — added 2026-09-02

Not a proposal item, but it belongs with the proxy discussion in §1. A
googlevideo URL carries `&ip=<address>` and is served only to that address.
Resolving `/player` through the Cloudflare Worker had YouTube issue the URL to
the Worker's egress; the bytes were then fetched over a Tor exit and refused
silently, as a stream that never started. `/player` is now always direct
(`NOSLOP_PLAYER_IP_LOCK_V1`); search and metadata still use the proxy, which
returns no IP-locked URLs.

Worth noting alongside §1.2: this is the proxy actively harming a request it
succeeded at, which is a stronger argument for user-configurable endpoints than
the refusal case.

## Backup — updated 2026-09-01

`importData` read the whole archive and its whole plaintext into memory, so a
restore including cached media could not complete at all. Both directions now
stream through a fixed buffer, GCM plaintext is not unzipped until the tag has
verified, and zip entry names are validated against path traversal.

Separately, and this is a design limit rather than a bug: `preferences.xml` in
the archive is sealed by a non-exportable Keystore key, so a restore on a NEW
device brings the data back but not the identity. This used to happen silently.
It is now detected and surfaced via `BackupManager.lastRestoreNeedsIdentityRecovery`
so the UI can prompt for Word Cloud recovery.

**Still open:** the UI does not yet read that flag. Wire it into the restore
screen before telling anyone that device migration works.

This document presents the technical designs and implementation strategies that were applied to address the privacy and security items identified during the audit of the NoSlop codebase.

---

## Executive Summary of Proposals

| Component | Identified Vulnerability / Limitation | Proposed Hardening Solution | Risk Reduction |
|---|---|---|---|
| **API Proxy** (`yt-proxy`) | Static shared secret (`NoSlopRocks2026`), single centralized Cloudflare endpoint, silent clearnet fallbacks | HMAC dynamic request signing, user-configurable / self-hosted worker endpoints, circuit-cycling & Invidious/Piped decentralized racing fallbacks | Removes single point of failure, prevents accidental IP leaks. **Static secret leak NOT yet eliminated** — see status note above. |
| **OTA Updates** | Unverified APK installation, clearnet downloads without TLS pinning or signature checks | Cryptographic release signing (Ed25519 signature & SHA-256 checksum verification before install), HTTPS Certificate Pinning, optional Tor routing for update downloads | Prevents MITM / malicious update substitution |
| **Key Storage** | Plaintext fallback (`noslop_identity_fallback`) when `EncryptedSharedPreferences` fails | Passphrase-derived AES-GCM local key store fallback, strict UI security warnings when running off-Keystore | Prevents unencrypted private key storage on disk |
| **Backup Export** | AES-256-CBC encryption without AEAD integrity validation | Upgrade to AES-256-GCM or ChaCha20-Poly1305 with MAC tag verification on restore | Protects backup archives against bit-flipping & padding oracle attacks |
| **DM Cryptography** | Static-static X25519 DH key agreement (no forward secrecy) | Implement Double Ratchet / Noise Protocol session keys | Ensures past message secrecy even if long-term key is compromised |
| **Mesh Gossip** | Unsigned typing/read receipts, un-ratelimited DM/handshake bursts | Signed ephemeral session tokens, per-peer adaptive token-bucket rate limiting | Prevents spam flooding & identity spoofing |

---

## 1. API Proxy Infrastructure & Clearnet Fallback Safeguards

### 1.1 Dynamic HMAC Request Signing & Rotatable Secret
**Problem**: Hardcoding `PROXY_SECRET = "NoSlopRocks2026"` in open-source clients allows external actors to abuse the Cloudflare Worker proxy.

**Design**:
1. Replace static header with a time-windowed HMAC signature:
   ```
   X-Proxy-Timestamp: <epoch_seconds>
   X-Proxy-Signature: HMAC-SHA256(timestamp + query, dynamic_secret)
   ```
2. The Cloudflare Worker validates that `|current_time - timestamp| < 300s` and verifies the signature using a rotatable secret key.
3. Allow users to input custom Worker URLs and secrets in `Settings > Content Preferences > Proxy Settings`.

### 1.2 Decentralized Proxy & Instance Racing Fallbacks
**Problem**: Relying solely on `yt-proxy.megadreamland.workers.dev` creates a centralized point of failure. Falling back to direct `youtube.com` / `jamendo.com` when `useTorForClearnet = false` exposes the user's real IP.

**Design**:
```
                        ┌──► Primary Cloudflare Worker Proxy
                        │
API Search / Fetch ─────┼──► Secondary Self-Hosted Worker (User Configured)
                        │
                        └──► Decentralized Invidious / Piped Instance Pool (Raced over Tor)
```
- **Tor Circuit Cycling on Fallback**: If the proxy returns 429/403 over Tor, call `TorService.requestNewCircuit()` (`SIGNAL NEWNYM`) before retrying.
- **Strict IP Protection Guard**: When `useTorForClearnet = true`, enforce a strict policy that **never** dispatches an API or fallback request via `rawClearnetClient`. If proxying fails and no Tor circuit can fetch the data, surface a graceful "Content unavailable over Tor" state to the user.

### 1.3 Per-Domain Tor Circuit Isolation for Media Streams
**Problem**: Media stream bytes (`googlevideo.com`, `jamendo.com`) connect directly from ExoPlayer, allowing CDNs to correlate requests originating from the same exit node IP.

**Design**:
- Configure OkHttp SOCKS proxy authentication credentials per media domain (e.g. `username="domain_hash"`). Tor SOCKS5 treats distinct SOCKS credentials as separate isolation contexts, forcing Tor to assign independent circuits and exit nodes for different media hosts.

---

## 2. Cryptographic OTA Update Verification

### 2.1 Ed25519 Release Signature & SHA-256 Hash Verification
**Problem**: `UpdateManager.kt` downloads an APK file and launches `ACTION_VIEW` package installation without validating file integrity or authenticity.

**Design**:
1. Embed the developer's release public key into `BuildConfig`:
   ```kotlin
   const val RELEASE_PUBLIC_KEY = "MCowBQYDK2VwAyEA..." // Ed25519 Public Key
   ```
2. Accompany every release asset on GitHub / `noslop.me` with a signed checksum file (`SHA256SUMS.sig`).
3. **Verification Flow in `UpdateManager.kt`**:
   ```kotlin
   // 1. Download APK and SHA256SUMS.sig
   val apkBytes = destFile.readBytes()
   val actualHash = sha256Hex(apkBytes)
   
   // 2. Verify signature against embedded public key
   val isValid = CryptoService.verify(
       payload = "$actualHash NoSlop_$version.apk",
       signatureB64 = downloadedSignatureB64,
       publicKeyB64 = BuildConfig.RELEASE_PUBLIC_KEY
   )

   if (!isValid) {
       destFile.delete()
       showErrorToast("Update verification failed! Downloaded file signature is invalid.")
       return
   }

   // 3. Launch package installer only after successful verification
   launchInstaller(context, destFile)
   ```

### 2.2 HTTPS Certificate Pinning
**Problem**: Direct update checks on clearnet could be subject to DNS spoofing or MITM proxies on untrusted networks.

**Design**:
- Configure OkHttp `CertificatePinner` for update endpoints:
  ```kotlin
  val updateClient = rawClearnetClient.newBuilder()
      .certificatePinner(
          CertificatePinner.Builder()
              .add("noslop.me", "sha256/HASH_OF_NOSLOP_ME_PUBLIC_KEY=")
              .add("api.github.com", "sha256/HASH_OF_GITHUB_PUBLIC_KEY=")
              .build()
      )
      .build()
  ```

---

## 3. Sovereign Key Isolation & Secure Persistence

### 3.1 Passphrase-Encrypted Fallback Key Storage
**Problem**: When Android Keystore is unavailable, `IdentityRepository.kt` writes private keys to unencrypted `SharedPreferences` (`noslop_identity_fallback`).

**Design**:
1. **Require User Master Passphrase on Fallback**: If `EncryptedSharedPreferences` fails:
   - Prompt user to set a local 6-digit PIN or passphrase.
   - Derive an AES-256 key via Argon2id / PBKDF2 from the passphrase.
   - Encrypt private keys in-memory before storing in fallback `SharedPreferences`.
2. **UI Security Indicator**: Display a persistent warning icon in the Settings header when running in fallback key storage mode, informing the user that private keys are PIN-protected rather than hardware-isolated.

### 3.2 Authenticated Backup Encryption (AES-GCM / ChaCha20-Poly1305)
**Problem**: `BackupManager.kt` uses `AES/CBC/PKCS5Padding` without authentication, making backups vulnerable to ciphertext tampering.

**Design**:
- Upgrade backup pipeline to `AES-256-GCM` or `ChaCha20-Poly1305`:
  ```kotlin
  val cipher = Cipher.getInstance("AES/GCM/NoPadding")
  val gcmSpec = GCMParameterSpec(128, iv) // 128-bit authentication tag
  cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
  ```
- On import, `cipher.doFinal()` automatically validates tag integrity, rejecting tampered or corrupted backup archives before disk extraction.

---

## 4. Mesh Cryptography & Gossip Protocol Hardening

### 4.1 Forward-Secrecy Session Keys (Double Ratchet Protocol)
**Problem**: DMs use static-static X25519 DH key agreement (`myEncPrivB64` + `theirEncPubB64`). If a node's identity private key is compromised in the future, all past recorded DM traffic can be decrypted.

**Design**:
1. Implement a lightweight Double Ratchet protocol for peer DM sessions.
2. Direct messages exchange DH ratchet keys in message headers (`ephemeral_dh_pub`).
3. Each message advances the sending/receiving chain, deriving unique per-message keys that are deleted immediately after encryption/decryption.

### 4.2 Signed Ephemeral Signals
**Problem**: `TYPING` and `READ_RECEIPT` packets are unsigned, allowing malicious peers on the mesh to forge indicators.

**Design**:
- Include an ephemeral HMAC tag computed using the active DM session key:
  ```json
  {
    "type": "TYPING",
    "sender_id": "<pubkey>",
    "timestamp": 1772345678,
    "hmac": "HMAC-SHA256(sender_id + timestamp, session_key)"
  }
  ```

### 4.3 Adaptive Per-Peer Rate Limiting
**Problem**: Handshake, DM, and media fetch traffic are currently exempt from the 20 pkts / 10s rate limit, leaving nodes open to resource exhaustion attacks from trusted peers.

**Design**:
- Implement per-peer sliding window token bucket rate limiters across **all** packet types:
  - **Posts / Reactions / Comments**: 20 packets / 10s
  - **DM Messages**: 30 packets / 10s
  - **Handshake / Sync**: 10 packets / 10s
  - **Media Request Chunks**: 100 packets / 10s
- Peers exceeding thresholds enter a transient 60-second cooldown state during which inbound packets from that peer are dropped at the `TrustFirewall` level.

---

## Implementation Roadmap & Action Plan

```
Phase 1: Release Integrity & Backup Safety (High Impact, Low Complexity)
├── Add Ed25519 signature & SHA-256 checksum verification to UpdateManager.kt
├── Upgrade BackupManager.kt to AES-256-GCM authenticated encryption
└── Add HTTPS Certificate Pinning for update check domains

Phase 2: Proxy Privacy & Tor Safeguards (High Impact, Medium Complexity)
├── Implement dynamic HMAC signing & user-configurable Worker proxy endpoints
├── Enforce strict Tor IP leak prevention (block clearnet fallbacks when Tor is ON)
└── Integrate Tor circuit cycling on API proxy errors

Phase 3: Key Isolation & Mesh Protocol Security (High Impact, Higher Complexity)
├── Add PIN-derived AES-GCM fallback storage when Android Keystore is unavailable
├── Implement Double Ratchet protocol for forward-secure DMs
└── Apply adaptive token-bucket rate limiting across all mesh packet types
```
