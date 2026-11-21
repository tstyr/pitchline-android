package com.pitchline.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

enum class AudioRoute(val label: String, val deviceTypes: Set<Int>) {
    SYSTEM("システム既定", emptySet()),
    SPEAKER("端末スピーカー", setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)),
    WIRED("有線 / USB", setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)),
    BLUETOOTH("Bluetooth", setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER)),
}

data class Preferences(
    val highQuality: Boolean = true,
    val bufferMs: Int = 80,
    val route: AudioRoute = AudioRoute.SYSTEM,
    val highContrast: Boolean = false,
) {
    companion object {
        fun read(context: Context): Preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE).let {
            Preferences(it.getBoolean("quality", true), it.getInt("buffer", 80),
                AudioRoute.entries.firstOrNull { route -> route.name == it.getString("route", null) } ?: AudioRoute.SYSTEM,
                it.getBoolean("contrast", false))
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("quality", highQuality)
            .putInt("buffer", bufferMs).putString("route", route.name).putBoolean("contrast", highContrast).apply()
    }
}

object Playback {
    suspend fun play(
        context: Context, audio: AudioInfo, source: MixReader, from: Long, loop: LongRange?,
        preferences: Preferences, mix: () -> Mix, position: (Long) -> Unit, interrupted: () -> Unit,
    ) {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val manager = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { if (it < 0) interrupted() }.build()
        require(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "音声出力を開始できません" }
        try {
            val mask = if (audio.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val size = AudioTrack.getMinBufferSize(audio.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            require(size > 0) { "このサンプルレートを再生できません" }
            val output = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(size, audio.rate * preferences.bufferMs / 1000 * 2 * audio.channels))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            try {
                if (preferences.route != AudioRoute.SYSTEM) {
                    val route = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in preferences.route.deviceTypes }
                    require(route != null && output.setPreferredDevice(route)) { "設定した音声出力が見つかりません" }
                }
                val start = if (loop != null && from !in loop) loop.first else from.coerceIn(0, audio.frames - 1)
                val end = loop?.last?.plus(1) ?: audio.frames
                var at = start
                var sent = 0L
                val samples = ShortArray(1024 * audio.channels)
                val initial = mix()
                var previousMix = initial
                var lastPosition = -1L
                output.play()
                fun update() {
                    val head = output.playbackHeadPosition.toUInt().toLong()
                    val firstPass = end - start
                    val next = if (loop != null && head >= firstPass) loop.first + (head - firstPass) % (end - loop.first)
                        else (start + head).coerceAtMost(end)
                    if (next != lastPosition) {
                        position(next)
                        lastPosition = next
                    }
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (at == end) {
                        if (loop == null) break
                        at = loop.first
                    }
                    val count = minOf(1024L, end - at).toInt()
                    val current = mix()
                    val pcm = source.read(at, count, previousMix, current)
                    for (i in pcm.indices) samples[i] = (pcm[i].coerceIn(-1f, 0.9999695f) * 32768).roundToInt().toShort()
                    previousMix = current
                    var written = 0
                    while (written < pcm.size) {
                        currentCoroutineContext().ensureActive()
                        val n = output.write(samples, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                        require(n > 0) { "再生エラー (AudioTrack $n)" }
                        written += n
                    }
                    sent += count
                    at += count
                    update()
                }
                while (output.playbackHeadPosition.toUInt().toLong() < sent) {
                    delay(20)
                    update()
                }
                position(end)
            } finally {
                output.release()
            }
        } finally {
            manager.abandonAudioFocusRequest(focus)
        }
    }
}
