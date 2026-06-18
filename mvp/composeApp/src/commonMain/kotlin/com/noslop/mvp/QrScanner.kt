package com.noslop.mvp

/**
 * Scan a hub's pairing QR (a [MeshInvite] URI) with the camera. The non-tech-user setup: point the phone at
 * the code the HUB shows and the app fills in the onion/host + Tor toggle and connects — no typing. iOS uses
 * AVFoundation via a Swift bridge; Android/desktop are no-ops for now (manual host/port still works there).
 */
expect object QrScanner {
    /** True if this platform can scan (iOS). */
    val isAvailable: Boolean
    /** Open the camera; [onResult] gets the decoded string, or null if cancelled / no camera. */
    fun scan(onResult: (String?) -> Unit)
}
