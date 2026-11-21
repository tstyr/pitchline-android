package com.pitchline.android

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private val data = ByteArray(140_000) { (it % 113).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private class Connection(url: URL, private val status: Int = 200, private val body: InputStream,
        private val location: String? = null) : HttpURLConnection(url) {
        val closed = AtomicBoolean()
        override fun connect() {}
        override fun disconnect() { closed.set(true); body.close() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = body
    }
    @Test fun followsHttpsRedirectsAndPublishesOnlyVerifiedBytes() = runBlocking {
        val calls = CopyOnWriteArrayList<Connection>()
        val phases = CopyOnWriteArrayList<ModelProgress>()
        val file = folder.newFile("model.pth")
        ModelDownload(connect = { url ->
            Connection(url, if (calls.isEmpty()) 302 else 200, ByteArrayInputStream(data), "https://assets.example/model").also { calls.add(it) }
        }).fetch("https://example/model", file, data.size.toLong(), hash, phases::add)
        assertArrayEquals(data, file.readBytes())
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.closed.get() })
        assertTrue(phases.first().fraction == null)
        assertTrue(phases.any { it.fraction == 1f })
        assertFalse(file.resolveSibling("model.pth.part").exists())
    }
    @Test fun corruptedDownloadsCannotReplaceThePreviousModel() = runBlocking {
        val file = folder.newFile("model.pth").apply { writeText("previous") }
        try {
            ModelDownload(connect = { Connection(it, body = ByteArrayInputStream(data)) })
                .fetch("https://example/model", file, data.size.toLong(), "0".repeat(64)) {}
            fail("A SHA mismatch must fail")
        } catch (_: IllegalArgumentException) {}
        assertEquals("previous", file.readText())
        assertFalse(file.resolveSibling("model.pth.part").exists())
    }
    @Test fun stalledReadsTimeOutAndDisconnectInsteadOfRemainingAtZero() = runBlocking {
        val entered = AtomicBoolean()
        val latch = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { entered.set(true); latch.await(); throw IOException("Closed") }
            override fun close() { latch.countDown() }
        }
        val connection = Connection(URL("https://example/model"), body = stream)
        val file = folder.newFile("model.pth")
        try {
            ModelDownload(connect = { connection }, deadlineMs = 200).fetch(connection.url.toString(), file, 100, hash) {}
            fail("Stalled transfer must time out")
        } catch (_: SocketTimeoutException) {}
        assertTrue(entered.get())
        assertTrue(connection.closed.get())
        assertFalse(file.resolveSibling("model.pth.part").exists())
    }
    @Test fun cancelDisconnectsImmediatelyAndHttpsCannotDowngrade() = runBlocking {
        val entered = AtomicBoolean()
        val latch = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int { entered.set(true); latch.await(); throw IOException("Closed") }
            override fun close() { latch.countDown() }
        }
        val connection = Connection(URL("https://example/model"), body = stream)
        val file = folder.newFile("model.pth")
        val job = launch(Dispatchers.Default) { ModelDownload(connect = { connection }).fetch(connection.url.toString(), file, 100, hash) {} }
        withTimeout(3000) { while (!entered.get()) delay(1) }
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(connection.closed.get())
        assertFalse(file.resolveSibling("model.pth.part").exists())
        try {
            ModelDownload(connect = { Connection(it, 302, ByteArrayInputStream(data), "http://example/unsafe") })
                .fetch("https://example/model", file, data.size.toLong(), hash) {}
            fail("HTTPS downgrade must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
