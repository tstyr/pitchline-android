package com.pitchline.android

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.math.sin

class EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cutterPlaysSkipsSwipesPinchesSplitsDeletesAndUndoes() {
        compose.onNodeWithTag("open-cutter").performClick()
        compose.onNodeWithTag("cutter").assertIsDisplayed()
        val file = File(compose.activity.cacheDir, "cutter-ui.wav")
        val bytes = 120_000 * 8 * 2
        val header = ByteBuffer.allocate(44).order(LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(bytes + 36).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(bytes)
        RandomAccessFile(file, "rw").use { out ->
            out.write(header.array())
            val audio = ByteBuffer.allocate(8000 * 2).order(LITTLE_ENDIAN)
            repeat(120) { second ->
                audio.clear()
                repeat(8000) { i ->
                    val time = second + i / 8000.0
                    audio.putShort((sin(time * 215 * 2 * Math.PI) * (0.25 + 0.2 * sin(time * 2)) * 32767).toInt().toShort())
                }
                out.write(audio.array())
            }
        }
        lateinit var model: CutterModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[CutterModel::class.java]
            model.open(Uri.fromFile(file))
        }
        compose.waitUntil(30_000) { model.state.value.source != null }
        compose.onNodeWithTag("cut-forward15").performClick()
        compose.runOnIdle { assertEquals(15_000L, model.state.value.edit.positionMs) }
        compose.onNodeWithTag("cut-back15").performClick()
        compose.runOnIdle { assertEquals(0L, model.state.value.edit.positionMs) }
        compose.onNodeWithTag("cut-play").performClick()
        compose.waitUntil(10_000) { model.state.value.playing }
        compose.waitUntil(5000) { model.state.value.edit.positionMs > 100 }
        compose.onNodeWithTag("cut-play").performClick()
        compose.runOnIdle { assertTrue(!model.state.value.playing) }
        assertNull(model.state.value.message)
        compose.onNodeWithTag("cut-wave").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.runOnIdle { assertTrue(model.state.value.edit.positionMs in 14_900..15_100) }
        compose.onNodeWithTag("cut-split").performClick()
        compose.runOnIdle { assertEquals(2, model.state.value.edit.clips.size) }
        compose.onNodeWithTag("cut-delete").performClick()
        compose.runOnIdle { assertTrue(model.state.value.edit.durationMs in 14_900..15_100) }
        compose.onNodeWithTag("cut-undo").performClick()
        compose.runOnIdle { assertEquals(120_000L, model.state.value.edit.durationMs) }
        compose.onNodeWithTag("cut-redo").performClick()
        compose.runOnIdle { assertEquals(1, model.state.value.edit.clips.size) }
        compose.onNodeWithTag("cut-undo").performClick()
        val span = model.state.value.window!!.lengthMs
        compose.onNodeWithTag("cut-wave").performTouchInput {
            down(0, Offset(width * 0.4f, height / 2f)); down(1, Offset(width * 0.6f, height / 2f))
            repeat(10) { i ->
                val delta = (i + 1) * width * 0.02f
                moveTo(0, Offset(width * 0.4f - delta, height / 2f), delayMillis = 16)
                moveTo(1, Offset(width * 0.6f + delta, height / 2f), delayMillis = 16)
            }
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(model.state.value.window!!.lengthMs < span / 2) }
        val start = model.state.value.window!!.startMs
        compose.onNodeWithTag("cut-wave").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(model.state.value.window!!.startMs > start) }
        // Tap a clip, and jump through the optional precise-time dialog.
        compose.onNodeWithTag("cut-position").performClick()
        compose.onNodeWithTag("cut-jump-time").performTextReplacement("00:00:45.000")
        compose.onNodeWithTag("cut-jump-apply").performClick()
        compose.runOnIdle { assertEquals(45_000L, model.state.value.edit.positionMs) }
        compose.onNodeWithTag("cut-split").performClick()
        compose.runOnIdle { assertEquals(3, model.state.value.edit.clips.size) }
        compose.onNodeWithTag("cut-wave").assertIsDisplayed()
        compose.onNodeWithTag("cut-export").performClick()
        compose.onNodeWithText("WAV").assertIsDisplayed()
        compose.onNodeWithText("FLAC").assertIsDisplayed()
        compose.onNodeWithText("M4A").assertIsDisplayed()
        compose.onNodeWithText("キャンセル").performClick()
        compose.waitUntil(30_000) { !model.state.value.waveLoading }
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "cutter-ui.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        file.delete()
    }

    @Test fun opensEditorAndUndoesAnEditFromTheTransport() {
        compose.onNodeWithTag("open-audio").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().context
        val reference = File(compose.activity.cacheDir, "ui-reference.wav")
        context.assets.open("reference.wav").use { input -> reference.outputStream().use { input.copyTo(it) } }
        lateinit var model: MainModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[MainModel::class.java]
            model.import(Uri.fromFile(reference))
        }
        compose.waitUntil(60_000) { model.state.value.editor != null && model.state.value.work == null }
        compose.onNodeWithTag("piano-roll").assertIsDisplayed()
        compose.onNodeWithContentDescription("ノート操作").performClick()
        compose.onNodeWithText("次のノート").performClick()
        compose.onNodeWithText("＋ 半音").performClick()
        compose.waitUntil(8_000) { model.state.value.editor?.project?.history?.notes?.first()?.controls?.semitones == 1 }
        compose.onNodeWithContentDescription("元に戻す").performClick()
        compose.waitUntil(8_000) { model.state.value.editor?.project?.history?.notes?.first()?.controls?.semitones == 0 }
        compose.onNodeWithContentDescription("ホーム").performClick()
        compose.onNodeWithTag("home").assertIsDisplayed()
        reference.delete()
    }
}
