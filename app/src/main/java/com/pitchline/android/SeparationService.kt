package com.pitchline.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface SeparationStatus {
    data object Idle : SeparationStatus
    data class Running(val text: String, val percent: Int, val request: String = "", val acknowledged: Boolean = true,
        val elapsedSeconds: Int = 0) : SeparationStatus
    data class Finished(val id: String?, val message: String, val openResult: Boolean = false, val request: String = "") : SeparationStatus
}

class SeparationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var task: Job? = null
    private var ticker: Job? = null
    private var request = ""
    private val notifications get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == cancelAction) {
            task?.cancel()
            if (task == null) { finish(SeparationStatus.Finished(null, "キャンセルしました", request = request)); stopSelf() }
            return START_NOT_STICKY
        }
        if (task?.isActive == true) return START_NOT_STICKY
        val mode = SeparationMode.entries.firstOrNull { it.key == intent?.getStringExtra("mode") }
        if (mode == null) { stopSelf(); return START_NOT_STICKY }
        request = intent?.getStringExtra("request").orEmpty()
        val existingId = intent?.getStringExtra("project")
        val importUri = intent?.getStringExtra("import")?.let(Uri::parse)
        notifications.createNotificationChannel(NotificationChannel(channel, "ボーカル分離", NotificationManager.IMPORTANCE_LOW))
        try {
            val type = if (Build.VERSION.SDK_INT >= 35 && (existingId != null || importUri != null))
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId, notification("分離の準備中", -1), type)
            else startForeground(notificationId, notification("分離の準備中", -1))
        } catch (error: Exception) {
            finish(SeparationStatus.Finished(null, "分離を開始できません: ${error.message.orEmpty()}", request = request))
            launches[request]?.complete(Unit)
            stopSelf(); return START_NOT_STICKY
        }
        updates.value = SeparationStatus.Running("分離の準備中", -1, request)
        launches[request]?.complete(Unit)
        val started = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                updates.update { if (it is SeparationStatus.Running && it.request == request)
                    it.copy(elapsedSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()) else it }
            }
        }
        task = scope.launch(Dispatchers.IO) {
            var pending: Pair<String, String>? = null
            var importingId: String? = null
            var imported = false
            val store = ProjectStore(this@SeparationService)
            val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pitchline:separation")
            val completion = try {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                val project = if (importUri != null) {
                    val id = store.newId().also { importingId = it }
                    runCatching { contentResolver.takePersistableUriPermission(importUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val name = contentResolver.query(importUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: importUri.lastPathSegment ?: "音声"
                    check(store.directory(id).mkdirs())
                    val info = AudioImport.decode(this@SeparationService, importUri, store.original(id)) { update("音声を読み込み中", it) }
                    Project(id, importUri.toString(), name, info, NoteHistory(emptyList()), originalAnalyzed = false)
                } else existingId?.let(store::load)
                val graph = ModelStore(this@SeparationService).prepareDetailed(mode) { update(it.text, it.fraction) }
                ensureActive()
                if (project == null) SeparationStatus.Finished(null, "${mode.label}モデルを保存しました", request = request)
                else {
                    val previous = project.stems[mode.key]
                    val updated = if (previous == null) {
                        val key = "${mode.key}-${store.newId()}"
                        pending = project.id to key
                        val vocal = store.vocal(project.id, key)
                        val backing = store.backing(project.id, key)
                        update("推論エンジンを準備中")
                        Separator.separate(graph, store.original(project.id), vocal, backing, project.info, mode,
                            stage = { title -> update(title) }) { update("ボーカルを分離中", it) }
                        val analysis = Processing.analyze(vocal, project.info) { update("ボーカルの音程を解析中", it) }
                        store.writeAnalysis(project.id, key, analysis)
                        project.copy(stems = project.stems + (mode.key to Stems(key, NoteHistory(segment(analysis)))))
                    } else project
                    ensureActive()
                    store.save(updated.copy(source = mode.key, modified = System.currentTimeMillis()))
                    pending = null
                    imported = true
                    SeparationStatus.Finished(project.id, "ボーカル分離が完了しました", importUri != null, request)
                }
            } catch (_: CancellationException) {
                SeparationStatus.Finished(null, "キャンセルしました", request = request)
            } catch (_: OutOfMemoryError) {
                SeparationStatus.Finished(null, "メモリ不足で分離を中断しました", request = request)
            } catch (error: Exception) {
                SeparationStatus.Finished(null, "分離エラー: ${error.message.orEmpty()}", request = request)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending?.let { (id, key) -> store.deleteStem(id, key) }
                if (!imported) importingId?.let { id ->
                    store.deleteProject(id)
                    if (importUri != null && store.recent().none { it.uri == importUri.toString() }) runCatching {
                        contentResolver.releasePersistableUriPermission(importUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
            withContext(NonCancellable + Dispatchers.Main) {
                ticker?.cancel()
                finish(completion)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(completion: SeparationStatus.Finished) {
        val current = updates.value
        if (current is SeparationStatus.Running && current.request == completion.request) updates.value = completion
    }
    private fun update(title: String, fraction: Float? = null) {
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: -1
        val before = updates.value
        updates.update { if (it is SeparationStatus.Running && it.request == request) it.copy(text = title, percent = percent) else it }
        val next = updates.value
        if (next != before && next is SeparationStatus.Running && next.request == request)
            notifications.notify(notificationId, notification(next.text, next.percent))
    }
    private fun notification(title: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SeparationService::class.java).setAction(cancelAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_pitchline)
            .setContentTitle(title).setContentText(if (percent < 0) "Pitchline · 処理中" else "Pitchline · $percent%")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, maxOf(0, percent), percent < 0)
            .addAction(Notification.Action.Builder(null, "キャンセル", cancel).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        finish(SeparationStatus.Finished(null, "分離処理が中断されました。再試行してください", request = request))
        super.onDestroy()
    }
    companion object {
        private const val notificationId = 17
        private const val channel = "separation"
        private const val cancelAction = "com.pitchline.android.CANCEL_SEPARATION"
        private val updates = MutableStateFlow<SeparationStatus>(SeparationStatus.Idle)
        private val launches = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val status = updates.asStateFlow()
        fun consumed(result: SeparationStatus.Finished) { updates.compareAndSet(result, SeparationStatus.Idle) }
        suspend fun start(context: Context, mode: SeparationMode, projectId: String?) = begin(context, mode, projectId, null)
        suspend fun import(context: Context, mode: SeparationMode, uri: Uri) = begin(context, mode, null, uri)
        private suspend fun begin(context: Context, mode: SeparationMode, projectId: String?, uri: Uri?) {
            check(updates.value !is SeparationStatus.Running) { "分離処理が実行中です" }
            val token = UUID.randomUUID().toString()
            val launched = CompletableDeferred<Unit>().also { launches[token] = it }
            updates.value = SeparationStatus.Running("分離サービスを開始中", -1, token, acknowledged = false)
            try {
                context.startForegroundService(Intent(context, SeparationService::class.java)
                    .putExtra("mode", mode.key).putExtra("project", projectId).putExtra("import", uri?.toString()).putExtra("request", token))
                withTimeout(15_000) { launched.await() }
            } catch (error: Exception) {
                context.stopService(Intent(context, SeparationService::class.java))
                val message = if (error is TimeoutCancellationException) "分離サービスが応答しません。再試行してください" else error.message.orEmpty()
                updates.value = SeparationStatus.Finished(null, message, request = token)
                throw IOException(message, error)
            } finally { launches.remove(token) }
        }
        fun cancel(context: Context) { context.startService(Intent(context, SeparationService::class.java).setAction(cancelAction)) }
    }
}
