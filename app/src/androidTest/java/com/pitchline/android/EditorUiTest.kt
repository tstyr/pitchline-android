package com.pitchline.android

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNull
import java.io.File

class EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cutterAcceptsExactTimesAndNudgesTheSelectedBoundary() {
        compose.onNodeWithTag("open-cutter").performClick()
        compose.onNodeWithTag("cutter").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(compose.activity.cacheDir, "cutter-ui.wav")
        context.assets.open("reference.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        lateinit var model: CutterModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[CutterModel::class.java]
            model.open(Uri.fromFile(file))
        }
        compose.waitUntil(30_000) { model.state.value.range != null }
        compose.onNodeWithTag("cut-start").performScrollTo().performTextReplacement("00:00:00.250")
        compose.onNodeWithTag("cut-start-apply").performClick()
        compose.onNodeWithTag("cut-end").performScrollTo().performTextReplacement("00:00:00.750")
        compose.onNodeWithTag("cut-end-apply").performClick()
        compose.waitUntil(5000) { model.state.value.range == CutRange(250, 750) }
        compose.onNodeWithText("10 ms").performScrollTo().performClick()
        compose.onNodeWithTag("cut-minus").performScrollTo().performClick()
        compose.waitUntil(5000) { model.state.value.range == CutRange(250, 740) }
        compose.onNodeWithText("開始から試聴").performScrollTo().performClick()
        compose.waitUntil(10_000) { !model.state.value.preparing }
        assertNull(model.state.value.message)
        compose.runOnUiThread { model.stop() }
        compose.onNodeWithText("戻る").performScrollTo().performClick()
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
