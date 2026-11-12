// FILE: app/src/test/java/com/noslop/app/ui/components/SourceCreditTest.kt
package com.noslop.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** NOSLOP_SOURCE_CREDIT_V1: which licence a media slide shows. */
class SourceCreditTest {

    @Test
    fun openverseShowsItsLicenceAndProvider() {
        assertEquals("CC BY · via flickr", SourceCredit.licenceLine("openverse", "CC BY · via flickr"))
    }

    @Test
    fun wikimediaShowsOnlyTheLicence() {
        assertEquals("CC BY-SA 4.0", SourceCredit.licenceLine("wikimedia", "CC BY-SA 4.0 · A heron at dawn"))
        assertEquals("Public domain", SourceCredit.licenceLine("wikimedia", "Public domain"))
    }

    @Test
    fun aWikimediaDescriptionWithoutALicenceShowsNothing() {
        assertNull(SourceCredit.licenceLine("wikimedia", "A heron at dawn · near the lake"))
    }

    @Test
    fun otherSourcesAndEmptyExcerptsShowNothing() {
        assertNull(SourceCredit.licenceLine("pexels", "CC BY"))
        assertNull(SourceCredit.licenceLine("openverse", "  "))
        assertNull(SourceCredit.licenceLine("openverse", null))
    }
}
