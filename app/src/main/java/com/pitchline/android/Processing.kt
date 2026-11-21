package com.pitchline.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Processing {
    suspend fun analyze(file: File, info: AudioInfo, progress: Progress): Analysis {
        val hop = (info.rate * 0.012).roundToInt()
        val total = (info.frames + hop - 1) / hop
        require(total in 1..2_000_000) { "解析可能な再生時間を超えています" }
        val count = total.toInt()
        val f0 = FloatArray(count)
        val confidence = FloatArray(count)
        val envelope = DoubleArray(count)
        val peaks = FloatArray(minOf(count, 8000))
        PcmReader(file, info.channels).use { pcm ->
            for (first in 0 until count step 160) {
                currentCoroutineContext().ensureActive()
                val length = minOf(160, count - first)
                val start = first.toLong() * hop
                val base = (start - info.rate / 20).coerceAtLeast(0)
                val end = minOf(info.frames, start + length.toLong() * hop + info.rate / 20)
                val audio = pcm.read(base, (end - base).toInt())
                val result = NativeDsp.detect(audio, info.channels, info.rate, hop, (start - base).toInt(), length)
                for (i in 0 until length) {
                    val frame = first + i
                    f0[frame] = result[2 * i]
                    confidence[frame] = result[2 * i + 1]
                    val begin = (start - base).toInt() + i * hop
                    val last = minOf(begin + hop, audio.size / info.channels)
                    var energy = 0.0
                    var maximum = 0f
                    for (sample in begin * info.channels until last * info.channels) {
                        val amplitude = kotlin.math.abs(audio[sample])
                        maximum = maxOf(maximum, amplitude)
                        energy += amplitude * amplitude
                    }
                    val bucket = (frame.toLong() * peaks.size / count).toInt()
                    peaks[bucket] = maxOf(peaks[bucket], maximum)
                    envelope[frame] = sqrt(energy / maxOf(1, (last - begin) * info.channels))
                }
                progress((first + length).toFloat() / count)
            }
        }
        stabilizePitch(f0, confidence)
        return Analysis(hop, f0, confidence, peaks, estimateTempo(envelope, hop.toDouble() / info.rate))
    }

    private fun estimateTempo(envelope: DoubleArray, step: Double): Float {
        if (envelope.size * step < 9) return 0f
        val sampleCount = minOf(envelope.size, (90 / step).toInt())
        val onset = DoubleArray(sampleCount)
        for (i in 2 until sampleCount) onset[i] = (ln1p(100 * envelope[i]) - ln1p(100 * envelope[i - 2])).coerceAtLeast(0.0)
        val mean = onset.average()
        for (i in onset.indices) onset[i] -= mean
        val energy = onset.sumOf { it * it }
        if (energy < 1e-6) return 0f
        val from = ceil(60 / (182 * step)).toInt()
        val to = (60 / (69 * step)).toInt()
        var bestLag = 0
        var best = 0.18
        for (lag in from..to) {
            var correlation = 0.0
            for (i in lag until sampleCount) correlation += onset[i] * onset[i - lag]
            val bpm = 60 / (lag * step)
            val score = correlation / energy * (0.84 + 0.16 * exp(-((bpm - 116) / 35).let { it * it }))
            if (score > best) {
                bestLag = lag
                best = score
            }
        }
        return if (bestLag == 0) 0f else (60 / (bestLag * step)).toFloat()
    }

    suspend fun render(
        source: File, output: File, info: AudioInfo, analysis: Analysis, target: Target,
        highQuality: Boolean, progress: Progress,
    ) {
        val chunk = info.rate * if (highQuality) 5 else 3
        val overlap = info.rate / 40
        val pad = info.rate / 8
        val channels = info.channels
        PcmReader(source, channels).use { reader ->
            PcmWriter(output).use { writer ->
                var start = 0L
                var previous = FloatArray(0)
                while (start < info.frames) {
                    currentCoroutineContext().ensureActive()
                    val coreEnd = minOf(start + chunk, info.frames)
                    val end = minOf(coreEnd + overlap, info.frames)
                    val inputStart = (start - pad).coerceAtLeast(0)
                    val inputEnd = minOf(end + pad, info.frames)
                    val first = (inputStart / analysis.hop - 1).coerceAtLeast(0).toInt()
                    val until = (inputEnd / analysis.hop + 2).coerceAtMost(analysis.f0.size.toLong()).toInt()
                    val audio = reader.read(inputStart, (inputEnd - inputStart).toInt())
                    var requiresEdit = false
                    for (i in first until until) {
                        if (kotlin.math.abs(target.cents[i]) > 0.15f || kotlin.math.abs(target.formant[i]) > 0.15f) {
                            requiresEdit = true
                            break
                        }
                    }
                    val rendered = if (requiresEdit) NativeDsp.resynthesize(
                        audio, channels, info.rate, analysis.hop, (first.toLong() * analysis.hop - inputStart).toInt(),
                        analysis.f0.copyOfRange(first, until), target.cents.copyOfRange(first, until),
                        target.formant.copyOfRange(first, until),
                    ) else audio
                    val offset = (start - inputStart).toInt() * channels
                    crossfade(previous, rendered, offset, channels)
                    val body = (coreEnd - start).toInt() * channels
                    writer.write(rendered, offset, body)
                    previous = rendered.copyOfRange(offset + body, offset + (end - start).toInt() * channels)
                    start = coreEnd
                    progress(start.toFloat() / info.frames)
                }
            }
        }
    }
}

fun crossfade(previous: FloatArray, next: FloatArray, offset: Int, channels: Int) {
    val frames = previous.size / channels
    for (i in 0 until frames) {
        val blend = (0.5 - 0.5 * cos(PI * (i + 1) / (frames + 1))).toFloat()
        for (channel in 0 until channels) {
            val sample = i * channels + channel
            val index = offset + sample
            next[index] = previous[sample] * (1 - blend) + next[index] * blend
        }
    }
}
