package com.noslop.mvp

/** Wall-clock milliseconds — platform `System.currentTimeMillis()` / `NSDate`. Used for timestamps + the
 *  gossip rate-limit window. */
expect fun nowMillis(): Long

/** A fresh unique id (UUID) for packets/posts. */
expect fun randomId(): String
