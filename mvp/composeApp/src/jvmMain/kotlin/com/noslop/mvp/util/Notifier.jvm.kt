package com.noslop.mvp.util

/** JVM (headless HUB) stub — no notifications on the server. */
actual object Notifier {
    actual fun initialize() {}
    actual fun show(title: String, message: String, deepLinkRoute: String?) {
        println("[NOTIFY] $title: $message")
    }
}
