// app/src/main/java/com/noslop/app/ui/components/AndroidGifTextField.kt
package com.noslop.app.ui.components

import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

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
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint, color = Color(0xFF475569)) }, // TextMuted
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp, max = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A)), // SurfaceDark
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color(0xFFF8FAFC), // TextLight
            unfocusedTextColor = Color(0xFFF8FAFC),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        keyboardOptions = if (sendOnEnter) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
        keyboardActions = KeyboardActions(onSend = { onSend?.invoke() }),
        maxLines = 4
    )
}
