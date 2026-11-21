package com.pitchline.android

import org.junit.Assert.*
import org.junit.Test

class CutTimelineTest {
    @Test fun deletingMiddlePieceRipplesAndMapsBackToOriginalAudio() {
        val cut = CutEdit(listOf(CutRange(0, 120_000))).seek(15_000).split().seek(45_000).split()
        assertEquals(listOf(CutRange(0, 15_000), CutRange(15_000, 45_000), CutRange(45_000, 120_000)), cut.clips)
        val deleted = cut.seek(25_000, select = true).deleteSelected()
        assertEquals(90_000L, deleted.durationMs)
        assertEquals(15_000L, deleted.positionMs)
        assertEquals(CutPiece(CutRange(45_000, 120_000), 15_000), deleted.locate(15_000))
        assertEquals(listOf(CutPiece(CutRange(14_000, 15_000), 14_000), CutPiece(CutRange(45_000, 46_000), 15_000)),
            deleted.slices(CutRange(14_000, 16_000)))
        assertEquals(90_000L, deleted.seek(Long.MAX_VALUE).positionMs)
    }
    @Test fun splitAtEdgesIsIgnoredAndDeletingEverythingIsRecoverableFromSnapshot() {
        val original = CutEdit(listOf(CutRange(0, 1000)))
        assertEquals(original, original.split())
        assertEquals(original.seek(1000), original.seek(1000).split())
        val empty = original.deleteSelected()
        assertTrue(empty.clips.isEmpty())
        assertEquals(0L, empty.durationMs)
        assertNull(empty.locate(0))
        assertFalse(empty.canSplit)
        assertEquals(0L, empty.seek(1000).positionMs)
        assertEquals(listOf(CutRange(0, 1000)), original.clips)
    }
    @Test fun hourScalePinchKeepsFocalTimeAndPanningClampsAtBothEnds() {
        val duration = 6L * 3_600_000
        val wide = CutRange(5L * 3_600_000, duration)
        val zoomed = transformCutWindow(duration, wide, 0.0, 4.0, 0.75)
        assertEquals(900_000L, zoomed.lengthMs)
        assertEquals(wide.startMs + wide.lengthMs * 0.75, zoomed.startMs + zoomed.lengthMs * 0.75, 0.0)
        assertEquals(0L, transformCutWindow(duration, zoomed, 100.0, 1.0, 0.5).startMs)
        assertEquals(duration, transformCutWindow(duration, zoomed, -100.0, 1.0, 0.5).endMs)
        assertEquals(250L, transformCutWindow(duration, zoomed, 0.0, 1e12, 0.5).lengthMs)
        assertEquals(CutRange(0, duration), transformCutWindow(duration, zoomed, 0.0, 1e-12, 0.5))
        val huge = CutEdit(listOf(CutRange(0, 3_600_000_001L))).seek(3_000_000_000L).split()
        assertEquals(3_000_000_000L, huge.clips.last().startMs)
    }
}
