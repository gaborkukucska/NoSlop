package com.noslop.mvp

/**
 * Swift→Kotlin bridge for the photo picker (PHPickerViewController is UIKit — unreachable from
 * Kotlin/Native cinterop the way our other UI bridges are). The Swift side copies the picked asset to a
 * readable temp file, scales a ~90 px JPEG thumbnail, and calls back with (path, mimeType, size,
 * thumbnailB64) — or a nil path on cancel/failure.
 */
interface IosImagePicker {
    fun pick(onResult: (path: String?, mimeType: String?, size: Long, thumbnailB64: String?) -> Unit)
}

/** Holds the Swift-supplied [IosImagePicker]; set once by the iOS app at startup (see iOSApp.swift). */
object IosImagePickerBridge {
    var picker: IosImagePicker? = null
}

actual object ImagePicker {
    actual val isAvailable: Boolean get() = IosImagePickerBridge.picker != null

    actual fun pick(onResult: (PickedMedia?) -> Unit) {
        val picker = IosImagePickerBridge.picker ?: return onResult(null)
        picker.pick { path, mime, size, thumb ->
            onResult(
                if (path == null) null
                else PickedMedia(path = path, mimeType = mime ?: "image/jpeg", size = size, thumbnailB64 = thumb),
            )
        }
    }
}
