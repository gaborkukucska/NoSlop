import UIKit
import AVKit
import AVFoundation
import ComposeApp

/// Full-screen clearnet video playback bridged into the Kotlin core (`IosVideoPlayer`). Presents a native
/// `AVPlayerViewController` over the app and starts playback — the same full-screen-modal pattern the QR
/// scanner uses. First slice of media playback; inline-in-feed embedding can come later.
final class VideoPlayerBridge: NSObject, IosVideoPlayer {
    func play(url: String) {
        DispatchQueue.main.async {
            guard let u = URL(string: url), let root = Self.topViewController() else { return }
            // Without an active .playback audio session, iOS keeps the player paused (first frame renders but
            // it won't start) and honours the mute switch. moviePlayback mode is the AVKit-recommended setup.
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try? AVAudioSession.sharedInstance().setActive(true)
            let player = AVPlayer(url: u)
            player.automaticallyWaitsToMinimizeStalling = false
            let vc = AVPlayerViewController()
            vc.player = player
            vc.modalPresentationStyle = .fullScreen
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
