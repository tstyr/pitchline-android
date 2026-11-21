package com.pitchline.android

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutterScreen(onClose: () -> Unit, model: CutterModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(ExportFormat.WAV) }
    var step by rememberSaveable { mutableStateOf(1000L) }
    var confirmClose by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.open(uri) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.export(it, format) }
    }
    val close = { if (state.exporting) confirmClose = true else { model.leave(); onClose() } }
    BackHandler(onBack = close)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        model.resumeUi()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.suspendUi() }
    }
    val view = LocalView.current
    DisposableEffect(state.exporting, view) {
        val previous = view.keepScreenOn
        if (state.exporting) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false },
        title = { Text("書き出しを中止しますか？") }, text = { Text("書き出しを中止します。元ファイルは変更しません。") },
        confirmButton = { TextButton({ model.leave(); onClose() }) { Text("中止して戻る") } },
        dismissButton = { TextButton({ confirmClose = false }) { Text("続ける") } })

    Column(Modifier.fillMaxSize().testTag("cutter").verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(close) { Text("戻る") }
            Text("オーディオカッター", style = MaterialTheme.typography.titleLarge)
        }
        Text("何時間の音声でも、時刻で区間へ直接移動。元ファイルを変更せず別ファイルに保存します。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ picker.launch(arrayOf("audio/*", "application/ogg", "application/x-flac")) },
            enabled = !state.loading && !state.exporting, modifier = Modifier.testTag("cut-open")) { Text("音声ファイルを選ぶ") }
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("音声の長さを確認中…") }
        val source = state.source
        val range = state.range
        val window = state.window
        val enabled = !state.exporting && !state.loading
        if (source != null && range != null && window != null) {
            var startText by rememberSaveable(source.uri.toString(), range.startMs) { mutableStateOf(cutTime(range.startMs)) }
            var endText by rememberSaveable(source.uri.toString(), range.endMs) { mutableStateOf(cutTime(range.endMs)) }
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text("全長 ${cutTime(source.durationMs)}  ·  ${source.rate} Hz  ·  ${source.channels} ch", fontFamily = FontFamily.Monospace)
            Text("全体から大まかに選ぶ", style = MaterialTheme.typography.labelLarge)
            RangeSlider(
                value = (range.startMs.toDouble() / source.durationMs).toFloat()..(range.endMs.toDouble() / source.durationMs).toFloat(),
                onValueChange = { values ->
                    val start = (values.start.toDouble() * source.durationMs).roundToLong()
                    val end = (values.endInclusive.toDouble() * source.durationMs).roundToLong()
                    if (start != range.startMs && kotlin.math.abs(start - range.startMs) >= kotlin.math.abs(end - range.endMs)) model.setBoundary(CutEdge.START, start)
                    else model.setBoundary(CutEdge.END, end)
                }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("cut-overview"))
            CutTimeField("開始", startText, { startText = it }, enabled, "cut-start") { model.setTime(CutEdge.START, it) }
            CutTimeField("終了", endText, { endText = it }, enabled, "cut-end") { model.setTime(CutEdge.END, it) }
            Text("切り出す長さ  ${cutTime(range.lengthMs)}", color = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("cut-duration"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CutEdge.entries.forEach { edge -> FilterChip(selected = state.edge == edge, onClick = { model.edge(edge) },
                    label = { Text("${edge.label}を調整") }, enabled = enabled) }
                OutlinedButton(model::showAll, enabled = enabled) { Text("全体") }
                OutlinedButton(model::showSelection, enabled = enabled) { Text("選択範囲") }
                OutlinedButton({ model.zoom(0.5) }, enabled = enabled) { Text("拡大＋") }
                OutlinedButton({ model.zoom(2.0) }, enabled = enabled) { Text("縮小−") }
            }
            Text("拡大表示  ${cutTime(window.startMs)} 〜 ${cutTime(window.endMs)}", style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            CutWave(state)
            if (state.waveLoading) Text("この区間の波形を読み込み中…", style = MaterialTheme.typography.bodySmall)
            if (window.lengthMs > 30_000) Text("30秒以内まで拡大すると波形を表示します。全曲の解析は行いません。", style = MaterialTheme.typography.bodySmall)
            state.waveError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val boundary = if (state.edge == CutEdge.START) range.startMs else range.endMs
            Slider(value = ((boundary - window.startMs).toDouble() / window.lengthMs).toFloat().coerceIn(0f, 1f),
                onValueChange = { model.setBoundary(state.edge, window.startMs + (it.toDouble() * window.lengthMs).roundToLong()) },
                enabled = enabled, modifier = Modifier.testTag("cut-fine").semantics { contentDescription = "${state.edge.label}時刻の微調整" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10L to "10 ms", 1000L to "1秒", 10_000L to "10秒", 60_000L to "1分").forEach { (ms, label) ->
                    FilterChip(selected = step == ms, onClick = { step = ms }, label = { Text(label) }, enabled = enabled)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.nudge(-step) }, enabled = enabled, modifier = Modifier.testTag("cut-minus")) { Text("− 戻す") }
                OutlinedButton({ model.nudge(step) }, enabled = enabled, modifier = Modifier.testTag("cut-plus")) { Text("＋ 進める") }
                Text("${state.edge.label}を調整", modifier = Modifier.padding(top = 12.dp))
            }
            HorizontalDivider()
            Text("試聴位置 ${cutTime(state.positionMs)}", fontFamily = FontFamily.Monospace)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ if (model.setRangeText(startText, endText)) model.preview() }, enabled = enabled && !state.preparing) { Text("開始から試聴") }
                OutlinedButton({ if (model.setRangeText(startText, endText)) model.preview(nearEnd = true) }, enabled = enabled && !state.preparing) { Text("終了前5秒を試聴") }
                OutlinedButton(model::stop, enabled = state.playing || state.preparing) { Text("停止") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ model.setBoundary(CutEdge.START, state.positionMs) }, enabled = enabled) { Text("再生位置を開始に") }
                OutlinedButton({ model.setBoundary(CutEdge.END, state.positionMs) }, enabled = enabled) { Text("再生位置を終了に") }
            }
            Text("試聴のシーク位置は端末のコーデックに依存します。書き出しでは指定時刻の PCM 区間を切り出します。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportFormat.entries.forEach { entry -> FilterChip(selected = format == entry, onClick = { format = entry },
                    label = { Text(entry.label) }, enabled = enabled) }
            }
            Button(onClick = {
                if (model.setRangeText(startText, endText)) {
                    model.stop()
                    val applied = requireNotNull(model.state.value.range)
                    save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = format.mime
                        putExtra(Intent.EXTRA_TITLE, "${source.name.substringBeforeLast('.')}-cut-${applied.startMs}-${applied.endMs}.${format.extension}")
                    })
                }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("cut-export")) { Text("選択した区間を保存") }
            if (state.exporting) {
                Text("選択区間を書き出し中 ${state.percent}% · この画面を開いたままお待ちください")
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                TextButton(model::cancelExport) { Text("書き出しを中止") }
            }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("cut-message")) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CutTimeField(label: String, text: String, onText: (String) -> Unit, enabled: Boolean, tag: String, apply: (String) -> Boolean) {
    var invalid by remember(text) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val applyTime: () -> Unit = { invalid = !apply(text); if (!invalid) focus.clearFocus() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = text, onValueChange = onText, singleLine = true,
            label = { Text("$label（時:分:秒.ミリ秒）") }, enabled = enabled, isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { applyTime() }),
            modifier = Modifier.weight(1f).testTag(tag))
        TextButton(onClick = applyTime, enabled = enabled, modifier = Modifier.padding(top = 12.dp).testTag("$tag-apply")) { Text("適用") }
    }
}

@Composable
private fun CutWave(state: CutterState) {
    val range = state.range ?: return
    val window = state.window ?: return
    val foreground = MaterialTheme.colorScheme.secondary
    val selection = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(MaterialTheme.colorScheme.surfaceVariant).testTag("cut-wave")) {
        fun x(ms: Long) = ((ms - window.startMs).toDouble() / window.lengthMs * size.width).toFloat()
        val left = x(range.startMs).coerceIn(0f, size.width)
        val right = x(range.endMs).coerceIn(left, size.width)
        drawRect(selection.copy(alpha = 0.14f), Offset(left, 0f), Size(right - left, size.height))
        drawLine(foreground.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        state.peaks?.let { peaks ->
            for (i in peaks.indices) {
                val xx = (i + 0.5f) * size.width / peaks.size
                val amplitude = peaks[i].coerceIn(0f, 1f) * size.height * 0.45f
                drawLine(foreground, Offset(xx, size.height / 2 - amplitude), Offset(xx, size.height / 2 + amplitude), 1f)
            }
        }
        listOf(range.startMs, range.endMs).forEach { ms ->
            if (ms in window.startMs..window.endMs) drawLine(selection, Offset(x(ms), 0f), Offset(x(ms), size.height), 3.dp.toPx())
        }
        if (state.playing && state.positionMs in window.startMs..window.endMs) drawLine(Color.White,
            Offset(x(state.positionMs), 0f), Offset(x(state.positionMs), size.height), 2.dp.toPx())
    }
}
