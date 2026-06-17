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

@main
struct iOSApp: App {
    init() {
        // Wire the Keychain-backed identity before any identity is loaded.
        IosKeychainBridge.shared.keychain = KeychainIdentity()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
