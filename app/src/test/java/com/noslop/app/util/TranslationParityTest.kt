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

    /**
     * D10: report (never fail) how many English keys each other language is missing. Missing keys
     * fall back to English at runtime. The report is printed and also written to
     * app/build/reports/translation-parity.txt. Unreadable JSON still fails the test.
     */
    @Test
    fun otherLanguageAssets_reportMissingKeysPerLanguage() {
        val projectRoot = findProjectRoot()
        val langDir = File(projectRoot, "app/src/main/assets/languages")
        val enAsset = File(langDir, "content_en.json")
        assertTrue("content_en.json must exist at ${enAsset.absolutePath}", enAsset.exists())

        val enKeys = JsonParser.parseString(enAsset.readText()).asJsonObject.keySet().toSet()
        val otherFiles = langDir.listFiles { _, name -> name.startsWith("content_") && name != "content_en.json" && name.endsWith(".json") }
            ?: emptyArray()

        val report = StringBuilder()
        report.appendLine("Translation parity: ${otherFiles.size} languages against ${enKeys.size} English keys (missing keys fall back to English).")
        for (f in otherFiles.sortedBy { it.name }) {
            val keys = JsonParser.parseString(f.readText()).asJsonObject.keySet().toSet()
            val missing = (enKeys - keys).sorted()
            val extra = (keys - enKeys).sorted()
            val coverage = ((enKeys.size - missing.size) * 100) / enKeys.size.coerceAtLeast(1)
            val code = f.name.removePrefix("content_").removeSuffix(".json")
            report.appendLine("  $code: ${missing.size} missing, ${extra.size} not in English, $coverage% covered")
            missing.take(10).forEach { report.appendLine("      - $it") }
            if (missing.size > 10) report.appendLine("      ... and ${missing.size - 10} more")
        }
        println(report)
        try {
            val out = File(projectRoot, "app/build/reports/translation-parity.txt")
            out.parentFile?.mkdirs()
            out.writeText(report.toString())
        } catch (e: Exception) {
            println("Could not write translation-parity.txt: ${e.message}")
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
