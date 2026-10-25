package com.noslop.app.feeds.api

import com.google.gson.JsonObject

/**
 * P4-5: Shared JSON parsing and string utilities across API clients.
 */
fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

fun stripHtml(html: String): String {
    return try {
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
    } catch (_: Exception) {
        html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
