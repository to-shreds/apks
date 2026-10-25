package app.torboxtv.data

import app.torboxtv.core.*
import app.torboxtv.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class NetworkReliabilityTest {
    private val movie = Target(Title("tt0113497", "movie", "Jumanji", year = "1995"))
    private val first = Source("a".repeat(40), "Jumanji.1995.1080p.BluRay.x264", "Primary", height = 1080)
    private val second = Source("b".repeat(40), "Jumanji.1995.720p.BluRay.x264", "Backup", height = 720)
    private val credential = "test-only-credential-123456"

    @Test fun olderCompletedDiscoveryCannotReplaceNewerResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            if (runs == 1) listOf({ started.complete(Unit); release.await(); listOf(second) })
            else listOf({ listOf(first) })
        })
        val older = async { client.find(movie) }
        started.await()
        assertEquals(listOf(first), client.find(movie))
        release.complete(Unit)
        assertEquals(listOf(second), older.await())
        assertEquals(listOf(first), client.find(movie))
        assertEquals(2, runs)
    }

    @Test fun invalidationPreventsAnInFlightDiscoveryFromRestoringStaleCache() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            if (runs == 1) listOf({ started.complete(Unit); release.await(); listOf(second) })
            else listOf({ listOf(first) })
        })
        val older = async { client.find(movie) }
        started.await()
        client.invalidate(movie)
        release.complete(Unit)
        older.await()
        assertEquals(listOf(first), client.find(movie))
        assertEquals(2, runs)
    }

    @Test fun partialProviderOutageRemainsRetryableAndKeepsUsefulSources() = runTest {
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            if (runs == 1) listOf({ listOf(first) }, { throw IOException("Fixture provider outage") })
            else listOf({ listOf(second) })
        })
        assertEquals(listOf(first), client.find(movie))
        val batches = mutableListOf<List<Source>>()
        assertEquals(listOf(first, second), client.find(movie) { batches += it; false })
        assertEquals(listOf(first), batches.first())
        assertEquals(2, runs)
        assertEquals(listOf(first, second), client.find(movie))
        assertEquals(2, runs)
    }

    @Test fun invalidationWhileVerifyingCachedBatchCannotRegainCacheOwnership() = runTest {
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            when (runs) {
                1 -> listOf({ listOf(first) }, { awaitCancellation() })
                2 -> listOf({ listOf(second) })
                else -> listOf({ listOf(first) })
            }
        })
        assertEquals(listOf(first), client.find(movie) { true })
        val older = async { client.find(movie) { verifying.complete(Unit); release.await(); false } }
        verifying.await()
        client.invalidate(movie)
        assertEquals(listOf(second), client.find(movie))
        release.complete(Unit)
        older.await()
        assertEquals(listOf(second), client.find(movie))
        assertEquals(3, runs)
    }

    @Test fun emptyResponsePlusProviderOutageDoesNotCacheACompleteNoMatch() = runTest {
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            if (runs == 1) listOf({ emptyList() }, { null })
            else listOf({ listOf(first) })
        })
        assertTrue(client.find(movie).isEmpty())
        assertEquals(listOf(first), client.find(movie))
        assertEquals(2, runs)
    }

    @Test fun interruptedCachedPreparationTriesTheNextVerifiedSourceWithoutRepeatingThePost() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(first.hash, true).put(second.hash, true))
                "mylist" -> 200 to envelope(if (request.url.queryParameter("id") != null) item(10, second) else JSONArray())
                "createtorrent" -> {
                    if (formField(request, "magnet").endsWith(first.hash)) throw IOException("Fixture interrupted POST")
                    200 to envelope(JSONObject().put("torrent_id", 10))
                }
                else -> error("Unexpected request")
            }
        }, requests))
        assertEquals(second.hash, client.choices(movie, listOf(first, second), maxChoices = 1).single().source.hash)
        try { client.prepare(movie, first); fail("Ambiguous POST was retried") }
        catch (error: UserError) { assertTrue(error.message!!.contains("unconfirmed")) }
        assertEquals(1, requests.count { it.method == "POST" && formField(it, "magnet").endsWith(first.hash) })
        assertEquals(2, requests.count { it.method == "POST" })
        assertTrue(requests.filter { it.method == "POST" }.all { formField(it, "add_only_if_cached") == "true" })
        assertFalse(requests.any { it.url.pathSegments.last() == "requestdl" })
    }

    @Test fun accountRateLimitStopsAlternativesAndKeepsTheCooldown() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(first.hash, true).put(second.hash, true))
                "mylist" -> 200 to envelope(JSONArray())
                "createtorrent" -> 429 to "{}"
                else -> error("Unexpected request")
            }
        }, requests))
        try { client.choices(movie, listOf(first, second)); fail("Rate limit was swallowed") }
        catch (error: UserError) { assertTrue(error.message!!.contains("limiting")) }
        val beforeRetry = requests.size
        try { client.prepare(movie, first); fail("Cooldown was ignored") }
        catch (error: UserError) { assertTrue(error.message!!.contains("slow down")) }
        assertEquals(beforeRetry, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test fun cancellationStopsAlternativesAndPreservesAmbiguousPreparationMarker() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(first.hash, true).put(second.hash, true))
                "mylist" -> 200 to envelope(JSONArray())
                "createtorrent" -> { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); 503 to "{}" }
                else -> error("Unexpected request")
            }
        }, requests))
        val lookup = async { client.choices(movie, listOf(first, second)) }
        try { withTimeout(5_000) { entered.await() }; lookup.cancelAndJoin() }
        finally { release.countDown() }
        try { client.prepare(movie, first); fail("Cancelled POST was retried") }
        catch (error: UserError) { assertTrue(error.message!!.contains("unconfirmed")) }
        assertEquals(1, requests.count { it.method == "POST" })
        assertTrue(formField(requests.single { it.method == "POST" }, "magnet").endsWith(first.hash))
    }

    @Test fun simultaneousClientsCannotCreateTheSameUnconfirmedPreparationTwice() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val existing = MemoryOperationLedger()
        val checked = CyclicBarrier(2)
        // Expose the interleaving between separate contains/mark calls deterministically.
        val ledger = object : OperationLedger by existing {
            override fun contains(id: String): Boolean {
                val result = existing.contains(id)
                checked.await(5, TimeUnit.SECONDS)
                return result
            }
        }
        val http = transport({ request ->
            when (request.url.pathSegments.last()) {
                "mylist" -> 200 to envelope(JSONArray())
                "createtorrent" -> 503 to "{}"
                else -> error("Unexpected request")
            }
        }, requests)
        val results = (1..2).map {
            async(Dispatchers.Default) { runCatching { TorBoxClient({ credential }, ledger, http).prepare(movie, first) } }
        }.awaitAll()
        assertTrue(results.all { it.isFailure })
        assertEquals("Only one client may send an ambiguous creation: ${results.map { it.exceptionOrNull()?.javaClass?.simpleName }}", 1, requests.count { it.method == "POST" })
        assertTrue(results.any { it.exceptionOrNull()?.message?.contains("unconfirmed") == true })
    }

    private fun item(id: Int, source: Source) = JSONObject().put("id", id).put("hash", source.hash)
        .put("download_finished", true).put("download_present", true)
        .put("files", JSONArray().put(JSONObject().put("id", 77).put("name", "Jumanji.1995.${source.height}p.mkv")))
    private fun envelope(value: Any) = JSONObject().put("success", true).put("data", value).toString()
    private fun formField(request: Request, name: String): String {
        val part = (request.body as MultipartBody).parts.single { it.headers?.get("Content-Disposition") == "form-data; name=\"$name\"" }
        val buffer = Buffer(); part.body.writeTo(buffer); return buffer.readUtf8()
    }
    private fun transport(script: (Request) -> Pair<Int, String>, requests: MutableList<Request>): JsonHttp =
        JsonHttp(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val (code, body) = script(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
}
