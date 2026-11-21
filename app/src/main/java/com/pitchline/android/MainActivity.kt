package com.pitchline.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val accent = Color(0xFFF0A56C)
private val aqua = Color(0xFF7AD7C3)
private val dim = Color(0xFFA0A6B2)
private val line = Color(0xFF33363E)
private val panel = Color(0xFF202329)
private val base = Color(0xFF15171B)
private val shape = RoundedCornerShape(6.dp)
private val palette = darkColorScheme(
    primary = accent, onPrimary = Color(0xFF29180D),
    secondary = aqua, onSecondary = Color(0xFF072820),
    background = base, onBackground = Color(0xFFF1F1F2),
    surface = panel, onSurface = Color(0xFFF1F1F2),
    surfaceVariant = Color(0xFF282B33), onSurfaceVariant = dim, outline = line,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { Pitchline() }
    }
}

@Composable
private fun Pitchline(model: MainModel = viewModel()) {
    val session by model.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf("") }
    var exportSource by rememberSaveable { mutableStateOf(ExportSource.MIX) }
    var exportFormat by rememberSaveable { mutableStateOf(ExportFormat.WAV) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { model.export(it, ExportRequest(exportSource, exportFormat)) }
        }
    }
    var requestedModel by rememberSaveable { mutableStateOf<SeparationMode?>(null) }
    var requestedModelOnly by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestedModel?.let { model.separate(it, requestedModelOnly) }
        requestedModel = null
    }
    val open: () -> Unit = { picker.launch(arrayOf("audio/*", "application/ogg", "application/x-flac")) }
    val locked = session.work != null || session.separation != null
    BackHandler(dialog.isNotEmpty() || session.editor != null) {
        if (dialog.isNotEmpty()) dialog = "" else if (!locked) model.home()
    }
    DisposableEffect(Unit) {
        onDispose { model.finishGesture() }
    }
    val scheme = if (session.prefs.highContrast) palette.copy(background = Color(0xFF0A0A0B),
        surface = Color(0xFF17181B), onSurface = Color.White, onSurfaceVariant = Color(0xFFCDD0D7)) else palette
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxSize()) {
                    val editor = session.editor
                    if (editor == null) Home(session, !locked, open, model::open, onSettings = { dialog = "settings" })
                    else Editor(session, editor, model, !locked, setDialog = { dialog = it })
                }
                val progress = session.separation?.let { WorkStatus(it.text, it.percent) } ?: session.work
                if (progress != null) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = scheme.surface,
                        shadowElevation = 8.dp) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label(progress.label, Modifier.weight(1f), fontSize = 13.sp)
                                Mono("${progress.percent}%")
                                if (progress.stoppable) TextButton(onClick = model::cancelWork) { Label("キャンセル") }
                            }
                            LinearProgressIndicator(progress = { progress.percent / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp), color = aqua)
                        }
                    }
                } else {
                    val message = session.message
                    if (message != null) {
                        LaunchedEffect(message) { delay(6000); model.dismissMessage() }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 480.dp),
                            dismissAction = { IconButton(model::dismissMessage) { Icon(Icons.Default.Close, "閉じる") } }) {
                            Label(message)
                        }
                    }
                }
            }
        }
        val editor = session.editor
        when (dialog) {
            "settings" -> SettingsDialog(session.prefs, model, !locked, { dialog = "" }, { dialog = "separate" })
            "separate" -> SeparationDialog(editor, model, !locked, { dialog = "" }) { mode, only ->
                dialog = ""
                if (Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(model.getApplication(), Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestedModel = mode
                    requestedModelOnly = only
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else model.separate(mode, only)
            }
            "mix" -> if (editor != null) MixerDialog(session, editor, model) { dialog = "" }
            "scale" -> if (editor != null) ScaleDialog(editor.project.scale, editor.selected.size, model) { dialog = "" }
            "export" -> if (editor != null) ExportDialog(editor.project, { dialog = "" }) { source, format ->
                exportSource = source
                exportFormat = format
                dialog = ""
                val stem = when (source) {
                    ExportSource.VOCAL -> "vocal"
                    ExportSource.RAW_VOCAL -> "vocal-original"
                    ExportSource.BACKING -> "instrumental"
                    ExportSource.MIX -> "mix"
                }
                val filename = "${editor.project.name.substringBeforeLast('.')}-$stem.${format.extension}"
                save.launch(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = format.mime
                    putExtra(android.content.Intent.EXTRA_TITLE, filename)
                })
            }
        }
    }
}

@Composable
private fun Home(
    session: Session, enabled: Boolean, onOpen: () -> Unit, onProject: (String) -> Unit, onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(57.dp).padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Brand()
        Spacer(Modifier.weight(1f))
        IconButton(onSettings) { Icon(Icons.Default.Settings, "設定", tint = dim) }
    }
    Divider()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth().testTag("home"),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 22.dp),
        ) {
            item {
                Label("新規プロジェクト", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Button(onOpen, enabled = enabled, shape = shape, modifier = Modifier.height(45.dp).testTag("open-audio")) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Label("音声ファイルを開く")
                }
                Spacer(Modifier.height(24.dp))
                Label("最近のプロジェクト", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = dim)
                Spacer(Modifier.height(9.dp))
                Divider()
                if (session.recent.isEmpty()) Label("プロジェクトはまだありません", color = dim, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            items(session.recent, key = Project::id) { project ->
                Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onProject(project.id) }
                    .heightIn(min = 59.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(TransportIcon.WAVE, accent, Modifier.size(23.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                        Label(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Label(if (project.activeStems != null) "分離済み · ${project.history.notes.size} 音"
                            else "${project.history.notes.size} 音", color = dim, fontSize = 11.sp)
                    }
                    Mono(timeLabel(project.info.seconds))
                }
                Divider()
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(TransportIcon.WAVE, accent, Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Label("Pitchline", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Editor(
    session: Session, editor: EditorState, model: MainModel, enabled: Boolean, setDialog: (String) -> Unit,
) {
    val project = editor.project
    val analysis = editor.analysis
    var tool by rememberSaveable(project.id) { mutableStateOf(RollTool.SELECT) }
    var wave by rememberSaveable { mutableStateOf(true) }
    var snap by rememberSaveable { mutableStateOf(false) }
    var inspector by rememberSaveable { mutableStateOf(true) }
    var noteMenu by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    val selected = remember(editor.selected, project.history.notes) { project.history.notes.filter { it.id in editor.selected } }
    val playhead = model.position.collectAsState()
    Row(Modifier.fillMaxWidth().height(54.dp).padding(end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(model::home, enabled = enabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "ホーム") }
        Column(Modifier.weight(1f).padding(end = 7.dp)) {
            Label(project.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val audio = project.info
            val tempo = if (analysis.bpm > 0f) " · ≈${analysis.bpm.roundToInt()} BPM" else ""
            Mono("${timeLabel(audio.seconds)}  ·  ${(audio.rate / 1000f).toString().removeSuffix(".0")} kHz  ·  ${audio.channels}ch$tempo",
                color = dim, size = 10)
        }
        Box {
            SmallAction(if (project.source == "original") "原曲" else SeparationMode.entries.firstOrNull { it.key == project.source }?.label.orEmpty(),
                enabled, active = project.activeStems != null, description = "編集ソースを選択") { sources = true }
            DropdownMenu(sources, { sources = false }, containerColor = panel) {
                DropdownMenuItem(text = { Label("原曲の解析") }, onClick = { sources = false; model.useSource("original") })
                SeparationMode.entries.filter { it.key in project.stems }.forEach { mode ->
                    DropdownMenuItem(text = { Label("${mode.label}ボーカルの解析") }, onClick = { sources = false; model.useSource(mode.key) })
                }
                DropdownMenuItem(text = { Label("ボーカル分離…") }, onClick = { sources = false; setDialog("separate") })
            }
        }
        IconButton({ setDialog("settings") }) { Icon(Icons.Default.Settings, "設定", tint = dim) }
    }
    Divider()
    Row(Modifier.fillMaxWidth().height(44.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            RollTool.entries.forEach { mode ->
                SmallAction(mode.title, enabled, tool == mode) { tool = mode }
            }
            Spacer(Modifier.width(5.dp))
            SmallAction("波形", enabled, wave) { wave = !wave }
            SmallAction("スナップ", enabled, snap) { snap = !snap }
            SmallAction("キー", enabled, project.scale.mode != ScaleMode.CHROMATIC) { setDialog("scale") }
            SmallAction("編集", enabled, inspector, description = "編集パネルを切替") { inspector = !inspector }
        }
        Box {
            IconButton({ noteMenu = true }, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.MoreVert, "ノート操作")
            }
            DropdownMenu(noteMenu, { noteMenu = false }, containerColor = panel) {
                val position = (playhead.value / analysis.hop).toInt()
                DropdownMenuItem(text = { Label("前のノート") }, enabled = project.history.notes.isNotEmpty(), onClick = {
                    noteMenu = false
                    val note = project.history.notes.lastOrNull { it.start < position } ?: project.history.notes.first()
                    model.select(note.id, false); model.seek(note.start.toLong() * analysis.hop)
                })
                DropdownMenuItem(text = { Label("次のノート") }, enabled = project.history.notes.isNotEmpty(), onClick = {
                    noteMenu = false
                    val note = project.history.notes.firstOrNull { it.start > position } ?: project.history.notes.last()
                    model.select(note.id, false); model.seek(note.start.toLong() * analysis.hop)
                })
                Divider()
                NoteAction.entries.forEach { action ->
                    val possible = when (action) {
                        NoteAction.SPLIT -> selected.size == 1
                        NoteAction.MERGE -> selected.size > 1
                        else -> selected.isNotEmpty()
                    }
                    DropdownMenuItem(text = { Label(action.label) }, enabled = possible,
                        onClick = { noteMenu = false; model.editNotes(action) })
                }
            }
        }
    }
    Divider()
    BoxWithConstraints(Modifier.weight(1f)) {
        val landscape = maxWidth > maxHeight && maxWidth > 650.dp
        val tight = maxHeight < 410.dp
        val show = inspector && selected.isNotEmpty()
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                PianoRoll(editor, playhead, model, tool, snap, wave, session.loopRange, enabled, true,
                    Modifier.weight(1f).fillMaxHeight().testTag("piano-roll"))
                if (show) {
                    Spacer(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Inspector(selected, analysis, project.info.rate, enabled, model, Modifier.width(288.dp).fillMaxHeight())
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PianoRoll(editor, playhead, model, tool, snap, wave, session.loopRange, enabled, tight,
                    Modifier.weight(1f).fillMaxWidth().testTag("piano-roll"))
                if (show) {
                    Divider()
                    Inspector(selected, analysis, project.info.rate, enabled, model, Modifier.fillMaxWidth().height(if (tight) 106.dp else 153.dp))
                }
            }
        }
    }
    Divider()
    Transport(session, project, playhead, model, enabled, setDialog)
}

@Composable
private fun Transport(
    session: Session, project: Project, playhead: androidx.compose.runtime.State<Long>, model: MainModel,
    enabled: Boolean, setDialog: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(timeLabel(playhead.value.toDouble() / project.info.rate, true), color = Color(0xFFFFD9A5), size = 13)
            Mono("  /  ${timeLabel(project.info.seconds)}", color = dim, size = 11)
            Spacer(Modifier.weight(1f))
            SmallAction(if (session.compare) "A  原音" else "B  編集後", enabled, session.compare,
                description = "原音と編集の A/B 切替", minHeight = 26) { model.toggleCompare() }
            SmallAction(session.listen.label, enabled, active = session.listen != Listen.MIX,
                description = "再生ソースとミキサー", minHeight = 26) { setDialog("mix") }
        }
        Row(Modifier.fillMaxWidth().height(49.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            TransportButton(TransportIcon.START, "先頭へ戻る", enabled) { model.seek(0) }
            TransportButton(if (session.playing) TransportIcon.STOP else TransportIcon.PLAY,
                if (session.playing) "停止" else "再生", enabled || session.playing, primary = true, onClick = model::togglePlay)
            TransportButton(TransportIcon.LOOP, "範囲ループ", enabled, session.looping, onClick = model::toggleLoop)
            Spacer(Modifier.width(2.dp))
            TransportButton(TransportIcon.UNDO, "元に戻す", enabled && project.history.undo.isNotEmpty()) { model.undo(false) }
            TransportButton(TransportIcon.REDO, "やり直す", enabled && project.history.redo.isNotEmpty()) { model.undo(true) }
            Spacer(Modifier.width(2.dp))
            TransportButton(TransportIcon.MIX, "ミキサー", enabled) { setDialog("mix") }
            TransportButton(TransportIcon.EXPORT, "書き出し", enabled) { setDialog("export") }
        }
    }
}

@Composable
private fun Inspector(
    notes: List<Note>, analysis: Analysis, rate: Int, enabled: Boolean, model: MainModel, modifier: Modifier,
) {
    var tab by rememberSaveable { mutableStateOf("ピッチ") }
    val first = notes.first()
    val controls = first.controls
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            Label(if (notes.size == 1) noteName(first.displayMidi.roundToInt()) else "${notes.size} 音", modifier = Modifier.padding(end = 8.dp),
                fontSize = 13.sp, color = accent, fontWeight = FontWeight.SemiBold)
            listOf("ピッチ", "安定", "ビブラート", "声質").forEach { item ->
                SmallAction(item, true, tab == item, minHeight = 30) { tab = item }
            }
        }
        Divider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            when (tab) {
                "ピッチ" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("移調", Modifier.weight(1f), fontSize = 12.sp)
                        SmallAction("− 半音", enabled) { model.editNotes(NoteAction.DOWN) }
                        Mono("${controls.semitones.signed()} st", color = aqua)
                        SmallAction("＋ 半音", enabled) { model.editNotes(NoteAction.UP) }
                    }
                    Parameter("ピッチ中心", controls.cents, -100f..100f, "¢", enabled, model, steps = 199, signed = true) {
                        copy(cents = it.roundToInt().toFloat())
                    }
                    Parameter("トランジション", controls.transitionMs, 0f..120f, "ms", enabled, model) { copy(transitionMs = it) }
                }
                "安定" -> {
                    Parameter("ドリフト", controls.drift * 100, 0f..100f, "%", enabled, model) { copy(drift = it / 100) }
                    Parameter("モジュレーション", controls.modulation * 100, 0f..100f, "%", enabled, model) { copy(modulation = it / 100) }
                    Parameter("平坦化", controls.straighten * 100, 0f..100f, "%", enabled, model) { copy(straighten = it / 100) }
                }
                "ビブラート" -> {
                    val estimate = remember(analysis, first.start, first.end, notes.size) {
                        if (notes.size == 1) estimateVibrato(first, analysis, rate) else null
                    }
                    if (estimate != null) Mono("原音 ≈%.1f Hz · %d¢".format(estimate.rateHz, estimate.depthCents.roundToInt()))
                    Parameter("深さ", controls.vibratoDepth, 0f..160f, "¢", enabled, model) { copy(vibratoDepth = it) }
                    Parameter("速さ", controls.vibratoRate, 2f..9f, "Hz", enabled, model, decimal = true) { copy(vibratoRate = it) }
                    Parameter("開始", controls.vibratoStart * 100, 0f..90f, "%", enabled, model) { copy(vibratoStart = it / 100) }
                    Parameter("フェードイン", controls.vibratoIn * 100, 0f..75f, "%", enabled, model) { copy(vibratoIn = it / 100) }
                    Parameter("フェードアウト", controls.vibratoOut * 100, 0f..75f, "%", enabled, model) { copy(vibratoOut = it / 100) }
                }
                "声質" -> Parameter("フォルマント", controls.formant, -600f..600f, "¢", enabled, model, signed = true) { copy(formant = it) }
            }
        }
    }
}

@Composable
private fun Parameter(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
    enabled: Boolean, model: MainModel, steps: Int = 0, signed: Boolean = false, decimal: Boolean = false,
    transform: Controls.(Float) -> Controls,
) {
    val number = if (decimal) "%.1f".format(value) else if (signed) value.roundToInt().signed() else value.roundToInt().toString()
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(100.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(value = value, onValueChange = { next -> model.controls { it.transform(next) } }, valueRange = range,
            onValueChangeFinished = model::finishGesture, enabled = enabled, steps = steps,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent,
                inactiveTrackColor = Color(0xFF41454E), activeTickColor = accent, inactiveTickColor = Color(0xFF41454E)))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { Mono("$number$unit", color = aqua, size = 11) }
    }
}

private fun Int.signed() = if (this > 0) "+$this" else toString()

@Composable
private fun SettingsDialog(prefs: Preferences, model: MainModel, unlocked: Boolean, close: () -> Unit, models: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Label("設定") }, confirmButton = { TextButton(close) { Label("閉じる") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingSwitch("高品質プレビュー", prefs.highQuality) { model.settings(prefs.copy(highQuality = it)) }
                MenuField("再生バッファ", "${prefs.bufferMs} ms", listOf(40, 80, 160, 240), { "$it ms" }) {
                    model.settings(prefs.copy(bufferMs = it))
                }
                MenuField("音声出力", prefs.route.label, AudioRoute.entries, AudioRoute::label) {
                    model.settings(prefs.copy(route = it))
                }
                SettingSwitch("高コントラスト", prefs.highContrast) { model.settings(prefs.copy(highContrast = it)) }
                Divider()
                TextButton(models, enabled = unlocked) { Label("分離モデル管理") }
                TextButton(onClick = model::clearRenders, enabled = unlocked) { Label("プレビューキャッシュを削除") }
            }
        }, containerColor = panel)
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SeparationDialog(
    editor: EditorState?, model: MainModel, enabled: Boolean, close: () -> Unit, start: (SeparationMode, Boolean) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(SeparationMode.STANDARD) }
    val cached = editor?.project?.stems?.containsKey(mode.key) == true
    val downloaded = model.hasModel(mode)
    AlertDialog(onDismissRequest = close, title = { Label("ボーカル分離") }, containerColor = panel,
        confirmButton = {
            val label = if (editor == null) "モデルを取得" else if (cached) "キャッシュを開く"
                else if (downloaded) "分離を開始" else "モデル取得と分離"
            Button({ start(mode, editor == null) }, enabled = enabled && (editor != null || !downloaded), shape = shape) {
                Label(label)
            }
        },
        dismissButton = { TextButton(close) { Label("閉じる") } },
        text = {
            Column {
                SeparationMode.entries.forEach { item ->
                    val available = model.hasModel(item)
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { mode = item }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(item == mode, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 5.dp)) {
                            Label("${item.label} · ${item.model}", fontSize = 14.sp)
                            Label(if (available) "取得済み" else "初回 約36 MB", color = dim, fontSize = 12.sp)
                        }
                        if (available) TextButton({ model.deleteModel(item) }, enabled = enabled) { Label("削除", fontSize = 11.sp) }
                    }
                    Divider()
                }
            }
        })
}

@Composable
private fun MixerDialog(session: Session, editor: EditorState, model: MainModel, close: () -> Unit) {
    val mix = editor.project.mix
    AlertDialog(onDismissRequest = { model.setMix(mix, true); close() }, title = { Label("ミキサー") }, containerColor = panel,
        confirmButton = { TextButton({ model.setMix(mix, true); close() }) { Label("閉じる") } },
        text = {
            Column {
                MenuField("再生ソース", session.listen.label, Listen.entries.filter {
                    it == Listen.ORIGINAL || it == Listen.MIX || editor.project.activeStems != null
                }, Listen::label, model::setListen)
                MixerSlider(if (editor.project.activeStems != null) "ボーカル" else "編集トラック", mix.vocal,
                    { model.setMix(mix.copy(vocal = it)) }, { model.setMix(model.state.value.editor?.project?.mix ?: mix, true) })
                if (editor.project.activeStems != null) MixerSlider("伴奏", mix.backing,
                    { model.setMix(mix.copy(backing = it)) }, { model.setMix(model.state.value.editor?.project?.mix ?: mix, true) })
            }
        })
}

@Composable
private fun MixerSlider(label: String, value: Float, change: (Float) -> Unit, finish: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, modifier = Modifier.width(82.dp), fontSize = 11.sp)
        Slider(value, change, valueRange = 0f..1.5f, onValueChangeFinished = finish, modifier = Modifier.weight(1f)
            .semantics { contentDescription = label })
        Box(Modifier.width(46.dp), contentAlignment = Alignment.CenterEnd) { Mono("${(value * 100).roundToInt()}%", color = aqua) }
    }
}

@Composable
private fun ScaleDialog(scale: MusicalScale, selected: Int, model: MainModel, close: () -> Unit) {
    var strength by rememberSaveable { mutableStateOf(80f) }
    AlertDialog(onDismissRequest = close, title = { Label("キーとスケール") }, containerColor = panel,
        confirmButton = {
            Button({ model.correct(strength / 100); close() }, shape = shape) {
                Label(if (selected == 0) "全ノートを補正" else "選択ノートを補正")
            }
        }, dismissButton = { TextButton(close) { Label("閉じる") } },
        text = {
            Column {
                MenuField("キー", pitchNames[scale.root], pitchNames.indices.toList(), { pitchNames[it] }) { model.setScale(scale.copy(root = it)) }
                MenuField("スケール", scale.mode.label, ScaleMode.entries, ScaleMode::label) { model.setScale(scale.copy(mode = it)) }
                MixerSlider("補正の強さ", strength / 100, { strength = (it * 100).coerceAtMost(100f) }, {})
            }
        })
}

@Composable
private fun ExportDialog(project: Project, close: () -> Unit, onExport: (ExportSource, ExportFormat) -> Unit) {
    var source by rememberSaveable { mutableStateOf(ExportSource.MIX) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.WAV) }
    AlertDialog(onDismissRequest = close, title = { Label("書き出し") }, containerColor = panel,
        confirmButton = { Button({ onExport(source, format) }, shape = shape) { Label("保存先を選択") } },
        dismissButton = { TextButton(close) { Label("キャンセル") } },
        text = {
            Column {
                val options = if (project.activeStems != null) ExportSource.entries else listOf(ExportSource.MIX)
                fun label(target: ExportSource) = if (project.activeStems == null) "編集トラック" else target.label
                MenuField("対象", label(source), options, ::label) { source = it }
                MenuField("形式", format.label, ExportFormat.entries, ExportFormat::label) { format = it }
            }
        })
}

@Composable
private fun <T> MenuField(label: String, selected: String, choices: List<T>, name: (T) -> String, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f), fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, shape = shape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) {
                Label(selected, fontSize = 12.sp)
            }
            DropdownMenu(expanded, { expanded = false }, containerColor = panel) {
                choices.forEach { item ->
                    DropdownMenuItem(text = { Label(name(item)) }, onClick = { expanded = false; change(item) })
                }
            }
        }
    }
}

@Composable
private fun SmallAction(
    title: String, enabled: Boolean, active: Boolean = false, description: String? = null, minHeight: Int = 38, click: () -> Unit,
) {
    val tint = if (active) aqua else MaterialTheme.colorScheme.onSurface
    val annotation = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    TextButton(click, enabled = enabled, modifier = annotation.height(minHeight.dp), shape = shape,
        colors = ButtonDefaults.textButtonColors(containerColor = if (active) Color(0xFF243C38) else Color.Transparent, contentColor = tint),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Label(title, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // Replace the text node when its metrics change so a retained layer never draws an invalidated paragraph.
    key(text, fontSize, fontWeight, fontFamily, letterSpacing, maxLines, overflow) {
        Text(text, modifier, color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily,
            letterSpacing = letterSpacing, maxLines = maxLines, overflow = overflow)
    }
}

@Composable
private fun Mono(text: String, color: Color = dim, size: Int = 11) {
    Label(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Divider() = HorizontalDivider(color = line)

private enum class TransportIcon { START, PLAY, STOP, LOOP, UNDO, REDO, MIX, EXPORT, WAVE }

@Composable
private fun TransportButton(
    icon: TransportIcon, label: String, enabled: Boolean, active: Boolean = false, primary: Boolean = false, onClick: () -> Unit,
) {
    val background = when { primary -> accent; active -> Color(0xFF243C38); else -> Color.Transparent }
    val tint = when { !enabled -> Color(0xFF666B77); primary -> Color(0xFF29180D); active -> aqua; else -> Color(0xFFE2E3E8) }
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(43.dp).background(background, shape)
        .semantics { contentDescription = label }) { Glyph(icon, tint, Modifier.size(21.dp)) }
}

@Composable
private fun Glyph(icon: TransportIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        fun p(x: Float, y: Float) = Offset(size.width * x / 24, size.height * y / 24)
        val stroke = size.width / 12
        fun segment(x: Float, y: Float, a: Float, b: Float) = drawLine(tint, p(x, y), p(a, b), stroke, StrokeCap.Round)
        fun curve(vararg points: Pair<Float, Float>, fill: Boolean = false) {
            val path = Path()
            points.forEachIndexed { i, (x, y) ->
                val point = p(x, y)
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            if (fill) { path.close(); drawPath(path, tint) } else drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when (icon) {
            TransportIcon.START -> {
                segment(5f, 5f, 5f, 19f)
                curve(18f to 5f, 7f to 12f, 18f to 19f, fill = true)
            }
            TransportIcon.PLAY -> curve(7f to 4f, 20f to 12f, 7f to 20f, fill = true)
            TransportIcon.STOP -> drawRect(tint, p(6f, 6f), Size(size.width / 2, size.height / 2))
            TransportIcon.LOOP -> {
                curve(5f to 10f, 5f to 6f, 20f to 6f, 17f to 3f)
                segment(20f, 6f, 17f, 9f)
                curve(20f to 14f, 20f to 18f, 5f to 18f, 8f to 21f)
                segment(5f, 18f, 8f, 15f)
            }
            TransportIcon.UNDO, TransportIcon.REDO -> {
                val sign = if (icon == TransportIcon.UNDO) 1f else -1f
                fun reflect(x: Float) = 12 + (x - 12) * sign
                val path = Path().apply {
                    val a = p(reflect(4f), 10f)
                    val b = p(reflect(9f), 6f); val c = p(reflect(19f), 7f); val d = p(reflect(20f), 15f)
                    val e = p(reflect(20f), 18f)
                    moveTo(a.x, a.y); cubicTo(b.x, b.y, c.x, c.y, d.x, d.y); lineTo(e.x, e.y)
                }
                drawPath(path, tint, style = Stroke(stroke, cap = StrokeCap.Round))
                curve(reflect(5f) to 5f, reflect(4f) to 10f, reflect(9f) to 11f)
            }
            TransportIcon.MIX -> {
                listOf(5f to 8f, 12f to 15f, 19f to 10f).forEach { (x, y) ->
                    segment(x, 3f, x, 21f)
                    segment(x - 2f, y, x + 2f, y)
                }
            }
            TransportIcon.EXPORT -> {
                curve(4f to 13f, 4f to 20f, 20f to 20f, 20f to 13f)
                segment(12f, 3f, 12f, 15f)
                curve(8f to 7f, 12f to 3f, 16f to 7f)
            }
            TransportIcon.WAVE -> curve(1f to 13f, 5f to 13f, 8f to 6f, 11f to 18f, 15f to 4f, 18f to 13f, 23f to 13f)
        }
    }
}
