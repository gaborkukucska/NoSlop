package com.noslop.app.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noslop.app.debug.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LanguageManager {
    private var translations: Map<String, String> = emptyMap()
    private val _currentLanguage = MutableStateFlow("en")
    val currentLanguage: StateFlow<String> = _currentLanguage
    private lateinit var appContext: Context

    fun init(context: Context, defaultLang: String) {
        appContext = context.applicationContext
        loadLanguage(defaultLang)
    }

    fun loadLanguage(langCode: String) {
        try {
            val fileName = "languages/content_$langCode.json"
            val jsonString = appContext.assets.open(fileName).bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, String>>() {}.type
            translations = Gson().fromJson(jsonString, type) ?: emptyMap()
            _currentLanguage.value = langCode
            Logger.info("LANG", "Loaded language: $langCode with ${translations.size} keys")
        } catch (e: Exception) {
            Logger.error("LANG", "Failed to load language $langCode: ${e.message}")
            if (langCode != "en") {
                loadLanguage("en")
            }
        }
    }

    fun translate(key: String): String {
        return translations[key] ?: key
    }
}

val String.tr: String
    @Composable
    get() {
        // --- NOSLOP_I18N_RECOMPOSE_V1 ---
        // collectAsState() used to be called and its result discarded. Compose
        // only invalidates a composable that READS a state value, so switching
        // language left every already-composed screen in the old language.
        val lang by LanguageManager.currentLanguage.collectAsState()
        return remember(lang, this) { LanguageManager.translate(this) }
    }
