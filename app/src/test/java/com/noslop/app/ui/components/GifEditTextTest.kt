// FILE: app/src/test/java/com/noslop/app/ui/components/GifEditTextTest.kt
package com.noslop.app.ui.components

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.inputmethod.EditorInfoCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for "the keyboard does not open in DMs or comments".
 *
 * The chat / comment input is [GifEditText]. When its base class became AppCompatEditText under
 * NoSlop's platform theme (android:Theme.DeviceDefault.NoActionBar, the parent of Theme.NoSlop),
 * it lost the EditText style: not focusable in touch mode, so a tap never focused it and the
 * keyboard was never requested. These tests build the real view under that same platform theme and
 * deliver a real tap to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GifEditTextTest {

    private lateinit var activity: Activity
    private lateinit var themed: Context

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Theme.NoSlop is an empty style whose parent is exactly this platform theme.
        themed = ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
    }

    /** Another focusable view holds focus first, so the test sees the tap move focus to the field. */
    private lateinit var focusHolder: android.view.View

    private fun attachedField(): GifEditText {
        val field = GifEditText(themed)
        focusHolder = android.view.View(themed).apply { isFocusable = true; isFocusableInTouchMode = true }
        val root = FrameLayout(themed)
        root.addView(focusHolder, FrameLayout.LayoutParams(100, 100).apply { topMargin = 1000 })
        root.addView(field, FrameLayout.LayoutParams(600, 150))
        activity.setContentView(root)
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 1920)
        return field
    }

    private fun tap(field: GifEditText) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, 50f, 50f, 0)
        field.dispatchTouchEvent(down)
        field.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun field_isAnEditorThatTouchCanFocus_underTheAppTheme() {
        val field = GifEditText(themed)
        assertTrue("focusable", field.isFocusable)
        assertTrue("focusable in touch mode — without this a tap never focuses the field", field.isFocusableInTouchMode)
        assertTrue("clickable", field.isClickable)
        assertTrue("reports itself as a text editor to the IME", field.onCheckIsTextEditor())
    }

    @Test
    fun tappingTheField_focusesIt() {
        val field = attachedField()
        assertTrue(focusHolder.requestFocus())
        assertFalse(field.isFocused)
        tap(field)
        assertTrue("a tap must focus the field so the keyboard opens", field.isFocused)
    }

    @Test
    fun inputConnection_advertisesKeyboardGifsAndImages() {
        val field = GifEditText(themed)
        val info = EditorInfo()
        assertNotNull(field.onCreateInputConnection(info))
        val mimes = EditorInfoCompat.getContentMimeTypes(info).toList()
        assertTrue(mimes.containsAll(listOf("image/gif", "image/png", "image/jpeg", "video/mp4")))
    }

    @Test
    fun sendOnEnter_usesTheLatestCallback_andCanBeToggled() {
        val field = GifEditText(themed)
        var sent = ""
        field.onSend = { sent = "stale" }
        field.applySendOnEnter(true)
        assertEquals(EditorInfo.IME_ACTION_SEND, field.imeOptions)
        field.onSend = { sent = "current" } // what a recomposition does
        field.onEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("current", sent)

        field.applySendOnEnter(false)
        sent = ""
        field.onEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals("multi-line mode never sends on Enter", "", sent)
        assertTrue(field.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
    }

    @Test
    fun typing_isReported_butEchoesOfTheComposeValueAreNot() {
        val field = GifEditText(themed)
        val reported = mutableListOf<String>()
        field.onValueChange = { reported += it }

        field.setText("hi")                 // the user typed
        field.currentValue = "hi"
        field.setText("hi")                 // Compose pushing the same value back
        field.currentValue = ""
        field.setText("")                   // Compose clearing after send

        assertEquals(listOf("hi"), reported)
    }
}
