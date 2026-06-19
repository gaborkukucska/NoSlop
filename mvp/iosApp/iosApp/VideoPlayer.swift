import UIKit
import AVKit
import AVFoundation
import ComposeApp

/// Full-screen clearnet video playback bridged into the Kotlin core (`IosVideoPlayer`). Presents a native
/// `AVPlayerViewController`. Starts playback when the item reports `.readyToPlay` (KVO) rather than racing on
/// the present-completion, and surfaces a load failure as an alert so issues are diagnosable on-device.
final class VideoPlayerBridge: NSObject, IosVideoPlayer {
    private var player: AVPlayer?
    private var statusObs: NSKeyValueObservation?
    private weak var presented: AVPlayerViewController?

    func play(url: String) {
        DispatchQueue.main.async {
            guard let u = URL(string: url), let root = Self.topViewController() else { return }
            // .playback keeps the player from being paused by the default session / mute switch.
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)

            let item = AVPlayerItem(url: u)
            let player = AVPlayer(playerItem: item)
            self.player = player

            let vc = AVPlayerViewController()
            vc.player = player
            vc.modalPresentationStyle = .fullScreen
            self.presented = vc

            // Drive playback off the item's readiness (robust) and report failures on screen.
            self.statusObs = item.observe(\.status, options: [.new, .initial]) { [weak vc] it, _ in
                switch it.status {
                case .readyToPlay:
                    player.play()
                case .failed:
                    let msg = (it.error as NSError?).map { "\($0.localizedDescription) [\($0.domain) \($0.code)]" } ?? "unknown error"
                    let alert = UIAlertController(title: "Video failed to load", message: msg, preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "OK", style: .default))
                    vc?.present(alert, animated: true)
                default:
                    break
                }
            }

            root.present(vc, animated: true) { player.play() }
        }
    }

    private static func topViewController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
