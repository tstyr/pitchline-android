package com.pitchline.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.sqrt

enum class SeparationMode(
    val key: String, val label: String, val model: String,
    val url: String, val sha256: String, val seconds: Int,
) {
    STANDARD("standard", "標準", "Open-Unmix",
        "https://zenodo.org/records/3370486/files/vocals-c8df74a5.pth",
        "c8df74a528f7bd3be90e21e9ec734d43da3a8f9c58cbb95ac5c17b0ecb52b217", 5),
    HIGH("high", "高品質", "Open-Unmix HQ",
        "https://zenodo.org/records/3370489/files/vocals-b62c91ce.pth",
        "b62c91cedbc7a066f1778ead5b5cecb377aa3a46a31af1cce7c5c8769339d083", 10),
}

class ModelStore(private val context: Context) {
    private val directory = File(context.filesDir, "models").apply { mkdirs() }
    private val bytes = 35_637_796L
    fun downloaded(mode: SeparationMode) = weight(mode).length() == bytes
    fun delete(mode: SeparationMode) = weight(mode).delete()
    private fun weight(mode: SeparationMode) = File(directory, "${mode.key}.pth")

    suspend fun prepare(mode: SeparationMode, progress: Progress): File = prepareDetailed(mode) {
        it.fraction?.let(progress)
    }

    suspend fun prepareDetailed(mode: SeparationMode, report: (ModelProgress) -> Unit): File = withContext(Dispatchers.IO) {
        val checkpoint = weight(mode)
        report(ModelProgress("モデルの保存状態を確認中"))
        if (!downloaded(mode) || sha256(checkpoint) { report(ModelProgress("保存済みモデルを検証中", it)) } != mode.sha256) {
            val mirror = "https://github.com/tstyr/pitchline-android/releases/download/models-v1/${mode.key}.pth"
            val sources = listOf(mirror, mode.url, mirror)
            for ((index, url) in sources.withIndex()) {
                try {
                    ModelDownload().fetch(url, checkpoint, bytes, mode.sha256, report)
                    break
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (index == sources.lastIndex) throw IOException("モデル配布先に接続できません。通信を確認して再試行してください: ${error.message}", error)
                    report(ModelProgress("接続できないため別の配布先を試します"))
                    delay(750)
                }
            }
        }
        report(ModelProgress("推論モデルを準備中"))
        val graph = File(directory, "${mode.key}.onnx")
        val partial = File(directory, "${mode.key}.onnx.part")
        try {
            context.assets.open("models/${mode.key}.onnx").use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32_768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(partial.renameTo(graph)) { "モデルを保存できません" }
        } finally {
            partial.delete()
        }
        graph
    }

    private suspend fun sha256(file: File, progress: Progress): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
                total += count
                progress(total.toFloat() / bytes)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

object Separator {
    private const val modelRate = 44100
    private const val spectralHop = 1024
    private const val bins = 2049

    suspend fun separate(
        graph: File, source: File, vocal: File, backing: File,
        info: AudioInfo, mode: SeparationMode, stage: (String) -> Unit = {}, progress: Progress,
    ) {
        val env = OrtEnvironment.getEnvironment()
        val channels = info.channels
        val chunk = info.rate * mode.seconds
        val pad = if (mode == SeparationMode.HIGH) info.rate * 5 / 4 else info.rate * 3 / 4
        val overlap = info.rate / 4
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            stage("推論エンジンを準備中")
            env.createSession(graph.absolutePath, options).use { session ->
                PcmReader(source, channels).use { reader ->
                    PcmWriter(vocal).use { voice ->
                        PcmWriter(backing).use { instrument ->
                            var start = 0L
                            var previous = FloatArray(0)
                            while (start < info.frames) {
                                currentCoroutineContext().ensureActive()
                                stage("ボーカル分離 ${start / chunk + 1} / ${(info.frames + chunk - 1) / chunk}")
                                val coreEnd = minOf(start + chunk, info.frames)
                                val end = minOf(coreEnd + overlap, info.frames)
                                val inputStart = (start - pad).coerceAtLeast(0)
                                val inputEnd = minOf(end + pad, info.frames)
                                val audio = reader.read(inputStart, (inputEnd - inputStart).toInt())
                                val stereo = if (channels == 2) audio else FloatArray(audio.size * 2) { audio[it / 2] }
                                val inputFrames = stereo.size / 2
                                val modelFrames = ceil(inputFrames.toDouble() * modelRate / info.rate).toInt()
                                val modelPcm = if (info.rate == modelRate) stereo else
                                    NativeDsp.resample(stereo, 2, info.rate, modelRate, 0.0, modelFrames)
                                val isolated = infer(env, session, modelPcm)
                                val converted = if (info.rate == modelRate) isolated else
                                    NativeDsp.resample(isolated, 2, modelRate, info.rate, 0.0, inputFrames)
                                val part = if (channels == 2) converted else FloatArray(inputFrames) { (converted[2 * it] + converted[2 * it + 1]) * 0.5f }
                                val offset = (start - inputStart).toInt() * channels
                                crossfade(previous, part, offset, channels)
                                val length = (coreEnd - start).toInt() * channels
                                voice.write(part, offset, length)
                                val residual = FloatArray(length) { audio[offset + it] - part[offset + it] }
                                instrument.write(residual)
                                previous = part.copyOfRange(offset + length, (end - inputStart).toInt() * channels)
                                start = coreEnd
                                progress(start.toFloat() / info.frames)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun infer(env: OrtEnvironment, session: OrtSession, stereo: FloatArray): FloatArray {
        val spectrum = NativeDsp.stft(stereo)
        val frames = (stereo.size / 2) / spectralHop + 1
        val magnitudes = ByteBuffer.allocateDirect(spectrum.size * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in spectrum.indices step 2) magnitudes.put(sqrt(spectrum[i] * spectrum[i] + spectrum[i + 1] * spectrum[i + 1]))
        magnitudes.flip()
        OnnxTensor.createTensor(env, magnitudes, longArrayOf(frames.toLong(), 2, bins.toLong())).use { input ->
            session.run(mapOf("magnitude" to input)).use { output ->
                val buffer = (output[0] as OnnxTensor).floatBuffer
                val estimated = FloatArray(buffer.remaining())
                buffer.get(estimated)
                return NativeDsp.istft(spectrum, estimated, stereo.size / 2)
            }
        }
    }
}
