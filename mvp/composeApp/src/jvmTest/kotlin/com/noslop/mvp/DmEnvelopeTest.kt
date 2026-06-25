package com.noslop.mvp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the DM content envelope to Android's shape (`{"content":…,"replyTo":…}`, see `DmPacketHandler` /
 * `MeshSocialRepository`): round-trips, the raw-text fallback (older iOS / non-envelope JSON), and that a
 * plain reply parses out the [DmContent.replyTo] id. Interop-critical — keep byte-compatible with Android.
 */
class DmEnvelopeTest {
    @Test
    fun wrap_then_unwrap_roundtrips_text_and_reply() {
        val c = unwrapDmContent(wrapDmContent("hi there", replyTo = "msg-123"))
        assertEquals("hi there", c.text)
        assertEquals("msg-123", c.replyTo)
    }

    @Test
    fun wrap_without_reply_has_content_and_no_reply() {
        val json = wrapDmContent("just text")
        assertTrue(json.contains("\"content\""), "envelope must carry a content field")
        assertTrue(!json.contains("replyTo"), "no replyTo field when not a reply")
        val c = unwrapDmContent(json)
        assertEquals("just text", c.text); assertNull(c.replyTo)
    }

    @Test
    fun unwrap_android_envelope_extracts_content_and_reply() {
        val c = unwrapDmContent("""{"content":"hello from android","replyTo":"abc"}""")
        assertEquals("hello from android", c.text)
        assertEquals("abc", c.replyTo)
    }

    @Test
    fun unwrap_raw_plaintext_falls_back() {
        val c = unwrapDmContent("not json at all 👋")
        assertEquals("not json at all 👋", c.text); assertNull(c.replyTo)
    }

    @Test
    fun unwrap_json_without_content_shows_raw_like_android() {
        // Valid JSON object but not our envelope → Android keeps the raw plaintext; we must match.
        val raw = """{"foo":1}"""
        val c = unwrapDmContent(raw)
        assertEquals(raw, c.text); assertNull(c.replyTo)
    }
}
