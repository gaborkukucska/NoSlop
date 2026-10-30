package com.noslop.mvp

/** Desktop/bridge build has no share sheet — no-op (the UI gates on isAvailable). */
actual object Sharer {
    actual val isAvailable: Boolean = false
    actual fun share(message: String, qrPayload: String?) {}
}
