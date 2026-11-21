package com.pitchline.android

import java.util.Locale

/** Millisecond selection, with an exclusive end; never store hour-long positions in Int. */
data class CutRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs && endMs <= Long.MAX_VALUE / 1000) }
    val lengthMs get() = endMs - startMs
}

enum class CutEdge(val label: String) { START("開始"), END("終了") }

fun cutTime(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", value / 3_600_000,
        value / 60_000 % 60, value / 1000 % 60, value % 1000)
}

/** Accept seconds, mm:ss, or hh:mm:ss with up to three fractional digits. */
fun parseCutTime(text: String): Long? = runCatching {
    val parts = text.trim().split(':')
    require(parts.size in 1..3 && parts.all { it.isNotEmpty() })
    val seconds = parts.last().split('.')
    require(seconds.size in 1..2 && seconds[0].all { it in '0'..'9' } && seconds[0].isNotEmpty())
    val whole = seconds[0].toLong()
    require(parts.size == 1 || whole < 60)
    val fraction = if (seconds.size == 2) {
        require(seconds[1].length in 1..3 && seconds[1].all { it in '0'..'9' })
        seconds[1].padEnd(3, '0').toLong()
    } else 0L
    var result = Math.addExact(Math.multiplyExact(whole, 1000), fraction)
    if (parts.size >= 2) {
        require(parts[parts.lastIndex - 1].all { it in '0'..'9' })
        val minutes = parts[parts.lastIndex - 1].toLong()
        require(parts.size == 2 || minutes < 60)
        result = Math.addExact(result, Math.multiplyExact(minutes, 60_000))
    }
    if (parts.size == 3) {
        require(parts[0].all { it in '0'..'9' })
        result = Math.addExact(result, Math.multiplyExact(parts[0].toLong(), 3_600_000))
    }
    require(result <= Long.MAX_VALUE / 1000)
    result
}.getOrNull()

fun cutWindow(durationMs: Long, focusMs: Long, spanMs: Long): CutRange {
    require(durationMs > 0)
    val span = spanMs.coerceIn(1, durationMs)
    val start = (focusMs - span / 2).coerceIn(0, durationMs - span)
    return CutRange(start, start + span)
}

/** ceil(timeUs * rate / 1e6), including negative deltas, without the large product. */
fun cutFrame(timeUs: Long, rate: Int): Long = Math.floorDiv(timeUs, 1_000_000L) * rate +
    (Math.floorMod(timeUs, 1_000_000L) * rate + 999_999L) / 1_000_000L

/** Codec timestamps round sample clocks to microseconds; recover the closest frame first. */
fun cutTimestampFrame(timeUs: Long, rate: Int): Long = Math.floorDiv(timeUs, 1_000_000L) * rate +
    (Math.floorMod(timeUs, 1_000_000L) * rate + 500_000L) / 1_000_000L
