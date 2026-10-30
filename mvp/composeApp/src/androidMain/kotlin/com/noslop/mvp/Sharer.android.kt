package com.noslop.mvp

/** Android share is handled by the native app; the KMP surface is a no-op here (UI gates on isAvailable). */
actual object Sharer {
    actual val isAvailable: Boolean = false
    actual fun share(message: String, qrPayload: String?) {}
}
