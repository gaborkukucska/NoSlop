package com.noslop.app.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProxyServiceTest {

    @Test
    fun parseRange_nullOrEmpty_returnsNone() {
        assertEquals(MediaProxyService.RangeResult.None, MediaProxyService.parseRange(null, 100))
        assertEquals(MediaProxyService.RangeResult.None, MediaProxyService.parseRange("", 100))
        assertEquals(MediaProxyService.RangeResult.None, MediaProxyService.parseRange("   ", 100))
    }

    @Test
    fun parseRange_nonBytesSpec_returnsNone() {
        assertEquals(MediaProxyService.RangeResult.None, MediaProxyService.parseRange("items=0-10", 100))
    }

    @Test
    fun parseRange_zeroLengthFile_returnsUnsatisfiable() {
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=0-10", 0))
    }

    @Test
    fun parseRange_standardRange_satisfiable() {
        val res = MediaProxyService.parseRange("bytes=0-49", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(0L, s.start)
        assertEquals(49L, s.end)
    }

    @Test
    fun parseRange_withRangePrefix_parsedCorrectly() {
        val res = MediaProxyService.parseRange("Range: bytes=10-25", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(10L, s.start)
        assertEquals(25L, s.end)
    }

    @Test
    fun parseRange_openEndedRange_satisfiableToEndOfFile() {
        val res = MediaProxyService.parseRange("bytes=50-", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(50L, s.start)
        assertEquals(99L, s.end)
    }

    @Test
    fun parseRange_suffixRange_returnsLastNBytes() {
        val res = MediaProxyService.parseRange("bytes=-30", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(70L, s.start)
        assertEquals(99L, s.end)
    }

    @Test
    fun parseRange_suffixRangeExceedingFile_clampsToZero() {
        val res = MediaProxyService.parseRange("bytes=-200", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(0L, s.start)
        assertEquals(99L, s.end)
    }

    @Test
    fun parseRange_endExceedingTotalLength_clampsToLastByte() {
        val res = MediaProxyService.parseRange("bytes=80-250", 100)
        assertTrue(res is MediaProxyService.RangeResult.Satisfiable)
        val s = res as MediaProxyService.RangeResult.Satisfiable
        assertEquals(80L, s.start)
        assertEquals(99L, s.end)
    }

    @Test
    fun parseRange_startAtOrPastTotalLength_returnsUnsatisfiable() {
        // S13: bytes=100- on 100-byte file must return HTTP 416
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=100-", 100))
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=150-200", 100))
    }

    @Test
    fun parseRange_reversedRange_returnsUnsatisfiable() {
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=80-50", 100))
    }

    @Test
    fun parseRange_invalidNumbers_returnsUnsatisfiable() {
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=abc-def", 100))
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=-0", 100))
    }

    @Test
    fun parseRange_missingHyphenOrMultipleHyphens_returnsUnsatisfiable() {
        // U11: Single value without hyphen is malformed
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=5", 100))
        // U11: Multiple split components is unsupported / malformed
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=5-10-20", 100))
        // Lone hyphen without values
        assertEquals(MediaProxyService.RangeResult.Unsatisfiable, MediaProxyService.parseRange("bytes=-", 100))
    }
}
