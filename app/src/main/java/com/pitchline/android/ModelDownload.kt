package com.pitchline.android

import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class ModelProgress(val text: String, val fraction: Float? = null)

/** HTTPS redirects, bounded reads, and a separate disconnect on cancellation. */
class ModelDownload(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val deadlineMs: Long = 90_000,
) {
    suspend fun fetch(url: String, destination: File, bytes: Long, checksum: String, report: (ModelProgress) -> Unit) {
        val partial = File(destination.parentFile, "${destination.name}.part")
        val active = AtomicReference<HttpURLConnection?>()
        try {
            val completed = withTimeoutOrNull(deadlineMs) {
                coroutineScope {
                    val disconnect = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { active.get()?.disconnect() }
                    }
                    try {
                        val context = currentCoroutineContext()
                        runInterruptible(Dispatchers.IO) {
                            var address = URL(url)
                            var redirects = 0
                            var connection: HttpURLConnection
                            while (true) {
                                context.ensureActive()
                                require(address.protocol == "https") { "モデル取得には HTTPS が必要です" }
                                report(ModelProgress("モデル配布先へ接続中"))
                                connection = connect(address).also { active.set(it) }
                                connection.connectTimeout = 8_000
                                connection.readTimeout = 8_000
                                connection.instanceFollowRedirects = false
                                connection.setRequestProperty("User-Agent", "Pitchline-Android")
                                val status = connection.responseCode
                                if (status in listOf(301, 302, 303, 307, 308)) {
                                    val location = connection.getHeaderField("Location") ?: throw IOException("転送先がありません")
                                    if (++redirects > 5) throw IOException("モデル配布先の転送が多すぎます")
                                    address = URL(address, location)
                                    connection.disconnect()
                                } else {
                                    if (status != 200) throw IOException("モデル配布先 HTTP $status")
                                    break
                                }
                            }
                            val hash = MessageDigest.getInstance("SHA-256")
                            connection.inputStream.use { input -> partial.outputStream().buffered().use { output ->
                                val buffer = ByteArray(65_536)
                                var total = 0L
                                report(ModelProgress("モデルを取得中", 0f))
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= bytes) { "モデルのサイズが一致しません" }
                                    hash.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                    report(ModelProgress("モデルを取得中", total.toFloat() / bytes))
                                }
                                report(ModelProgress("モデルの SHA-256 を検証中"))
                                require(total == bytes && hash.digest().joinToString("") { "%02x".format(it) } == checksum) {
                                    "モデルの SHA-256 が一致しません"
                                }
                            } }
                            context.ensureActive()
                            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally { disconnect.cancelAndJoin() }
                }
                true
            }
            if (completed == null) throw SocketTimeoutException("モデル取得が ${deadlineMs / 1000} 秒を超えました")
        } finally {
            active.get()?.disconnect()
            partial.delete()
        }
    }
}
