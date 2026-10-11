package com.noslop.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D10: the status documents may only cite tests that exist.
 *
 * - Every "Tested (unit: ...)" label must start with a backticked test class, and every backticked
 *   name ending in "Test" inside it must be a class declared under app/src/test or app/src/androidTest.
 * - In the findings matrices (rows starting with `| **ID**`), every backticked name ending in "Test"
 *   in the last column must exist too.
 *
 * Whether those tests pass is checked by running them; this test stops a document from citing a test
 * that was never written, renamed or deleted.
 */
class StatusDocCitationTest {

    private val docs = listOf(
        "docs/PROJECT_STATUS.md",
        "docs/TECHNICAL_REFERENCE.md",
        "docs/WIRE_PROTOCOL_REFERENCE.md",
        "README.md"
    )
    private val testedSpan = Regex("""Tested \((?:unit|androidTest|instrumented)[^:)]*:([^)]*)\)""")
    private val backticked = Regex("`([^`]+)`")
    private val testClassName = Regex("""^[A-Za-z_][A-Za-z0-9_]*Test$""")
    private val classDecl = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)""")

    @Test
    fun statusDocuments_citeOnlyTestClassesThatExist() {
        val root = findProjectRoot()
        val status = File(root, "docs/PROJECT_STATUS.md")
        assertTrue("docs/PROJECT_STATUS.md must exist at ${status.absolutePath}", status.exists())

        val testClasses = collectTestClasses(root)
        assertTrue("no test classes found under app/src/test", testClasses.isNotEmpty())

        val problems = mutableListOf<String>()
        for (doc in docs) {
            val file = File(root, doc)
            if (!file.exists()) continue
            file.readLines().forEachIndexed { index, line ->
                val where = "$doc:${index + 1}"

                val spans = testedSpan.findAll(line).toList()
                if (spans.isEmpty() && Regex("""Tested \((?:unit|androidTest|instrumented)""").containsMatchIn(line)) {
                    problems += "$where: \"Tested (...)\" label is not closed on the same line"
                }
                for (span in spans) {
                    val names = backticked.findAll(span.groupValues[1]).map { it.groupValues[1].trim() }.toList()
                    val first = names.firstOrNull()
                    if (first == null || !testClassName.matches(first)) {
                        problems += "$where: \"Tested (...)\" must start with a backticked test class, found ${first ?: "none"}"
                    }
                    names.filter { testClassName.matches(it) && it !in testClasses }.forEach {
                        problems += "$where: `$it` is not a test class under app/src/test or app/src/androidTest"
                    }
                }

                if (line.trimStart().startsWith("| **")) {
                    val cells = line.trim().removePrefix("|").removeSuffix("|").split("|")
                    if (cells.size >= 5) {
                        backticked.findAll(cells.last()).map { it.groupValues[1].trim() }
                            .filter { testClassName.matches(it) && it !in testClasses }
                            .forEach { problems += "$where: matrix cites `$it`, which is not a test class" }
                    }
                }
            }
        }

        assertTrue(
            "Status documents cite ${problems.size} missing or malformed test reference(s):\n" +
                problems.joinToString("\n") { "  - $it" },
            problems.isEmpty()
        )
    }

    private fun collectTestClasses(root: File): Set<String> {
        val names = mutableSetOf<String>()
        for (dir in listOf("app/src/test", "app/src/androidTest")) {
            File(root, dir).takeIf { it.exists() }?.walkTopDown()
                ?.filter { it.isFile && it.extension == "kt" }
                ?.forEach { file ->
                    classDecl.findAll(file.readText()).forEach { names += it.groupValues[1] }
                }
        }
        return names
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "docs/PROJECT_STATUS.md").exists() && File(dir, "app/src").exists()) return dir
            dir = dir.parentFile
        }
        return File(".").canonicalFile
    }
}
