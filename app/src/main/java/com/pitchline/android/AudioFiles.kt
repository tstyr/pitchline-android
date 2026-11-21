package com.pitchline.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.math.abs
import kotlin.math.roundToInt

private const val block = 4096
typealias Progress = (Float) -> Unit

class PcmReader(file: File, private val channels: Int) : AutoCloseable {
    private val handle = RandomAccessFile(file, "r")
    private var storage = ByteArray(0)
    fun read(first: Long, frames: Int): FloatArray {
        val samples = frames * channels
        val bytes = samples * 4
        if (storage.size < bytes) storage = ByteArray(bytes)
        handle.seek(first * channels * 4)
        handle.readFully(storage, 0, bytes)
        val result = FloatArray(samples)
        ByteBuffer.wrap(storage, 0, bytes).order(LITTLE_ENDIAN).asFloatBuffer().get(result)
        return result
    }
    override fun close() = handle.close()
}

class PcmWriter(file: File) : AutoCloseable {
    private val stream = file.outputStream().buffered()
    private var storage = ByteArray(0)
    fun write(data: FloatArray, offset: Int = 0, count: Int = data.size - offset) {
        val bytes = count * 4
        if (storage.size < bytes) storage = ByteArray(bytes)
        ByteBuffer.wrap(storage, 0, bytes).order(LITTLE_ENDIAN).asFloatBuffer().put(data, offset, count)
        stream.write(storage, 0, bytes)
    }
    override fun close() = stream.close()
}

object AudioImport {
    suspend fun decode(context: Context, uri: Uri, output: File, progress: Progress): AudioInfo {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.buffered()?.use { input ->
            readWav(input, output, progress)?.let { return it }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("対応する音声トラックが見つかりません")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            validateAudio(rate, channels)
            var frames = 0L
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(65536, 8_388_608) else 524288
                val data = ByteBuffer.allocateDirect(capacity).order(LITTLE_ENDIAN)
                PcmWriter(output).use { writer ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        data.clear()
                        val size = extractor.readSampleData(data, 0)
                        if (size < 0) break
                        data.position(0)
                        data.limit(size)
                        frames += writePcm(data, channels, encoding, writer)
                        if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                }
            } else {
                val codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    var encoding = AudioFormat.ENCODING_PCM_16BIT
                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputDone = false
                    var finished = false
                    PcmWriter(output).use { writer ->
                        while (!finished) {
                            currentCoroutineContext().ensureActive()
                            if (!inputDone) {
                                val index = codec.dequeueInputBuffer(5_000)
                                if (index >= 0) {
                                    val input = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                                    val size = extractor.readSampleData(input, 0)
                                    if (size < 0) {
                                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                        extractor.advance()
                                    }
                                }
                            }
                            val index = codec.dequeueOutputBuffer(bufferInfo, 5_000)
                            when {
                                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val decoded = codec.outputFormat
                                    val nextRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val nextChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    validateAudio(nextRate, nextChannels)
                                    require(frames == 0L || (nextRate == rate && nextChannels == channels)) {
                                        "途中で音声形式が変わるファイルには対応していません"
                                    }
                                    rate = nextRate
                                    channels = nextChannels
                                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                }
                                index >= 0 -> {
                                    try {
                                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                            val data = requireNotNull(codec.getOutputBuffer(index)).order(LITTLE_ENDIAN)
                                            data.limit(bufferInfo.offset + bufferInfo.size)
                                            data.position(bufferInfo.offset)
                                            frames += writePcm(data, channels, encoding, writer)
                                        }
                                        finished = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                        if (durationUs > 0) progress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                                    } finally {
                                        codec.releaseOutputBuffer(index, false)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    codec.release()
                }
            }
            require(frames > 0) { "音声が空です" }
            return AudioInfo(rate, channels, frames)
        } finally {
            extractor.release()
        }
    }

    private suspend fun readWav(input: BufferedInputStream, output: File, progress: Progress): AudioInfo? {
        val header = readBytes(input, 12)
        if (header.size < 12 || header.decodeToString(0, 4) != "RIFF" || header.decodeToString(8, 12) != "WAVE") return null
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var alignment = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val bytes = readBytes(input, 8)
            if (bytes.size < 8) return null
            val name = bytes.decodeToString(0, 4)
            val size = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN).getInt(4).toUInt().toLong()
            when (name) {
                "fmt " -> {
                    if (size !in 16..4096) return null
                    val b = readBytes(input, size.toInt())
                    if (b.size != size.toInt()) return null
                    val chunk = ByteBuffer.wrap(b).order(LITTLE_ENDIAN)
                    format = chunk.short.toInt() and 0xffff
                    channels = chunk.short.toInt() and 0xffff
                    rate = chunk.int
                    chunk.int
                    alignment = chunk.short.toInt() and 0xffff
                    bits = chunk.short.toInt() and 0xffff
                    if (format == 0xfffe && b.size >= 40) format = chunk.getInt(24)
                }
                "data" -> {
                    val encoding = when {
                        format == 1 && bits == 8 -> AudioFormat.ENCODING_PCM_8BIT
                        format == 1 && bits == 16 -> AudioFormat.ENCODING_PCM_16BIT
                        format == 1 && bits == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                        format == 1 && bits == 32 -> AudioFormat.ENCODING_PCM_32BIT
                        format == 3 && bits == 32 -> AudioFormat.ENCODING_PCM_FLOAT
                        else -> return null
                    }
                    validateAudio(rate, channels)
                    require(alignment == channels * (bits / 8) && size >= alignment && size % alignment == 0L) {
                        "WAV のデータサイズが正しくありません"
                    }
                    val buffer = ByteArray(block * alignment)
                    var remaining = size
                    PcmWriter(output).use { writer ->
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val length = minOf(buffer.size.toLong(), remaining).toInt()
                            var offset = 0
                            while (offset < length) {
                                val received = input.read(buffer, offset, length - offset)
                                require(received > 0) { "WAV が途中で終了しています" }
                                offset += received
                            }
                            writePcm(ByteBuffer.wrap(buffer, 0, length).order(LITTLE_ENDIAN), channels, encoding, writer)
                            remaining -= length
                            progress(1f - remaining.toFloat() / size)
                        }
                    }
                    return AudioInfo(rate, channels, size / alignment)
                }
                else -> skip(input, size)
            }
            if (size % 2 != 0L) skip(input, 1)
        }
    }

    private fun readBytes(input: BufferedInputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) return bytes.copyOf(offset)
            offset += count
        }
        return bytes
    }

    private fun skip(input: BufferedInputStream, size: Long) {
        var left = size
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped
            else {
                require(input.read() >= 0) { "WAV が途中で終了しています" }
                left--
            }
        }
    }

    private fun validateAudio(rate: Int, channels: Int) {
        require(channels in 1..2) { "モノラルまたはステレオの音声を選択してください" }
        require(rate in 8000..192000) { "8〜192 kHz の音声を選択してください" }
    }

    private fun writePcm(buffer: ByteBuffer, channels: Int, encoding: Int, writer: PcmWriter): Int {
        val width = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("未対応の PCM 形式です")
        }
        val frames = buffer.remaining() / (width * channels)
        val result = FloatArray(minOf(block, frames) * channels)
        var remaining = frames
        while (remaining > 0) {
            val count = minOf(result.size, remaining * channels)
            for (i in 0 until count) {
                result[i] = when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val low = buffer.get().toInt() and 255
                        val middle = buffer.get().toInt() and 255
                        (low or (middle shl 8) or (buffer.get().toInt() shl 16)) / 8388608f
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                    else -> buffer.float.let { if (it.isFinite()) it.coerceIn(-8f, 8f) else 0f }
                }
            }
            writer.write(result, 0, count)
            remaining -= count / channels
        }
        return frames
    }
}

enum class Listen(val label: String) {
    MIX("ミックス"), VOCAL("ボーカル"), BACKING("伴奏"), ORIGINAL("原曲"),
}

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    WAV("WAV · 24 bit", "wav", "audio/wav"),
    FLAC("FLAC · 16 bit", "flac", "audio/flac"),
    AAC("M4A · AAC", "m4a", "audio/mp4"),
}

class MixReader(
    store: ProjectStore, project: Project, rendered: File?, private val listen: Listen, private val mixer: Mix = project.mix,
) : AutoCloseable {
    private val channels = project.info.channels
    private val vocal = if (listen != Listen.BACKING) PcmReader(
        if (listen == Listen.ORIGINAL) store.original(project.id) else rendered ?: store.vocal(project), channels
    ) else null
    private val backing = if (listen == Listen.MIX || listen == Listen.BACKING) {
        store.backing(project)?.let { PcmReader(it, channels) }
    } else null
    fun read(first: Long, frames: Int, before: Mix = mixer, after: Mix = before): FloatArray {
        val lead = vocal?.read(first, frames)
        val base = backing?.read(first, frames)
        if (listen == Listen.ORIGINAL) return requireNotNull(lead)
        val output = lead ?: requireNotNull(base) { "伴奏を分離してください" }
        if (base == null && before.vocal == 1f && after.vocal == 1f) return output
        for (i in output.indices) {
            val blend = (i / channels + 1).toFloat() / frames
            val vocalGain = before.vocal + (after.vocal - before.vocal) * blend
            val backingGain = before.backing + (after.backing - before.backing) * blend
            output[i] = (lead?.get(i) ?: 0f) * vocalGain + (base?.get(i) ?: 0f) * backingGain
        }
        return output
    }

    override fun close() {
        try { vocal?.close() } finally { backing?.close() }
    }
}

object AudioExport {
    suspend fun encode(file: File, info: AudioInfo, mix: MixReader, format: ExportFormat, progress: Progress) {
        var peak = 0f
        var at = 0L
        while (at < info.frames) {
            currentCoroutineContext().ensureActive()
            val count = minOf(block.toLong(), info.frames - at).toInt()
            for (sample in mix.read(at, count)) peak = maxOf(peak, abs(sample))
            at += count
            progress(0.18f * at / info.frames)
        }
        val ceiling = if (format == ExportFormat.AAC) 0.89f else 0.995f
        val gain = if (peak > ceiling) ceiling / peak else 1f
        if (format == ExportFormat.WAV) {
            file.outputStream().buffered().use { output -> wav(output, info, mix, gain, progress) }
        } else encodeCodec(file, info, mix, format, gain, progress)
    }

    private suspend fun wav(output: OutputStream, info: AudioInfo, mix: MixReader, gain: Float, progress: Progress) {
        val bytes = info.frames * info.channels * 3
        val riffSize = bytes + 36 + bytes % 2
        require(riffSize <= 0xffffffffL) { "WAV の 4 GB 上限を超えます" }
        val header = ByteBuffer.allocate(44).order(LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(riffSize.toInt()).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(info.channels.toShort()).putInt(info.rate)
        header.putInt(info.rate * info.channels * 3).putShort((info.channels * 3).toShort()).putShort(24)
        header.put("data".toByteArray()).putInt(bytes.toInt())
        output.write(header.array())
        val storage = ByteArray(block * info.channels * 3)
        var at = 0L
        while (at < info.frames) {
            currentCoroutineContext().ensureActive()
            val count = minOf(block.toLong(), info.frames - at).toInt()
            val samples = mix.read(at, count)
            for (i in samples.indices) {
                val value = (samples[i] * gain * 8388608).roundToInt().coerceIn(-8388608, 8388607)
                val j = i * 3
                storage[j] = value.toByte()
                storage[j + 1] = (value shr 8).toByte()
                storage[j + 2] = (value shr 16).toByte()
            }
            output.write(storage, 0, samples.size * 3)
            at += count
            progress(0.18f + 0.82f * at / info.frames)
        }
        if (bytes % 2 != 0L) output.write(0)
    }

    private suspend fun encodeCodec(
        file: File, info: AudioInfo, mix: MixReader, format: ExportFormat, gain: Float, progress: Progress,
    ) {
        val aac = format == ExportFormat.AAC
        val rate = if (aac && info.rate !in listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000)) 48000 else info.rate
        val total = if (rate == info.rate) info.frames else (info.frames * rate + info.rate / 2) / info.rate
        val mime = if (aac) MediaFormat.MIMETYPE_AUDIO_AAC else MediaFormat.MIMETYPE_AUDIO_FLAC
        val config = MediaFormat.createAudioFormat(mime, rate, info.channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            if (aac) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (info.channels == 1) 128_000 else 256_000)
            } else setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 6)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        var output: OutputStream? = null
        var muxTrack = -1
        try {
            if (aac) muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            else output = file.outputStream().buffered()
            codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var at = 0L
            var inputDone = false
            var outputDone = false
            var wroteFlacHeader = false
            val infoBuffer = MediaCodec.BufferInfo()
            val bytes = ByteArray(65_536)
            fun writeFlac(data: ByteBuffer) {
                while (data.hasRemaining()) {
                    val size = minOf(bytes.size, data.remaining())
                    data.get(bytes, 0, size)
                    requireNotNull(output).write(bytes, 0, size)
                }
            }
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).order(LITTLE_ENDIAN).apply { clear() }
                        val count = minOf((buffer.capacity() / (info.channels * 2)).toLong(), total - at, block.toLong()).toInt()
                        val timestamp = at * 1_000_000 / rate
                        if (count == 0) {
                            codec.queueInputBuffer(index, 0, 0, timestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val samples = if (rate == info.rate) mix.read(at, count) else {
                                val step = info.rate.toDouble() / rate
                                val origin = at * step
                                val begin = (origin.toLong() - 96).coerceAtLeast(0)
                                val end = ((at + count) * step).toLong().plus(96).coerceAtMost(info.frames)
                                NativeDsp.resample(mix.read(begin, (end - begin).toInt()), info.channels, info.rate, rate, origin - begin, count)
                            }
                            for (sample in samples) buffer.putShort((sample * gain * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                            codec.queueInputBuffer(index, 0, buffer.position(), timestamp, 0)
                            at += count
                            progress(0.18f + 0.8f * at / total)
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(infoBuffer, 5_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (aac) {
                            muxTrack = requireNotNull(muxer).addTrack(codec.outputFormat)
                            muxer.start()
                        } else if (!wroteFlacHeader) {
                            codec.outputFormat.getByteBuffer("csd-0")?.duplicate()?.let {
                                writeFlac(it)
                                wroteFlacHeader = true
                            }
                        }
                    }
                    index >= 0 -> {
                        try {
                            if (infoBuffer.size > 0) {
                                val data = requireNotNull(codec.getOutputBuffer(index))
                                data.limit(infoBuffer.offset + infoBuffer.size)
                                data.position(infoBuffer.offset)
                                val isConfig = infoBuffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (aac && !isConfig) requireNotNull(muxer).writeSampleData(muxTrack, data, infoBuffer)
                                if (!aac && (!isConfig || !wroteFlacHeader)) {
                                    writeFlac(data)
                                    wroteFlacHeader = true
                                }
                            }
                            outputDone = infoBuffer.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            if (aac) requireNotNull(muxer).stop()
            if (!aac) require(wroteFlacHeader)
        } finally {
            try { codec.release() } finally {
                try { muxer?.release() } finally { output?.close() }
            }
        }
        if (!aac) {
            RandomAccessFile(file, "r").use {
                require(it.readInt() == 0x664c6143) { "この端末では FLAC ヘッダーを作成できません" }
            }
        }
        progress(1f)
    }
}
