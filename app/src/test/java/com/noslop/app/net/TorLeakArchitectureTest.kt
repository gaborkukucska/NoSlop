// FILE: app/src/test/java/com/noslop/app/net/TorLeakArchitectureTest.kt
package com.noslop.app.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorLeakArchitectureTest {

    private val ALLOWLISTED_WEBVIEW_FILES = setOf(
        "MediaComponents.kt",
        "VideoPlayer.kt"
    )

    @Test
    fun testNoUngatedWebViewInSrcMain() {
        val rootDir = File(".").canonicalFile
        val srcMain = File(rootDir, "src/main").let {
            if (it.exists()) it else File(rootDir, "app/src/main")
        }
        assertTrue("Could not locate src/main directory", srcMain.exists())

        val violations = mutableListOf<String>()
        srcMain.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .forEach { file ->
                val content = file.readText()
                if (content.contains("WebView(") || content.contains("android.webkit.WebView(")) {
                    if (file.name !in ALLOWLISTED_WEBVIEW_FILES) {
                        violations.add("Disallowed WebView instantiation in ${file.relativeTo(srcMain)}")
                    } else {
                        assertTrue(
                            "Allowlisted file ${file.name} must gate on useTorForClearnet",
                            content.contains("useTorForClearnet")
                        )
                    }
                }
            }

        assertTrue(
            "Found un-gated WebView instantiation in non-allowlisted files:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
