package com.noslop.mvp

/** Swift→Kotlin bridge for the iOS share sheet (UIActivityViewController + a CoreImage-generated QR). */
interface IosSharer {
    fun share(message: String, qrPayload: String?)
}

object IosSharerBridge {
    var sharer: IosSharer? = null
}

actual object Sharer {
    actual val isAvailable: Boolean get() = IosSharerBridge.sharer != null
    actual fun share(message: String, qrPayload: String?) {
        IosSharerBridge.sharer?.share(message, qrPayload)
    }
}
