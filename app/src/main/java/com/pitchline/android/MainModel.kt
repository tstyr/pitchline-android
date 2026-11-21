package com.pitchline.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class EditorState(val project: Project, val analysis: Analysis, val selected: Set<Long> = emptySet())
data class WorkStatus(val label: String, val percent: Int, val stoppable: Boolean = true)
enum class ExportSource(val label: String, val listen: Listen, val unedited: Boolean = false) {
    MIX("ミックス", Listen.MIX),
    VOCAL("編集済みボーカル", Listen.VOCAL),
    RAW_VOCAL("未編集ボーカル", Listen.VOCAL, true),
    BACKING("伴奏", Listen.BACKING),
}
data class ExportRequest(val source: ExportSource, val format: ExportFormat)
data class Session(
    val recent: List<Project> = emptyList(),
    val editor: EditorState? = null,
    val work: WorkStatus? = null,
    val separation: SeparationStatus.Running? = null,
    val message: String? = null,
    val prefs: Preferences = Preferences(),
    val listen: Listen = Listen.MIX,
    val compare: Boolean = false,
    val playing: Boolean = false,
    val looping: Boolean = false,
    val loopRange: IntRange? = null,
    val pendingImport: Uri? = null,
)
class MainModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = ProjectStore(app)
    private val renderDirectory = File(app.cacheDir, "render").apply { mkdirs() }
    private val mutable = MutableStateFlow(Session(prefs = Preferences.read(app)))
    val state = mutable.asStateFlow()
    private val playhead = MutableStateFlow(0L)
    val position = playhead.asStateFlow()
    private var worker: Job? = null
    private var saveJob: Job? = null
    private var playingJob: Job? = null
    @Volatile private var playRevision = 0L
    private var beforeGesture: List<Note>? = null
    private var clipboard: List<Note> = emptyList()
    private data class Cache(val id: String, val key: String, val notes: List<Note>, val quality: Boolean, val file: File)
    private var cache: Cache? = null

    init {
        refresh()
        viewModelScope.launch {
            SeparationService.status.collect { status ->
                when (status) {
                    SeparationStatus.Idle -> Unit
                    is SeparationStatus.Running -> mutable.update { it.copy(separation = status) }
                    is SeparationStatus.Finished -> {
                        val current = state.value.editor
                        if (status.id != null && (status.openResult || current?.project?.id == status.id)) {
                            val updated = runCatching { withContext(Dispatchers.IO) {
                                val project = store.load(status.id)
                                EditorState(project, store.readAnalysis(project))
                            } }
                            updated.onSuccess { editor ->
                                if (status.openResult) setEditor(editor) else mutable.update { it.copy(editor = editor, loopRange = null) }
                            }
                                .onFailure { message(it.message ?: "分離結果を開けません") }
                        }
                        mutable.update { it.copy(separation = null, message = status.message) }
                        SeparationService.consumed(status)
                        refresh()
                    }
                }
            }
        }
    }

    fun dismissMessage() = mutable.update { it.copy(message = null) }
    private fun message(text: String) = mutable.update { it.copy(message = text) }

    private fun refresh() = viewModelScope.launch {
        val recent = withContext(Dispatchers.IO) { store.recent() }
        mutable.update { it.copy(recent = recent) }
    }

    fun requestImport(uri: Uri) {
        if (locked()) return
        finishGesture()
        stopPlayback()
        mutable.update { it.copy(pendingImport = uri) }
    }
    fun dismissImport() { mutable.update { it.copy(pendingImport = null) } }
    fun confirmImport(mode: SeparationMode?) {
        val uri = state.value.pendingImport ?: return
        dismissImport()
        import(uri, mode)
    }

    fun import(uri: Uri, mode: SeparationMode? = null) {
        if (locked()) return
        stopPlayback()
        if (mode != null) {
            launchSeparation { SeparationService.import(app, mode, uri) }
            return
        }
        val id = store.newId()
        work("音声を読み込み中") {
            val context = currentCoroutineContext()
            var complete = false
            try {
                runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "音声"
                val directory = store.directory(id)
                check(directory.mkdirs())
                val audio = store.original(id)
                val info = AudioImport.decode(app, uri, audio) { progress("音声を読み込み中", it) }
                val analysis = Processing.analyze(audio, info) { progress("音程を解析中", it) }
                store.writeAnalysis(id, "original", analysis)
                val project = Project(id, uri.toString(), name, info, NoteHistory(segment(analysis)))
                context.ensureActive()
                store.save(project)
                complete = true
                withContext(Dispatchers.Main) { setEditor(EditorState(project, analysis)) }
            } finally {
                if (!complete) {
                    store.deleteProject(id)
                    val inUse = store.recent().any { it.uri == uri.toString() }
                    if (!inUse) runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }
    }

    fun open(id: String) {
        stopPlayback()
        work("プロジェクトを開いています", false) {
            saveJob?.join()
            val project = store.load(id)
            val editor = EditorState(project, store.readAnalysis(project))
            withContext(Dispatchers.Main) { setEditor(editor) }
        }
    }

    fun home() {
        finishGesture()
        stopPlayback()
        mutable.update { it.copy(editor = null, loopRange = null, looping = false) }
        viewModelScope.launch { saveJob?.join(); refresh() }
    }

    private fun setEditor(editor: EditorState) {
        beforeGesture = null
        playhead.value = 0
        mutable.update { it.copy(editor = editor, listen = Listen.MIX, compare = false, loopRange = null, looping = false) }
        refresh()
    }

    private fun persist(project: Project) {
        val predecessor = saveJob
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            predecessor?.join()
            try {
                store.save(project)
            } catch (error: Exception) {
                message("プロジェクトを保存できません: ${error.message.orEmpty()}")
            }
        }
    }

    fun select(id: Long?, add: Boolean) {
        finishGesture()
        mutable.update { session ->
            val editor = session.editor ?: return@update session
            val chosen = when {
                id == null -> if (add) editor.selected else emptySet()
                !add -> setOf(id)
                id in editor.selected -> editor.selected - id
                else -> editor.selected + id
            }
            session.copy(editor = editor.copy(selected = chosen))
        }
    }

    fun selectRange(frames: IntRange) {
        finishGesture()
        mutable.update { session ->
            val editor = session.editor ?: return@update session
            val selected = editor.project.history.notes.filter { it.start <= frames.last && it.end > frames.first }.map { it.id }.toSet()
            session.copy(editor = editor.copy(selected = selected), loopRange = frames)
        }
        if (state.value.playing && state.value.looping) play()
    }

    fun beginGesture() {
        val editor = state.value.editor ?: return
        if (locked() || beforeGesture != null || editor.selected.isEmpty()) return
        beforeGesture = editor.project.history.notes
        stopPlayback()
    }

    fun finishGesture() {
        val before = beforeGesture ?: return
        beforeGesture = null
        val editor = state.value.editor ?: return
        val current = editor.project.history
        if (before != current.notes) change(editor.project.withHistory(current.edit(before, current.notes)), save = true)
    }

    fun moveGesture(semitones: Int, snap: Boolean) {
        val editor = state.value.editor ?: return
        val previous = beforeGesture?.associateBy { it.id } ?: return
        applyToSelected { note ->
            val old = previous[note.id] ?: return@applyToSelected note
            val c = old.controls.copy(semitones = (old.controls.semitones + semitones).coerceIn(-12, 12), snapCents = 0f)
            if (!snap || semitones == 0) old.copy(controls = if (semitones == 0) old.controls else c)
            else {
                val moved = old.copy(controls = c)
                moved.copy(controls = c.copy(snapCents = snapDelta(moved, editor.project.scale)))
            }
        }
    }

    fun controls(transform: (Controls) -> Controls) {
        beginGesture()
        applyToSelected { it.copy(controls = transform(it.controls)) }
    }

    fun draw(id: Long, points: Collection<CurvePoint>) {
        val editor = state.value.editor ?: return
        val note = editor.project.history.notes.firstOrNull { it.id == id } ?: return
        val significant = points.filter { it.frame in note.start until note.end }
        if (significant.isEmpty()) return
        val first = significant.minOf(CurvePoint::frame)
        val last = significant.maxOf(CurvePoint::frame)
        applyToSelected {
            if (it.id == id) it.copy(drawing = (it.drawing.filter { point -> point.frame !in first..last } + significant)
                .distinctBy(CurvePoint::frame).sortedBy(CurvePoint::frame))
            else it
        }
    }

    fun editNotes(action: NoteAction) {
        finishGesture()
        val editor = state.value.editor ?: return
        if (locked()) return
        val notes = editor.project.history.notes
        val selected = notes.filter { it.id in editor.selected }
        if (selected.isEmpty()) return
        when (action) {
            NoteAction.COPY -> {
                clipboard = selected
                message("${selected.size} 音の編集設定をコピーしました")
            }
            NoteAction.PASTE -> {
                if (clipboard.isEmpty()) return
                applyToSelected { note ->
                    val source = clipboard[(selected.indexOf(note)).coerceAtLeast(0) % clipboard.size]
                    val drawing = source.drawing.map { point ->
                        val relative = (point.frame - source.start).toFloat() / (source.end - source.start)
                        CurvePoint((note.start + (note.end - note.start) * relative).roundToInt().coerceIn(note.start, note.end - 1),
                            note.center + point.midi - source.center)
                    }.distinctBy(CurvePoint::frame)
                    note.copy(controls = source.controls, drawing = drawing)
                }
            }
            NoteAction.RESET -> applyToSelected { it.copy(controls = Controls(), drawing = emptyList()) }
            NoteAction.CLEAR_DRAW -> applyToSelected { it.copy(drawing = emptyList()) }
            NoteAction.UP -> applyToSelected { it.copy(controls = it.controls.copy(semitones = (it.controls.semitones + 1).coerceAtMost(12))) }
            NoteAction.DOWN -> applyToSelected { it.copy(controls = it.controls.copy(semitones = (it.controls.semitones - 1).coerceAtLeast(-12))) }
            NoteAction.CORRECT -> correct(1f)
            NoteAction.SPLIT -> {
                val note = selected.singleOrNull() ?: return
                val split = (playhead.value / editor.analysis.hop).toInt()
                if (split < note.start + 4 || split > note.end - 4) {
                    message("ノートの内側へ再生位置を移動してください")
                    return
                }
                val rightId = (notes.maxOfOrNull(Note::id) ?: 0L) + 1
                val left = slice(note, note.start, split, note.id, editor.analysis)
                val right = slice(note, split, note.end, rightId, editor.analysis)
                setNotes(notes.flatMap { if (it == note) listOf(left, right) else listOf(it) }, setOf(left.id, right.id))
            }
            NoteAction.MERGE -> {
                if (selected.size < 2) return
                val first = notes.indexOf(selected.first())
                val end = notes.indexOf(selected.last())
                if (end - first + 1 != selected.size) { message("隣り合うノートを選択してください"); return }
                val lead = selected.first()
                val merged = slice(lead.copy(drawing = selected.flatMap(Note::drawing)),
                    lead.start, selected.last().end, lead.id, editor.analysis)
                setNotes(notes.take(first) + merged + notes.drop(end + 1), setOf(merged.id))
            }
        }
    }

    private fun slice(note: Note, start: Int, end: Int, id: Long, analysis: Analysis): Note {
        val values = (start until end).mapNotNull { analysis.f0[it].takeIf { hz -> hz > 0 }?.let(::midi) }.sorted()
        return note.copy(id = id, start = start, end = end, center = values.getOrElse(values.size / 2) { note.center },
            drawing = note.drawing.filter { it.frame in start until end })
    }

    fun setScale(scale: MusicalScale) {
        val project = state.value.editor?.project ?: return
        if (!locked()) change(project.copy(scale = scale), save = true)
    }

    fun correct(amount: Float) {
        val editor = state.value.editor ?: return
        if (locked()) return
        val notes = editor.project.history.notes.map {
            if (editor.selected.isEmpty() || it.id in editor.selected) it.copy(controls = it.controls.copy(
                snapCents = amount * snapDelta(it, editor.project.scale)
            )) else it
        }
        setNotes(notes, editor.selected)
    }

    private fun snapDelta(note: Note, scale: MusicalScale): Float {
        val before = note.center + note.controls.semitones + note.controls.cents / 100
        return (scale.closest(before) - before) * 100
    }

    fun undo(redo: Boolean) {
        finishGesture()
        val project = state.value.editor?.project ?: return
        if (locked()) return
        val history = if (redo) project.history.forward() else project.history.back()
        if (history == project.history) return
        stopPlayback()
        change(project.withHistory(history), save = true)
    }

    private fun applyToSelected(transform: (Note) -> Note) {
        val editor = state.value.editor ?: return
        if (locked()) return
        val notes = editor.project.history.notes.map { if (it.id in editor.selected) transform(it) else it }
        setNotes(notes, editor.selected)
    }

    private fun setNotes(notes: List<Note>, selection: Set<Long>) {
        val editor = state.value.editor ?: return
        val history = editor.project.history
        if (notes == history.notes) return
        val transient = beforeGesture != null
        if (!transient) stopPlayback()
        val updated = if (transient) history.copy(notes = notes) else history.edit(history.notes, notes)
        change(editor.project.withHistory(updated), selection, !transient)
    }

    private fun change(project: Project, selection: Set<Long>? = null, save: Boolean) {
        val next = if (save) project.copy(modified = System.currentTimeMillis()) else project
        mutable.update { session ->
            val editor = session.editor ?: return@update session
            val ids = selection ?: editor.selected.filter { id -> next.history.notes.any { it.id == id } }.toSet()
            session.copy(editor = editor.copy(project = next, selected = ids))
        }
        if (save) persist(next)
    }

    fun useSource(key: String) {
        val editor = state.value.editor ?: return
        if (editor.project.source == key || locked()) return
        var next = editor.project.copy(source = key)
        stopPlayback()
        work("解析結果を開いています", false) {
            val analysis = if (key == "original" && !next.originalAnalyzed) {
                Processing.analyze(store.original(next.id), next.info) { progress("原曲の音程を解析中", it) }.also {
                    store.writeAnalysis(next.id, "original", it)
                    next = next.copy(original = NoteHistory(segment(it)), originalAnalyzed = true)
                }
            } else store.readAnalysis(next)
            withContext(Dispatchers.Main) {
                change(next, emptySet(), save = true)
                mutable.update { it.copy(editor = it.editor?.copy(analysis = analysis), listen = Listen.MIX,
                    loopRange = null, looping = false) }
            }
        }
    }

    fun setMix(mix: Mix, save: Boolean = false) {
        val project = state.value.editor?.project ?: return
        if (!locked()) change(project.copy(mix = mix), save = save)
    }

    fun settings(prefs: Preferences) {
        prefs.save(app)
        mutable.update { it.copy(prefs = prefs) }
        if (state.value.playing) play()
    }

    fun hasModel(mode: SeparationMode) = ModelStore(app).downloaded(mode)

    fun deleteModel(mode: SeparationMode) {
        if (!locked()) {
            ModelStore(app).delete(mode)
            message("${mode.label}モデルを削除しました")
        }
    }

    fun clearRenders() {
        if (!locked()) {
            stopPlayback()
            work("プレビューキャッシュを削除中", false) {
                playingJob?.join()
                renderDirectory.listFiles().orEmpty().forEach(File::delete)
                cache = null
                message("プレビューキャッシュを削除しました")
            }
        }
    }

    fun separate(mode: SeparationMode, modelOnly: Boolean) {
        if (locked()) return
        val editor = state.value.editor
        if (!modelOnly && editor == null) return
        if (!modelOnly && editor?.project?.stems?.containsKey(mode.key) == true) {
            useSource(mode.key)
            return
        }
        finishGesture()
        stopPlayback()
        launchSeparation { SeparationService.start(app, mode, if (modelOnly) null else editor?.project?.id) }
    }

    private fun launchSeparation(start: suspend () -> Unit) {
        worker = viewModelScope.launch {
            mutable.update { it.copy(work = WorkStatus("分離の準備中", 0, false)) }
            try {
                saveJob?.join()
                playingJob?.join()
                start()
            } catch (error: Exception) {
                message(error.message ?: "分離を開始できません")
            } finally {
                mutable.update { it.copy(work = null) }
            }
        }
    }

    fun cancelWork() {
        if (state.value.separation != null) SeparationService.cancel(app) else worker?.cancel()
    }

    fun setListen(listen: Listen) {
        if (locked()) return
        val playing = state.value.playing
        mutable.update { it.copy(listen = listen) }
        if (playing) play()
    }

    fun toggleCompare() {
        if (locked()) return
        val playing = state.value.playing
        mutable.update { it.copy(compare = !it.compare) }
        if (playing) play()
    }

    fun toggleLoop() {
        if (locked()) return
        val session = state.value
        val editor = session.editor ?: return
        val selected = editor.project.history.notes.filter { it.id in editor.selected }
        val range = session.loopRange ?: selected.takeIf { it.isNotEmpty() }?.let { it.first().start until it.last().end }
        mutable.update { it.copy(looping = !it.looping, loopRange = range) }
        if (session.playing) play()
    }

    fun seek(frames: Long) {
        val info = state.value.editor?.project?.info ?: return
        playhead.value = frames.coerceIn(0, info.frames)
        if (state.value.playing) play()
    }

    fun togglePlay() {
        if (state.value.playing) stopPlayback() else play()
    }

    private fun play() {
        val session = state.value
        val editor = session.editor ?: return
        if (locked()) return
        finishGesture()
        val start = playhead.value.takeIf { it < editor.project.info.frames } ?: 0L
        val revision = ++playRevision
        val previous = playingJob
        previous?.cancel()
        val project = editor.project
        mutable.update { it.copy(playing = true) }
        playingJob = viewModelScope.launch {
            try {
                previous?.join()
                val raw = session.compare || session.listen == Listen.ORIGINAL || session.listen == Listen.BACKING
                val rendered = if (raw) null else {
                    val target = withContext(Dispatchers.Default) {
                        targetCurve(editor.analysis, editor.project.history.notes, project.info.rate)
                    }
                    if (!target.changed) null else {
                        val fast = cached(editor, session.prefs.highQuality)
                        if (fast != null) fast else {
                            progress("プレビューを生成中", 0f)
                            val renderJob = currentCoroutineContext()[Job]
                            worker = renderJob
                            try {
                                withContext(Dispatchers.IO) {
                                    render(editor, target, session.prefs.highQuality) { progress("プレビューを生成中", it) }
                                }
                            } finally {
                                if (revision == playRevision) mutable.update { it.copy(work = null) }
                                if (worker === renderJob) worker = null
                            }
                        }
                    }
                }
                val loop = if (!session.looping) null else session.loopRange?.let { range ->
                    val low = range.first.toLong() * editor.analysis.hop
                    val high = ((range.last.toLong() + 1) * editor.analysis.hop).coerceAtMost(project.info.frames)
                    (low until high).takeUnless(LongRange::isEmpty)
                } ?: if (session.looping) 0L until project.info.frames else null
                withContext(Dispatchers.IO) {
                    MixReader(store, project, rendered, session.listen).use {
                        Playback.play(app, project.info, it, start, loop, session.prefs,
                            { state.value.editor?.project?.mix ?: project.mix },
                            { frame -> if (revision == playRevision) playhead.value = frame },
                            { viewModelScope.launch { if (revision == playRevision) stopPlayback() } })
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message("再生エラー: ${error.message.orEmpty()}")
            } catch (_: OutOfMemoryError) {
                message("メモリ不足で再生を中断しました")
            } finally {
                if (revision == playRevision) mutable.update { it.copy(playing = false, work = null) }
            }
        }
    }

    private fun stopPlayback() {
        val rendering = worker === playingJob && worker != null
        playRevision++
        playingJob?.cancel()
        if (rendering) worker = null
        mutable.update { it.copy(playing = false, work = if (rendering) null else it.work) }
    }

    private fun cached(editor: EditorState, highQuality: Boolean): File? = cache?.takeIf {
        it.id == editor.project.id && it.key == editor.project.analysisKey && it.notes == editor.project.history.notes &&
            it.quality == highQuality && it.file.isFile
    }?.file

    private suspend fun render(editor: EditorState, target: Target, highQuality: Boolean, progress: Progress): File {
        cached(editor, highQuality)?.let { return it }
        val project = editor.project
        val file = File.createTempFile("preview-", ".pcm", renderDirectory)
        try {
            Processing.render(store.vocal(project), file, project.info, editor.analysis, target, highQuality, progress)
            cache?.file?.delete()
            cache = Cache(project.id, project.analysisKey, project.history.notes, highQuality, file)
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun export(uri: Uri, request: ExportRequest) {
        finishGesture()
        val editor = state.value.editor ?: return
        val project = editor.project
        stopPlayback()
        work("書き出し中") {
            playingJob?.join()
            val temporary = File.createTempFile("export-", ".${request.format.extension}", app.cacheDir)
            var success = false
            try {
                val outputSource = request.source
                val rendered = if (outputSource.unedited || outputSource.listen == Listen.BACKING) null else {
                    val target = targetCurve(editor.analysis, project.history.notes, project.info.rate)
                    if (target.changed) render(editor, target, true) { progress("音声を再合成中", it) } else null
                }
                val mix = if (outputSource.unedited) Mix() else project.mix
                MixReader(store, project, rendered, outputSource.listen, mix).use {
                    AudioExport.encode(temporary, project.info, it, request.format) { value -> progress("書き出し中", value) }
                }
                val resolver = app.contentResolver
                val output = resolver.openOutputStream(uri, "wt") ?: error("保存先を開けません")
                output.use { stream ->
                    temporary.inputStream().use { input ->
                        val bytes = ByteArray(65_536)
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(bytes)
                            if (read < 0) break
                            stream.write(bytes, 0, read)
                            written += read
                            progress("ファイルを保存中", written.toFloat() / temporary.length())
                        }
                    }
                }
                success = true
                message("書き出しが完了しました")
            } finally {
                temporary.delete()
                if (!success) {
                    val resolver = app.contentResolver
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
            }
        }
    }

    private fun locked() = state.value.work != null || state.value.separation != null

    private fun progress(label: String, fraction: Float) {
        val status = WorkStatus(label, (fraction.coerceIn(0f, 1f) * 100).toInt())
        mutable.update { if (it.work == status) it else it.copy(work = status) }
    }

    private fun work(label: String, stoppable: Boolean = true, body: suspend () -> Unit) {
        if (locked() || worker?.isActive == true) return
        mutable.update { it.copy(work = WorkStatus(label, 0, stoppable)) }
        worker = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { body() }
            } catch (_: CancellationException) {
                message("キャンセルしました")
            } catch (_: OutOfMemoryError) {
                message("メモリ不足で処理を中断しました")
            } catch (error: Exception) {
                message("$label: ${error.message.orEmpty()}")
            } finally {
                mutable.update { it.copy(work = null) }
            }
        }
    }
}

enum class NoteAction(val label: String) {
    UP("半音上げる"), DOWN("半音下げる"), RESET("元の音程に戻す"), SPLIT("再生位置で分割"),
    MERGE("選択ノートを結合"), COPY("編集設定をコピー"), PASTE("編集設定を貼り付け"),
    CLEAR_DRAW("手描きカーブを消去"), CORRECT("スケールに補正"),
}
