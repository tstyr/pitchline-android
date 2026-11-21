package com.pitchline.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

@RunWith(AndroidJUnit4::class)
class CutterAudioTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private val context get() = test.targetContext

    @Test fun deletedMiddleIsAbsentFromAllExportsAndSplitAloneKeepsEverySample() = runBlocking {
        val dir = File(context.cacheDir, "cut-joined").apply { mkdirs() }
        try {
            val original = File(dir, "original.wav")
            test.context.assets.open("reference.wav").use { input -> original.outputStream().use { input.copyTo(it) } }
            val bytes = original.readBytes()
            val source = CutterAudio.inspect(context, Uri.fromFile(original))
            val full = File(dir, "full.pcm")
            val info = AudioImport.decode(context, source.uri, full) {}
            val clips = listOf(CutRange(0, 250), CutRange(750, 1000))
            val expected = PcmReader(full, info.channels).use { it.read(0, 12000) + it.read(36000, 12000) }
            val pcm = File(dir, "joined.pcm")
            for (format in ExportFormat.entries) {
                val output = File(dir, "joined.${format.extension}")
                CutterAudio.exportClips(context, source, clips, Uri.fromFile(output), format) {}
                val joined = AudioImport.decode(context, Uri.fromFile(output), pcm) {}
                assertEquals(format.name, 0.5, joined.seconds, if (format == ExportFormat.AAC) 0.075 else 0.00001)
                if (format != ExportFormat.AAC) PcmReader(pcm, joined.channels).use {
                    assertArrayEquals(format.name, expected, it.read(0, joined.frames.toInt()), 0.00004f)
                }
            }
            val splitOnly = File(dir, "split-only.wav")
            CutterAudio.exportClips(context, source, listOf(CutRange(0, 250), CutRange(250, 1000)), Uri.fromFile(splitOnly), ExportFormat.WAV) {}
            val splitInfo = AudioImport.decode(context, Uri.fromFile(splitOnly), pcm) {}
            assertEquals(48000L, splitInfo.frames)
            PcmReader(full, info.channels).use { before -> PcmReader(pcm, info.channels).use { after ->
                assertArrayEquals(before.read(0, 48000), after.read(0, 48000), 0.000001f)
            } }
            assertArrayEquals(bytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellingCutKeepsOriginalAndRemovesTemporaryAndPartialFiles() = runBlocking {
        val dir = File(context.cacheDir, "cut-cancel").apply { mkdirs() }
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("cut-export-") }.map { it.name }.toSet()
        try {
            val original = File(dir, "original.wav")
            test.context.assets.open("reference.wav").use { input -> original.outputStream().use { input.copyTo(it) } }
            val bytes = original.readBytes()
            val source = CutterAudio.inspect(context, Uri.fromFile(original))
            val output = File(dir, "cancelled.wav").apply { writeText("") }
            try {
                CutterAudio.export(context, source, CutRange(0, 1000), Uri.fromFile(output), ExportFormat.WAV) {
                    if (it > 0.05f) throw CancellationException("Test cancellation during decoding")
                }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertFalse(output.exists())
            assertArrayEquals(bytes, original.readBytes())
            assertEquals(before, context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("cut-export-") }.map { it.name }.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun selectedHalfSecondIsDecodedFromAllFiveInputFormats() = runBlocking {
        val dir = File(context.cacheDir, "cut-formats").apply { mkdirs() }
        try {
            for (extension in listOf("wav", "mp3", "flac", "m4a", "ogg")) {
                val file = File(dir, "reference.$extension")
                test.context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
                val source = CutterAudio.inspect(context, Uri.fromFile(file))
                assertTrue(extension, source.durationMs >= 1100)
                val pcm = File(dir, "selected.pcm")
                val info = AudioImport.decodeRange(context, source.uri, pcm, CutRange(250, 750)) {}
                assertEquals(extension, 0.5, info.seconds, 0.025)
                assertEquals(extension, info.frames * info.channels * 4, pcm.length())
                val pitch = Processing.analyze(pcm, info) {}
                assertEquals(extension, 215f, pitch.f0[20], 2f)
                if (extension == "wav" || extension == "flac") {
                    val full = File(dir, "full.pcm")
                    AudioImport.decode(context, source.uri, full) {}
                    val expected = PcmReader(full, info.channels).use { it.read(12000, 24000) }
                    val actual = PcmReader(pcm, info.channels).use { it.read(0, info.frames.toInt()) }
                    assertArrayEquals(extension, expected, actual, 0.00001f)
                }
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun sixHourSparseWavSeeksPastTwoGiBAndExportsOnlyTheChosenQuarterSecond() = runBlocking {
        val dir = File(context.cacheDir, "cut-six-hours").apply { mkdirs() }
        try {
            val source = File(dir, "six-hours.wav")
            val durationMs = 6L * 3_600_000
            val range = CutRange(5L * 3_600_000 + 45 * 60_000, 5L * 3_600_000 + 45 * 60_000 + 250)
            val dataBytes = durationMs * 48 * 4
            val firstFrame = range.startMs * 48
            val samples = ByteBuffer.allocate(12000 * 4).order(LITTLE_ENDIAN)
            repeat(12000) { i -> samples.putShort(((i % 1000 - 500) * 20).toShort()); samples.putShort(8000) }
            RandomAccessFile(source, "rw").use {
                val header = ByteBuffer.allocate(44).order(LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt((dataBytes + 36).toInt()).put("WAVEfmt ".toByteArray())
                header.putInt(16).putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
                header.put("data".toByteArray()).putInt(dataBytes.toInt())
                it.write(header.array()); it.setLength(dataBytes + 44)
                it.seek(44 + firstFrame * 4); it.write(samples.array())
            }
            assertTrue(firstFrame * 4 > Int.MAX_VALUE)
            val metadata = CutterAudio.inspect(context, Uri.fromFile(source))
            assertEquals(durationMs, metadata.durationMs)
            val pcm = File(dir, "cut.pcm")
            val info = AudioImport.decodeRange(context, metadata.uri, pcm, range) {}
            assertEquals(12000L, info.frames)
            assertEquals(96000L, pcm.length())
            val actual = PcmReader(pcm, 2).use { it.read(0, 12000) }
            assertEquals(-10000 / 32768f, actual[0], 0f)
            assertEquals(8000 / 32768f, actual.last(), 0f)
            assertEquals(1200, CutterAudio.waveform(context, metadata, range).size)
            for (format in ExportFormat.entries) {
                val output = File(dir, "cut.${format.extension}")
                CutterAudio.export(context, metadata, range, Uri.fromFile(output), format) {}
                val decoded = AudioImport.decode(context, Uri.fromFile(output), pcm) {}
                assertEquals(format.name, 0.25, decoded.seconds, 0.075)
                assertTrue(format.name, output.length() in 1..100_000)
            }
            try {
                CutterAudio.export(context, metadata, range, metadata.uri, ExportFormat.WAV) {}
                fail("Must reject overwriting the original")
            } catch (_: IllegalArgumentException) { assertEquals(dataBytes + 44, source.length()) }
        } finally { dir.deleteRecursively() }
    }
}
