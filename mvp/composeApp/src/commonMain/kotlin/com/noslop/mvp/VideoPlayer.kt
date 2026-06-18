package com.noslop.mvp

/**
 * Play a clearnet video [url] (mp4/HLS/…). First slice of media playback (roadmap item 1): iOS presents a
 * native `AVPlayerViewController` via a Swift bridge (same pattern as [QrScanner]); Android hands off to a
 * video viewer intent; desktop is a no-op. Inline-in-feed embedding and peer-to-peer mesh media come later.
 */
expect object VideoPlayer {
    /** True if this platform can play video (iOS, Android). */
    val isAvailable: Boolean
    /** Play [url] full-screen. No-op where unavailable. */
    fun play(url: String)
}
