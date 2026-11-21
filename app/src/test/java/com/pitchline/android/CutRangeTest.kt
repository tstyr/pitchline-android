package com.pitchline.android

import org.junit.Assert.*
import org.junit.Test

class CutRangeTest {
    @Test fun hoursMillisecondsAndSingleSecondsRoundTripWithoutIntOverflow() {
        for (ms in listOf(0L, 1L, 59_999L, 3_600_001L, 86_400_123L, 3_600_000_001L)) {
            assertEquals(ms, parseCutTime(cutTime(ms)))
        }
        assertEquals(8_130_500L, parseCutTime("02:15:30.5"))
        assertEquals(3_910_125L, parseCutTime("65:10.125"))
        assertEquals(8_130_500L, parseCutTime("8130.500"))
        for (text in listOf("", "-1", "1:60", "1:60:00", "1.0001", "1::2", "NaN", "999999999999999999999:00:00")) {
            assertNull(text, parseCutTime(text))
        }
    }
    @Test fun zoomWindowStaysInsideLongSourceAndIncludesTheBoundary() {
        val duration = 30L * 3_600_000
        assertEquals(CutRange(0, 30_000), cutWindow(duration, 0, 30_000))
        assertEquals(CutRange(duration - 1000, duration), cutWindow(duration, duration, 1000))
        assertEquals(CutRange(0, duration), cutWindow(duration, duration / 2, Long.MAX_VALUE))
        assertEquals(CutRange(0, 400), cutWindow(400, 200, 1000))
    }
    @Test fun sampleBoundsAreExclusiveAndUseCeilingEvenAtNegativeSeekDeltas() {
        assertEquals(48L, cutFrame(1000, 48000))
        assertEquals(49L, cutFrame(1001, 48000))
        assertEquals(0L, cutFrame(-1, 48000))
        assertEquals(-48L, cutFrame(-1000, 48000))
        assertEquals(4_320_000_000L, cutFrame(25L * 3_600_000_000, 48000))
        assertEquals(22_050L, cutFrame(750_000, 44100) - cutFrame(250_000, 44100))
        assertEquals(11_776L, cutTimestampFrame(245_333, 48000))
        assertEquals(224L, cutFrame(250_000, 48000) - cutTimestampFrame(245_333, 48000))
    }
}
