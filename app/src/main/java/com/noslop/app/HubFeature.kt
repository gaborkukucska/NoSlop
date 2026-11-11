// FILE: app/src/main/java/com/noslop/app/HubFeature.kt
package com.noslop.app

/**
 * NOSLOP_HUB_BUILD_SWITCH_V1 — whether this build includes the HAI-Net HUB page and its functions.
 *
 * Off unless the build sets NOSLOP_ENABLE_HUB=true (environment variable or Gradle property), which
 * becomes BuildConfig.ENABLE_HUB. When off, [NoSlopRepository.getAppSetting] answers null for
 * [STATUS_KEY], so every Hub path acts as if no Hub were linked, and the UI leaves the HUB out.
 * Tests may set [enabled]; the app never changes it at run time.
 */
object HubFeature {
    const val STATUS_KEY = "hub_deployment_status"

    @Volatile
    var enabled: Boolean = BuildConfig.ENABLE_HUB
}
