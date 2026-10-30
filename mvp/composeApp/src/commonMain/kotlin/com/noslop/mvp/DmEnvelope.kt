package com.noslop.mvp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A decoded DM payload: the visible [text] plus an optional [replyTo] message id (Android's envelope). */
data class DmContent(val text: String, val replyTo: String? = null)

/**
 * Wrap DM plaintext in Android's JSON envelope: `{"content": text [, "replyTo": id]}`. Android **always**
 * wraps outgoing DMs this way ([app] `MeshSocialRepository`/`DmPacketHandler`) and parses it back
 * order-independently; an unwrapped raw string still works via its not-JSON fallback. We match it so text
 * (and message replies — `replyTo`) interoperate both directions. Media (`"media"`) is a later addition.
 */
fun wrapDmContent(text: String, replyTo: String? = null): String =
    WireJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("content", text)
        if (replyTo != null) put("replyTo", replyTo)
    })

/**
 * Inverse of [wrapDmContent]: pull [DmContent] out of an incoming DM. Falls back to the raw string when it
 * isn't our envelope (older iOS builds sent raw text; Android does the same fallback) — so an unwrapped or
 * non-`content` JSON body shows verbatim instead of leaking braces into the chat, matching Android exactly.
 */
fun unwrapDmContent(plaintext: String): DmContent =
    try {
        val obj = WireJson.parseToJsonElement(plaintext).jsonObject
        val content = obj["content"]?.jsonPrimitive?.contentOrNull
        if (content == null) DmContent(plaintext)   // valid JSON but not our envelope → show raw (Android parity)
        else DmContent(content, obj["replyTo"]?.jsonPrimitive?.contentOrNull)
    } catch (_: Exception) {
        DmContent(plaintext)                         // not JSON at all → raw plaintext
    }
