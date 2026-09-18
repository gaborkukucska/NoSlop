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
import java.util.Locale

object LanguageManager {
    private var translations: Map<String, String> = emptyMap()
    private val _currentLanguage = MutableStateFlow("en")
    val currentLanguage: StateFlow<String> = _currentLanguage
    private lateinit var appContext: Context

    private val _availableLanguages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableLanguages: StateFlow<List<Pair<String, String>>> = _availableLanguages

    private val WELL_KNOWN_LANGUAGES = mapOf(
        "en" to "English",
        "hu" to "Magyar",
        "es" to "Español",
        "de" to "Deutsch",
        "fr" to "Français",
        "it" to "Italiano",
        "pt" to "Português",
        "ru" to "Русский",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ar" to "العربية",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "uk" to "Українська",
        "cs" to "Čeština",
        "sv" to "Svenska",
        "ro" to "Română",
        "el" to "Ελληνικά",
        "vi" to "Tiếng Việt",
        "hi" to "हिन्दी"
    )

    val supportedLanguages: List<Pair<String, String>>
        get() {
            if (_availableLanguages.value.isEmpty() && ::appContext.isInitialized) {
                refreshAvailableLanguages()
            }
            return _availableLanguages.value.ifEmpty { listOf("en" to "English") }
        }

    fun init(context: Context, defaultLang: String) {
        appContext = context.applicationContext
        refreshAvailableLanguages()
        loadLanguage(defaultLang)
    }

    fun refreshAvailableLanguages() {
        if (!::appContext.isInitialized) return
        try {
            val assetFiles = appContext.assets.list("languages") ?: emptyArray()
            val langs = assetFiles
                .filter { it.startsWith("content_") && it.endsWith(".json") }
                .map { fileName ->
                    val code = fileName.removePrefix("content_").removeSuffix(".json")
                    val displayName = getLanguageDisplayName(code)
                    code to displayName
                }
                // English first, then alphabetical by display name
                .sortedWith(compareBy({ it.first != "en" }, { it.second }))

            _availableLanguages.value = if (langs.isNotEmpty()) langs else listOf("en" to "English")
            Logger.info("LANG", "Dynamically discovered ${langs.size} language files: ${langs.map { it.first }}")
        } catch (e: Exception) {
            Logger.error("LANG", "Failed to list available languages from assets: ${e.message}")
            _availableLanguages.value = listOf("en" to "English")
        }
    }

    private fun getLanguageDisplayName(code: String): String {
        return WELL_KNOWN_LANGUAGES[code] ?: run {
            try {
                val loc = Locale.forLanguageTag(code)
                val name = loc.getDisplayLanguage(loc)
                if (name.isNotBlank() && name != code) {
                    name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                } else {
                    code.uppercase()
                }
            } catch (_: Exception) {
                code.uppercase()
            }
        }
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
        val lang by LanguageManager.currentLanguage.collectAsState()
        return remember(lang, this) { LanguageManager.translate(this) }
    }
