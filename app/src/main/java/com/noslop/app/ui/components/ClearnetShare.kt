// FILE: app/src/main/java/com/noslop/app/ui/components/ClearnetShare.kt
package com.noslop.app.ui.components

import android.content.Context
import android.content.Intent
import com.noslop.app.util.LanguageManager
import kotlin.random.Random

/** NOSLOP_CLEARNET_SHARE_V1 — the text sent when a clearnet slide's link is shared outside NoSlop. */
object ClearnetShareText {
    val MESSAGES = listOf(
        "Check out what I found on NoSlop! Get NoSlop at https://noslop.me",
        "Found this on NoSlop, a feed with no ads and no algorithm. Get it at https://noslop.me",
        "Spotted this on NoSlop. No accounts, no tracking: https://noslop.me",
        "This turned up in my NoSlop feed. Try NoSlop at https://noslop.me",
        "Sharing from NoSlop, the private feed and mesh app: https://noslop.me"
    )

    fun canShare(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

    /** The title, the link, then a NoSlop line ([message], already translated). */
    fun compose(title: String, url: String, message: String): String =
        listOf(title.trim(), url.trim()).filter { it.isNotEmpty() }.joinToString("\n") + "\n\n" + message

    fun pickMessage(random: Random): String = MESSAGES[random.nextInt(MESSAGES.size)]
}

object ClearnetShare {
    /**
     * Opens the Android share sheet with [title], [url] and a NoSlop line picked at random.
     * Runs from a click handler, outside composition, so it translates with LanguageManager (the tr property works only inside composition).
     */
    fun send(context: Context, title: String, url: String) {
        val text = ClearnetShareText.compose(title, url, LanguageManager.translate(ClearnetShareText.pickMessage(Random.Default)))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, LanguageManager.translate("Share link")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
