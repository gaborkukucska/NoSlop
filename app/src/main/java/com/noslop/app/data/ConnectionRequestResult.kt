// FILE: app/src/main/java/com/noslop/app/data/ConnectionRequestResult.kt
package com.noslop.app.data

/**
 * What a connection request did (NOSLOP_DM_PATH_NO_SILENT_EXIT_V1, docs/TECHNICAL_REFERENCE.md §16.55).
 * [ok] is what `sendConnectionRequest` has always returned as a Boolean.
 */
enum class ConnectionRequestResult(val ok: Boolean) {
    /** A CONNECTION_REQUEST was handed to the transport (sending itself is asynchronous). */
    SENT(true),

    /** They had already asked us, so asking back accepted their request. */
    ACCEPTED_THEIRS(true),

    /** They had asked us, but accepting was refused; acceptConnectionRequest logs why. */
    ACCEPT_REFUSED(false),

    /** The contact is blocked on this device. Nothing was sent. */
    BLOCKED(false),

    /** The request must come from our creator identity, which is no longer on this device. Nothing was stored or sent. */
    NO_CREATOR_IDENTITY(false),

    /** This device has no personal identity. Nothing was stored or sent. */
    NO_PERSONAL_IDENTITY(false)
}
