package com.noslop.mvp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wire the app context so IdentityKeyStore can use EncryptedSharedPreferences.
        AndroidAppContext.context = applicationContext
        setContent { App() }
    }
}
