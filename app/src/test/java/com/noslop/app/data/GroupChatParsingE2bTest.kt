package com.noslop.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round E2b: a group's member handles and banned members. They used to be read through a Gson TypeToken,
 * whose type argument R8 strips in release builds: every read failed, so group handles were empty and
 * nobody was ever banned (G1's GROUP_QUERY check included).
 */
class GroupChatParsingE2bTest {

    @Test
    fun memberHandles_parse() {
        assertEquals(
            mapOf("pkA" to "alice", "pkB" to "bob"),
            parseGroupMemberHandles("""{"pkA":"alice","pkB":"bob"}""")
        )
    }

    @Test
    fun memberHandles_roundTripTheStoredForm() {
        val stored = com.noslop.app.util.Json.gson.toJson(mapOf("pkA" to "älice", "pkB" to "b\"ob"))
        assertEquals(mapOf("pkA" to "älice", "pkB" to "b\"ob"), parseGroupMemberHandles(stored))
    }

    @Test
    fun memberHandles_unreadableOrEmpty_isEmpty() {
        assertEquals(emptyMap<String, String>(), parseGroupMemberHandles(null))
        assertEquals(emptyMap<String, String>(), parseGroupMemberHandles(" "))
        assertEquals(emptyMap<String, String>(), parseGroupMemberHandles("{oops"))
        assertEquals(emptyMap<String, String>(), parseGroupMemberHandles("[\"not\",\"an object\"]"))
        assertEquals("non-string values are skipped", mapOf("pkA" to "alice"), parseGroupMemberHandles("""{"pkA":"alice","pkB":{"x":1},"pkC":null}"""))
    }

    @Test
    fun bannedMembers_parse() {
        assertEquals(listOf("pkA", "pkB"), parseGroupBannedMembers("""["pkA","pkB"]"""))
        assertEquals(listOf("pkA"), parseGroupBannedMembers(com.noslop.app.util.Json.gson.toJson(listOf("pkA"))))
        assertEquals("null entries dropped", listOf("pkA"), parseGroupBannedMembers("""["pkA",null]"""))
    }

    @Test
    fun bannedMembers_unreadableOrEmpty_isEmpty() {
        assertEquals(emptyList<String>(), parseGroupBannedMembers(null))
        assertEquals(emptyList<String>(), parseGroupBannedMembers(""))
        assertEquals(emptyList<String>(), parseGroupBannedMembers("{\"pkA\":true}"))
        assertEquals(emptyList<String>(), parseGroupBannedMembers("null"))
    }
}
