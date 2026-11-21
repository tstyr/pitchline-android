package com.pitchline.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DeviceAudioTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val context get() = test.targetContext

    @Test fun importsAppleAndroidAndLosslessFormatsAndMeasuresTheirPitch() = runBlocking {
        val directory = File(context.cacheDir, "import-tests").apply { mkdirs() }
        try {
            for (extension in listOf("wav", "mp3", "flac", "m4a", "ogg")) {
                val fixture = fixture(directory, extension)
                val pcm = File(directory, "$extension.pcm")
                val info = AudioImport.decode(context, Uri.fromFile(fixture), pcm) {}
                val pitch = Processing.analyze(pcm, info) {}
                assertEquals(extension, 48000, info.rate)
                assertEquals(extension, 2, info.channels)
                assertEquals(extension, 1.2, info.seconds, 0.08)
                assertEquals(extension, 215f, pitch.f0[45], 1.3f)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun reopenAndEncodeAnActuallyEditedTrack() = runBlocking {
        val store = ProjectStore(context)
        val id = store.newId()
        val directory = store.directory(id).apply { mkdirs() }
        try {
            val input = fixture(directory, "wav")
            val info = AudioImport.decode(context, Uri.fromFile(input), store.original(id)) {}
            val analysis = Processing.analyze(store.original(id), info) {}
            val original = segment(analysis)
            val edited = original.map { it.copy(controls = Controls(semitones = 1, cents = 50f)) }
            val history = NoteHistory(original).edit(original, edited)
            val project = Project(id, Uri.fromFile(input).toString(), input.name, info, history)
            store.writeAnalysis(id, "original", analysis)
            store.save(project)
            val reopened = ProjectStore(context).load(id)
            assertEquals(original, reopened.history.back().notes)
            val restored = store.readAnalysis(reopened)
            val target = targetCurve(restored, reopened.history.notes, info.rate)
            val preview = File(directory, "preview.pcm")
            Processing.render(store.original(id), preview, info, restored, target, true) {}
            val expected = frequency(midi(215f) + 1.5f)
            for (format in ExportFormat.entries) {
                val exported = File(directory, "export.${format.extension}")
                MixReader(store, reopened, preview, Listen.MIX).use {
                    AudioExport.encode(exported, info, it, format) {}
                }
                val roundTrip = File(directory, "export.pcm")
                val decoded = AudioImport.decode(context, Uri.fromFile(exported), roundTrip) {}
                val outputPitch = Processing.analyze(roundTrip, decoded) {}
                assertEquals(format.name, expected, outputPitch.f0[45], 1.4f)
                assertEquals(format.name, info.seconds, decoded.seconds, 0.075)
                if (format == ExportFormat.WAV) assertEquals(info.frames, decoded.frames)
            }
        } finally {
            store.deleteProject(id)
        }
    }

    @Test fun bothCpuModelsDownloadVerifiedWeightsAndProduceAlignedStems() = runBlocking {
        val models = ModelStore(context)
        val directory = File(context.cacheDir, "separation-test").apply { mkdirs() }
        try {
            val fixture = fixture(directory, "wav")
            val source = File(directory, "original.pcm")
            val info = AudioImport.decode(context, Uri.fromFile(fixture), source) {}
            for (mode in SeparationMode.entries) {
                val graph = models.prepare(mode) {}
                assertTrue("Verified model must be cached", models.downloaded(mode))
                val vocals = File(directory, "vocals.pcm")
                val backing = File(directory, "backing.pcm")
                Separator.separate(graph, source, vocals, backing, info, mode) {}
                assertEquals(source.length(), vocals.length())
                assertEquals(source.length(), backing.length())
                val raw = PcmReader(source, info.channels).use { it.read(12000, 16000) }
                val voice = PcmReader(vocals, info.channels).use { it.read(12000, 16000) }
                val base = PcmReader(backing, info.channels).use { it.read(12000, 16000) }
                assertTrue(voice.all(Float::isFinite))
                assertTrue(voice.any { abs(it) > 0.000001f })
                assertTrue(base.any { abs(it) > 0.000001f })
                assertTrue(raw.indices.all { abs(raw[it] - (voice[it] + base[it])) < 0.000002f })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fixture(directory: File, extension: String) = File(directory, "reference.$extension").apply {
        test.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }
}
