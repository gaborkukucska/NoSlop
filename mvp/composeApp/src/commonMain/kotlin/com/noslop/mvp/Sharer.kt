package com.noslop.mvp

/**
 * Share the user's identity via the platform share sheet — friendlier than dumping raw JSON to the clipboard.
 * iOS presents UIActivityViewController with a short message + a QR image generated from [qrPayload] (so the
 * recipient can scan it, or paste the message text into Add Contact → Paste). Other platforms are no-ops.
 */
expect object Sharer {
    val isAvailable: Boolean
    fun share(message: String, qrPayload: String?)
}
