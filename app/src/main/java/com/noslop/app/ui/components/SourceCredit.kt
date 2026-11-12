// FILE: app/src/main/java/com/noslop/app/ui/components/SourceCredit.kt
package com.noslop.app.ui.components

/**
 * NOSLOP_SOURCE_CREDIT_V1 — the licence line a media slide shows for openly licensed media.
 *
 * Openverse items carry "CC BY · via flickr" as their excerpt. Wikimedia items start theirs with the
 * file's licence (LicenseShortName), then " · " and the description; only the licence is shown.
 */
internal object SourceCredit {
    private val LICENCE = Regex("""^(CC[ -]|CC0|Public domain|PD[ -]|GFDL|Attribution|No restrictions|FAL\b)""", RegexOption.IGNORE_CASE)

    fun licenceLine(apiSource: String?, excerpt: String?): String? {
        val text = excerpt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (apiSource) {
            "openverse" -> text
            "wikimedia" -> text.substringBefore(" · ").trim().takeIf { LICENCE.containsMatchIn(it) }
            else -> null
        }
    }
}
