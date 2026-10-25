package app.torboxtv.data

import app.torboxtv.core.*
import app.torboxtv.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class StartupTest {
    private val movie = Target(Title("tt0113497", "movie", "Jumanji", year = "1995"))
    private val first = Source("a".repeat(40), "Jumanji.1995.1080p.BluRay.x264", "Fast", height = 1080)
    private val second = Source("b".repeat(40), "Jumanji.1995.720p.BluRay.x264", "Backup", height = 720)
    private val credential = "test-only-credential-123456"

    @Test fun firstAcceptedBatchDoesNotWaitForSlowProviders() = runTest {
        val started = CompletableDeferred<Unit>()
        var slowCancelled = false
        var otherCancelled = false
        val client = SourceClient(providerTasks = { listOf(
            {
                started.complete(Unit)
                try { delay(60_000); listOf(second) } finally { slowCancelled = true }
            },
            { started.await(); listOf(first) },
            { try { awaitCancellation() } finally { otherCancelled = true } }
        ) })
        val batches = mutableListOf<List<Source>>()
        val result = client.find(movie) { batches += it; true }
        assertEquals(listOf(first), result)
        assertEquals(listOf(listOf(first)), batches)
        assertEquals(0L, currentTime)
        assertTrue(slowCancelled)
        assertTrue(otherCancelled)
    }

    @Test fun verificationCallbackHasItsOwnBudgetBeyondProviderTimeout() = runTest {
        var slowFinished = false
        val client = SourceClient(providerTasks = { listOf(
            { listOf(first) },
            { try { delay(60_000); listOf(second) } finally { slowFinished = true } }
        ) })
        val result = client.find(movie) { batch ->
            assertEquals(listOf(first), batch)
            delay(20_000)
            true
        }
        assertEquals(listOf(first), result)
        assertEquals(20_000L, currentTime)
        assertTrue(slowFinished)
    }

    @Test fun emptyAndUnrelatedRowsCannotTriggerPlaybackBatch() = runTest {
        val unrelated = first.copy(name = "Jumanji.2017.1080p.BluRay.x264")
        val client = SourceClient(providerTasks = { listOf(
            { emptyList() }, { listOf(unrelated) }, { delay(10); listOf(first) }
        ) })
        val batches = mutableListOf<List<Source>>()
        assertEquals(listOf(first), client.find(movie) { batches += it; true })
        assertEquals(listOf(listOf(first)), batches)
        assertEquals(10L, currentTime)
    }

    @Test fun rejectedEarlyBatchContinuesToLaterProviderAndRanksCumulativeRows() = runTest {
        val client = SourceClient(providerTasks = { listOf(
            { listOf(second) }, { delay(10); listOf(first, second) }
        ) })
        val batches = mutableListOf<List<Source>>()
        val result = client.find(movie) { batch -> batches += batch; batch.any { it.hash == first.hash } }
        assertEquals(listOf(listOf(second), listOf(first, second)), batches)
        assertEquals(listOf(first, second), result)
        assertEquals(10L, currentTime)
    }

    @Test fun partialCacheIsImmediateButCompleteDiscoveryStillRuns() = runTest {
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = {
            discoveryRuns++
            if (discoveryRuns == 1) listOf({ listOf(first) }, { delay(60_000); listOf(second) })
            else listOf({ listOf(second) })
        })
        assertEquals(listOf(first), client.find(movie) { true })
        assertEquals(1, discoveryRuns)
        val batches = mutableListOf<List<Source>>()
        assertEquals(listOf(first, second), client.find(movie) { batches += it; false })
        assertEquals(listOf(first), batches.first())
        assertEquals(2, discoveryRuns)
        assertEquals(listOf(first, second), client.find(movie))
        assertEquals(2, discoveryRuns)
        assertEquals(0L, currentTime)
    }

    @Test fun cancellationAfterBatchPreservesPartialWithoutCallingItComplete() = runTest {
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = {
            discoveryRuns++
            if (discoveryRuns == 1) listOf({ listOf(first) }, { delay(60_000); listOf(second) })
            else listOf({ listOf(second) })
        })
        val batchReceived = CompletableDeferred<Unit>()
        val interrupted = launch {
            client.find(movie) { batchReceived.complete(Unit); awaitCancellation() }
        }
        batchReceived.await()
        interrupted.cancelAndJoin()
        val batches = mutableListOf<List<Source>>()
        assertEquals(listOf(first, second), client.find(movie) { batches += it; false })
        assertEquals(listOf(first), batches.first())
        assertEquals(2, discoveryRuns)
        assertEquals(0L, currentTime)
    }

    @Test fun completeCacheDoesNotRestartProviders() = runTest {
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = {
            discoveryRuns++
            listOf({ listOf(first) }, { listOf(second) })
        })
        assertEquals(listOf(first, second), client.find(movie))
        val cachedBatches = mutableListOf<List<Source>>()
        assertEquals(listOf(first, second), client.find(movie) { cachedBatches += it; false })
        assertEquals(1, discoveryRuns)
        assertEquals(listOf(listOf(first, second)), cachedBatches)
    }

    @Test fun olderPartialDiscoveryCannotOverwriteNewCompleteCache() = runTest {
        val olderStarted = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = {
            discoveryRuns++
            if (discoveryRuns == 1) listOf(
                { olderStarted.complete(Unit); releaseOlder.await(); listOf(second) },
                { awaitCancellation() }
            ) else listOf({ listOf(first) })
        })
        val older = async { client.find(movie) { true } }
        olderStarted.await()
        assertEquals(listOf(first), client.find(movie))
        releaseOlder.complete(Unit)
        assertEquals(listOf(second), older.await())
        assertEquals(listOf(first), client.find(movie))
        assertEquals(2, discoveryRuns)
    }

    @Test fun allProviderFailuresRemainAnOutageRatherThanCompleteEmptyCache() = runTest {
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = {
            discoveryRuns++
            listOf({ null }, { throw java.io.IOException("Fixture outage") })
        })
        repeat(2) {
            try { client.find(movie); fail("Outage accepted as an empty successful response") }
            catch (error: UserError) { assertTrue(error.message!!.contains("unavailable")) }
        }
        assertEquals(2, discoveryRuns)
    }

    @Test fun outageCannotPromotePartialCacheToComplete() = runTest {
        var runs = 0
        val client = SourceClient(providerTasks = {
            runs++
            if (runs == 1) listOf({ listOf(first) }, { awaitCancellation() })
            else listOf({ null })
        })
        assertEquals(listOf(first), client.find(movie) { true })
        repeat(2) {
            try { client.find(movie); fail("Outage promoted partial metadata to complete") }
            catch (_: UserError) { }
        }
        assertEquals(3, runs)
    }

    @Test fun successfulEmptyDiscoveryCanBeCached() = runTest {
        var discoveryRuns = 0
        val client = SourceClient(providerTasks = { discoveryRuns++; listOf({ emptyList() }) })
        assertTrue(client.find(movie).isEmpty())
        assertTrue(client.find(movie).isEmpty())
        assertEquals(1, discoveryRuns)
    }

    @Test fun publicMetadataDiscoveryHasNoAccountOrMediaRequests() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = SourceClient(transport({ request ->
            val response = if (request.url.host == "zileanfortheweebs.midnightignite.me")
                JSONArray().put(JSONObject().put("imdb_id", movie.title.id).put("info_hash", first.hash)
                    .put("raw_title", first.name)).toString()
            else JSONObject().put("streams", JSONArray().put(JSONObject().put("infoHash", first.hash)
                .put("behaviorHints", JSONObject().put("filename", first.name)))).toString()
            200 to response
        }, requests))
        assertEquals(1, client.find(movie).size)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.header("Authorization") == null })
        assertTrue(requests.none { it.url.host == "api.torbox.app" || MediaHostGuard.valid(it.url.toString()) })
        assertTrue(requests.none { it.url.queryParameter("token") != null || it.url.toString().contains(credential) })
    }

    @Test fun startupReturnsFirstValidatedCachedChoiceWithoutPreparingAlternatives() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(first.hash, true).put(second.hash, true))
                "mylist" -> 200 to envelope(if (request.url.queryParameter("id") != null) item(9, first) else JSONArray())
                "createtorrent" -> if (formField(request, "magnet").endsWith(first.hash))
                    200 to envelope(JSONObject().put("torrent_id", 9)) else 503 to "{}"
                else -> 500 to "{}"
            }
        }, requests))
        val selected = client.choices(movie, listOf(first, second), maxChoices = 1).single()
        assertEquals(first.hash, selected.source.hash)
        assertEquals(77, selected.file.id)
        val creates = requests.filter { it.method == "POST" }
        assertEquals(1, creates.size)
        assertEquals("true", formField(creates.single(), "add_only_if_cached"))
        assertTrue(formField(creates.single(), "magnet").endsWith(first.hash))
        assertFalse(requests.any { it.url.pathSegments.last() == "requestdl" })
    }

    @Test fun startupSkipsUnsafeFirstFileAndChecksSecondBeforeReturning() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(first.hash, true).put(second.hash, true))
                "mylist" -> {
                    val id = request.url.queryParameter("id")?.toInt()
                    200 to envelope(when (id) {
                        9 -> item(9, first, "Jumanji.2017.1080p.mkv")
                        10 -> item(10, second)
                        else -> JSONArray()
                    })
                }
                "createtorrent" -> 200 to envelope(JSONObject().put("torrent_id",
                    if (formField(request, "magnet").endsWith(first.hash)) 9 else 10))
                else -> 500 to "{}"
            }
        }, requests))
        val selected = client.choices(movie, listOf(first, second), maxChoices = 1).single()
        assertEquals(second.hash, selected.source.hash)
        assertEquals(2, requests.count { it.method == "POST" })
        assertTrue(requests.filter { it.method == "POST" }.all { formField(it, "add_only_if_cached") == "true" })
    }

    @Test fun cacheMissStopsBeforeAccountListOrPreparationAndMarksNoExaminedRows() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val examined = mutableListOf<Source>()
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            if (request.url.pathSegments.last() == "checkcached")
                200 to envelope(JSONObject().put(first.hash, false).put(second.hash, false))
            else 500 to "{}"
        }, requests))
        assertTrue(client.choices(movie, listOf(first, second), maxChoices = 1, onExamined = { examined += it }).isEmpty())
        assertTrue(examined.isEmpty())
        assertEquals(1, requests.size)
        assertEquals("checkcached", requests.single().url.pathSegments.last())
        assertEquals("GET", requests.single().method)
    }

    @Test fun attemptBudgetCountsOnlyCachedRowsActuallyExaminedAndStopsBeforeThird() = runBlocking {
        val uncached = first.copy(hash = "c".repeat(40))
        val third = first.copy(hash = "d".repeat(40))
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val examined = mutableListOf<Source>()
        val client = TorBoxClient({ credential }, MemoryOperationLedger(), transport({ request ->
            when (request.url.pathSegments.last()) {
                "checkcached" -> 200 to envelope(JSONObject().put(uncached.hash, false).put(first.hash, true)
                    .put(second.hash, true).put(third.hash, true))
                "mylist" -> {
                    val id = request.url.queryParameter("id")?.toInt()
                    200 to envelope(when (id) {
                        9 -> item(9, first, "Jumanji.2017.1080p.mkv")
                        10 -> item(10, second, "Jumanji.2017.720p.mkv")
                        11 -> item(11, third)
                        else -> JSONArray()
                    })
                }
                "createtorrent" -> 200 to envelope(JSONObject().put("torrent_id", when {
                    formField(request, "magnet").endsWith(first.hash) -> 9
                    formField(request, "magnet").endsWith(second.hash) -> 10
                    else -> 11
                }))
                else -> 500 to "{}"
            }
        }, requests))
        val choices = client.choices(movie, listOf(uncached, first, second, third), maxChoices = 1,
            maxAttempts = 2, onExamined = { examined += it })
        assertTrue(choices.isEmpty())
        assertEquals(listOf(first, second), examined)
        val creates = requests.filter { it.method == "POST" }
        assertEquals(2, creates.size)
        assertTrue(creates.all { formField(it, "add_only_if_cached") == "true" })
        assertFalse(creates.any { formField(it, "magnet").endsWith(uncached.hash) || formField(it, "magnet").endsWith(third.hash) })
    }

    private fun item(id: Int, source: Source, filename: String = "Jumanji.1995.${source.height}p.mkv") = JSONObject()
        .put("id", id).put("hash", source.hash).put("download_finished", true).put("download_present", true)
        .put("files", JSONArray().put(JSONObject().put("id", 77).put("name", filename)))
    private fun envelope(value: Any) = JSONObject().put("success", true).put("data", value).toString()
    private fun formField(request: Request, name: String): String {
        val body = request.body as MultipartBody
        val field = body.parts.single { it.headers?.get("Content-Disposition") == "form-data; name=\"$name\"" }
        val buffer = Buffer(); field.body.writeTo(buffer)
        return buffer.readUtf8()
    }
    private fun transport(script: (Request) -> Pair<Int, String>, requests: MutableList<Request>): JsonHttp =
        JsonHttp(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (code, body) = script(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
}
