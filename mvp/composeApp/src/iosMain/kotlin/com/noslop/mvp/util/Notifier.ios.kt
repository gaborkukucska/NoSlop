package com.noslop.mvp.util

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.Foundation.NSUUID

/**
 * iOS implementation of Notifier using UNUserNotificationCenter.
 */
actual object Notifier {

    actual fun initialize() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        center.requestAuthorizationWithOptions(options) { granted, error ->
            if (error != null) {
                println("Notification authorization error: ${error.localizedDescription}")
            } else {
                println("Notification authorization granted: $granted")
            }
        }
    }

    actual fun show(title: String, message: String, deepLinkRoute: String?) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(message)
        }
        
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString(),
            content = content,
            trigger = null // Deliver immediately
        )
        
        center.addNotificationRequest(request) { error ->
            if (error != null) {
                println("Failed to deliver notification: ${error.localizedDescription}")
            }
        }
    }
}
