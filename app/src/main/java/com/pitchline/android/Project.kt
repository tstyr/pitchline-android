package com.pitchline.android

import android.content.Context
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

@Serializable
data class AudioInfo(val rate: Int, val channels: Int, val frames: Long) {
    val seconds get() = frames.toDouble() / rate
}

@Serializable
data class Mix(val vocal: Float = 1f, val backing: Float = 1f)

@Serializable
data class Stems(val key: String, val history: NoteHistory)

@Serializable
data class Project(
    val id: String,
    val uri: String,
    val name: String,
    val info: AudioInfo,
    val original: NoteHistory,
    val stems: Map<String, Stems> = emptyMap(),
    val source: String = "original",
    val mix: Mix = Mix(),
    val scale: MusicalScale = MusicalScale(),
    val modified: Long = System.currentTimeMillis(),
    val version: Int = 1,
) {
    val activeStems get() = stems[source]
    val history get() = activeStems?.history ?: original
    val analysisKey get() = activeStems?.key ?: "original"

    fun withHistory(history: NoteHistory): Project {
        val current = activeStems ?: return copy(original = history)
        return copy(stems = stems + (source to current.copy(history = history)))
    }
}

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun newId(): String = UUID.randomUUID().toString()
    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(root, id)
    }

    fun original(id: String) = File(directory(id), "original.pcm")
    fun vocal(project: Project) = project.activeStems?.let { vocal(project.id, it.key) } ?: original(project.id)
    fun vocal(id: String, key: String) = File(directory(id), "vocal-$key.pcm")
    fun backing(project: Project) = project.activeStems?.let { backing(project.id, it.key) }
    fun backing(id: String, key: String) = File(directory(id), "backing-$key.pcm")
    private fun metadata(id: String) = AtomicFile(File(directory(id), "project.pitchline"))
    private fun analysisFile(id: String, key: String) = AtomicFile(File(directory(id), "pitch-$key.bin"))

    @Synchronized
    fun save(project: Project) = atomic(metadata(project.id)) { it.write(json.encodeToString(project).toByteArray()) }

    @Synchronized
    fun load(id: String): Project {
        val project = json.decodeFromString<Project>(metadata(id).readFully().decodeToString())
        require(project.version == 1 && project.id == id) { "未対応のプロジェクト形式です" }
        return project
    }

    fun recent(): List<Project> = root.listFiles().orEmpty().mapNotNull { runCatching { load(it.name) }.getOrNull() }
        .sortedByDescending { it.modified }

    fun writeAnalysis(id: String, key: String, analysis: Analysis) = atomic(analysisFile(id, key)) { stream ->
        val out = DataOutputStream(stream.buffered())
        out.writeInt(0x50495431)
        out.writeInt(analysis.hop)
        out.writeFloat(analysis.bpm)
        out.writeInt(analysis.f0.size)
        for (i in analysis.f0.indices) {
            out.writeFloat(analysis.f0[i])
            out.writeFloat(analysis.confidence[i])
        }
        out.writeInt(analysis.waveform.size)
        analysis.waveform.forEach(out::writeFloat)
        out.flush()
    }

    fun readAnalysis(project: Project): Analysis = DataInputStream(
        analysisFile(project.id, project.analysisKey).openRead().buffered()
    ).use { input ->
        require(input.readInt() == 0x50495431) { "解析キャッシュを読み込めません" }
        val hop = input.readInt()
        val bpm = input.readFloat()
        val count = input.readInt()
        require(hop > 0 && count in 1..2_000_000)
        val f0 = FloatArray(count)
        val confidence = FloatArray(count)
        for (i in 0 until count) {
            f0[i] = input.readFloat()
            confidence[i] = input.readFloat()
        }
        val waveformSize = input.readInt()
        require(waveformSize in 1..12_000)
        Analysis(hop, f0, confidence, FloatArray(waveformSize) { input.readFloat() }, bpm)
    }

    fun deleteStem(id: String, key: String) {
        vocal(id, key).delete()
        backing(id, key).delete()
        analysisFile(id, key).delete()
    }

    fun deleteProject(id: String) = directory(id).deleteRecursively()

    private fun atomic(file: AtomicFile, writer: (java.io.FileOutputStream) -> Unit) {
        val stream = file.startWrite()
        try {
            writer(stream)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
}
