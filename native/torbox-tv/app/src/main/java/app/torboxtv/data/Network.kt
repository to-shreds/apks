package app.torboxtv.data

import android.content.Context
import app.torboxtv.core.*
import app.torboxtv.core.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No browser/backend dependencies: keys go only to the TorBox API. JSON calls never follow redirects. */
internal class HttpFailure(val status: Int, val code: String = "") : Exception("Request failed")
private class JsonRedirect(val destination: HttpUrl) : Exception()

class JsonHttp internal constructor(private val client: OkHttpClient = defaultClient()) {
    companion object {
        private val hosts = setOf("v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io", "cinemeta-live.strem.io",
            "v3.sg.media-imdb.com", "zileanfortheweebs.midnightignite.me", "torrentio.strem.fun",
            "stremthru.13377001.xyz", "stremthru.elfhosted.com", "api.torbox.app")
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
    suspend fun json(url: HttpUrl, key: String? = null, body: RequestBody? = null, maxBytes: Int = 4 * 1024 * 1024): Any {
        return withTimeout(20000) {
            var destination = url
            val catalogHosts = setOf("v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io", "cinemeta-live.strem.io")
            for (hop in 0..2) {
                try { return@withTimeout exchange(destination, key, body, maxBytes) }
                catch (redirect: JsonRedirect) {
                    if (hop == 2 || key != null || body != null || url.host !in catalogHosts || redirect.destination.host !in catalogHosts || !redirect.destination.isHttps || redirect.destination.username.isNotEmpty() || redirect.destination.password.isNotEmpty() || redirect.destination.port != 443)
                        throw UserError("The service redirected to an unverified destination.")
                    destination = redirect.destination
                }
            }
            throw UserError("The service redirected too many times.")
        }
    }
    private suspend fun exchange(url: HttpUrl, key: String?, body: RequestBody?, maxBytes: Int): Any {
        require(url.isHttps && url.host in hosts && url.username.isEmpty() && url.password.isEmpty() && url.port == 443)
        // Enforce separation even when a caller accidentally supplies authentication to public providers.
        require(key == null || url.host == "api.torbox.app")
        val request = Request.Builder().url(url).header("Accept", "application/json")
        key?.let { request.header("Authorization", "Bearer $it") }
        if (body != null) request.post(body)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(UserError("The service could not be reached. Check your connection and try again."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (response.code in listOf(301, 302, 303, 307, 308)) {
                                val location = response.header("Location")?.let { url.resolve(it) } ?: throw UserError("The service returned an incomplete redirect.")
                                throw JsonRedirect(location)
                            }
                            val stream = response.body ?: throw UserError("The service returned an empty response.")
                            if (stream.contentLength() > maxBytes) throw UserError("The service response was too large.")
                            val bytes = ByteArrayOutputStream()
                            stream.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > maxBytes) throw UserError("The service response was too large.")
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            val value = try { JSONTokener(bytes.toString("UTF-8")).nextValue() } catch (_: Exception) { null }
                            if (!response.isSuccessful) throw HttpFailure(response.code, (value as? JSONObject)?.optString("error").orEmpty())
                            if (value !is JSONObject && value !is JSONArray) throw UserError("The service returned an unreadable response.")
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(if (e is UserError || e is HttpFailure || e is JsonRedirect) e else UserError("The service response could not be read."))
                        }
                    }
                }
            })
        }
    }
}

/** Redirect requests are checked before every network exchange when installed as a NETWORK interceptor. */
class MediaHostGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!valid(chain.request().url.toString())) throw IOException("Unverified playback destination")
        if (chain.request().header("Authorization") != null) throw IOException("Unexpected media authorization")
        return chain.proceed(chain.request())
    }
    companion object {
        private val suffixes = setOf("torbox.app", "tb-cdn.cx", "tb-cdn.io", "tb-cdn.pw", "tb-cdn.sh", "tb-cdn.st", "tb-cdn.to", "tb-cdn.earth")
        fun valid(value: String): Boolean = try {
            val u = value.toHttpUrl()
            value.length <= 16000 && u.isHttps && u.username.isEmpty() && u.password.isEmpty() && u.port == 443 &&
                u.host != "api.torbox.app" && suffixes.any { u.host == it || u.host.endsWith(".$it") }
        } catch (_: Exception) { false }
    }
}

internal fun clean(value: String, max: Int = 700) = value.replace(Regex("[\\u0000-\\u001f\\u007f]"), " ").take(max).trim()
internal fun arrayObjects(value: JSONArray, max: Int = 2000): List<JSONObject> = (0 until minOf(value.length(), max)).mapNotNull { value.optJSONObject(it) }
internal fun integer(row: JSONObject, key: String): Int? = (row.opt(key) as? Number)?.let { value ->
    val n = value.toLong()
    if (n in 0..Int.MAX_VALUE && n.toDouble() == value.toDouble()) n.toInt() else null
}
internal fun validTarget(target: Target) {
    if (!Regex("tt[0-9]{5,12}").matches(target.title.id) || target.title.type !in listOf("movie", "series") ||
        (target.title.type == "series" && (target.episode == null || target.episode.season !in 0..999 || target.episode.number !in 1..9999)))
        throw UserError("Choose a valid movie or episode.")
}
internal fun height(name: String): Int = Regex("(?i)\\b(2160|1080|720|480)p\\b").find(name)?.groupValues?.get(1)?.toIntOrNull()
    ?: if (Regex("(?i)\\b4k\\b").containsMatchIn(name)) 2160 else 0

private suspend fun <T> firstSuccess(tasks: List<suspend () -> T>): T = supervisorScope {
    val channel = Channel<Result<T>>(tasks.size)
    val jobs = tasks.map { task -> launch { try { channel.send(Result.success(task())) } catch (e: CancellationException) { throw e } catch (e: Exception) { channel.send(Result.failure(e)) } } }
    try {
        var failure: Throwable? = null
        repeat(tasks.size) {
            val result = channel.receive()
            if (result.isSuccess) return@supervisorScope result.getOrThrow()
            failure = result.exceptionOrNull()
        }
        throw failure ?: UserError("The service is unavailable. Try again shortly.")
    } finally { jobs.forEach { it.cancel() } }
}

class CatalogClient(private val http: JsonHttp = JsonHttp()) {
    private val cache = object : LinkedHashMap<String, Pair<Long, Any>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Any>>) = size > 80
    }
    private suspend fun <T : Any> remember(key: String, load: suspend () -> T): T {
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.let { @Suppress("UNCHECKED_CAST") return it.second as T } }
        val value = load()
        synchronized(cache) { cache[key] = System.currentTimeMillis() to value }
        return value
    }
    suspend fun browse(type: String, genre: String? = null): List<Title> {
        if (type !in listOf("movie", "series") || (genre?.length ?: 0) > 40) throw UserError("Choose Movies or Shows.")
        return remember("browse:$type:$genre") {
            withTimeout(20000) {
                firstSuccess(listOf("top", "imdbRating").map { feed -> suspend {
                    val extra = if (genre.isNullOrBlank()) "" else "/genre=${java.net.URLEncoder.encode(genre, "UTF-8").replace("+", "%20") }"
                    val url = "https://v3-cinemeta.strem.io/catalog/$type/$feed$extra.json".toHttpUrl()
                    parseList(http.json(url) as? JSONObject ?: throw UserError("The catalog returned an unreadable list."), type)
                } })
            }
        }
    }
    suspend fun search(query: String): List<Title> {
        val q = clean(query, 150)
        if (q.length < 2) return emptyList()
        return remember("search:${q.lowercase()}") {
            withTimeout(22000) {
                supervisorScope {
                    val results = listOf("movie", "series").map { type -> async { try { searchType(type, q) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } }.awaitAll()
                    if (results.all { it == null }) throw UserError("Search services are unavailable. Please try again shortly.")
                    results.filterNotNull().flatten().distinctBy { it.id }.sortedByDescending { it.name.equals(q, true) }
                }
            }
        }
    }
    private suspend fun searchType(type: String, q: String): List<Title> {
        if (Regex("tt[0-9]{5,12}").matches(q)) return listOf(details(Title(q, type, q)))
        val encoded = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        return firstNonEmpty(listOf(
            suspend { parseList(http.json("https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json".toHttpUrl()) as JSONObject, type).filter { searchRelevant(it.name, q) } },
            suspend { parseList(http.json("https://cinemeta-catalogs.strem.io/top/catalog/$type/top/search=$encoded.json".toHttpUrl()) as JSONObject, type).filter { searchRelevant(it.name, q) } },
            suspend {
                val data = http.json("https://v3.sg.media-imdb.com/suggestion/x/$encoded.json".toHttpUrl(), maxBytes = 1024 * 1024) as JSONObject
                arrayObjects(data.optJSONArray("d") ?: JSONArray(), 40).mapNotNull { row ->
                    val kind = row.optString("qid").lowercase()
                    val matches = if (type == "series") kind in listOf("tvseries", "tvminiseries") else kind in listOf("movie", "tvmovie", "video")
                    val id = row.optString("id")
                    if (!matches || !Regex("tt[0-9]{5,12}").matches(id) || !searchRelevant(row.optString("l"), q)) null else Title(id, type, clean(row.optString("l"), 250), year = row.optString("y"), poster = artwork(row.optJSONObject("i")?.optString("imageUrl").orEmpty()))
                }.take(20)
            }
        ))
    }
    private suspend fun firstNonEmpty(tasks: List<suspend () -> List<Title>>): List<Title> = supervisorScope {
        val channel = Channel<Result<List<Title>>>(tasks.size)
        val jobs = tasks.map { task -> launch { try { channel.send(Result.success(task())) } catch (e: CancellationException) { throw e } catch (e: Exception) { channel.send(Result.failure(e)) } } }
        try {
            var anySuccess = false
            repeat(tasks.size) {
                val result = channel.receive()
                if (result.isSuccess) { anySuccess = true; val rows = result.getOrThrow(); if (rows.isNotEmpty()) return@supervisorScope rows }
            }
            if (anySuccess) emptyList() else throw UserError("Search services are unavailable.")
        } finally { jobs.forEach { it.cancel() } }
    }
    suspend fun details(title: Title): Title = remember("meta:${title.type}:${title.id}") {
        if (title.type !in listOf("movie", "series") || !Regex("tt[0-9]{5,12}").matches(title.id)) throw UserError("This title could not be identified.")
        val data = http.json("https://v3-cinemeta.strem.io/meta/${title.type}/${title.id}.json".toHttpUrl()) as? JSONObject
        parseMeta(data?.optJSONObject("meta") ?: throw UserError("Title details are unavailable."), title.type, title.id)
    }
    companion object {
        internal fun searchRelevant(name: String, query: String): Boolean {
            fun norm(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
            val title = norm(name); val needle = norm(query)
            if (title.isEmpty() || needle.isEmpty()) return false
            if (title == needle || title.contains(needle) || needle.contains(title)) return true
            val stopWords = setOf("a", "an", "and", "at", "by", "for", "from", "in", "of", "on", "or", "the", "to", "with")
            val wanted = needle.split(' ').filter { it.length >= 2 && it !in stopWords }
            if (wanted.isEmpty()) return false
            val have = title.split(' ')
            val matched = wanted.count { token -> have.any { word -> word == token || token.length >= 3 && word.startsWith(token) || word.length >= 3 && token.startsWith(word) } }
            return matched >= maxOf(1, (wanted.size + 1) / 2)
        }
        fun artwork(value: String): String = try {
            val u = value.toHttpUrl()
            if (ArtworkUrls.valid(u.toString())) u.toString() else ""
        } catch (_: Exception) { "" }
        internal fun parseList(data: JSONObject, type: String): List<Title> {
            val rows = data.optJSONArray("metas") ?: throw UserError("The catalog returned no title list.")
            return arrayObjects(rows, 200).mapNotNull { row -> try { parseMeta(row, type, row.optString("id", row.optString("imdb_id"))) } catch (_: Exception) { null } }.distinctBy { it.id }.take(100)
        }
        internal fun parseMeta(row: JSONObject, type: String, id: String): Title {
            if (!Regex("tt[0-9]{5,12}").matches(id) || row.optString("id", row.optString("imdb_id")) != id || (row.has("type") && row.optString("type") != type) || row.optString("name").isBlank()) throw UserError("The catalog returned a different title.")
            val episodes = if (type != "series") emptyList() else arrayObjects(row.optJSONArray("videos") ?: JSONArray(), 12000).mapNotNull { ep ->
                val season = integer(ep, "season") ?: -1; val number = integer(ep, "episode") ?: integer(ep, "number") ?: -1
                if (season !in 0..999 || number !in 1..9999 || (ep.has("id") && ep.optString("id") != "$id:$season:$number")) null
                else Episode(season, number, clean(ep.optString("name", ep.optString("title", "Episode $number")), 250), dateMs(ep.optString("released", ep.optString("firstAired"))))
            }.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
            val genres = row.optJSONArray("genres") ?: JSONArray()
            return Title(id, type, clean(row.optString("name"), 250), clean(row.optString("description"), 4000), clean(row.optString("releaseInfo", row.optString("year")), 30), artwork(row.optString("poster")), artwork(row.optString("background")), (0 until minOf(genres.length(), 8)).mapNotNull { genres.opt(it) as? String }.map { clean(it, 40) }, episodes)
        }
        private fun dateMs(value: String): Long? {
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")) try { return SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(value)?.time } catch (_: Exception) { }
            return null
        }
    }
}

class SourceClient(
    private val http: JsonHttp = JsonHttp(),
    private val providerTasks: ((Target) -> List<suspend () -> List<Source>?>)? = null
) {
    private data class Entry(val savedMs: Long, val sources: List<Source>, val complete: Boolean)
    private val cache = LinkedHashMap<String, Entry>()
    private val discoveries = LinkedHashMap<String, Any>()

    fun invalidate(target: Target) { synchronized(cache) { cache.remove(target.key); discoveries.remove(target.key) } }

    /** A true callback stops discovery once the caller has a verified playable choice. */
    suspend fun find(target: Target, onBatch: suspend (List<Source>) -> Boolean = { false }): List<Source> {
        validTarget(target)
        // Acquire ownership before any verification callback can suspend. Keep bookkeeping bounded too.
        val discovery = Any()
        synchronized(cache) {
            if (discoveries.size >= 30 && !discoveries.containsKey(target.key)) discoveries.remove(discoveries.keys.first())
            discoveries[target.key] = discovery
        }
        try { return findOwned(target, onBatch, discovery) }
        finally { synchronized(cache) { if (discoveries[target.key] === discovery) discoveries.remove(target.key) } }
    }

    private suspend fun findOwned(target: Target, onBatch: suspend (List<Source>) -> Boolean, discovery: Any): List<Source> {
        val cached = synchronized(cache) { cache[target.key]?.takeIf { System.currentTimeMillis() - it.savedMs < 300000 } }
        var accumulated = retained(cached?.sources.orEmpty(), target)
        if (accumulated.isNotEmpty() && onBatch(accumulated)) return accumulated
        if (cached?.complete == true && (accumulated.isNotEmpty() || cached.sources.isEmpty())) return accumulated
        val resource = target.key
        val config = "eyJzdG9yZXMiOlt7ImMiOiJwMnAiLCJ0IjoiIn1dfQ=="
        val providers = providerTasks?.invoke(target) ?: listOf<suspend () -> List<Source>?>(
            { safe { val u = "https://zileanfortheweebs.midnightignite.me/dmm/filtered".toHttpUrl().newBuilder().addQueryParameter("ImdbId", target.title.id); target.episode?.let { u.addQueryParameter("Season", it.season.toString()).addQueryParameter("Episode", it.number.toString()) }; parseZilean(http.json(u.build()) as? JSONArray ?: JSONArray(), target) } },
            { safe { parseStreams(http.json("https://torrentio.strem.fun/sort=qualityseed/stream/${target.title.type}/$resource.json".toHttpUrl()) as JSONObject, target, "Torrentio") } },
            { safe { parseStreams(http.json("https://stremthru.13377001.xyz/stremio/torz/$config/stream/${target.title.type}/$resource.json".toHttpUrl()) as JSONObject, target, "StremThru") } },
            { safe { parseStreams(http.json("https://stremthru.elfhosted.com/stremio/torz/$config/stream/${target.title.type}/$resource.json".toHttpUrl()) as JSONObject, target, "StremThru backup") } }
        )
        return supervisorScope {
            val responses = Channel<List<Source>?>(providers.size.coerceAtLeast(1))
            val jobs = providers.map { provider -> launch { responses.send(safe { withTimeout(16000) { provider() } }) } }
            var successes = 0
            try {
                repeat(providers.size) {
                    val rows = responses.receive()
                    if (rows != null) {
                        successes++
                        accumulated = retained(accumulated + rows, target)
                        remember(target, accumulated, false, discovery)
                        if (accumulated.isNotEmpty() && onBatch(accumulated)) return@supervisorScope accumulated
                    }
                }
                if (successes == 0) throw UserError("Source services are unavailable. Please try again shortly. No download was started.")
                // A failed provider may recover on the next visit; keep its successful peers as a partial cache.
                remember(target, accumulated, successes == providers.size, discovery)
                accumulated
            } finally {
                jobs.forEach { it.cancel() }
                responses.close()
            }
        }
    }

    private fun retained(values: List<Source>, target: Target): List<Source> {
        val ranked = Policies.rankSources(values.filter { Policies.sourceIdentity(it, target) }, Settings(maxHeight = 2160))
        // Preserve each supported quality while collecting completed provider responses.
        return listOf(1080 to 14, 720 to 10, 2160 to 10, 480 to 3, 0 to 3).flatMap { (quality, limit) -> ranked.filter { it.height == quality }.take(limit) }
    }
    private fun remember(target: Target, sources: List<Source>, complete: Boolean, discovery: Any) = synchronized(cache) {
        if (discoveries[target.key] !== discovery) return@synchronized
        // A cancelled partial refresh must not replace a previously complete result.
        val old = cache[target.key]
        if (old?.complete == true && System.currentTimeMillis() - old.savedMs < 300000 && !complete) return@synchronized
        if (cache.size >= 30 && !cache.containsKey(target.key)) cache.remove(cache.keys.first())
        cache[target.key] = Entry(System.currentTimeMillis(), sources, complete)
    }
    private suspend fun safe(block: suspend () -> List<Source>?): List<Source>? = try { block() }
        catch (_: TimeoutCancellationException) { null }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    companion object {
        /** Public metadata only. No account identifiers, keys or signed URLs enter this cache. */
        val shared by lazy { SourceClient() }
        internal fun parseZilean(rows: JSONArray, target: Target): List<Source> = arrayObjects(rows).mapNotNull { row ->
            if (row.optString("imdb_id") != target.title.id) return@mapNotNull null
            target.episode?.let { ep ->
                for ((field, expected) in listOf("seasons" to ep.season, "episodes" to ep.number)) {
                    val a = row.optJSONArray(field)
                    if (a != null && a.length() > 0 && (0 until a.length()).none { a.optInt(it, -1) == expected }) return@mapNotNull null
                }
            }
            source(row.optString("info_hash"), clean(row.optString("raw_title"), 450), "Zilean", row.optLong("size", 0), height(row.optString("resolution") + " " + row.optString("raw_title")))?.takeIf { Policies.sourceIdentity(it, target) }
        }
        internal fun parseStreams(data: JSONObject, target: Target, provider: String): List<Source> = arrayObjects(data.optJSONArray("streams") ?: throw UserError("Unreadable source response."), 1500).mapNotNull { row ->
            // An IMDb endpoint is useful evidence, but every returned release/file must still match the selected title.
            val name = clean(row.optJSONObject("behaviorHints")?.optString("filename").orEmpty().ifBlank { row.optString("description", row.optString("title")) }.substringBefore('\n'), 450)
            source(row.optString("infoHash", row.optString("hash")), name, provider, row.optJSONObject("behaviorHints")?.optLong("videoSize", 0) ?: row.optLong("size", 0), height(name + " " + row.optString("name")))?.takeIf { Policies.sourceIdentity(it, target) }
        }
        private fun source(hash: String, name: String, provider: String, size: Long, height: Int): Source? = if (Regex("[a-fA-F0-9]{40}").matches(hash) && name.isNotBlank()) Source(hash.lowercase(), name, provider, size.coerceAtLeast(0), height) else null
    }
}

/** The marker is written BEFORE POST. Ambiguous additions cannot be duplicated, even after process death. */
interface OperationLedger { fun contains(id: String): Boolean; fun mark(id: String); fun claim(id: String): Boolean; fun clear(id: String) }
class MemoryOperationLedger : OperationLedger {
    private val hashes = mutableSetOf<String>()
    override fun contains(id: String) = synchronized(hashes) { id in hashes }
    override fun mark(id: String) { synchronized(hashes) { hashes.add(id) } }
    override fun claim(id: String): Boolean = synchronized(hashes) { hashes.add(id) }
    override fun clear(id: String) { synchronized(hashes) { hashes.remove(id) } }
}
class PersistentOperationLedger(context: Context) : OperationLedger {
    companion object { private val lock = Any() }
    private val prefs = context.applicationContext.getSharedPreferences("torbox_network_operations", Context.MODE_PRIVATE)
    override fun contains(id: String) = synchronized(lock) { prefs.getBoolean(id, false) }
    override fun mark(id: String) { synchronized(lock) { if (!prefs.edit().putBoolean(id, true).commit()) throw UserError("This device could not save preparation state. No download was started.") } }
    override fun claim(id: String): Boolean = synchronized(lock) {
        if (prefs.getBoolean(id, false)) false else { mark(id); true }
    }
    override fun clear(id: String) { synchronized(lock) { prefs.edit().remove(id).commit() } }
}

class TorBoxClient(private val keyProvider: () -> String?, private val ledger: OperationLedger = MemoryOperationLedger(), private val http: JsonHttp = JsonHttp()) {
    private val mutex = Mutex()
    private var cooldownUntil = 0L
    private fun key(): String = keyProvider()?.trim()?.takeIf { it.length in 16..512 && !it.any { c -> c.isWhitespace() || c.code < 33 || c.code > 126 } } ?: throw UserError("Enter your TorBox API key in Settings.")
    private fun marker(hash: String): String = MessageDigest.getInstance("SHA-256").digest((key() + ":" + hash).toByteArray()).joinToString("") { "%02x".format(it) }
    private suspend fun api(path: String, params: List<Pair<String, String>> = emptyList(), body: RequestBody? = null, token: Boolean = false): Any? {
        if (System.currentTimeMillis() < cooldownUntil) throw UserError("TorBox asked this app to slow down. Try again in a minute.")
        val credential = key()
        val url = "https://api.torbox.app/v1/api/$path".toHttpUrl().newBuilder()
        params.forEach { url.addQueryParameter(it.first, it.second) }
        if (token) url.addQueryParameter("token", credential)
        val payload = try { http.json(url.build(), if (token) null else credential, body, 8 * 1024 * 1024) as? JSONObject } catch (e: HttpFailure) {
            if (e.status == 429) cooldownUntil = System.currentTimeMillis() + 60000
            throw e
        } ?: throw UserError("TorBox returned an unreadable response.")
        if (payload.optBoolean("success") != true) throw HttpFailure(200, payload.optString("error"))
        return payload.opt("data")
    }
    suspend fun verify() { try { if (api("user/me", listOf("settings" to "false")) !is JSONObject) throw UserError("TorBox did not confirm the account.") } catch (e: HttpFailure) { throw readable(e) } }
    suspend fun choices(target: Target, sources: List<Source>, cachedOnly: Boolean = true, maxChoices: Int = 3, maxAttempts: Int = 5, onExamined: (Source) -> Unit = {}): List<Choice> = mutex.withLock {
        validTarget(target)
        require(maxChoices in 1..3 && maxAttempts in 1..5)
        val candidates = sources.filter { Policies.sourceIdentity(it, target) }.distinctBy { it.hash }.take(40)
        if (candidates.isEmpty()) return@withLock emptyList()
        try {
            withTimeout(45000) {
                val cache = api("torrents/checkcached", candidates.map { "hash" to it.hash } + listOf("format" to "object", "list_files" to "true")) as? JSONObject ?: throw UserError("TorBox could not confirm cached availability.")
                val ready = candidates.filter { cached(cache.opt(it.hash)) }
                if (ready.isEmpty()) return@withTimeout if (cachedOnly) emptyList()
                    else candidates.take(6).map { Choice(it, -1, VideoFile(-1, "Preparation required"), false) }
                val library = accountItems()
                val result = mutableListOf<Choice>()
                // Create only cached entries, and stop once a useful short alternative list is ready.
                for (source in ready.take(maxAttempts)) {
                    currentCoroutineContext().ensureActive()
                    onExamined(source)
                    try {
                        val item = connect(source, true, library)
                        matchingChoice(item, source, target)?.let { result.add(it) }
                    } catch (e: CancellationException) { throw e } catch (e: HttpFailure) { if (e.status in listOf(401, 403, 429)) throw e } catch (_: UserError) { }
                    if (result.size == maxChoices) break
                }
                if (!cachedOnly) result += candidates.filterNot { cached(cache.opt(it.hash)) }.take(6).map { Choice(it, -1, VideoFile(-1, "Preparation required"), false) }
                result
            }
        } catch (e: HttpFailure) { throw readable(e) }
    }
    /** Explicit user action ONLY. Still-preparing results use cached=false and no playable file ID. */
    suspend fun prepare(target: Target, source: Source): Choice = mutex.withLock {
        validTarget(target)
        if (!Policies.sourceIdentity(source, target)) throw UserError("This source does not match the selected title.")
        try {
            val item = withTimeout(30000) { connect(source, false, accountItems()) }
            val selected = matchingChoice(item, source, target)
            if (selected == null && item.opt("download_finished") == true && item.opt("download_present") == true)
                throw UserError("This version is ready, but its video could not be identified uniquely. Choose another version.")
            selected ?: Choice(source, item.optInt("id", -1), VideoFile(-1, "Preparing in TorBox"), false)
        } catch (e: HttpFailure) { throw readable(e) }
    }
    /** Safe bounded preparation refresh: this method NEVER creates or adds a torrent. */
    suspend fun status(target: Target, source: Source): Choice? = mutex.withLock {
        validTarget(target)
        if (!Policies.sourceIdentity(source, target)) throw UserError("This source does not match the selected title.")
        try {
            withTimeout(20000) {
                val found = accountItems().firstOrNull { it.optString("hash").equals(source.hash, true) } ?: return@withTimeout null
                val fresh = item(integer(found, "id") ?: throw UserError("TorBox returned an invalid torrent identifier."))
                if (!fresh.optString("hash").equals(source.hash, true)) throw UserError("TorBox returned a different torrent. Playback was stopped.")
                ledger.clear(marker(source.hash))
                val selected = matchingChoice(fresh, source, target)
                if (selected == null && fresh.opt("download_finished") == true && fresh.opt("download_present") == true)
                    throw UserError("This version is ready, but its video could not be identified uniquely. Choose another version.")
                selected
            }
        } catch (e: HttpFailure) { throw readable(e) }
    }
    suspend fun link(choice: Choice, target: Target): String {
        validTarget(target)
        if (!choice.cached || choice.torrentId < 0 || choice.file.id < 0 || !Policies.sourceIdentity(choice.source, target)) throw UserError("This version is not ready yet. Check its preparation status.")
        try {
            val fresh = item(choice.torrentId)
            val verified = matchingChoice(fresh, choice.source, target, choice.file.id) ?: throw UserError("This file is no longer ready or could not be matched safely. Choose another version.")
            val link = api("torrents/requestdl", listOf("torrent_id" to verified.torrentId.toString(), "file_id" to verified.file.id.toString(), "zip_link" to "false", "redirect" to "false"), token = true) as? String ?: throw UserError("TorBox did not return a playback link.")
            if (!MediaHostGuard.valid(link)) throw UserError("TorBox returned an unverified playback destination. Playback was stopped.")
            // Owner CDN links can contain the account token. Kept in memory only, never logged or saved.
            return link
        } catch (e: HttpFailure) { throw readable(e) }
    }
    private suspend fun accountItems(): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        for (offset in 0 until 1000 step 100) {
            val page = api("torrents/mylist", listOf("offset" to offset.toString(), "limit" to "100", "bypass_cache" to "true")) as? JSONArray ?: throw UserError("TorBox returned an unreadable account list. Nothing was added.")
            val entries = arrayObjects(page, 100)
            if (entries.size != page.length() || entries.any { integer(it, "id") == null || !Regex("[a-fA-F0-9]{40}").matches(it.optString("hash")) }) throw UserError("TorBox returned incomplete torrent identities. Nothing was added.")
            rows += entries
            if (page.length() < 100) return rows
        }
        throw UserError("This account is too large to safely check for duplicate preparations. Nothing was added.")
    }
    private suspend fun connect(source: Source, onlyCached: Boolean, library: List<JSONObject>): JSONObject {
        val operation = marker(source.hash)
        val existing = library.firstOrNull { it.optString("hash").equals(source.hash, true) }
        if (existing != null) { ledger.clear(operation); return item(existing.optInt("id")) }
        if (!ledger.claim(operation)) throw UserError("An earlier preparation is still unconfirmed. Wait and check again. This app will not add it twice.")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("magnet", "magnet:?xt=urn:btih:${source.hash}")
            .addFormDataPart("allow_zip", "false").addFormDataPart("add_only_if_cached", onlyCached.toString()).build()
        val created = try { api("torrents/createtorrent", body = form) as? JSONObject } catch (e: HttpFailure) {
            // A received definite rejection is safe to retry. Transport interruptions and 5xx remain marked.
            if (e.status < 500) ledger.clear(operation)
            throw e
        }
        val id = created?.let { integer(it, "torrent_id") } ?: -1
        if (id < 0) throw UserError("TorBox's preparation response was incomplete. Wait and check again before retrying.")
        val fresh = item(id)
        if (!fresh.optString("hash").equals(source.hash, true)) throw UserError("TorBox returned a different torrent. Playback was stopped.")
        ledger.clear(operation)
        return fresh
    }
    private suspend fun item(id: Int): JSONObject {
        val data = api("torrents/mylist", listOf("id" to id.toString(), "bypass_cache" to "true"))
        val found = when (data) { is JSONArray -> arrayObjects(data).firstOrNull { it.optInt("id", -1) == id }; is JSONObject -> data.takeIf { it.optInt("id", -1) == id }; else -> null }
        return found ?: throw UserError("The selected torrent is no longer in your TorBox account.")
    }
    companion object {
        internal fun cached(row: Any?): Boolean = row == true || (row is JSONObject && row.length() > 0 && !row.has("cached")) || (row is JSONObject && row.opt("cached") == true)
        internal fun matchingChoice(item: JSONObject, source: Source, target: Target, fileId: Int? = null): Choice? {
            if (!item.optString("hash").equals(source.hash, true) || integer(item, "id") == null) throw UserError("TorBox returned a different torrent. Playback was stopped.")
            if (item.optString("download_state").lowercase(Locale.ROOT) in listOf("error", "failed")) throw UserError("TorBox reported that this version failed. Choose another version.")
            if (item.opt("download_finished") != true || item.opt("download_present") != true) return null
            val files = arrayObjects(item.optJSONArray("files") ?: JSONArray()).mapNotNull { f ->
                val id = integer(f, "id") ?: -1; val name = clean(f.optString("name", f.optString("short_name")))
                if (id < 0 || !Regex("(?i)\\.(mp4|m4v|mkv|webm|mov|ts|m2ts|avi)$").containsMatchIn(name)) null else VideoFile(id, name, f.optLong("size", 0))
            }.filter { Policies.fileMatches(it, target) }
            val selected = if (fileId != null) files.singleOrNull { it.id == fileId } else files.singleOrNull()
            return selected?.let { Choice(source, item.optInt("id"), it) }
        }
        private fun readable(e: HttpFailure): UserError = UserError(when {
            e.status in listOf(401, 403) -> "TorBox rejected this request. Check your API key in Settings."
            e.status == 429 -> "TorBox is limiting requests. Wait a minute, then try again."
            e.code.contains("CACHE", true) -> "This source is no longer cached. Choose another version or explicitly prepare it."
            e.code == "PLAN_RESTRICTED_FEATURE" -> "Your TorBox plan does not permit this action."
            else -> "TorBox could not complete the request. Please try again shortly."
        })
    }
}
