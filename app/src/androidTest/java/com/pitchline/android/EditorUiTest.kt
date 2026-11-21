package com.pitchline.android

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
