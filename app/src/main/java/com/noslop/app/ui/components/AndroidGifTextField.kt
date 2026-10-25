// app/src/main/java/com/noslop/app/ui/components/AndroidGifTextField.kt
package com.noslop.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.widget.addTextChangedListener
import com.noslop.app.debug.Logger
import java.io.File

/**
 * Text input used by DMs, group chats and comments. It is a platform [EditText] (not a Compose
 * TextField) because only a View-based input can receive GIFs / stickers / images committed by the
 * keyboard (Gboard's rich-content API).
 *
 * KEYBOARD FIX (v0.7.0): the lint pass of 2026-10-04 switched the base class to AppCompatEditText.
 * AppCompatEditText takes its default style from AppCompat's own `editTextStyle` theme attribute,
 * which only Theme.AppCompat defines. NoSlop uses `android:Theme.DeviceDefault.NoActionBar`, so the
 * field got no EditText style at all: not focusable in touch mode, so a tap never focused it and the
 * keyboard never opened (logcat: "ThemeUtils: ... is an AppCompat widget that can only be used with a
 * Theme.AppCompat theme"). It is a platform EditText again (styled by the platform theme), and the
 * focus flags are also set explicitly so no future theme or base-class change can silently make the
 * field untappable. Pinned by GifEditTextTest.
 */
@SuppressLint("AppCompatCustomView") // AppCompatEditText needs a Theme.AppCompat theme; see above.
internal class GifEditText(context: Context) : EditText(context) {

    /** Current callbacks, refreshed on every recomposition (never the first composition's copies). */
    var onValueChange: (String) -> Unit = {}
    var onMediaAttached: (File) -> Unit = {}
    var onSend: (() -> Unit)? = null

    /** The value Compose currently holds; text changes equal to it are not reported back. */
    var currentValue: String = ""

    private var sendOnEnterApplied: Boolean? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        isCursorVisible = true
        setHintTextColor(android.graphics.Color.parseColor("#475569")) // TextMuted
        setTextColor(android.graphics.Color.parseColor("#F8FAFC")) // TextLight
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(32, 24, 32, 24)
        applySendOnEnter(false)
        isVerticalScrollBarEnabled = true

        addTextChangedListener { editable ->
            val newText = editable?.toString() ?: ""
            if (newText != currentValue) {
                onValueChange(newText)
            }
        }
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND && sendOnEnterApplied == true) {
                onSend?.invoke()
                true
            } else false
        }
    }

    /** Single-line with a Send action when enabled, multi-line otherwise. Re-applied when the setting changes. */
    fun applySendOnEnter(enabled: Boolean) {
        if (sendOnEnterApplied == enabled) return
        sendOnEnterApplied = enabled
        if (enabled) {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_NULL
        }
        // setInputType() re-applies single-line mode; restore the 4-line cap afterwards (as before).
        maxLines = 4
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        EditorInfoCompat.setContentMimeTypes(
            editorInfo,
            arrayOf("image/gif", "image/png", "image/jpeg", "video/mp4")
        )

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, _ ->
            val lacksPermission = (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            if (lacksPermission) {
                try {
                    inputContentInfo.requestPermission()
                } catch (e: Exception) {
                    Logger.error("GIF_INPUT", "Failed to get permission for rich content: ${e.message}")
                    return@OnCommitContentListener false
                }
            }

            val uri = inputContentInfo.contentUri
            val description = inputContentInfo.description
            val mimes = mutableListOf<String>()
            for (i in 0 until description.mimeTypeCount) {
                description.getMimeType(i)?.let { mimes.add(it.lowercase()) }
            }
            val crType = context.contentResolver.getType(uri)?.lowercase()
            if (crType != null) mimes.add(crType)
            val uriPath = uri.toString().lowercase()

            var isGif = mimes.any { it.contains("gif") } || uriPath.contains(".gif")
            var isPng = mimes.any { it.contains("png") } || uriPath.contains(".png")
            val isMp4 = mimes.any { it.contains("video") || it.contains("mp4") } || uriPath.contains(".mp4")
            var isJpg = mimes.any { it.contains("jpeg") || it.contains("jpg") } || uriPath.contains(".jpg") || uriPath.contains(".jpeg")

            // Magic bytes fallback check
            if (!isGif && !isPng && !isMp4 && !isJpg) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val header = ByteArray(8)
                        val read = input.read(header, 0, 8)
                        if (read >= 3 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) {
                            isGif = true
                        } else if (read >= 4 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) {
                            isPng = true
                        } else if (read >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                            isJpg = true
                        }
                    }
                } catch (e: Exception) {
                    Logger.error("GIF_INPUT", "Failed magic bytes check: ${e.message}")
                }
            }

            // Also verify magic bytes if ambiguous to prevent false non-GIF categorization
            if (!isGif) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val header = ByteArray(3)
                        val read = input.read(header, 0, 3)
                        if (read >= 3 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()) {
                            isGif = true
                        }
                    }
                } catch (_: Exception) {}
            }

            val ext = when {
                isGif -> ".gif"
                isPng -> ".png"
                isMp4 -> ".mp4"
                isJpg -> ".jpg"
                else -> ".bin"
            }

            try {
                val tempFile = File(context.cacheDir, "gboard_attach_${System.currentTimeMillis()}$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                onMediaAttached(tempFile)
            } catch (e: Exception) {
                Logger.error("GIF_INPUT", "Failed to process Gboard content: ${e.message}")
                return@OnCommitContentListener false
            } finally {
                inputContentInfo.releasePermission()
            }
            return@OnCommitContentListener true
        }
        return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
    }
}

@Composable
fun AndroidGifTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    onMediaAttached: (File) -> Unit,
    modifier: Modifier = Modifier,
    sendOnEnter: Boolean = false,
    onSend: (() -> Unit)? = null
) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp, max = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A)) // SurfaceDark
            .padding(horizontal = 4.dp),
        factory = { context -> GifEditText(context) },
        update = { view ->
            // Always hand the view this composition's callbacks: the factory runs once, so values
            // captured there (e.g. hasAttachment inside onSend) would otherwise stay stale forever.
            view.onValueChange = onValueChange
            view.onMediaAttached = onMediaAttached
            view.onSend = onSend
            view.applySendOnEnter(sendOnEnter)
            if (view.hint?.toString() != hint) view.hint = hint
            view.currentValue = value
            if (view.text.toString() != value) {
                val selectionStart = view.selectionStart
                val selectionEnd = view.selectionEnd
                view.setText(value)
                if (selectionStart <= value.length && selectionEnd <= value.length && selectionStart > -1) {
                    view.setSelection(selectionStart, selectionEnd)
                } else {
                    view.setSelection(value.length)
                }
            }
        }
    )
}
