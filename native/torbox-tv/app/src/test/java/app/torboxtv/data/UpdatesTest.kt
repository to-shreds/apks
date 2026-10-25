package app.torboxtv.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdatesTest {
    private val payload = ByteArray(100_000) { (it % 251).toByte() }
    private fun manifest(code: Long = 8, data: ByteArray = payload) = JSONObject()
        .put("schema", 1).put("package", "app.torboxtv").put("versionCode", code).put("versionName", "0.2.5")
        .put("apkUrl", UpdateChannel.BASE + "releases/TorBox-TV-0.2.5-test.apk")
        .put("sha256", UpdateChannel.sha256(data)).put("bytes", data.size).put("notes", "Reliability fixes")
    private fun directory() = Files.createTempDirectory("apk-updater-test").toFile()
    private fun client(server: MockWebServer): UpdateClient = UpdateClient(OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val original = chain.request()
            assertEquals("raw.githubusercontent.com", original.url.host)
            assertNull(original.header("Authorization"))
            assertTrue(original.url.encodedPath.startsWith("/to-shreds/torbox-web-player/native-updates/native/updates/"))
            chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
        }.build())
    private fun rejects(action: () -> Unit) { try { action(); fail("Unsafe update was accepted") } catch (_: IllegalArgumentException) {} }

    @Test fun manifestRequiresExactAppVersionChecksumSizeAndGitHubPath() {
        assertEquals(8L, UpdateChannel.parse(manifest().toString()).code)
        for ((key, value) in listOf("schema" to 2, "package" to "other.app", "versionCode" to -1,
            "versionName" to "../../bad", "sha256" to "none", "bytes" to 0, "bytes" to UpdateChannel.MAX_APK_BYTES + 1,
            "apkUrl" to "https://evil.example/update.apk", "apkUrl" to "http://raw.githubusercontent.com/update.apk",
            "apkUrl" to UpdateChannel.BASE + "releases/TorBox-TV-0.2.5-test.apk?token=private")) {
            rejects { UpdateChannel.parse(manifest().put(key, value).toString()) }
        }
    }

    @Test fun currentAndOlderVersionsDoNotOfferOrDownloadAnUpdate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest(7).toString()))
            server.enqueue(MockResponse().setBody(manifest(6).toString()))
            val client = client(server)
            assertNull(client.check(7)); assertNull(client.check(7))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun downloadPublishesOnlyCompleteChecksumVerifiedBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest().toString()))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val client = client(server); val dir = directory()
            try {
                val update = client.check(7)!!
                val progress = mutableListOf<Int>()
                val result = client.download(update, dir) { progress.add(it) }
                assertArrayEquals(payload, result.readBytes())
                assertTrue(dir.listFiles()!!.none { it.extension == "part" })
                assertEquals(100, progress.last()); assertTrue(progress.zipWithNext().all { it.first <= it.second })
                assertEquals(2, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun wrongHashTruncatedAndOversizeBodiesNeverBecomeInstallable() = runBlocking {
        for (body in listOf(payload.map { (it + 1).toByte() }.toByteArray(), payload.copyOf(1000), payload + byteArrayOf(3))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setChunkedBody(Buffer().write(body), 1024))
                val dir = directory()
                try {
                    try { client(server).download(UpdateChannel.parse(manifest().toString()), dir); fail("Bad download was accepted") }
                    catch (_: java.io.IOException) { }
                    assertFalse(File(dir, "update.apk").exists()); assertTrue(dir.listFiles()!!.none { it.extension == "part" })
                } finally { dir.deleteRecursively() }
            }
        }
    }

    @Test fun oversizedChunkedMetadataAndRedirectsAreRejected() = runBlocking {
        for (response in listOf(MockResponse().setChunkedBody("x".repeat(40_000), 1000),
            MockResponse().setResponseCode(302).addHeader("Location", "https://evil.example/latest.json"))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                try { client(server).check(7); fail("Invalid metadata response accepted") }
                catch (_: java.io.IOException) { }
                assertEquals("The updater must not follow a redirect", 1, server.requestCount)
            }
        }
    }

    @Test fun cancelingDownloadStopsTheActualBodyAndRemovesPartialFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(payload)).throttleBody(1000, 1, TimeUnit.SECONDS))
            val client = client(server); val dir = directory(); val entered = CountDownLatch(1)
            try {
                val download = launch { client.download(UpdateChannel.parse(manifest().toString()), dir) { entered.countDown() } }
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                withTimeout(3000) { download.cancelAndJoin() }
                withTimeout(3000) { while (dir.listFiles()!!.any { it.extension == "part" }) delay(20) }
                assertFalse(File(dir, "update.apk").exists())
                assertEquals(1, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun cancellationAlsoInterruptsAnUnansweredMetadataCheck() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val check = launch { client(server).check(7) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(3000) { check.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidDownloadArgumentFailsBeforeNetworkOrFileMutation() = runBlocking {
        MockWebServer().use { server ->
            val dir = directory(); val marker = File(dir, "update.apk").apply { writeText("retained") }
            try {
                try { client(server).download(UpdateChannel.parse(manifest().toString()).copy(url = "https://evil.example/update.apk"), dir); fail("Invalid download accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals("retained", marker.readText()); assertEquals(0, server.requestCount)
            } finally { dir.deleteRecursively() }
        }
    }
}
