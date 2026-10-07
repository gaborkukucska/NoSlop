package com.noslop.app.util

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

class TranslationParityTest {

    @Test
    fun allCodeTranslationKeys_existInEnglishLanguageAsset() {
        val projectRoot = findProjectRoot()
        val srcDir = File(projectRoot, "app/src/main/java")
        val enAsset = File(projectRoot, "app/src/main/assets/languages/content_en.json")
        assertTrue("content_en.json must exist at ${enAsset.absolutePath}", enAsset.exists())

        val enJson = JsonParser.parseString(enAsset.readText()).asJsonObject
        val enKeys = enJson.keySet().toSet()

        val pattern = Pattern.compile(""""([^"\\]*(?:\\.[^"\\]*)*)"\.(?:tr\b)|LanguageManager\.translate\("([^"\\]*(?:\\.[^"\\]*)*)"\)""")
        val codeKeys = mutableSetOf<String>()

        srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val key = matcher.group(1) ?: matcher.group(2)
                if (!key.isNullOrBlank()) {
                    codeKeys.add(key)
                }
            }
        }

        val missingInEn = codeKeys.filter { it !in enKeys }
        assertTrue(
            "Missing ${missingInEn.size} translation key(s) in content_en.json:\n" +
                missingInEn.joinToString("\n") { "  - $it" },
            missingInEn.isEmpty()
        )
    }

    @Test
    fun otherLanguageAssets_areScannedForParityReporting() {
        val projectRoot = findProjectRoot()
        val langDir = File(projectRoot, "app/src/main/assets/languages")
        val enAsset = File(langDir, "content_en.json")
        if (!enAsset.exists()) return

        val enJson = JsonParser.parseString(enAsset.readText()).asJsonObject
        val enKeys = enJson.keySet().toSet()

        val otherFiles = langDir.listFiles { _, name -> name.startsWith("content_") && name != "content_en.json" && name.endsWith(".json") }
            ?: emptyArray()

        println("Scanned ${otherFiles.size} secondary language files against ${enKeys.size} English keys.")
        for (f in otherFiles.sortedBy { it.name }) {
            val json = JsonParser.parseString(f.readText()).asJsonObject
            val keys = json.keySet().toSet()
            val coverage = (keys.intersect(enKeys).size * 100) / enKeys.size.coerceAtLeast(1)
            println("Language file ${f.name}: $coverage% coverage (${keys.size}/${enKeys.size} keys)")
        }
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/assets/languages/content_en.json").exists()) {
                return dir
            }
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
