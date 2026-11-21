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

data class CutterState(
    val source: CutAudio? = null, val range: CutRange? = null,
    val edge: CutEdge = CutEdge.START, val window: CutRange? = null,
    val peaks: FloatArray? = null, val waveLoading: Boolean = false, val waveError: String? = null,
    val loading: Boolean = false, val exporting: Boolean = false, val percent: Int = 0,
    val playing: Boolean = false, val preparing: Boolean = false, val positionMs: Long = 0,
    val message: String? = null,
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
    private var loadRevision = 0

    init { saved.get<String>("cut-uri")?.let { open(Uri.parse(it), restore = true) } }

    fun open(uri: Uri, restore: Boolean = false) {
        if (state.value.exporting) return
        stop()
        openJob?.cancel()
        waveJob?.cancel()
        val revision = ++loadRevision
        mutable.value = CutterState(loading = true)
        openJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) { CutterAudio.inspect(app, uri) }
                if (revision != loadRevision) return@launch
                runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val first = if (restore) (saved.get<Long>("cut-start") ?: 0L).coerceIn(0, source.durationMs - 1) else 0L
                val end = if (restore) (saved.get<Long>("cut-end") ?: source.durationMs).coerceIn(first + 1, source.durationMs) else source.durationMs
                val range = CutRange(first, end)
                mutable.value = CutterState(source, range, window = cutWindow(source.durationMs, first, 30_000), positionMs = first)
                saved["cut-uri"] = uri.toString()
                saveRange(range)
                requestWave()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loading = false, message = "開けません: ${error.message.orEmpty()}") }
            }
        }
    }

    private fun saveRange(range: CutRange) { saved["cut-start"] = range.startMs; saved["cut-end"] = range.endMs }
    fun edge(edge: CutEdge) {
        mutable.update { it.copy(edge = edge) }
        showBoundary()
    }
    fun setBoundary(edge: CutEdge, value: Long, exact: Boolean = false): Boolean {
        val s = state.value
        val range = s.range ?: return false
        val source = s.source ?: return false
        if (s.exporting) return false
        val low = if (edge == CutEdge.START) 0L else range.startMs + 1
        val high = if (edge == CutEdge.START) range.endMs - 1 else source.durationMs
        if (exact && value !in low..high) {
            message("開始 < 終了となる、ファイル内の時刻を入力してください")
            return false
        }
        val next = if (edge == CutEdge.START) range.copy(startMs = value.coerceIn(low, high))
            else range.copy(endMs = value.coerceIn(low, high))
        mutable.update { it.copy(range = next, edge = edge) }
        saveRange(next)
        showBoundary()
        return true
    }
    fun setTime(edge: CutEdge, text: String): Boolean {
        val value = parseCutTime(text) ?: run { message("時:分:秒.ミリ秒で入力してください（例 02:15:30.500）"); return false }
        return setBoundary(edge, value, exact = true)
    }
    fun setRangeText(start: String, end: String): Boolean {
        val source = state.value.source ?: return false
        if (state.value.exporting) return false
        val first = parseCutTime(start)
        val last = parseCutTime(end)
        if (first == null || last == null || first >= last || last > source.durationMs) {
            message("開始・終了を時:分:秒.ミリ秒で入力してください。0 ≤ 開始 < 終了 ≤ 全長にしてください")
            return false
        }
        val range = CutRange(first, last)
        mutable.update { it.copy(range = range) }
        saveRange(range)
        showBoundary()
        return true
    }
    fun nudge(delta: Long) {
        val s = state.value
        val range = s.range ?: return
        setBoundary(s.edge, (if (s.edge == CutEdge.START) range.startMs else range.endMs) + delta)
    }
    private fun showBoundary() {
        val s = state.value
        val range = s.range ?: return
        val window = s.window ?: return
        val at = if (s.edge == CutEdge.START) range.startMs else range.endMs
        if (at !in window.startMs..window.endMs) setWindow(cutWindow(requireNotNull(s.source).durationMs, at, window.lengthMs))
    }
    fun zoom(factor: Double) {
        val s = state.value
        val source = s.source ?: return
        val range = s.range ?: return
        val span = ((s.window?.lengthMs ?: 30_000) * factor).toLong().coerceIn(minOf(1000L, source.durationMs), source.durationMs)
        val at = if (s.edge == CutEdge.START) range.startMs else range.endMs
        setWindow(cutWindow(source.durationMs, at, span))
    }
    fun showAll() { state.value.source?.let { setWindow(CutRange(0, it.durationMs)) } }
    fun showSelection() { state.value.range?.let { setWindow(it) } }
    private fun setWindow(window: CutRange) {
        mutable.update { it.copy(window = window) }
        requestWave()
    }
    private fun requestWave() {
        waveJob?.cancel()
        val source = state.value.source ?: return
        val window = state.value.window ?: return
        mutable.update { it.copy(peaks = null, waveError = null, waveLoading = window.lengthMs <= 30_000) }
        if (window.lengthMs > 30_000) return
        waveJob = viewModelScope.launch {
            try {
                delay(350)
                val peaks = withContext(Dispatchers.IO) { CutterAudio.waveform(app, source, window) }
                mutable.update { it.copy(peaks = peaks, waveLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(waveLoading = false, waveError = "波形を表示できません。時刻指定と書き出しは利用できます") }
            }
        }
    }

    fun preview(nearEnd: Boolean = false) {
        val s = state.value
        val source = s.source ?: return
        val range = s.range ?: return
        if (s.exporting) return
        stop()
        val from = if (nearEnd) maxOf(range.startMs, range.endMs - 5000) else range.startMs
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val manager = app.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { if (it < 0) stop() }.build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message("音声出力を開始できません"); return }
        focus = request
        mutable.update { it.copy(preparing = true, positionMs = from) }
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setOnErrorListener { _, _, _ -> stop(); message("この音声の試聴を開始できません"); true }
            next.setOnCompletionListener { stop() }
            next.setOnPreparedListener { if (player === it) it.seekTo(from, MediaPlayer.SEEK_CLOSEST) }
            next.setOnSeekCompleteListener {
                if (player === it) {
                    it.start()
                    mutable.update { value -> value.copy(preparing = false, playing = true) }
                    playbackJob = viewModelScope.launch {
                        while (player === next) {
                            val position = next.currentPosition.toLong()
                            mutable.update { value -> value.copy(positionMs = position) }
                            if (position >= (state.value.range?.endMs ?: range.endMs)) { stop(); break }
                            delay(40)
                        }
                    }
                }
            }
            next.setDataSource(app, source.uri)
            next.prepareAsync()
        } catch (error: Exception) { stop(); message("試聴エラー: ${error.message.orEmpty()}") }
    }
    fun stop() {
        playbackJob?.cancel()
        player?.release()
        player = null
        focus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        mutable.update { it.copy(playing = false, preparing = false) }
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun leave() { stop(); waveJob?.cancel(); exportJob?.cancel() }
    fun cancelExport() { exportJob?.cancel() }
    fun export(destination: Uri, format: ExportFormat) {
        val s = state.value
        val source = s.source ?: return
        val range = s.range ?: return
        if (s.exporting) return
        stop()
        waveJob?.cancel()
        mutable.update { it.copy(exporting = true, percent = 0, waveLoading = false, message = null) }
        exportJob = viewModelScope.launch {
            try {
                waveJob?.join()
                withContext(Dispatchers.IO) {
                    CutterAudio.export(app, source, range, destination, format) { fraction ->
                        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
                        mutable.update { if (it.percent == percent) it else it.copy(percent = percent) }
                    }
                }
                message("選択した ${cutTime(range.lengthMs)} を保存しました")
            } catch (_: CancellationException) { message("書き出しを中止しました") }
            catch (error: Exception) { message("書き出しエラー: ${error.message.orEmpty()}") }
            finally { mutable.update { it.copy(exporting = false) } }
        }
    }
    override fun onCleared() { leave(); super.onCleared() }
}
