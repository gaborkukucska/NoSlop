import SwiftUI
import CryptoKit
import ComposeApp

/// Real iOS Ed25519 keypair generation via CryptoKit, bridged into the shared Kotlin core.
/// `Curve25519.Signing` is Ed25519 (EdDSA); `publicKey.rawRepresentation` is the 32-byte public key
/// of a real keypair (with a backing private key).
class CryptoKitEd25519: Ed25519KeyProvider {
    func publicKeyBase64() -> String {
        let key = Curve25519.Signing.PrivateKey()
        return key.publicKey.rawRepresentation.base64EncodedString()
    }
}

@main
struct iOSApp: App {
    init() {
        // Inject the CryptoKit keygen before any identity is generated.
        IosCrypto.shared.provider = CryptoKitEd25519()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
