// app/src/main/java/com/noslop/app/ui/components/AndroidGifTextField.kt
package com.noslop.app.ui.components

import android.content.ClipDescription
import android.net.Uri
import android.os.Bundle
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
            .padding(horizontal = 4.dp), // Let EditText handle inner padding
        factory = { context ->
            object : EditText(context) {
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
                                Logger.error("GIF_INPUT", "Failed to get permission for rich content", e.message)
                                return@OnCommitContentListener false
                            }
                        }
                        
                        val uri = inputContentInfo.contentUri
                        // Determine extension based on MIME type rather than URI string
                        val mime = inputContentInfo.description.getMimeType(0)
                        val ext = when {
                            mime.contains("gif") -> ".gif"
                            mime.contains("png") -> ".png"
                            mime.contains("video") -> ".mp4"
                            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
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
                            Logger.error("GIF_INPUT", "Failed to process Gboard content", e.message)
                            return@OnCommitContentListener false
                        } finally {
                            inputContentInfo.releasePermission()
                        }
                        true
                    }
                    return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
                }
            }.apply {
                this.hint = hint
                this.setHintTextColor(android.graphics.Color.parseColor("#475569")) // TextMuted
                this.setTextColor(android.graphics.Color.parseColor("#F8FAFC")) // TextLight
                this.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.setPadding(32, 24, 32, 24)
                
                // Allow multiline if sendOnEnter is false
                if (sendOnEnter) {
                    this.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    this.imeOptions = EditorInfo.IME_ACTION_SEND
                    this.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) {
                            onSend?.invoke()
                            true
                        } else false
                    }
                } else {
                    this.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                
                this.maxLines = 4
                this.isVerticalScrollBarEnabled = true
                
                this.addTextChangedListener { editable ->
                    val newText = editable?.toString() ?: ""
                    if (newText != value) {
                        onValueChange(newText)
                    }
                }
            }
        },
        update = { view ->
            if (view.text.toString() != value) {
                view.setText(value)
                view.setSelection(view.length())
            }
        }
    )
}
