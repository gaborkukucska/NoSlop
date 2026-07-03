package com.noslop.mvp

/**
 * The KMP Android shell doesn't wire a photo picker (the production Android app is upstream's `app/`,
 * which has its own capture flow) — the attach button simply doesn't render here.
 */
actual object ImagePicker {
    actual val isAvailable: Boolean = false
    actual fun pick(onResult: (PickedMedia?) -> Unit) = onResult(null)
}
