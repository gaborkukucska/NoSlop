// FILE: app/src/main/java/com/noslop/app/util/MarkdownUtils.kt
package com.noslop.app.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.noslop.app.ui.theme.AccentGreen

object MarkdownUtils {

    /**
     * Parses simple Markdown formatting (bold, italic, code, headings, lists, links)
     * into an [AnnotatedString] for Compose Text components.
     */
    fun parseMarkdown(markdownText: String): AnnotatedString {
        if (markdownText.isBlank()) return AnnotatedString("")

        return buildAnnotatedString {
            val lines = markdownText.lines()
            lines.forEachIndexed { index, line ->
                val trimmedLine = line.trim()

                // Headings: #, ##, ###
                val (prefix, headingLevel) = when {
                    trimmedLine.startsWith("# ") -> Pair("# ", 1)
                    trimmedLine.startsWith("## ") -> Pair("## ", 2)
                    trimmedLine.startsWith("### ") -> Pair("### ", 3)
                    else -> Pair("", 0)
                }

                var textToProcess = if (headingLevel > 0) trimmedLine.removePrefix(prefix) else line

                // Bullets: - item or * item
                if (headingLevel == 0 && (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* "))) {
                    append("• ")
                    textToProcess = trimmedLine.substring(2)
                }

                val style = when (headingLevel) {
                    1 -> SpanStyle(fontWeight = FontWeight.Bold)
                    2 -> SpanStyle(fontWeight = FontWeight.Bold)
                    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }

                if (style != null) {
                    pushStyle(style)
                }

                // Process inline markdown (bold, italic, code, links)
                processInlineMarkdown(textToProcess)

                if (style != null) {
                    pop()
                }

                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private fun AnnotatedString.Builder.processInlineMarkdown(text: String) {
        var i = 0
        val len = text.length

        while (i < len) {
            // Bold **text**
            if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            // Italic *text*
            if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end != -1 && (end == len - 1 || text[end + 1] != '*')) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            // Inline code `code`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }

            // Links [title](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket != -1 && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen != -1) {
                        val title = text.substring(i + 1, closeBracket)
                        withStyle(SpanStyle(color = AccentGreen, textDecoration = TextDecoration.Underline)) {
                            append(title)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            append(text[i])
            i++
        }
    }
}
