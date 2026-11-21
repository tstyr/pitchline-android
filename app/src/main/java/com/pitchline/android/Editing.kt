package com.pitchline.android

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class CurvePoint(val frame: Int, val midi: Float)

@Serializable
data class Controls(
    val semitones: Int = 0,
    val cents: Float = 0f,
    val drift: Float = 0f,
    val modulation: Float = 0f,
    val straighten: Float = 0f,
    val transitionMs: Float = 15f,
    val vibratoDepth: Float = 0f,
    val vibratoRate: Float = 5.5f,
    val vibratoStart: Float = 0.15f,
    val vibratoIn: Float = 0.15f,
    val vibratoOut: Float = 0.15f,
    val formant: Float = 0f,
    val snapCents: Float = 0f,
)

@Serializable
data class Note(
    val id: Long,
    val start: Int,
    val end: Int,
    val center: Float,
    val controls: Controls = Controls(),
    val drawing: List<CurvePoint> = emptyList(),
) {
    val displayMidi get() = center + (controls.semitones * 100 + controls.cents + controls.snapCents).coerceIn(-1200f, 1200f) / 100f
}

@Serializable
data class NoteHistory(
    val notes: List<Note>,
    val undo: List<List<Note>> = emptyList(),
    val redo: List<List<Note>> = emptyList(),
) {
    fun edit(previous: List<Note>, updated: List<Note>): NoteHistory =
        if (previous == updated) this else NoteHistory(updated, (undo + listOf(previous)).takeLast(24))

    fun back(): NoteHistory = if (undo.isEmpty()) this else
        NoteHistory(undo.last(), undo.dropLast(1), (redo + listOf(notes)).takeLast(24))

    fun forward(): NoteHistory = if (redo.isEmpty()) this else
        NoteHistory(redo.last(), (undo + listOf(notes)).takeLast(24), redo.dropLast(1))
}

@Serializable
data class MusicalScale(val root: Int = 0, val mode: ScaleMode = ScaleMode.CHROMATIC) {
    fun closest(midi: Float): Int {
        val rounded = midi.roundToInt()
        return (rounded - 6..rounded + 6)
            .filter { Math.floorMod(it - root, 12) in mode.intervals }
            .minBy { abs(it - midi) }
    }
}

@Serializable
enum class ScaleMode(val label: String, val intervals: Set<Int>) {
    CHROMATIC("クロマチック", (0..11).toSet()),
    MAJOR("メジャー", setOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("ナチュラルマイナー", setOf(0, 2, 3, 5, 7, 8, 10)),
}

data class Analysis(
    val hop: Int,
    val f0: FloatArray,
    val confidence: FloatArray,
    val waveform: FloatArray,
    val bpm: Float,
)

data class Target(val cents: FloatArray, val formant: FloatArray, val midi: FloatArray, val changed: Boolean)

fun midi(hz: Float): Float = (69 + 12 * log2(hz / 440.0)).toFloat()
fun frequency(midi: Float): Float = (440 * exp((midi - 69) * ln(2.0) / 12.0)).toFloat()

fun segment(analysis: Analysis): List<Note> {
    val pitch = analysis.f0
    val result = mutableListOf<Note>()
    val voiced = FloatArray(pitch.size) { if (pitch[it] > 0f) midi(pitch[it]) else Float.NaN }
    var start = 0
    fun add(end: Int) {
        if (end - start >= 5) {
            val sorted = (start until end).mapNotNull { voiced[it].takeIf(Float::isFinite) }.sorted()
            if (sorted.size >= 4) result += Note(start.toLong() + 1, start, end, sorted[sorted.size / 2])
        }
    }
    while (start < pitch.size) {
        if (pitch[start] <= 0f) {
            start++
            continue
        }
        var end = start + 1
        while (end < pitch.size && pitch[end] > 0f) {
            if (end >= start + 5 && end + 2 < pitch.size && pitch[end + 1] > 0f && pitch[end + 2] > 0f) {
                val before = (voiced[end - 3] + voiced[end - 2] + voiced[end - 1]) / 3
                val after = (voiced[end] + voiced[end + 1] + voiced[end + 2]) / 3
                if (abs(after - before) >= 1.2f && abs(voiced[end] - voiced[end - 1]) >= 0.48f) break
            }
            end++
        }
        add(end)
        start = end
    }
    return result
}

fun targetCurve(analysis: Analysis, notes: List<Note>, rate: Int): Target {
    val size = analysis.f0.size
    val cents = FloatArray(size)
    val formant = FloatArray(size)
    val curve = FloatArray(size) { if (analysis.f0[it] > 0f) midi(analysis.f0[it]) else Float.NaN }
    val original = curve.copyOf()
    var changed = false
    val dt = analysis.hop.toDouble() / rate
    val radius = (0.11 / dt).roundToInt().coerceAtLeast(1)
    val identity = Controls()

    for ((noteIndex, note) in notes.withIndex()) {
        val c = note.controls
        if (c == identity && note.drawing.isEmpty()) continue
        val start = note.start.coerceIn(0, size)
        val end = note.end.coerceIn(start, size)
        var cursor = 0
        var phase = 0.0
        var accumulated = 0.0
        var sampleCount = 0
        var ahead = start
        var behind = start
        val shift = c.semitones * 100 + c.cents + c.snapCents
        val transition = c.transitionMs / 1000 / dt
        val duration = (end - start - 1).coerceAtLeast(1)
        val linkedLeft = notes.getOrNull(noteIndex - 1)?.end == start
        val linkedRight = notes.getOrNull(noteIndex + 1)?.start == end
        for (i in start until end) {
            while (ahead < end && ahead <= i + radius) {
                if (original[ahead].isFinite()) {
                    accumulated += original[ahead]
                    sampleCount++
                }
                ahead++
            }
            while (behind < i - radius) {
                if (original[behind].isFinite()) {
                    accumulated -= original[behind]
                    sampleCount--
                }
                behind++
            }
            val fraction = (i - start).toFloat() / duration
            phase += 2 * PI * c.vibratoRate * dt *
                (1 + 0.095 * sin(2 * PI * 0.7 * i * dt) + 0.035 * sin(2 * PI * 1.37 * i * dt))
            if (fraction <= c.vibratoStart) phase = 0.0
            if (!original[i].isFinite()) continue
            val trend = if (sampleCount > 0) accumulated / sampleCount else original[i].toDouble()
            val drift = (trend - note.center) * 100
            val modulation = (original[i] - trend) * 100
            val fadeIn = smooth((fraction - c.vibratoStart) / c.vibratoIn.coerceAtLeast(0.015f))
            val fadeOut = smooth((1 - fraction) / c.vibratoOut.coerceAtLeast(0.015f))
            val vibrato = c.vibratoDepth * fadeIn * fadeOut *
                (sin(phase) + 0.11 * sin(phase * 2 + 0.55)) * (0.91 + 0.09 * sin(1.9 * i * dt))
            var amount = shift + (1 - c.straighten) *
                ((1 - c.drift) * drift + (1 - c.modulation) * modulation) - drift - modulation + vibrato
            val drawing = note.drawing
            if (drawing.isNotEmpty() && i in drawing.first().frame..drawing.last().frame) {
                while (cursor + 1 < drawing.lastIndex && drawing[cursor + 1].frame < i) cursor++
                val a = drawing[cursor]
                val b = drawing.getOrElse(cursor + 1) { a }
                val t = if (a.frame == b.frame) 0f else (i - a.frame).toFloat() / (b.frame - a.frame)
                amount = (a.midi + (b.midi - a.midi) * t - original[i]) * 100.0
            }
            val edge = minOf(
                if (linkedLeft) Double.POSITIVE_INFINITY else i - start + 0.5,
                if (linkedRight) Double.POSITIVE_INFINITY else end - i - 0.5,
            )
            val blend = if (transition > 0) smooth((edge / transition).toFloat()) else 1f
            cents[i] = (amount * blend).toFloat().coerceIn(-1200f, 1200f)
            formant[i] = c.formant * blend
            curve[i] = original[i] + cents[i] / 100
            changed = changed || abs(cents[i]) > 0.15f || abs(formant[i]) > 0.15f
        }
    }
    if (changed) {
        for (index in 1 until notes.size) {
            val left = notes[index - 1]
            val right = notes[index]
            val boundary = right.start
            if (left.end != boundary || boundary !in 1 until original.size) continue
            if (!original[boundary - 1].isFinite() || !original[boundary].isFinite()) continue
            if (abs(cents[boundary] - cents[boundary - 1]) <= 0.15f &&
                abs(formant[boundary] - formant[boundary - 1]) <= 0.15f) continue
            val transition = maxOf(left.controls.transitionMs, right.controls.transitionMs)
            val span = (transition * 0.0005 / dt).roundToInt()
            val begin = (boundary - span).coerceAtLeast((boundary + left.start) / 2)
            val end = (boundary + span).coerceAtMost((boundary + right.end) / 2).coerceAtMost(size)
            if (end - begin < 2) continue
            val fromCents = cents[begin]
            val toCents = cents[end - 1]
            val fromFormant = formant[begin]
            val toFormant = formant[end - 1]
            for (i in begin until end) {
                if (!original[i].isFinite()) continue
                val ratio = smooth((i - begin).toFloat() / (end - begin - 1))
                cents[i] = fromCents + (toCents - fromCents) * ratio
                formant[i] = fromFormant + (toFormant - fromFormant) * ratio
                curve[i] = original[i] + cents[i] / 100
            }
        }
    }
    return Target(cents, formant, curve, changed)
}

private fun smooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

fun stabilizePitch(f0: FloatArray, confidence: FloatArray) {
    val baseline = f0.copyOf()
    for (i in f0.indices) {
        if (confidence[i] < 0.71f) f0[i] = 0f
        if (i == 0 || i == f0.lastIndex) continue
        val a = baseline[i - 1]
        val b = baseline[i + 1]
        if (a <= 0f || b <= 0f || baseline[i] <= 0f || abs(midi(a) - midi(b)) >= 0.45f) continue
        val neighbors = sqrt(a * b)
        if (abs(abs(midi(baseline[i]) - midi(neighbors)) - 12) < 0.55f && confidence[i] < 0.96f) f0[i] = neighbors
    }
}

data class VibratoEstimate(val rateHz: Float, val depthCents: Float)

fun estimateVibrato(note: Note, analysis: Analysis, sampleRate: Int): VibratoEstimate? {
    val step = analysis.hop.toDouble() / sampleRate
    val count = note.end - note.start
    if (count * step < 0.55) return null
    val pitch = DoubleArray(count) { i ->
        val hz = analysis.f0[note.start + i]
        if (hz <= 0f) return null
        (midi(hz) - note.center) * 100.0
    }
    val mean = pitch.average()
    val xMean = (count - 1) * 0.5
    var numerator = 0.0
    var denominator = 0.0
    for (i in pitch.indices) {
        numerator += (i - xMean) * (pitch[i] - mean)
        denominator += (i - xMean) * (i - xMean)
    }
    val slope = numerator / denominator
    for (i in pitch.indices) pitch[i] -= mean + slope * (i - xMean)
    val depth = sqrt(2 * pitch.sumOf { it * it } / count)
    if (depth < 7) return null
    val first = (1 / (9 * step)).roundToInt().coerceAtLeast(2)
    val last = minOf((1 / (2.7 * step)).roundToInt(), count / 2)
    val scores = DoubleArray(last + 2)
    for (shift in first - 1..last + 1) {
        var powerA = 0.0
        var powerB = 0.0
        var cross = 0.0
        for (i in shift until count) {
            val a = pitch[i - shift]
            val b = pitch[i]
            powerA += a * a
            powerB += b * b
            cross += a * b
        }
        scores[shift] = cross / sqrt((powerA * powerB).coerceAtLeast(1e-10))
    }
    val lag = (first..last).firstOrNull { scores[it] > 0.63 && scores[it] >= scores[it - 1] && scores[it] >= scores[it + 1] } ?: return null
    val shape = scores[lag - 1] - 2 * scores[lag] + scores[lag + 1]
    val correction = if (shape < -1e-6) 0.5 * (scores[lag - 1] - scores[lag + 1]) / shape else 0.0
    return VibratoEstimate((1 / ((lag + correction) * step)).toFloat(), depth.toFloat())
}
