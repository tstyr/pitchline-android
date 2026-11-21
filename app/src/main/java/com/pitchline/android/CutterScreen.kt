package com.pitchline.android

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

@Composable
fun CutterScreen(onClose: () -> Unit, model: CutterModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var format by rememberSaveable { mutableStateOf(ExportFormat.AAC) }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf("") }
    var jumpText by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.open(it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.export(it, format) }
    }
    val close: () -> Unit = { if (state.exporting) dialog = "close" else { model.leave(); onClose() } }
    BackHandler { if (dialog.isNotEmpty()) dialog = "" else close() }
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
    LaunchedEffect(state.message) { if (state.message != null) { delay(5000); model.message(null) } }
    val enabled = !state.loading && !state.exporting
    val source = state.source
    val edit = state.edit
    fun openFile() = picker.launch(arrayOf("audio/*", "application/ogg", "application/x-flac"))
    fun jump() { jumpText = cutTime(edit.positionMs); dialog = "jump" }

    Box(Modifier.fillMaxSize().testTag("cutter")) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                Text("カッター", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ model.stop(); dialog = "save" }, enabled = enabled && edit.clips.isNotEmpty(), modifier = Modifier.testTag("cut-export")) { Text("保存") }
                Box {
                    IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "その他") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("音声を開く") }, onClick = { menu = false; openFile() })
                        DropdownMenuItem(text = { Text("全体を表示") }, enabled = edit.durationMs > 0, onClick = { menu = false; model.showAll() })
                        DropdownMenuItem(text = { Text("時刻へ移動") }, enabled = edit.durationMs > 0, onClick = { menu = false; jump() })
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (source == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button({ openFile() }, enabled = enabled, modifier = Modifier.testTag("cut-open")) { Text("音声を開く") }
                }
            } else {
                Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp).testTag("cut-position").clickable(enabled && edit.durationMs > 0) { jump() },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(cutTime(edit.positionMs), fontSize = 23.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Text("/ ${cutTime(edit.durationMs).substringBefore('.')}", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    CutterWaveEditor(state, model, enabled)
                    if (state.waveLoading) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                    if (state.exporting) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("保存中 ${state.percent}%")
                            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.width(180.dp).padding(vertical = 12.dp))
                            TextButton(model::cancelExport) { Text("中止") }
                        }
                    }
                }
                CutOverview(state, model, enabled, Modifier.fillMaxWidth().height(38.dp).padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val selected = edit.clips.indexOfFirst { it.startMs == edit.selected }
                    Text(if (selected >= 0) "クリップ ${selected + 1} / ${edit.clips.size}" else "クリップなし", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("cut-clips"))
                    Text(if (state.waveError) "波形を取得できません" else if ((state.window?.lengthMs ?: 0) > 30_000) "概略" else "",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ model.skip(-15_000) }, enabled = enabled && edit.durationMs > 0, modifier = Modifier.size(56.dp).testTag("cut-back15")) {
                        CutGlyph(CutSymbol.BACK15, "15秒戻す")
                    }
                    FilledIconButton(model::togglePlay, enabled = enabled && edit.durationMs > 0,
                        modifier = Modifier.size(64.dp).testTag("cut-play")) {
                        if (state.preparing) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                        else CutGlyph(if (state.playing) CutSymbol.PAUSE else CutSymbol.PLAY, if (state.playing) "一時停止" else "再生", Modifier.size(30.dp))
                    }
                    IconButton({ model.skip(15_000) }, enabled = enabled && edit.durationMs > 0, modifier = Modifier.size(56.dp).testTag("cut-forward15")) {
                        CutGlyph(CutSymbol.FORWARD15, "15秒進む")
                    }
                }
                Row(Modifier.fillMaxWidth().height(66.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    CutTool(CutSymbol.UNDO, "戻す", enabled && state.undoCount > 0, "cut-undo", model::undo)
                    CutTool(CutSymbol.SPLIT, "分割", enabled && edit.canSplit, "cut-split", model::split)
                    CutTool(CutSymbol.DELETE, "削除", enabled && edit.selected != null, "cut-delete", model::deleteSelected)
                    CutTool(CutSymbol.REDO, "やり直す", enabled && state.redoCount > 0, "cut-redo", model::redo)
                }
                Text("スワイプで移動 · ピンチで拡大", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            }
        }
        state.message?.let { text -> Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp),
            dismissAction = { TextButton({ model.message(null) }) { Text("閉じる") } }) { Text(text) } }
    }

    when (dialog) {
        "close" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("保存を中止しますか？") },
            confirmButton = { TextButton({ model.leave(); onClose() }) { Text("中止して戻る") } },
            dismissButton = { TextButton({ dialog = "" }) { Text("続ける") } })
        "jump" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("時刻へ移動") },
            text = { OutlinedTextField(jumpText, { jumpText = it }, singleLine = true, label = { Text("時:分:秒.ミリ秒") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.testTag("cut-jump-time")) },
            confirmButton = { TextButton({ if (model.jump(jumpText)) dialog = "" }, modifier = Modifier.testTag("cut-jump-apply")) { Text("移動") } },
            dismissButton = { TextButton({ dialog = "" }) { Text("キャンセル") } })
        "save" -> AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("音声を保存") }, text = {
            Column {
                Text(cutTime(edit.durationMs), fontFamily = FontFamily.Monospace)
                ExportFormat.entries.forEach { entry -> Row(Modifier.fillMaxWidth().clickable { format = entry }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(format == entry, { format = entry })
                    Text(when (entry) { ExportFormat.WAV -> "WAV"; ExportFormat.FLAC -> "FLAC"; ExportFormat.AAC -> "M4A" })
                } }
            }
        }, confirmButton = { TextButton({
            dialog = ""
            save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = format.mime
                putExtra(Intent.EXTRA_TITLE, "${source?.name?.substringBeforeLast('.') ?: "audio"}-edit.${format.extension}")
            })
        }) { Text("保存する") } }, dismissButton = { TextButton({ dialog = "" }) { Text("キャンセル") } })
    }
}

@Composable
private fun CutterWaveEditor(state: CutterState, model: CutterModel, enabled: Boolean) {
    val latest by rememberUpdatedState(state)
    val wave = MaterialTheme.colorScheme.secondary
    val accent = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE } }
    Canvas(Modifier.fillMaxSize().testTag("cut-wave").semantics { contentDescription = "波形。スワイプで移動、ピンチで拡大縮小" }
        .pointerInput(state.source?.uri, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                model.beginNavigation()
                var moved = false
                var total = Offset.Zero
                var last = first.position
                var pointer = first.id
                try {
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            last = pressed.first().position
                            pointer = pressed.first().id
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val focal = event.calculateCentroid(useCurrent = false)
                            if (focal.x.isFinite() && zoom.isFinite() && size.width > 0) {
                                model.transform(pan.x.toDouble() / size.width, zoom.toDouble(), focal.x.toDouble() / size.width)
                                moved = true
                                event.changes.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1) {
                            val change = pressed.first()
                            val delta = if (change.id == pointer) change.position - last else Offset.Zero
                            pointer = change.id
                            last = change.position
                            total += delta
                            if (moved || total.getDistance() > viewConfiguration.touchSlop) {
                                val pan = if (moved) delta.x else total.x
                                model.transform(pan.toDouble() / size.width.coerceAtLeast(1), 1.0, 0.5)
                                moved = true
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved) latest.window?.let { window ->
                        val at = window.startMs + (first.position.x.toDouble() / size.width.coerceAtLeast(1) * window.lengthMs).roundToLong()
                        model.seek(at, select = true)
                    }
                } finally { model.endNavigation() }
            }
        }) {
        val window = state.window ?: return@Canvas
        val ruler = 28.dp.toPx()
        if (size.height <= ruler || size.width < 2f) return@Canvas
        fun x(ms: Long) = ((ms - window.startMs).toDouble() / window.lengthMs * size.width).toFloat()
        val center = ruler + (size.height - ruler) / 2
        state.edit.pieces.forEachIndexed { index, piece ->
            if (piece.endMs >= window.startMs && piece.startMs <= window.endMs) {
                val left = x(piece.startMs).coerceIn(0f, size.width)
                val right = x(piece.endMs).coerceIn(left, size.width)
                val selected = piece.source.startMs == state.edit.selected
                drawRect(if (selected) accent.copy(alpha = 0.15f) else wave.copy(alpha = 0.035f), Offset(left, ruler), Size(right - left, size.height - ruler))
                if (selected) drawRect(accent.copy(alpha = 0.75f), Offset(left, ruler), Size(right - left, 3.dp.toPx()))
                drawLine(textColor.copy(alpha = 0.5f), Offset(left, ruler), Offset(left, size.height), 1.dp.toPx())
                if (right - left > 28.dp.toPx()) {
                    paint.color = android.graphics.Color.LTGRAY; paint.textSize = 11.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText("${index + 1}", left + 7.dp.toPx(), ruler + 19.dp.toPx(), paint)
                }
            }
        }
        drawLine(wave.copy(alpha = 0.2f), Offset(0f, center), Offset(size.width, center), 1f)
        state.peaks?.let { peaks ->
            val sampled = state.waveWindow ?: window
            val displayedWidth = size.width * sampled.lengthMs.toDouble() / window.lengthMs
            val stride = (peaks.size / displayedWidth.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
            for (i in peaks.indices step stride) {
                val amplitude = peaks[i].coerceIn(0f, 1f) * (size.height - ruler) * 0.39f
                val xx = x(sampled.startMs + ((i + 0.5) * sampled.lengthMs / peaks.size).roundToLong())
                if (xx in 0f..size.width) drawLine(wave, Offset(xx, center - amplitude), Offset(xx, center + amplitude), 1.5.dp.toPx())
            }
        }
        val tick = listOf(100L, 250L, 500L, 1000L, 5000L, 15000L, 30000L, 60000L, 300000L, 900000L, 3600000L, 21600000L)
            .firstOrNull { it.toDouble() / window.lengthMs * size.width >= 64.dp.toPx() } ?: 86_400_000L
        val first = floor(window.startMs.toDouble() / tick).toLong()
        val last = ceil(window.endMs.toDouble() / tick).toLong()
        paint.color = android.graphics.Color.LTGRAY; paint.textSize = 10.dp.toPx()
        for (i in first..last) {
            val at = i * tick
            val label = if (tick < 1000) cutTime(at).takeLast(9) else cutTime(at).substringBefore('.').let { if (at < 3_600_000) it.drop(3) else it }
            val xx = x(at)
            drawLine(textColor.copy(alpha = 0.2f), Offset(xx, ruler - 5.dp.toPx()), Offset(xx, size.height), 1f)
            drawContext.canvas.nativeCanvas.drawText(label, xx + 4.dp.toPx(), 16.dp.toPx(), paint)
        }
        if (state.edit.positionMs in window.startMs..window.endMs) {
            val head = x(state.edit.positionMs).coerceIn(1f, size.width - 1f)
            drawLine(Color.White, Offset(head, ruler), Offset(head, size.height), 2.dp.toPx())
            drawPath(Path().apply { moveTo(head - 5.dp.toPx(), ruler - 7.dp.toPx()); lineTo(head + 5.dp.toPx(), ruler - 7.dp.toPx()); lineTo(head, ruler); close() }, Color.White)
        }
    }
}

@Composable
private fun CutOverview(state: CutterState, model: CutterModel, enabled: Boolean, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(state)
    val accent = MaterialTheme.colorScheme.primary
    val wave = MaterialTheme.colorScheme.secondary
    Canvas(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant).testTag("cut-overview")
        .pointerInput(enabled) {
            if (enabled) detectTapGestures { point -> model.seek((point.x.toDouble() / size.width * latest.edit.durationMs).roundToLong(), select = true) }
        }.pointerInput(enabled) {
            if (enabled) detectDragGestures(onDragStart = { model.beginNavigation() }, onDragEnd = model::endNavigation,
                onDragCancel = model::endNavigation) { change, _ ->
                model.seek((change.position.x.toDouble() / size.width * latest.edit.durationMs).roundToLong(), select = true); change.consume()
            }
        }) {
        val duration = state.edit.durationMs
        if (duration == 0L) return@Canvas
        fun x(ms: Long) = (ms.toDouble() / duration * size.width).toFloat()
        state.edit.pieces.forEach { piece ->
            drawRect(if (piece.source.startMs == state.edit.selected) accent.copy(alpha = 0.5f) else wave.copy(alpha = 0.25f),
                Offset(x(piece.startMs), 0f), Size((x(piece.endMs) - x(piece.startMs) - 1).coerceAtLeast(0f), size.height))
        }
        state.window?.let { drawRect(Color.White.copy(alpha = 0.6f), Offset(x(it.startMs), 0f), Size(x(it.endMs) - x(it.startMs), size.height), style = Stroke(1.dp.toPx())) }
        drawLine(Color.White, Offset(x(state.edit.positionMs), 0f), Offset(x(state.edit.positionMs), size.height), 2.dp.toPx())
    }
}

private enum class CutSymbol { PLAY, PAUSE, BACK15, FORWARD15, SPLIT, DELETE, UNDO, REDO }

@Composable
private fun CutTool(symbol: CutSymbol, label: String, enabled: Boolean, tag: String, action: () -> Unit) {
    Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(action, enabled = enabled, modifier = Modifier.size(44.dp).testTag(tag)) { CutGlyph(symbol, label) }
        Text(label, fontSize = 11.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
private fun CutGlyph(symbol: CutSymbol, label: String, modifier: Modifier = Modifier.size(26.dp)) {
    val tint = LocalContentColor.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    Canvas(modifier.semantics { contentDescription = label }) {
        fun p(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
        val stroke = size.width / 13
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, p(x, y), p(a, b), stroke, StrokeCap.Round)
        when (symbol) {
            CutSymbol.PLAY -> drawPath(Path().apply { moveTo(p(7f, 3f).x, p(7f, 3f).y); lineTo(p(21f, 12f).x, p(21f, 12f).y); lineTo(p(7f, 21f).x, p(7f, 21f).y); close() }, tint)
            CutSymbol.PAUSE -> { drawRect(tint, p(5f, 4f), Size(size.width / 5, size.height * 2 / 3)); drawRect(tint, p(15f, 4f), Size(size.width / 5, size.height * 2 / 3)) }
            CutSymbol.DELETE -> { line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f); line(6f, 7f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 7f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
            CutSymbol.SPLIT -> { drawCircle(tint, size.width / 8, p(6f, 17f), style = Stroke(stroke)); drawCircle(tint, size.width / 8, p(6f, 6f), style = Stroke(stroke)); line(8f, 8f, 20f, 20f); line(8f, 15f, 20f, 3f) }
            else -> {
                val reverse = symbol == CutSymbol.FORWARD15 || symbol == CutSymbol.REDO
                fun xx(x: Float) = if (reverse) 24 - x else x
                val path = Path().apply { moveTo(p(xx(5f), 7f).x, p(5f, 7f).y); cubicTo(p(xx(16f), 2f).x, p(16f, 2f).y, p(xx(23f), 10f).x, p(23f, 10f).y, p(xx(18f), 19f).x, p(18f, 19f).y) }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                line(xx(5f), 3f, xx(4f), 8f); line(xx(4f), 8f, xx(9f), 9f)
                if (symbol == CutSymbol.BACK15 || symbol == CutSymbol.FORWARD15) {
                    paint.color = android.graphics.Color.argb((tint.alpha * 255).toInt(), (tint.red * 255).toInt(), (tint.green * 255).toInt(), (tint.blue * 255).toInt())
                    paint.textSize = size.width * 0.42f
                    drawContext.canvas.nativeCanvas.drawText("15", size.width * 0.47f, size.height * 0.79f, paint)
                }
            }
        }
    }
}
