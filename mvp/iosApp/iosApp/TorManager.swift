import Foundation
import Tor
import ComposeApp

/// Embedded Tor for the iOS leaf (ADR-009). Launches a `TorThread`, connects a cookie-authed `TorController`
/// over Tor's **auto control port** (file-based — avoids the fragile unix-socket-path issue on iOS), watches
/// for "Bootstrapped 100%", and exposes a local SOCKS port. The shared `MeshClient` dials the hub's `.onion`
/// through `SocksProxy(127.0.0.1, socksPort())`. `status()` surfaces a human-readable stage for the UI so a
/// stuck bootstrap is diagnosable. Bridged into Kotlin via `IosTor` (injected at launch).
final class TorManager: IosTor {
    private var thread: TorThread?
    private var controller: TorController?
    private var ready = false
    private var progress = 0
    private var statusText = "idle"
    private let socks = 39050

    private lazy var dataDir: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = base.appendingPathComponent("tor", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()
    private var logFile: URL { dataDir.appendingPathComponent("tor.log") }

    /// Tor's own last log line — the ground truth for what Tor is actually doing.
    private func lastTorLog() -> String {
        guard let s = try? String(contentsOf: logFile, encoding: .utf8) else { return "no tor.log" }
        let lines = s.split(separator: "\n")
        return lines.suffix(1).first.map(String.init) ?? "tor.log empty"
    }

    func start() {
        if thread != nil { return }
        statusText = "starting Tor…"

        let config = TorConfiguration()
        config.cookieAuthentication = true
        config.autoControlPort = true            // tor picks a free control port + writes it to controlPortFile
        config.dataDirectory = dataDir
        config.ignoreMissingTorrc = true
        try? FileManager.default.removeItem(at: logFile) // fresh log each start
        config.options = [
            "SocksPort": "127.0.0.1:\(socks)",
            "AvoidDiskWrites": "1",
            "DNSPort": "0",
            "Log": "notice file \(logFile.path)",
        ]

        let t = TorThread(configuration: config)
        thread = t
        t.start()

        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 1.0) {
            self.connectController(config: config, attempt: 0)
        }
    }

    private func connectController(config: TorConfiguration, attempt: Int) {
        if ready { return }
        if attempt > 150 { statusText = "control connect gave up (\(statusText))"; return } // ~75s of retries

        // Both the control port AND the auth cookie must exist before we connect — connecting early then
        // reconnecting (the old behaviour) raced and got "connection refused". This is best-effort: it only
        // drives the bootstrap-% display. Readiness/connection no longer depends on it (we retry SOCKS).
        guard let portFile = config.controlPortFile,
              FileManager.default.fileExists(atPath: portFile.path),
              let cookie = config.cookie else {
            statusText = "tor starting…"
            return retry(config: config, attempt: attempt)
        }
        let controller = TorController(controlPortFile: portFile)
        do {
            try controller.connect()
        } catch {
            statusText = "tor starting…"
            return retry(config: config, attempt: attempt)
        }
        self.controller = controller
        statusText = "authenticating…"
        controller.authenticate(with: cookie) { success, error in
            if !success {
                self.statusText = "control auth failed"
                return
            }
            self.statusText = "bootstrapping… 0%"
            controller.addObserver(forStatusEvents: { (type, _, action, args) -> Bool in
                if type == "STATUS_CLIENT", action == "BOOTSTRAP", let p = args?["PROGRESS"], let pi = Int(p) {
                    self.progress = pi
                    self.statusText = pi >= 100 ? "ready" : "bootstrapping… \(pi)%"
                    if pi >= 100 { self.ready = true }
                }
                return false
            })
            controller.getInfoForKeys(["status/bootstrap-phase"]) { values in
                if let v = values.first, let r = v.range(of: "PROGRESS="),
                   let pi = Int(v[r.upperBound...].prefix(while: { $0.isNumber })) {
                    self.progress = pi
                    if pi >= 100 { self.ready = true; self.statusText = "ready" }
                }
            }
        }
    }

    private func retry(config: TorConfiguration, attempt: Int) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.5) {
            self.connectController(config: config, attempt: attempt + 1)
        }
    }

    // SOCKS port is available as soon as Tor is launched; Tor's SOCKS rejects requests until it's
    // bootstrapped, so the app just retries the connection rather than gating on a separate "ready" signal.
    func socksPort() -> Int32 { thread != nil ? Int32(socks) : 0 }
    func bootstrapProgress() -> Int32 { Int32(progress) }
    func status() -> String { ready ? "ready" : "\(statusText) — tor: \(lastTorLog())" }
}
