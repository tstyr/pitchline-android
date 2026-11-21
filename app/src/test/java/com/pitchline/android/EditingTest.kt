package com.pitchline.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EditingTest {
    private val analysis = Analysis(576, FloatArray(200) { i ->
        if (i in 87..108) 0f else frequency(57 + 0.7f * sin(i * 2 * PI * 5.0 * 0.012).toFloat())
    }, FloatArray(200) { 0.98f }, FloatArray(100), 120f)

    @Test fun segmentsDetectTheBreathAndKeepTheContinuousCurve() {
        val notes = segment(analysis)
        assertEquals(listOf(0 until 87, 109 until 200), notes.map { it.start until it.end })
        val target = targetCurve(analysis, notes, 48000)
        assertFalse(target.changed)
        assertTrue(target.midi[100].isNaN())
        assertEquals(midi(analysis.f0[30]), target.midi[30], 0.001f)
    }

    @Test fun centsDrawingAndStraightenReachTheTargetFedToDsp() {
        val note = segment(analysis).first()
        val changes = note.copy(controls = Controls(semitones = 1, cents = 50f, straighten = 1f),
            drawing = listOf(CurvePoint(45, 60f), CurvePoint(53, 61f)))
        val target = targetCurve(analysis, listOf(changes), 48000)
        assertTrue(target.changed)
        assertEquals(note.center + 1.5f, target.midi[25], 0.001f)
        assertEquals(60.5f, target.midi[49], 0.001f)
        assertEquals(target.midi[49], midi(analysis.f0[49]) + target.cents[49] / 100, 0.001f)
        assertEquals(0f, target.cents[100], 0f)
    }

    @Test fun originalVibratoPeriodAndDepthAreMeasured() {
        val note = segment(analysis).first()
        val measured = requireNotNull(estimateVibrato(note, analysis, 48000))
        assertEquals(5f, measured.rateHz, 0.20f)
        assertEquals(70f, measured.depthCents, 6f)
    }

    @Test fun adjacentNoteOffsetsCrossfadeAtTheRequestedTransition() {
        val f0 = analysis.copy(f0 = FloatArray(200) { frequency(if (it < 100) 57f else 60f) })
        val left = Note(1, 0, 100, 57f, Controls(semitones = 2, transitionMs = 90f))
        val right = Note(2, 100, 200, 60f, Controls(semitones = -2, transitionMs = 90f))
        val target = targetCurve(f0, listOf(left, right), 48000)
        assertEquals(200f, target.cents[80], 0.001f)
        assertEquals(-200f, target.cents[120], 0.001f)
        assertTrue(target.cents[100] in -60f..0f)
        assertTrue(target.cents[99] in 0f..60f)
    }

    @Test fun scaleAndUndoRedoRemainDeterministic() {
        val notes = segment(analysis)
        val history = NoteHistory(notes)
        val changed = listOf(notes.first().copy(controls = Controls(cents = 50f)), notes.last())
        val updated = history.edit(notes, changed)
        assertEquals(notes, updated.back().notes)
        assertEquals(changed, updated.back().forward().notes)
        assertTrue(updated.back().edit(notes, changed.reversed()).redo.isEmpty())
        assertEquals(60, MusicalScale(0, ScaleMode.MAJOR).closest(60.3f))
        assertEquals(63, MusicalScale(0, ScaleMode.MINOR).closest(62.8f))
    }
}
