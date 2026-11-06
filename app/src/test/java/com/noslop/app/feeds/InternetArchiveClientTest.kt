// FILE: app/src/test/java/com/noslop/app/feeds/InternetArchiveClientTest.kt
package com.noslop.app.feeds

import com.google.gson.JsonParser
import com.noslop.app.feeds.api.InternetArchiveClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternetArchiveClientTest {

    private fun field(json: String) = JsonParser.parseString(json)

    @Test
    fun aFieldIsAStringOrTheFirstOfSeveral() {
        assertEquals("Title", InternetArchiveClient.firstText(field("\"Title\"")))
        assertEquals("First", InternetArchiveClient.firstText(field("[\"First\",\"Second\"]")))
        assertNull(InternetArchiveClient.firstText(field("[]")))
        assertNull(InternetArchiveClient.firstText(field("null")))
        assertNull(InternetArchiveClient.firstText(null))
    }

    @Test
    fun theSummarySaysWhatWasKeptAndWhyTheRestWent() {
        assertEquals(
            "Internet Archive: fetched 7 of 20 results (dropped: YouTube mirror 3, over the lookup cap 10)",
            InternetArchiveClient.fetchSummary(7, 20, mapOf("over the lookup cap" to 10, "YouTube mirror" to 3))
        )
        assertEquals("Internet Archive: fetched 20 of 20 results", InternetArchiveClient.fetchSummary(20, 20, emptyMap()))
    }
}
