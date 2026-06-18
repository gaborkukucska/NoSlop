import Foundation
import Tor
import ComposeApp

/// Embedded Tor for the iOS leaf (ADR-009). Starts a `TorThread`, connects a `TorController` over a cookie-
/// authed control socket, watches for "Bootstrapped 100%", and then exposes a local SOCKS port. The shared
/// `MeshClient` dials the hub's `.onion` through `SocksProxy(127.0.0.1, socksPort())`. Bridged into the
/// Kotlin core via `IosTor` (injected at app launch), mirroring the CryptoKit bridges.
final class TorManager: IosTor {
    private var thread: TorThread?
    private var controller: TorController?
    private var ready = false
    private let socks = 39050 // fixed local SOCKS port the app dials through

    private lazy var dataDir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("tor", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// Idempotent: launch Tor once, then drive the controller handshake + bootstrap watch.
    func start() {
        if thread != nil { return }

        let config = TorConfiguration()
        config.cookieAuthentication = true
        config.dataDirectory = dataDir
        config.controlSocket = dataDir.appendingPathComponent("control.sock")
        config.ignoreMissingTorrc = true
        config.options = [
            "SocksPort": "127.0.0.1:\(socks)",
            "AvoidDiskWrites": "1",
            "DNSPort": "0",
        ]

        let t = TorThread(configuration: config)
        thread = t
        t.start()

        // Tor needs a moment to create the control socket + auth cookie; connect with retries.
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 1.0) {
            self.connectController(config: config, attempt: 0)
        }
    }

    private func connectController(config: TorConfiguration, attempt: Int) {
        if ready || attempt > 40 { return }
        guard let socket = config.controlSocket else { return }
        let controller = TorController(socketURL: socket)
        do { try controller.connect() } catch {
            return retry(config: config, attempt: attempt)
        }
        guard let cookie = config.cookie else {
            return retry(config: config, attempt: attempt)
        }
        self.controller = controller
        controller.authenticate(with: cookie) { success, _ in
            guard success else { return }
            controller.addObserver(forStatusEvents: { (type, _, action, args) -> Bool in
                if type == "STATUS_CLIENT", action == "BOOTSTRAP",
                   let progress = args?["PROGRESS"], (Int(progress) ?? 0) >= 100 {
                    self.ready = true
                }
                return false
            })
            // If we attached after bootstrap already finished, catch up with a one-shot query.
            controller.getInfoForKeys(["status/bootstrap-phase"]) { values in
                if values.first?.contains("PROGRESS=100") == true { self.ready = true }
            }
        }
    }

    private func retry(config: TorConfiguration, attempt: Int) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) {
            self.connectController(config: config, attempt: attempt + 1)
        }
    }

    func socksPort() -> Int32 { ready ? Int32(socks) : 0 }
}
