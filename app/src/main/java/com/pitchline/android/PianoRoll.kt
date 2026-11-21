package com.pitchline.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

val pitchNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
fun noteName(midi: Int) = "${pitchNames[Math.floorMod(midi, 12)]}${Math.floorDiv(midi, 12) - 1}"

enum class RollTool(val title: String) {
    SELECT("選択"), MULTI("複数"), RANGE("範囲"), DRAW("描画"),
}

private data class Viewport(val left: Float, val top: Float, val pixelsPerSecond: Float, val rowHeight: Float) {
    fun time(x: Float, keyboard: Float) = left + (x - keyboard) / pixelsPerSecond
    fun pitch(y: Float, timeline: Float) = top - (y - timeline) / rowHeight + 0.5f
}

@Composable
fun PianoRoll(
    editor: EditorState,
    playhead: State<Long>,
    model: MainModel,
    tool: RollTool,
    snap: Boolean,
    wave: Boolean,
    loop: IntRange?,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val project = editor.project
    val analysis = editor.analysis
    val notes = project.history.notes
    val duration = project.info.seconds.toFloat()
    val frameSeconds = analysis.hop.toFloat() / project.info.rate
    val center = remember(project.id, project.analysisKey) {
        val values = notes.map { it.center }.sorted()
        values.getOrElse((values.size * 0.66).toInt()) { 67f }
    }
    val current by rememberUpdatedState(editor)
    val timeRange by rememberUpdatedState(loop)
    val base = remember(analysis) { FloatArray(analysis.f0.size) { if (analysis.f0[it] > 0) midi(analysis.f0[it]) else Float.NaN } }
    val target by produceState<Target?>(null, analysis, notes, project.info.rate) {
        value = if (notes.any { it.controls != Controls() || it.drawing.isNotEmpty() }) withContext(Dispatchers.Default) {
            targetCurve(analysis, notes, project.info.rate)
        } else null
    }
    val keyboard = 49.dp
    val timeline = if (wave && !compact) 49.dp else if (wave) 40.dp else 28.dp
    var viewport by remember(project.id, project.analysisKey, compact) { mutableStateOf<Viewport?>(null) }
    var draggingRange by remember(project.id) { mutableStateOf<IntRange?>(null) }
    val paints = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("monospace", Typeface.NORMAL) }
    }
    fun defaultViewport(span: Float, row: Float): Viewport {
        val initial = notes.firstOrNull()?.start?.times(frameSeconds)?.minus(0.25f)?.coerceAtLeast(0f) ?: 0f
        return Viewport(initial.coerceAtMost((duration - span).coerceAtLeast(0f)), (center + 3).coerceAtMost(105f), 1f, row)
    }

    Canvas(modifier
        .semantics { contentDescription = "ピアノロール、${notes.size} 音" }
        .pointerInput(project.id, project.analysisKey, tool, snap, wave, compact, enabled) {
            if (!enabled) return@pointerInput
            val key = keyboard.toPx()
            val ruler = timeline.toPx()
            val width = (size.width - key).coerceAtLeast(1f)
            fun fallback() = defaultViewport(minOf(5f, maxOf(duration, 1.5f)), (if (compact) 23.dp else 25.dp).toPx())
                .copy(pixelsPerSecond = width / minOf(5f, maxOf(duration, 1.5f)))
            fun restricted(view: Viewport) = view.copy(
                left = view.left.coerceIn(0f, (duration - width / view.pixelsPerSecond).coerceAtLeast(0f)),
                top = view.top.coerceIn(37f, 110f),
            )
            fun hit(point: Offset, v: Viewport): Note? {
                if (point.x < key || point.y < ruler) return null
                val t = v.time(point.x, key) / frameSeconds
                val pitch = v.pitch(point.y, ruler)
                return current.project.history.notes.firstOrNull {
                    t >= it.start && t < it.end && abs(pitch - it.displayMidi) < 0.59f
                }
            }

            var lastTap = 0L
            var lastPoint = Offset.Unspecified
            awaitEachGesture {
                val start = awaitFirstDown(requireUnconsumed = false)
                val original = viewport ?: fallback()
                val pressed = hit(start.position, original)
                val firstFrame = (original.time(start.position.x, key) / frameSeconds).roundToInt().coerceIn(0, analysis.f0.lastIndex)
                val drawNote = if (tool == RollTool.DRAW && start.position.x >= key && start.position.y >= ruler)
                    current.project.history.notes.firstOrNull { firstFrame in it.start until it.end } else null
                val samples = linkedMapOf<Int, CurvePoint>()
                fun point(where: Offset, note: Note): CurvePoint {
                    val f = (original.time(where.x, key) / frameSeconds).roundToInt().coerceIn(note.start, note.end - 1)
                    return CurvePoint(f, original.pitch(where.y, ruler).coerceIn(27f, 112f))
                }
                if (drawNote != null) samples[firstFrame] = point(start.position, drawNote)
                var mode = ""
                var moving = false
                var total = Offset.Zero
                var latest = start.position
                do {
                    val event = awaitPointerEvent()
                    val primary = event.changes.firstOrNull { it.id == start.id }
                    if (event.changes.count { it.pressed } > 1) {
                        if (moving && mode != "pinch") model.finishGesture()
                        draggingRange = null
                        mode = "pinch"
                        moving = true
                        val old = viewport ?: original
                        val zoom = event.calculateZoom().coerceIn(0.55f, 1.8f)
                        val pan = event.calculatePan()
                        val focal = event.calculateCentroid(useCurrent = false)
                        if (focal != Offset.Unspecified) {
                            val pps = (old.pixelsPerSecond * zoom).coerceIn(width / maxOf(duration, 2f), width / 0.55f)
                            val row = (old.rowHeight * zoom).coerceIn(10.dp.toPx(), 58.dp.toPx())
                            viewport = restricted(Viewport(
                                old.time(focal.x, key) - (focal.x + pan.x - key) / pps,
                                old.pitch(focal.y, ruler) + (focal.y + pan.y - ruler) / row - 0.5f, pps, row,
                            ))
                        }
                        event.changes.forEach { it.consume() }
                    } else if (primary?.pressed == true && mode != "pinch") {
                        total += primary.position - latest
                        latest = primary.position
                        if (!moving && total.getDistance() >= viewConfiguration.touchSlop) {
                            moving = true
                            mode = when {
                                tool == RollTool.RANGE && start.position.x >= key -> "range"
                                drawNote != null -> "draw"
                                start.position.x >= key && start.position.y < ruler -> "seek"
                                pressed != null && abs(total.y) > abs(total.x) * 1.05f -> "transpose"
                                else -> "pan"
                            }
                            if (mode == "draw" || mode == "transpose") {
                                val edit = requireNotNull(if (mode == "draw") drawNote else pressed)
                                if (edit.id !in current.selected) model.select(edit.id, tool == RollTool.MULTI)
                                model.beginGesture()
                            }
                        }
                        if (moving) {
                            val at = original.time(latest.x, key).coerceIn(0f, duration)
                            when (mode) {
                                "pan" -> viewport = restricted(original.copy(left = original.left - total.x / original.pixelsPerSecond,
                                    top = original.top + total.y / original.rowHeight))
                                "seek" -> Unit
                                "transpose" -> model.moveGesture((-total.y / original.rowHeight).roundToInt(), snap)
                                "range" -> {
                                    val last = (at / frameSeconds).roundToInt().coerceIn(0, analysis.f0.lastIndex)
                                    draggingRange = minOf(firstFrame, last)..maxOf(firstFrame, last)
                                }
                                "draw" -> {
                                    val note = requireNotNull(drawNote)
                                    val value = point(latest, note)
                                    samples[value.frame] = value
                                    model.draw(note.id, samples.values)
                                }
                            }
                            primary.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
                val releaseTime = original.time(latest.x, key).coerceIn(0f, duration)
                when {
                    moving && (mode == "transpose" || mode == "draw") -> model.finishGesture()
                    moving && mode == "seek" -> model.seek((releaseTime * project.info.rate).toLong())
                    moving && mode == "range" -> {
                        draggingRange?.let(model::selectRange)
                        draggingRange = null
                    }
                    !moving && start.position.x >= key -> {
                        val now = SystemClock.uptimeMillis()
                        val note = pressed
                        val doubleTap = note != null && now - lastTap <= viewConfiguration.doubleTapTimeoutMillis &&
                            lastPoint != Offset.Unspecified && (lastPoint - latest).getDistance() < 28.dp.toPx()
                        if (doubleTap) {
                            val length = ((note.end - note.start) * frameSeconds * 2.2f).coerceIn(0.65f, 4f)
                            val pps = width / length
                            viewport = restricted(Viewport((note.start + note.end) * frameSeconds / 2 - length / 2,
                                note.displayMidi + (size.height - ruler) / (2 * original.rowHeight), pps, original.rowHeight))
                            model.select(note.id, false)
                            model.seek((note.start.toLong() * analysis.hop))
                            lastTap = 0
                        } else {
                            if (start.position.y >= ruler) {
                                if (drawNote != null) {
                                    model.select(drawNote.id, false)
                                    model.beginGesture()
                                    model.draw(drawNote.id, samples.values)
                                    model.finishGesture()
                                } else model.select(note?.id, tool == RollTool.MULTI)
                            }
                            model.seek((releaseTime * project.info.rate).toLong())
                            lastTap = now
                            lastPoint = latest
                        }
                    }
                }
            }
        }
    ) {
        val key = keyboard.toPx()
        val ruler = timeline.toPx()
        if (size.width <= key || size.height <= ruler) return@Canvas
        val width = size.width - key
        val span = minOf(5f, maxOf(duration, 1.5f))
        val v = viewport ?: defaultViewport(span, (if (compact) 23.dp else 25.dp).toPx())
            .copy(pixelsPerSecond = width / span)
        fun x(t: Float) = key + (t - v.left) * v.pixelsPerSecond
        fun y(note: Float) = ruler + (v.top - note + 0.5f) * v.rowHeight
        fun label(value: String, xx: Float, yy: Float, tint: Int, font: Float) {
            paints.color = tint
            paints.textSize = font
            drawContext.canvas.nativeCanvas.drawText(value, xx, yy, paints)
        }
        val right = v.left + width / v.pixelsPerSecond
        val lowest = floor(v.pitch(size.height, ruler) - 1).toInt()
        val highest = ceil(v.top + 1).toInt()
        drawRect(Color(0xFF14171B))
        clipRect(top = ruler) {
            for (pitch in lowest..highest) {
                val black = Math.floorMod(pitch, 12) in listOf(1, 3, 6, 8, 10)
                val top = y(pitch + 0.5f)
                if (black) drawRect(Color(0xFF101318), Offset(key, top), Size(width, v.rowHeight))
                drawLine(if (pitch % 12 == 0) Color(0xFF353940) else Color(0xFF24282F),
                    Offset(key, top), Offset(size.width, top), strokeWidth = 1f)
                drawRect(if (black) Color(0xFF2A2C33) else Color(0xFFCDD0D4), Offset(0f, top), Size(key, v.rowHeight - 1f))
                label(noteName(pitch), 9.dp.toPx(), top + v.rowHeight * 0.68f,
                    if (black) 0xFFAAAFBB.toInt() else 0xFF32363E.toInt(), 10.dp.toPx().coerceAtMost(v.rowHeight * 0.63f))
            }
        }
        val tick = listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f, 120f, 300f, 600f, 1800f)
            .firstOrNull { it * v.pixelsPerSecond >= 58.dp.toPx() } ?: 3600f
        val firstTick = floor(v.left / tick).toInt()
        val lastTick = ceil(right / tick).toInt()
        clipRect(left = key) {
            drawRect(Color(0xFF202329), Offset(key, 0f), Size(width, ruler))
            if (wave) {
                val waveform = analysis.waveform
                val first = (v.left / duration * waveform.size).toInt().coerceIn(0, waveform.lastIndex)
                val last = ceil(right / duration * waveform.size).toInt().coerceIn(first, waveform.lastIndex)
                val skip = ((last - first) / width).toInt().coerceAtLeast(1)
                val baseline = ruler - 10.dp.toPx()
                for (i in first..last step skip) {
                    val xx = x(i.toFloat() / waveform.size * duration)
                    val amplitude = waveform[i].coerceIn(0f, 1f) * 9.dp.toPx()
                    drawLine(Color(0xFF607471), Offset(xx, baseline - amplitude), Offset(xx, baseline + amplitude), 1.dp.toPx())
                }
            }
            for (i in firstTick..lastTick) {
                val seconds = i * tick
                val xx = x(seconds)
                drawLine(Color(0xFF353940), Offset(xx, 0f), Offset(xx, size.height), 1f)
                label(timeLabel(seconds.toDouble(), tick < 1f), xx + 5.dp.toPx(), 16.dp.toPx(), 0xFFB4BAC4.toInt(), 10.dp.toPx())
            }
            val range = draggingRange ?: timeRange
            if (range != null) {
                val xx = x(range.first * frameSeconds)
                val end = x((range.last + 1) * frameSeconds)
                drawRect(Color(0x225CC8BA), Offset(xx, 0f), Size((end - xx).coerceAtLeast(0f), size.height))
                drawLine(Color(0xFF69C9B9), Offset(xx, 0f), Offset(end, 0f), 3.dp.toPx())
            }
            clipRect(top = ruler) {
                val firstFrame = (v.left / frameSeconds).toInt().coerceAtLeast(0)
                val endFrame = ceil(right / frameSeconds).toInt().coerceAtMost(base.lastIndex)
                val visibleStart = notes.lowerBound(firstFrame)
                for (index in visibleStart until notes.size) {
                    val note = notes[index]
                    if (note.start > endFrame + 1) break
                    val xx = x(note.start * frameSeconds)
                    val yy = y(note.displayMidi) - v.rowHeight * 0.32f
                    val ww = (x(note.end * frameSeconds) - xx - 1).coerceAtLeast(2f)
                    val hh = v.rowHeight * 0.64f
                    val selected = note.id in editor.selected
                    drawRoundRect(if (selected) Color(0xFFE6A16D) else Color(0xFFAA704A),
                        Offset(xx, yy), Size(ww, hh), CornerRadius(4.dp.toPx()))
                    if (selected) drawLine(Color(0xFFFFD5A3), Offset(xx + 2f, yy + 1f), Offset(xx + ww - 2f, yy + 1f), 1f)
                    if (ww > 37.dp.toPx() && hh > 13.dp.toPx()) {
                        label(noteName(note.displayMidi.roundToInt()), xx + 5.dp.toPx(), yy + hh * 0.75f,
                            0xFF29170E.toInt(), 9.dp.toPx())
                    }
                }
                fun curve(values: FloatArray, tint: Color, onlyEdited: Boolean) {
                    val path = Path()
                    var active = false
                    for (i in maxOf(0, firstFrame - 1)..minOf(values.lastIndex, endFrame + 1)) {
                        val pitch = values[i]
                        if (!pitch.isFinite() || (onlyEdited && abs(pitch - base[i]) < 0.002f &&
                                    (target?.formant?.get(i) ?: 0f) == 0f &&
                                    (i == 0 || abs(values[i - 1] - base[i - 1]) < 0.002f))) {
                            active = false
                            continue
                        }
                        val xx = x(i * frameSeconds)
                        val yy = y(pitch)
                        if (active) path.lineTo(xx, yy) else path.moveTo(xx, yy)
                        active = true
                    }
                    drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                curve(base, Color(0xFFE4E7EB), false)
                target?.takeIf { it.changed }?.let { curve(it.midi, Color(0xFF68E4CE), true) }
            }
            val head = x(playhead.value.toFloat() / project.info.rate)
            drawLine(Color(0xFFFFD9A5), Offset(head, 0f), Offset(head, size.height), 1.dp.toPx())
        }
        drawRect(Color(0xFF252830), size = Size(key, ruler))
        label("音高", 9.dp.toPx(), (ruler / 2 + 4.dp.toPx()), 0xFFB4BAC4.toInt(), 10.dp.toPx())
        drawLine(Color(0xFF454952), Offset(key, 0f), Offset(key, size.height), 1.dp.toPx())
        drawLine(Color(0xFF454952), Offset(0f, ruler), Offset(size.width, ruler), 1.dp.toPx())
    }
}

private fun List<Note>.lowerBound(frame: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) / 2
        if (this[mid].end < frame) low = mid + 1 else high = mid
    }
    return low
}

fun timeLabel(seconds: Double, fractions: Boolean = false): String {
    val total = (seconds.coerceAtLeast(0.0) * 100).toLong()
    return if (fractions) "%02d:%02d.%02d".format(total / 6000, total / 100 % 60, total % 100)
    else "%02d:%02d".format(total / 6000, total / 100 % 60)
}
