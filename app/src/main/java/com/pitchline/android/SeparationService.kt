package com.pitchline.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SeparationStatus {
    data object Idle : SeparationStatus
    data class Running(val text: String, val percent: Int) : SeparationStatus
    data class Finished(val id: String?, val message: String) : SeparationStatus
}

class SeparationService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var task: kotlinx.coroutines.Job? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == cancelAction) {
            task?.cancel()
            return START_NOT_STICKY
        }
        if (task?.isActive == true) return START_NOT_STICKY
        val mode = SeparationMode.entries.firstOrNull { it.key == intent?.getStringExtra("mode") }
        if (mode == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("project")
        val modelStore = ModelStore(this)
        notifications.createNotificationChannel(NotificationChannel(channel, "ボーカル分離", NotificationManager.IMPORTANCE_LOW))
        val first = "モデルを確認中"
        try {
            val foregroundType = if (Build.VERSION.SDK_INT >= 35 && id != null)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId, notification(first, 0), foregroundType)
            else startForeground(notificationId, notification(first, 0))
        } catch (error: Exception) {
            updates.value = SeparationStatus.Finished(null, "分離を開始できません: ${error.message.orEmpty()}")
            stopSelf()
            return START_NOT_STICKY
        }
        updates.value = SeparationStatus.Running(first, 0)
        task = scope.launch(Dispatchers.IO) {
            var pending: Pair<String, String>? = null
            val store = ProjectStore(this@SeparationService)
            val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pitchline:separation")
            var completion: SeparationStatus.Finished
            try {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                val graph = modelStore.prepare(mode) { update("モデルを取得中", it) }
                ensureActive()
                if (id == null) {
                    completion = SeparationStatus.Finished(null, "${mode.label}モデルを保存しました")
                } else {
                    val project = store.load(id)
                    val previous = project.stems[mode.key]
                    if (previous == null) {
                        val key = "${mode.key}-${store.newId()}"
                        pending = id to key
                        val vocal = store.vocal(id, key)
                        val backing = store.backing(id, key)
                        update("ボーカルを分離中", 0f)
                        Separator.separate(graph, store.original(id), vocal, backing, project.info, mode) {
                            update("ボーカルを分離中", it)
                        }
                        val analysis = Processing.analyze(vocal, project.info) { update("ボーカルの音程を解析中", it) }
                        store.writeAnalysis(id, key, analysis)
                        ensureActive()
                        store.save(project.copy(stems = project.stems + (mode.key to Stems(key, NoteHistory(segment(analysis)))),
                            source = mode.key, modified = System.currentTimeMillis()))
                        pending = null
                    }
                    completion = SeparationStatus.Finished(id, "ボーカル分離が完了しました")
                }
            } catch (_: CancellationException) {
                completion = SeparationStatus.Finished(null, "キャンセルしました")
            } catch (_: OutOfMemoryError) {
                completion = SeparationStatus.Finished(null, "メモリ不足で分離を中断しました")
            } catch (error: Exception) {
                completion = SeparationStatus.Finished(null, "分離エラー: ${error.message.orEmpty()}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending?.let { (project, key) -> store.deleteStem(project, key) }
            }
            android.os.Handler(mainLooper).post {
                updates.value = completion
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun update(title: String, fraction: Float) {
        val status = SeparationStatus.Running(title, (fraction.coerceIn(0f, 1f) * 100).toInt())
        if (updates.value == status) return
        updates.value = status
        notifications.notify(notificationId, notification(status.text, status.percent))
    }

    private fun notification(title: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, SeparationService::class.java).setAction(cancelAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel)
            .setSmallIcon(com.pitchline.android.R.drawable.ic_pitchline)
            .setContentTitle(title).setContentText("Pitchline · $percent%").setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, percent, false)
            .addAction(Notification.Action.Builder(null, "キャンセル", cancel).build()).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        task?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val notificationId = 17
        private const val channel = "separation"
        private const val cancelAction = "com.pitchline.android.CANCEL_SEPARATION"
        private val updates = MutableStateFlow<SeparationStatus>(SeparationStatus.Idle)
        val status = updates.asStateFlow()

        fun start(context: Context, mode: SeparationMode, projectId: String?) {
            context.startForegroundService(Intent(context, SeparationService::class.java)
                .putExtra("mode", mode.key).putExtra("project", projectId))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SeparationService::class.java).setAction(cancelAction))
        }
    }
}
