// FILE: app/src/main/java/com/noslop/app/NoSlopApp.kt
package com.noslop.app

import android.app.Application
import com.noslop.app.debug.Logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class NoSlopApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Register Bouncy Castle as a security provider.
        // Required for Ed25519 support on API 24-32 (Android Keystore only adds Ed25519 in API 33).
        // Insert at position 1 (highest priority) so it is preferred over the default Android provider
        // for Ed25519 operations on older devices. On API 33+, Android Keystore takes precedence
        // because we explicitly request it in CryptoService — BC is only the fallback.
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        Logger.initialize(this)
        Logger.info("APP", "NoSlop initialised. SDK=${android.os.Build.VERSION.SDK_INT}")
    }
}
