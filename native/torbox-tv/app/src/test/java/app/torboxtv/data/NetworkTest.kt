package app.torboxtv.data

import app.torboxtv.core.*
import app.torboxtv.core.Target
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class NetworkTest {
    private val hash = "a".repeat(40)
    private val key = "test-only-credential-123456"
    private val movie = Target(Title("tt0113497", "movie", "Jumanji", year = "1995"))
    private val source = Source(hash, "Jumanji.1995.1080p.BluRay.x264", "Fixture", height = 1080)
    private fun item(files: List<Pair<Int, String>> = listOf(77 to "Jumanji.1995.1080p.mkv"), present: Boolean = true) = JSONObject()
        .put("id", 9).put("hash", hash).put("download_finished", true).put("download_present", present)
        .put("files", JSONArray(files.map { JSONObject().put("id", it.first).put("name", it.second) }))
    private fun envelope(value: Any) = JSONObject().put("success", true).put("data", value).toString()
    private fun formField(request: Request, name: String): String {
        val body = request.body as MultipartBody
        val field = body.parts.single { it.headers?.get("Content-Disposition") == "form-data; name=\"$name\"" }
        val buffer = Buffer(); field.body.writeTo(buffer)
        return buffer.readUtf8()
    }
    private fun transport(script: (Request) -> Pair<Int, String>, requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())): JsonHttp {
        return JsonHttp(OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val (code, body) = script(request)
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType()))
            if (code in listOf(301, 302, 303, 307, 308)) response.header("Location", body)
            response.build()
        }.build())
    }
    @Test fun validatesMediaHostsAndBoundary() {
        assertTrue(MediaHostGuard.valid("https://n1.tb-cdn.io/stream?token=owner"))
        assertTrue(MediaHostGuard.valid("https://cdn.torbox.app/file"))
        for (url in listOf("http://n1.tb-cdn.io/file", "https://api.torbox.app/v1/api/torrents/requestdl", "https://tb-cdn.io.evil.example/file", "https://evil-tb-cdn.io/file", "https://user:pass@cdn.torbox.app/file", "https://cdn.torbox.app:8443/file")) assertFalse(url, MediaHostGuard.valid(url))
    }
    @Test fun unfilteredFallbackCatalogDoesNotBecomeUnrelatedSearchResults() {
        assertTrue(CatalogClient.searchRelevant("Jumanji: Welcome to the Jungle", "Jumanji"))
        assertTrue(CatalogClient.searchRelevant("Pokémon", "Pokemon"))
        assertFalse(CatalogClient.searchRelevant("Avengers: Endgame", "Jumanji"))
        assertFalse(CatalogClient.searchRelevant("The Matrix", "The Simpsons"))
    }
    @Test fun cacheFalseAndMalformedValuesAreNotReady() {
        assertFalse(TorBoxClient.cached(false)); assertFalse(TorBoxClient.cached(null)); assertFalse(TorBoxClient.cached(JSONObject()))
        assertFalse(TorBoxClient.cached(JSONObject().put("cached", false).put("files", JSONArray())))
        assertFalse(TorBoxClient.cached(JSONObject().put("cached", "true")))
        assertTrue(TorBoxClient.cached(true)); assertTrue(TorBoxClient.cached(JSONObject().put("hash", hash)))
    }
    @Test fun onlyUnambiguousVerifiedActualFileIdIsSelected() {
        assertEquals(77, TorBoxClient.matchingChoice(item(), source, movie)?.file?.id)
        assertNull(TorBoxClient.matchingChoice(item(present = false), source, movie))
        assertNull(TorBoxClient.matchingChoice(item(listOf(1 to "Jumanji.2017.1080p.mkv")), source, movie))
        val ambiguous = item(listOf(1 to "Jumanji.1995.720p.mp4", 77 to "Jumanji.1995.1080p.mkv"))
        assertNull(TorBoxClient.matchingChoice(ambiguous, source, movie))
        assertEquals(77, TorBoxClient.matchingChoice(ambiguous, source, movie, 77)?.file?.id)
        assertNull(TorBoxClient.matchingChoice(ambiguous, source, movie, 0))
        val invalid = item().put("files", JSONArray().put(JSONObject().put("id", 77.4).put("name", "Jumanji.1995.mp4")))
        assertNull(TorBoxClient.matchingChoice(invalid, source, movie))
    }
    @Test fun wrongHashStopsSelection() {
        try { TorBoxClient.matchingChoice(item().put("hash", "b".repeat(40)), source, movie); fail("Wrong hash accepted") }
        catch (e: UserError) { assertTrue(e.message!!.contains("different torrent")) }
    }
    @Test fun verifiesCatalogAndEpisodeIdentity() {
        val rows = JSONObject().put("id", "tt0096697").put("type", "series").put("name", "The Simpsons")
            .put("videos", JSONArray().put(JSONObject().put("id", "tt0096697:1:1").put("season", 1).put("episode", 1).put("name", "First"))
                .put(JSONObject().put("id", "tt0113497:1:2").put("season", 1).put("episode", 2))
                .put(JSONObject().put("season", 1.2).put("episode", 3)))
        val title = CatalogClient.parseMeta(rows, "series", "tt0096697")
        assertEquals(1, title.episodes.size)
        try { CatalogClient.parseMeta(rows, "series", "tt0000000"); fail("Wrong metadata accepted") } catch (_: UserError) { }
        assertEquals("", CatalogClient.artwork("https://untrusted.example/poster.jpg"))
    }
    @Test fun zileanRequiresRowImdbEvidenceAndEpisodeFit() {
        val title = Title("tt0096697", "series", "The Simpsons", year = "1989–2026")
        val target = Target(title, Episode(3, 4, "Episode 4"))
        val row = JSONObject().put("imdb_id", title.id).put("info_hash", hash).put("raw_title", "The.Simpsons.S03E04.1080p.x264")
            .put("seasons", JSONArray().put(3)).put("episodes", JSONArray().put(4))
        assertEquals(1, SourceClient.parseZilean(JSONArray().put(row), target).size)
        assertTrue(SourceClient.parseZilean(JSONArray().put(JSONObject(row.toString()).put("imdb_id", "tt0000000")), target).isEmpty())
        assertTrue(SourceClient.parseZilean(JSONArray().put(JSONObject(row.toString()).put("episodes", JSONArray().put(5))), target).isEmpty())
        assertTrue(SourceClient.parseZilean(JSONArray().put(JSONObject(row.toString()).put("raw_title", "The.Simpsons.S03E05.1080p")), target).isEmpty())
    }
    @Test fun publicProvidersCannotReceiveKey() = runBlocking {
        val http = transport({ 200 to "{}" })
        try { http.json("https://v3-cinemeta.strem.io/meta/movie/tt0113497.json".toHttpUrl(), key); fail("Public credential accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun catalogRedirectsAreBoundedAndCannotForwardCredentials() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val http = transport({ r -> if (r.url.host == "v3-cinemeta.strem.io") 302 to "https://cinemeta-catalogs.strem.io/top/catalog/movie/top.json" else 200 to "{\"metas\":[]}" }, requests)
        assertTrue(http.json("https://v3-cinemeta.strem.io/catalog/movie/top.json".toHttpUrl()) is JSONObject)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Authorization") == null })
        val blocked = Collections.synchronizedList(mutableListOf<Request>())
        val malicious = transport({ 302 to "https://untrusted.example/catalog" }, blocked)
        try { malicious.json("https://v3-cinemeta.strem.io/catalog/movie/top.json".toHttpUrl()); fail("Untrusted redirect followed") } catch (_: UserError) { }
        assertEquals(1, blocked.size)
        val loops = Collections.synchronizedList(mutableListOf<Request>())
        val loop = transport({ 302 to "https://v3-cinemeta.strem.io/catalog/movie/top.json" }, loops)
        try { loop.json("https://v3-cinemeta.strem.io/catalog/movie/top.json".toHttpUrl()); fail("Redirect loop accepted") } catch (_: UserError) { }
        assertEquals(3, loops.size)
        val apiRequests = Collections.synchronizedList(mutableListOf<Request>())
        val api = transport({ 302 to "https://cdn.torbox.app/secret" }, apiRequests)
        try { api.json("https://api.torbox.app/v1/api/user/me".toHttpUrl(), key); fail("Authenticated redirect followed") } catch (_: UserError) { }
        assertEquals(1, apiRequests.size)
    }
    @Test fun responseBytesAreStrictlyBounded() = runBlocking {
        val http = transport({ 200 to "{\"large\":\"${"x".repeat(100)}\"}" })
        try { http.json("https://v3-cinemeta.strem.io/catalog/movie/top.json".toHttpUrl(), maxBytes = 20); fail("Oversize response accepted") }
        catch (e: UserError) { assertTrue(e.message!!.contains("too large")) }
    }
    @Test fun automaticallyAddsCachedOnlyAndRechecksFilesBeforeLink() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val http = transport({ r -> when (r.url.pathSegments.last()) {
            "checkcached" -> 200 to envelope(JSONObject().put(hash, JSONObject().put("files", JSONArray())))
            "mylist" -> 200 to envelope(if (r.url.queryParameter("id") != null) item() else JSONArray())
            "createtorrent" -> 200 to envelope(JSONObject().put("torrent_id", 9))
            "requestdl" -> 200 to envelope("https://n1.tb-cdn.io/file?token=$key")
            else -> 500 to "{}"
        } }, requests)
        val client = TorBoxClient({ key }, MemoryOperationLedger(), http)
        val chosen = client.choices(movie, listOf(source)).single()
        val posted = requests.single { it.url.pathSegments.last() == "createtorrent" }
        assertEquals("true", formField(posted, "add_only_if_cached"))
        assertEquals(77, chosen.file.id)
        assertTrue(client.link(chosen, movie).startsWith("https://n1.tb-cdn.io/"))
        val linkRequest = requests.single { it.url.pathSegments.last() == "requestdl" }
        assertEquals("77", linkRequest.url.queryParameter("file_id"))
        assertEquals(key, linkRequest.url.queryParameter("token"))
        assertNull(linkRequest.header("Authorization"))
        assertEquals(2, requests.count { it.url.pathSegments.last() == "mylist" && it.url.queryParameter("id") == "9" })
    }
    @Test fun cacheMissNeverAddsOrDownloads() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val http = transport({ r -> 200 to envelope(if (r.url.pathSegments.last() == "checkcached") JSONObject().put(hash, false) else JSONArray()) }, requests)
        val client = TorBoxClient({ key }, MemoryOperationLedger(), http)
        assertTrue(client.choices(movie, listOf(source)).isEmpty())
        assertFalse(requests.any { it.method == "POST" })
    }
    @Test fun explicitPreparationOnlyAndInterruptedPostDoesNotRepeat() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val ledger = MemoryOperationLedger()
        val http = transport({ r -> if (r.method == "POST") 503 to "{}" else 200 to envelope(JSONArray()) }, requests)
        val first = TorBoxClient({ key }, ledger, http)
        try { first.prepare(movie, source); fail("503 accepted") } catch (_: UserError) { }
        val second = TorBoxClient({ key }, ledger, http)
        try { second.prepare(movie, source); fail("Repeated uncertain create") } catch (e: UserError) { assertTrue(e.message!!.contains("unconfirmed")) }
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals("false", formField(requests.single { it.method == "POST" }, "add_only_if_cached"))
        assertNull(second.status(movie, source))
        assertEquals(1, requests.count { it.method == "POST" })
    }
    @Test fun missingCredentialsHaveNoNetworkSideEffects() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = TorBoxClient({ null }, MemoryOperationLedger(), transport({ 200 to "{}" }, requests))
        try { client.verify(); fail("Missing key accepted") } catch (e: UserError) { assertTrue(e.message!!.contains("API key")) }
        assertTrue(requests.isEmpty())
    }
    @Test fun browsingIsIndependentOfTorBoxAndOutageIsNotEmptySearch() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val catalog = CatalogClient(transport({ r -> if (r.url.encodedPath.contains("/catalog/")) 200 to JSONObject().put("metas", JSONArray().put(JSONObject().put("id", movie.title.id).put("type", "movie").put("name", "Jumanji"))).toString() else 503 to "{}" }, requests))
        assertEquals("Jumanji", catalog.browse("movie").single().name)
        assertTrue(requests.none { it.url.host == "api.torbox.app" })
        val unavailable = CatalogClient(transport({ 503 to "{}" }))
        try { unavailable.search("Jumanji"); fail("Outage hidden as empty search") } catch (e: UserError) { assertTrue(e.message!!.contains("unavailable")) }
        val empty = CatalogClient(transport({ 200 to "{\"metas\":[],\"d\":[]}" }))
        assertTrue(empty.search("nothing there").isEmpty())
    }
}
