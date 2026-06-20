package com.noslop.mvp.util

/**
 * Platform-agnostic notification sender.
 */
expect object Notifier {
    fun initialize()
    fun show(title: String, message: String, deepLinkRoute: String? = null)
}
