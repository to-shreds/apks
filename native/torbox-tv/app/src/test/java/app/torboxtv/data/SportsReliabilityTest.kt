package app.torboxtv.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SportsReliabilityTest {
    private val replay = SportsSource("redbull", "Replay", videoId = "rrn:content:live-videos:12345678-1234-1234-1234-123456789abc:en-INT")
    private fun response(request: Request, body: ResponseBody) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    @Test fun cancellingReplayOpeningCancelsTheActualHttpCallImmediately() = runBlocking {
        val active = AtomicReference<Call>()
        val release = AtomicBoolean(false)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            active.set(chain.call())
            while (!release.get() && !chain.call().isCanceled()) Thread.sleep(2)
            throw IOException("Fixture released")
        }.build()
        val opening = launch { try { SportsClient(http).resolve(replay) } catch (_: IOException) { } }
        withTimeout(2000) { while (active.get() == null) delay(2) }
        opening.cancel()
        delay(100)
        val cancelledByOpening = active.get().isCanceled()
        release.set(true)
        active.get().cancel()
        withTimeout(2000) { opening.join() }
        assertTrue("Leaving a replay must cancel its HTTP call, not only suppress the result", cancelledByOpening)
    }

    @Test fun repeatedOpenCancelDoesNotLeaveProviderCallsRunning() = runBlocking {
        val calls = mutableListOf<Call>()
        val release = AtomicBoolean(false)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(calls) { calls += chain.call() }
            while (!release.get() && !chain.call().isCanceled()) Thread.sleep(2)
            throw IOException("Fixture released")
        }.build()
        val client = SportsClient(http)
        try {
            repeat(120) { index ->
                val opening = launch { try { client.resolve(replay) } catch (_: IOException) { } }
                withTimeout(2000) { while (synchronized(calls) { calls.size } <= index) delay(2) }
                opening.cancel()
                withTimeout(1000) { opening.join() }
                assertTrue(synchronized(calls) { calls.all { it.isCanceled() } })
            }
            assertEquals(120, synchronized(calls) { calls.size })
        } finally {
            release.set(true)
            synchronized(calls) { calls.forEach { it.cancel() } }
        }
    }

    @Test fun unknownLengthResponsesRemainBoundedAndAreClosed() = runBlocking {
        val closed = AtomicBoolean(false)
        val body = object : ResponseBody() {
            val bytes = object : ForwardingSource(Buffer().writeUtf8("x".repeat(1024 * 1024 + 1))) {
                override fun close() { closed.set(true); super.close() }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = bytes
        }
        val http = OkHttpClient.Builder().addInterceptor { response(it.request(), body) }.build()
        try { SportsClient(http).resolve(replay); fail("Oversized unknown-length metadata accepted") }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("Unreadable")) }
        assertTrue(closed.get())
    }

    @Test fun replayResolutionPreservesPublicOnlyRequestsAndRejectsUnsafeMedia() = runBlocking {
        val requested = mutableListOf<Request>()
        for (destination in listOf("https://torbox-web-player-key.onrender.com/video", "https://play.redbull.com.evil.test/movie", "https://user:secret@play.redbull.com/movie")) {
            val http = OkHttpClient.Builder().addInterceptor {
                requested += it.request()
                response(it.request(), "{\"videoUrl\":\"$destination\"}".toResponseBody())
            }.build()
            try { SportsClient(http).resolve(replay); fail("Unsafe replay destination accepted") }
            catch (_: IOException) { }
        }
        assertEquals(3, requested.size)
        assertTrue(requested.all { it.url.host == "api-player.redbull.com" && it.header("Authorization") == null && it.header("Cookie") == null })
    }
}
