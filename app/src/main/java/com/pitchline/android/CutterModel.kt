package com.pitchline.android

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class CutterState(
    val source: CutAudio? = null, val edit: CutEdit = CutEdit(emptyList()), val window: CutRange? = null,
    val peaks: FloatArray? = null, val waveLoading: Boolean = false, val waveError: Boolean = false,
    val loading: Boolean = false, val exporting: Boolean = false, val percent: Int = 0,
    val playing: Boolean = false, val preparing: Boolean = false,
    val undoCount: Int = 0, val redoCount: Int = 0, val message: String? = null,
)

class CutterModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application
    private val mutable = MutableStateFlow(CutterState())
    val state = mutable.asStateFlow()
    private var waveJob: Job? = null
    private var openJob: Job? = null
    private var exportJob: Job? = null
    private var playbackJob: Job? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var navigationResume = false
    private val undo = ArrayDeque<CutEdit>()
    private val redo = ArrayDeque<CutEdit>()
    private data class WaveKey(val uri: String, val clips: List<CutRange>, val window: CutRange)
    private val waveCache = linkedMapOf<WaveKey, FloatArray>()

    init { saved.get<String>("cut-uri")?.let { open(Uri.parse(it), restore = true) } }

    fun open(uri: Uri, restore: Boolean = false) {
        if (state.value.exporting) return
        stop()
        openJob?.cancel()
        waveJob?.cancel()
        undo.clear(); redo.clear(); waveCache.clear()
        mutable.value = CutterState(loading = true)
        openJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { CutterAudio.inspect(app, uri) }
                runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val clips = if (restore) restoreClips(source) else listOf(CutRange(0, source.durationMs))
                val duration = clips.sumOf { it.lengthMs }
                val position = if (restore) (saved.get<Long>("cut-position") ?: 0L).coerceIn(0, duration) else 0L
                val selected = if (restore) saved.get<Long>("cut-selected") else clips.firstOrNull()?.startMs
                val edit = CutEdit(clips, position, selected?.takeIf { id -> clips.any { it.startMs == id } } ?: clips.firstOrNull()?.startMs)
                val span = if (restore) saved.get<Long>("cut-span") ?: 30_000L else 30_000L
                mutable.value = CutterState(source, edit, window = windowFor(edit, span))
                saved["cut-uri"] = uri.toString()
                saveEdit(edit)
                requestWave()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loading = false, message = "開けません: ${error.message.orEmpty()}") }
            }
        }
    }
    private fun restoreClips(source: CutAudio): List<CutRange> = runCatching {
        val starts = saved.get<LongArray>("cut-clips-start")
        val ends = saved.get<LongArray>("cut-clips-end")
        if (starts != null && ends != null) {
            require(starts.size == ends.size && starts.size <= 2000)
            val clips = starts.indices.map { CutRange(starts[it], ends[it]) }
            require(clips.all { it.endMs <= source.durationMs })
            CutEdit(clips).clips
        } else {
            val first = (saved.get<Long>("cut-start") ?: 0L).coerceIn(0, source.durationMs - 1)
            val end = (saved.get<Long>("cut-end") ?: source.durationMs).coerceIn(first + 1, source.durationMs)
            listOf(CutRange(first, end))
        }
    }.getOrElse { listOf(CutRange(0, source.durationMs)) }

    private fun saveEdit(edit: CutEdit) {
        saved["cut-clips-start"] = edit.clips.map { it.startMs }.toLongArray()
        saved["cut-clips-end"] = edit.clips.map { it.endMs }.toLongArray()
        saved["cut-selected"] = edit.selected
        saved["cut-position"] = edit.positionMs
    }
    private fun windowFor(edit: CutEdit, span: Long): CutRange? = if (edit.durationMs == 0L) null
        else cutWindow(edit.durationMs, edit.positionMs, span.coerceIn(minOf(250L, edit.durationMs), edit.durationMs))

    private fun changeEdit(edit: CutEdit, remember: Boolean = true) {
        if (state.value.exporting || edit == state.value.edit) return
        stop()
        if (remember) {
            undo.addLast(state.value.edit)
            while (undo.size > 40) undo.removeFirst()
            redo.clear()
        }
        val window = windowFor(edit, state.value.window?.lengthMs ?: 30_000)
        mutable.update { it.copy(edit = edit, window = window, undoCount = undo.size, redoCount = redo.size) }
        saveEdit(edit)
        requestWave()
    }
    fun split() { if (state.value.edit.canSplit) changeEdit(state.value.edit.split()) }
    fun deleteSelected() { changeEdit(state.value.edit.deleteSelected()) }
    fun undo() {
        if (state.value.exporting || undo.isEmpty()) return
        redo.addLast(state.value.edit)
        changeEdit(undo.removeLast(), remember = false)
    }
    fun redo() {
        if (state.value.exporting || redo.isEmpty()) return
        undo.addLast(state.value.edit)
        changeEdit(redo.removeLast(), remember = false)
    }

    fun seek(timeMs: Long, select: Boolean = false) {
        if (state.value.exporting) return
        val resume = state.value.playing || state.value.preparing
        if (resume) stop()
        val edit = state.value.edit.seek(timeMs, select)
        val old = state.value.window
        val window = if (old == null || edit.positionMs !in old.startMs..old.endMs) windowFor(edit, old?.lengthMs ?: 30_000) else old
        mutable.update { it.copy(edit = edit, window = window) }
        saved["cut-position"] = edit.positionMs
        saved["cut-selected"] = edit.selected
        if (window != old) requestWave()
        if (resume) play()
    }
    fun jump(text: String): Boolean {
        val value = parseCutTime(text)
        if (value == null || value > state.value.edit.durationMs) { message("時刻を確認してください"); return false }
        seek(value, select = true)
        return true
    }
    fun skip(delta: Long) { seek(state.value.edit.positionMs + delta) }
    fun beginNavigation() {
        navigationResume = state.value.playing || state.value.preparing
        stopPlayer()
    }
    fun endNavigation() {
        val resume = navigationResume
        navigationResume = false
        if (resume) play()
    }
    fun transform(panFraction: Double, zoom: Double, focal: Double) {
        val s = state.value
        val old = s.window ?: return
        if (s.exporting) return
        val window = transformCutWindow(s.edit.durationMs, old, panFraction, zoom, focal)
        val at = if (abs(zoom - 1) < 0.0001) s.edit.positionMs + window.startMs - old.startMs else s.edit.positionMs
        val edit = s.edit.seek(at.coerceIn(window.startMs, window.endMs))
        mutable.update { it.copy(edit = edit, window = window) }
        saved["cut-position"] = edit.positionMs
        saved["cut-span"] = window.lengthMs
        if (window != old) requestWave()
    }
    fun showAll() {
        val edit = state.value.edit
        if (edit.durationMs == 0L) return
        mutable.update { it.copy(window = CutRange(0, edit.durationMs)) }
        saved["cut-span"] = edit.durationMs
        requestWave()
    }
    private fun requestWave() {
        waveJob?.cancel()
        val source = state.value.source ?: return
        val edit = state.value.edit
        val window = state.value.window ?: run { mutable.update { it.copy(peaks = null, waveLoading = false) }; return }
        val key = WaveKey(source.uri.toString(), edit.clips, window)
        val cached = waveCache[key]
        mutable.update { it.copy(peaks = cached, waveError = false, waveLoading = cached == null) }
        if (cached != null) return
        waveJob = viewModelScope.launch {
            try {
                delay(180)
                val peaks = withContext(Dispatchers.IO) {
                    CutterAudio.timelineWaveform(app, source, edit, window) { partial ->
                        mutable.update { current ->
                            if (current.source?.uri == source.uri && current.edit.clips == edit.clips && current.window == window)
                                current.copy(peaks = partial) else current
                        }
                    }
                }
                waveCache[key] = peaks
                while (waveCache.size > 12) waveCache.remove(waveCache.keys.first())
                mutable.update { it.copy(peaks = peaks, waveLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(waveLoading = false, waveError = true) }
            }
        }
    }

    fun togglePlay() { if (state.value.playing || state.value.preparing) stop() else play() }
    private fun play() {
        val s = state.value
        val source = s.source ?: return
        if (s.exporting || s.edit.durationMs == 0L) return
        stopPlayer()
        val edit = if (s.edit.positionMs >= s.edit.durationMs) s.edit.seek(0) else s.edit
        mutable.update { it.copy(edit = edit, preparing = true) }
        val pieces = edit.pieces
        var index = pieces.indexOf(requireNotNull(edit.locate(edit.positionMs)))
        val from = pieces[index].source.startMs + edit.positionMs - pieces[index].startMs
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val manager = app.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stop(); message("音声出力を開始できません"); return }
        focus = request
        try {
            val next = MediaPlayer()
            player = next
            var seeking = true
            fun finished() {
                mutable.update { it.copy(edit = it.edit.seek(edit.durationMs)) }
                saved["cut-position"] = edit.durationMs
                stop()
            }
            fun advance() {
                if (index == pieces.lastIndex) { finished(); return }
                val previous = pieces[index++]
                val following = pieces[index]
                if (previous.source.endMs != following.source.startMs) {
                    next.pause()
                    seeking = true
                    next.seekTo(following.source.startMs, MediaPlayer.SEEK_CLOSEST)
                }
            }
            next.setAudioAttributes(attrs)
            next.setOnErrorListener { _, _, _ -> stop(); message("再生できません"); true }
            next.setOnCompletionListener { if (index < pieces.lastIndex) advance() else finished() }
            next.setOnPreparedListener { if (player === it) it.seekTo(from, MediaPlayer.SEEK_CLOSEST) }
            next.setOnSeekCompleteListener {
                if (player === it) {
                    seeking = false
                    it.start()
                    mutable.update { value -> value.copy(preparing = false, playing = true) }
                    if (playbackJob?.isActive != true) playbackJob = viewModelScope.launch {
                        while (player === next) {
                            if (!seeking) {
                                val original = next.currentPosition.toLong()
                                val piece = pieces[index]
                                if (original >= piece.source.endMs) advance()
                                else {
                                    val at = (piece.startMs + original - piece.source.startMs).coerceIn(piece.startMs, piece.endMs)
                                    val old = state.value.window
                                    val window = if (old != null && at > old.startMs + old.lengthMs * 9 / 10 && at < edit.durationMs)
                                        cutWindow(edit.durationMs, at + old.lengthMs / 4, old.lengthMs) else old
                                    mutable.update { value -> value.copy(edit = value.edit.seek(at), window = window) }
                                    saved["cut-position"] = at
                                    if (window != old) requestWave()
                                }
                            }
                            delay(20)
                        }
                    }
                }
            }
            next.setDataSource(app, source.uri)
            next.prepareAsync()
        } catch (error: Exception) { stop(); message("再生エラー: ${error.message.orEmpty()}") }
    }
    private fun stopPlayer() {
        playbackJob?.cancel(); playbackJob = null
        player?.release(); player = null
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        mutable.update { it.copy(playing = false, preparing = false) }
    }
    fun stop() { navigationResume = false; stopPlayer() }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun resumeUi() { if (!state.value.exporting && state.value.source != null && state.value.peaks == null) requestWave() }
    fun suspendUi() { stop(); waveJob?.cancel(); mutable.update { it.copy(waveLoading = false) } }
    fun leave() { suspendUi(); exportJob?.cancel() }
    fun cancelExport() { exportJob?.cancel() }
    fun export(destination: Uri, format: ExportFormat) {
        val s = state.value
        val source = s.source ?: return
        if (s.exporting || s.edit.clips.isEmpty()) return
        stop(); waveJob?.cancel()
        mutable.update { it.copy(exporting = true, percent = 0, waveLoading = false, message = null) }
        exportJob = viewModelScope.launch {
            try {
                waveJob?.join()
                withContext(Dispatchers.IO) {
                    CutterAudio.exportClips(app, source, s.edit.clips, destination, format) { value ->
                        val percent = (value.coerceIn(0f, 1f) * 100).toInt()
                        mutable.update { if (it.percent == percent) it else it.copy(percent = percent) }
                    }
                }
                message("保存しました")
            } catch (_: CancellationException) { message("保存を中止しました") }
            catch (error: Exception) { message("保存エラー: ${error.message.orEmpty()}") }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }
    override fun onCleared() { leave(); super.onCleared() }
}
