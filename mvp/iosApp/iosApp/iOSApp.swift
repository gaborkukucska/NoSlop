import SwiftUI
import CryptoKit
import Security
import ComposeApp

/// Persistent iOS identity: a real Ed25519 keypair (`Curve25519.Signing`) whose private key is stored
/// in the iOS Keychain, so the same identity is returned on every launch. Bridged into the shared
/// Kotlin core via `IosKeychain`.
class KeychainIdentity: IosKeychain {
    private let service = "com.noslop.mvp.identity"
    private let account = "ed25519-private-key"

    func loadOrCreatePublicKeyBase64() -> String {
        if let priv = loadPrivateKey() {
            return priv.publicKey.rawRepresentation.base64EncodedString()
        }
        let priv = Curve25519.Signing.PrivateKey()
        storePrivateKey(priv)
        return priv.publicKey.rawRepresentation.base64EncodedString()
    }

    func reset() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    // MARK: - Keychain

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func loadPrivateKey() -> Curve25519.Signing.PrivateKey? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: data)
        else { return nil }
        return priv
    }

    private func storePrivateKey(_ priv: Curve25519.Signing.PrivateKey) {
        SecItemDelete(baseQuery() as CFDictionary) // overwrite any prior
        var query = baseQuery()
        query[kSecValueData as String] = priv.rawRepresentation
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }
}

/// CryptoKit Ed25519 signing, bridged into the shared core. Keys arrive as raw 32-byte values (the
/// Kotlin side strips the PKCS#8/X.509 headers); CryptoKit produces standard RFC 8032 signatures.
class CryptoKitSigner: IosSigner {
    func signBase64(payloadBase64: String, privateKeyRawBase64: String) -> String? {
        guard let key = Data(base64Encoded: privateKeyRawBase64),
              let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: key),
              let msg = Data(base64Encoded: payloadBase64),
              let sig = try? priv.signature(for: msg) else { return nil }
        return sig.base64EncodedString()
    }

    func verifyBase64(payloadBase64: String, signatureBase64: String, publicKeyRawBase64: String) -> Bool {
        guard let pubData = Data(base64Encoded: publicKeyRawBase64),
              let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: pubData),
              let sig = Data(base64Encoded: signatureBase64),
              let msg = Data(base64Encoded: payloadBase64) else { return false }
        return pub.isValidSignature(sig, for: msg)
    }
}

/// CryptoKit DM crypto, bridged into the shared core: X25519 key agreement + ChaCha20-Poly1305 (IETF,
/// 12-byte nonce). Keys arrive as raw 32-byte values; SHA3 of the shared secret is done in Kotlin commonMain.
class CryptoKitDm: IosDm {
    func sharedSecretBase64(myPrivRawBase64: String, theirPubRawBase64: String) -> String {
        guard let privData = Data(base64Encoded: myPrivRawBase64),
              let pubData = Data(base64Encoded: theirPubRawBase64),
              let priv = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privData),
              let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: pubData),
              let secret = try? priv.sharedSecretFromKeyAgreement(with: pub) else { return "" }
        // Raw X25519 output (not the HKDF-derived form) to match BouncyCastle / the Python golden vector.
        return secret.withUnsafeBytes { Data($0).base64EncodedString() }
    }

    func sealBase64(keyBase64: String, nonceBase64: String, plaintextBase64: String) -> String {
        guard let keyData = Data(base64Encoded: keyBase64),
              let nonceData = Data(base64Encoded: nonceBase64),
              let plaintext = Data(base64Encoded: plaintextBase64),
              let nonce = try? ChaChaPoly.Nonce(data: nonceData),
              let box = try? ChaChaPoly.seal(plaintext, using: SymmetricKey(data: keyData), nonce: nonce)
        else { return "" }
        return (box.ciphertext + box.tag).base64EncodedString()
    }

    func openBase64(keyBase64: String, nonceBase64: String, ciphertextAndTagBase64: String) -> String? {
        guard let keyData = Data(base64Encoded: keyBase64),
              let nonceData = Data(base64Encoded: nonceBase64),
              let ctAndTag = Data(base64Encoded: ciphertextAndTagBase64), ctAndTag.count >= 16,
              let nonce = try? ChaChaPoly.Nonce(data: nonceData) else { return nil }
        let ciphertext = ctAndTag.prefix(ctAndTag.count - 16)
        let tag = ctAndTag.suffix(16)
        guard let box = try? ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag),
              let plaintext = try? ChaChaPoly.open(box, using: SymmetricKey(data: keyData)) else { return nil }
        return plaintext.base64EncodedString()
    }
}

@main
struct iOSApp: App {
    init() {
        // Wire the CryptoKit bridges before any identity / signing happens.
        IosKeychainBridge.shared.keychain = KeychainIdentity()
        IosSignerBridge.shared.signer = CryptoKitSigner()
        IosDmBridge.shared.dm = CryptoKitDm()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
