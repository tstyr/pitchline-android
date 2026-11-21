package com.pitchline.android

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.abs

data class CutAudio(val uri: Uri, val name: String, val durationMs: Long, val rate: Int, val channels: Int)

object CutterAudio {
    fun inspect(context: Context, uri: Uri): CutAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("対応する音声が見つかりません")
            val format = extractor.getTrackFormat(track)
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(duration in 1..Long.MAX_VALUE / 1000 && rate in 8000..192000 && channels in 1..2) {
                "長さを取得できるモノラル／ステレオの音声を選択してください"
            }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "音声"
            return CutAudio(uri, name, duration, rate, channels)
        } finally { extractor.release() }
    }

    fun sameDocument(context: Context, source: Uri, destination: Uri): Boolean {
        if (source.normalizeScheme() == destination.normalizeScheme()) return true
        if (source.scheme == "file" && destination.scheme == "file" &&
            File(requireNotNull(source.path)).canonicalFile == File(requireNotNull(destination.path)).canonicalFile) return true
        if (source.authority == destination.authority && runCatching {
                DocumentsContract.getDocumentId(source) == DocumentsContract.getDocumentId(destination)
            }.getOrDefault(false)) return true
        val resolver = context.contentResolver
        return runCatching {
            val canonical = resolver.canonicalize(source)
            canonical != null && canonical == resolver.canonicalize(destination)
        }.getOrDefault(false)
    }

    /** Only the visible window (at most 30s), with fixed-size display data. No pitch analysis. */
    suspend fun waveform(context: Context, source: CutAudio, window: CutRange): FloatArray {
        require(window.lengthMs <= 30_000)
        val pcm = File.createTempFile("cut-wave-", ".pcm", context.cacheDir)
        try {
            val info = AudioImport.decodeRange(context, source.uri, pcm, window) {}
            val peaks = FloatArray(1200)
            PcmReader(pcm, info.channels).use { reader ->
                var at = 0L
                while (at < info.frames) {
                    currentCoroutineContext().ensureActive()
                    val frames = minOf(4096L, info.frames - at).toInt()
                    val audio = reader.read(at, frames)
                    for (i in 0 until frames) {
                        val bucket = ((at + i) * peaks.size / info.frames).toInt().coerceAtMost(peaks.lastIndex)
                        for (channel in 0 until info.channels) peaks[bucket] =
                            maxOf(peaks[bucket], abs(audio[i * info.channels + channel]))
                    }
                    at += frames
                }
            }
            return peaks
        } finally { pcm.delete() }
    }

    suspend fun export(context: Context, source: CutAudio, range: CutRange, destination: Uri,
        format: ExportFormat, progress: Progress) {
        require(!sameDocument(context, source.uri, destination)) { "元ファイルと別の保存先を選択してください" }
        var pcm: File? = null
        var encoded: File? = null
        var success = false
        try {
            require(range.endMs <= source.durationMs) { "選択区間が音声の長さを超えています" }
            val frames = cutFrame(range.lengthMs * 1000, source.rate)
            val estimate = Math.multiplyExact(Math.multiplyExact(frames, source.channels.toLong()), 7L)
            require(context.cacheDir.usableSpace > Math.addExact(estimate, 32L * 1024 * 1024)) {
                "作業用の空き容量が不足しています。区間を短くするか、端末の空きを増やしてください"
            }
            if (format == ExportFormat.WAV) require(frames * source.channels * 3 + 37 <= 0xffffffffL) {
                "WAV の 4 GB 上限を超えます。区間を短くするか M4A／FLAC を選んでください"
            }
            val pcmFile = File.createTempFile("cut-export-", ".pcm", context.cacheDir).also { pcm = it }
            val encodedFile = File.createTempFile("cut-export-", ".${format.extension}", context.cacheDir).also { encoded = it }
            val info = AudioImport.decodeRange(context, source.uri, pcmFile, range) { progress(it * 0.42f) }
            PcmReader(pcmFile, info.channels).use {
                AudioExport.encode(encodedFile, info, it, format) { value -> progress(0.42f + 0.48f * value) }
            }
            (context.contentResolver.openOutputStream(destination, "wt") ?: error("保存先を開けません")).use { output ->
                encodedFile.inputStream().use { input ->
                    val buffer = ByteArray(65_536)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        progress(0.9f + 0.1f * (written.toDouble() / encodedFile.length()).toFloat())
                    }
                }
            }
            success = true
        } finally {
            pcm?.delete()
            encoded?.delete()
            if (!success) {
                if (destination.scheme == "file") destination.path?.let { File(it).delete() }
                else runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
            }
        }
    }
}
