package com.pitchline.android

import kotlin.math.roundToLong

data class CutPiece(val source: CutRange, val startMs: Long) {
    val endMs get() = startMs + source.lengthMs
}

/** A non-destructive, ripple-edited sequence of source ranges. */
data class CutEdit(val clips: List<CutRange>, val positionMs: Long = 0, val selected: Long? = clips.firstOrNull()?.startMs) {
    init { require(clips.size <= 2000 && clips.zipWithNext().all { (a, b) -> a.endMs <= b.startMs }) }
    val durationMs get() = clips.sumOf { it.lengthMs }
    val pieces: List<CutPiece> get() {
        var at = 0L
        return clips.map { clip -> CutPiece(clip, at).also { at += clip.lengthMs } }
    }
    fun locate(timeMs: Long): CutPiece? {
        if (clips.isEmpty()) return null
        val at = timeMs.coerceIn(0, durationMs - 1)
        return pieces.first { at < it.endMs }
    }
    fun seek(timeMs: Long, select: Boolean = false): CutEdit {
        val at = timeMs.coerceIn(0, durationMs)
        return copy(positionMs = at, selected = if (select) locate(at)?.source?.startMs else selected)
    }
    val canSplit get() = clips.size < 2000 && locate(positionMs)?.let { positionMs > it.startMs && positionMs < it.endMs } == true
    fun split(): CutEdit {
        if (!canSplit) return this
        val piece = requireNotNull(locate(positionMs))
        val sourceAt = piece.source.startMs + positionMs - piece.startMs
        val index = clips.indexOf(piece.source)
        return copy(clips = clips.take(index) + listOf(CutRange(piece.source.startMs, sourceAt), CutRange(sourceAt, piece.source.endMs)) + clips.drop(index + 1), selected = sourceAt)
    }
    fun deleteSelected(): CutEdit {
        val piece = pieces.firstOrNull { it.source.startMs == selected } ?: return this
        val next = clips.filterNot { it.startMs == selected }
        val position = when {
            positionMs >= piece.endMs -> positionMs - piece.source.lengthMs
            positionMs >= piece.startMs -> piece.startMs
            else -> positionMs
        }.coerceAtMost(durationMs - piece.source.lengthMs)
        val edit = CutEdit(next, position, null)
        return edit.copy(selected = edit.locate(position)?.source?.startMs)
    }
    fun slices(window: CutRange): List<CutPiece> = pieces.mapNotNull { piece ->
        val first = maxOf(piece.startMs, window.startMs)
        val end = minOf(piece.endMs, window.endMs)
        if (end <= first) null else CutPiece(CutRange(piece.source.startMs + first - piece.startMs,
            piece.source.startMs + end - piece.startMs), first)
    }
}

/** Pinch about the fingers; horizontal pan follows the waveform, with no Float hour clock. */
fun transformCutWindow(durationMs: Long, window: CutRange, panFraction: Double, zoom: Double, focal: Double): CutRange {
    require(durationMs > 0)
    val z = if (zoom.isFinite() && zoom > 0) zoom else 1.0
    val span = (window.lengthMs / z).roundToLong().coerceIn(minOf(250L, durationMs), durationMs)
    val anchor = window.startMs + window.lengthMs * focal.coerceIn(0.0, 1.0)
    val start = (anchor - span * focal.coerceIn(0.0, 1.0) - panFraction * span).roundToLong().coerceIn(0, durationMs - span)
    return CutRange(start, start + span)
}
