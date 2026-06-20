import Foundation
import AVFoundation
import AVKit
import UIKit
import ComposeApp

class SwiftMediaPlayer: NSObject, IosMediaPlayer {
    private var player: AVPlayer
    private var statusObservation: NSKeyValueObservation?
    private var playingObservation: NSKeyValueObservation?
    private var bufferingObservation: NSKeyValueObservation?

    private var _isPlaying = false
    private var _isBuffering = true
    
    private var stateChangedCallback: ((KotlinBoolean, KotlinBoolean) -> Void)?
    private var errorCallback: ((String) -> Void)?

    init(url: URL) {
        let playerItem = AVPlayerItem(url: url)
        self.player = AVPlayer(playerItem: playerItem)
        super.init()

        statusObservation = playerItem.observe(\.status, options: [.new, .old]) { [weak self] item, _ in
            guard let self = self else { return }
            if item.status == .failed {
                let errorMsg = item.error?.localizedDescription ?? "Unknown AVPlayerItem error"
                self.errorCallback?(errorMsg)
            } else if item.status == .readyToPlay {
                self._isBuffering = false
                self.notifyState()
            }
        }

        playingObservation = player.observe(\.timeControlStatus, options: [.new, .old]) { [weak self] p, _ in
            guard let self = self else { return }
            self._isPlaying = (p.timeControlStatus == .playing)
            self._isBuffering = (p.timeControlStatus == .waitingToPlayAtSpecifiedRate)
            self.notifyState()
        }

        bufferingObservation = playerItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new, .old]) { [weak self] item, _ in
            guard let self = self else { return }
            self._isBuffering = !item.isPlaybackLikelyToKeepUp && self._isPlaying
            self.notifyState()
        }
    }

    var isPlaying: Bool { _isPlaying }

    var progressMs: Int64 {
        let t = player.currentTime()
        if t.isNumeric {
            return Int64(t.seconds * 1000)
        }
        return 0
    }

    var durationMs: Int64 {
        if let d = player.currentItem?.duration, d.isNumeric {
            return Int64(d.seconds * 1000)
        }
        return 0
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    func seekTo(positionMs: Int64) {
        let target = CMTime(seconds: Double(positionMs) / 1000.0, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func release() {
        pause()
        statusObservation?.invalidate()
        playingObservation?.invalidate()
        bufferingObservation?.invalidate()
        player.replaceCurrentItem(with: nil)
    }

    func setListener(onStateChanged: @escaping (KotlinBoolean, KotlinBoolean) -> Void, onError: @escaping (String) -> Void) {
        self.stateChangedCallback = onStateChanged
        self.errorCallback = onError
    }

    private func notifyState() {
        stateChangedCallback?(KotlinBoolean(bool: _isPlaying), KotlinBoolean(bool: _isBuffering))
    }
    
    func getPlayer() -> AVPlayer {
        return player
    }
}

class SwiftVideoPlayerFactory: NSObject, IosVideoPlayerFactory {
    func createPlayer(url: String) -> MediaPlayerResult {
        guard let parsedUrl = URL(string: url) else {
            let player = SwiftMediaPlayer(url: URL(fileURLWithPath: ""))
            return MediaPlayerResult(player: player, view: UIView())
        }
        
        let player = SwiftMediaPlayer(url: parsedUrl)
        
        let controller = AVPlayerViewController()
        controller.player = player.getPlayer()
        controller.showsPlaybackControls = true
        
        let view = controller.view!
        
        return MediaPlayerResult(player: player, view: view)
    }
}
