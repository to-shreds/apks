package app.torboxtv.data

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SportsSource(val type: String, val label: String, val url: String = "", val videoId: String = "", val streamIndex: Int = 0)
data class SportsItem(val id: String, val title: String, val kind: String, val sport: String, val date: String, val sources: List<SportsSource>, val provider: String = "")

/** Only catalog fields enter browsing. Provider HTML, scores and artwork never do. */
object SportsBrowse {
    private val names = mapOf("football" to "American football", "soccer" to "Soccer", "mixed" to "Various sports", "combat" to "Combat sports", "motorsport" to "Motorsport", "motorsports" to "Motorsport", "action" to "Action sports", "winter" to "Winter sports", "wintersports" to "Winter sports", "water" to "Water sports", "watersports" to "Water sports", "college" to "College sports")
    fun sportName(value: String): String = names[value] ?: value.replace('-', ' ').replaceFirstChar { it.uppercase() }
    fun provider(item: SportsItem): String = item.provider.ifBlank {
        when(item.sources.firstOrNull()?.type) {
            "redbull" -> "Red Bull TV"
            "buffstreams" -> "Buffstreams"
            else -> item.sources.firstOrNull()?.label.orEmpty()
        }
    }
    fun sports(items: List<SportsItem>): List<String> = items.map { it.sport }.distinct().sortedBy { sportName(it) }
    fun providers(items: List<SportsItem>): List<String> = items.map { provider(it) }.filter { it.isNotBlank() }.distinct().sorted()
    fun select(items: List<SportsItem>, mode: String, sport: String = "all", providerName: String = "all", query: String = ""): List<SportsItem> {
        val words = query.take(80).trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return items.filter { item ->
            item.kind == mode && (sport == "all" || item.sport == sport) && (providerName == "all" || provider(item) == providerName) &&
                words.all { word -> (item.title + " " + sportName(item.sport) + " " + provider(item)).lowercase(java.util.Locale.ROOT).contains(word) }
        }
    }
}

/** Public sports networking has no account key, cookie jar, browser or app backend. */
object SportsPolicy {
    private val hosts = setOf(
        "aegis-cloudfront-1.tubi.video",
        "amg01074-fueltv-amg01074c1-firetv-us-3150.playouts.now.amagi.tv",
        "amg01074-fueltv-fueltvemeaen-rakuten-b6j62.amagi.tv",
        "amg01444-amg01444c3-lg-us-3412.playouts.now.amagi.tv",
        "amg01444-tennischannel-amg01444c2-lg-us-959.playouts.now.amagi.tv",
        "amg01444-tennischannelth-tennischannelnl-samsungnl-x3dq1.amagi.tv",
        "amg01874-nhl-amg01874c1-lg-us-5458.playouts.now.amagi.tv",
        "amg01935-amg01935c1-amgplt0165.playout.now3.amagi.tv",
        "amg02278-amg02278c1-flosports-worldwide-7592.playouts.now.amagi.tv",
        "amg02278-amg02278c2-flosports-worldwide-9916.playouts.now.amagi.tv",
        "amg12058-c15studio-amg12058c1-lg-us-5787.playouts.now.amagi.tv",
        "bein-esp-xumo.amagi.tv",
        "chatgpt.hereisman.net",
        "d11k1mnrgfposz.cloudfront.net",
        "d1e354daam8g5r.cloudfront.net",
        "d1jzu95oc8fgt3.cloudfront.net",
        "d1m1xk35ma8qfl.cloudfront.net",
        "d1t5afz6qed3xk.cloudfront.net",
        "d265y4sk8257lt.cloudfront.net",
        "d2a31kzot4f8t3.cloudfront.net",
        "d2icntm6fhxrry.cloudfront.net",
        "d2w9b8sam7j3zv.cloudfront.net",
        "d2w9q46ikgrcwx.cloudfront.net",
        "d36r8jifhgsk5j.cloudfront.net",
        "d39accvx65hq9o.cloudfront.net",
        "d3ialx0k0mla2a.cloudfront.net",
        "d7vulbd7rxo7j.cloudfront.net",
        "d9ssxzmclhfo4.cloudfront.net",
        "dc1644a9jazgj.cloudfront.net",
        "dgwfm675921lp.cloudfront.net",
        "dnf08l6u6uxnz.cloudfront.net",
        "dpltey7dr5q2g.cloudfront.net",
        "dt1kh32hg3tft.cloudfront.net",
        "dzz957bqk62e8.cloudfront.net",
        "linear-600.frequency.stream",
        "linear-893.frequency.stream",
        "mvg-mv-xumo.otteravision.com",
        "pac12-firetv.amagi.tv",
        "pb-2y9ox4r1fy550.akamaized.net",
        "pb-5pdyic0cu7tri.akamaized.net",
        "pb-783hpus5r91wv.akamaized.net",
        "pb-ljudrmfw5j68n.akamaized.net",
        "pb-we3ltka9xobj6.akamaized.net",
        "pb-wm04vonaerv0k.akamaized.net",
        "play.redbull.com",
        "rbmn-live.akamaized.net",
        "red.redirector1.space",
        "sportsgrid-samsungus.amagi.tv",
        "xumo-xumoent-vc-122-sjv70.fast.nbcuni.com"
    )
    private val publicGameSegment = Regex("[a-z0-9]{1,60}\\.(?:270b29c3f31524af69f0d729a4646332|1953611ea5fddadb06c2c04ab7719218)\\.us\\.r2\\.cloudflarestorage\\.com")
    val videoId = Regex("rrn:content:live-videos:[a-f0-9-]{36}:en-INT")
    fun validMedia(value: String): Boolean = try {
        val u = value.toHttpUrl()
        value.length <= 16000 && u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && (u.host in hosts || publicGameSegment.matches(u.host))
    } catch (_: Exception) { false }
    fun resume(position: Long, duration: Long): Long = if (position >= 10000 && duration > 0 && position < duration - 30000) (position - 5000).coerceAtLeast(0) else 0
    fun parse(raw: String): List<SportsItem> {
        val rows = JSONObject(raw).getJSONArray("items")
        val seen = mutableSetOf<String>()
        return (0 until minOf(rows.length(),600)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (!Regex("[a-z0-9-]{1,100}").matches(id) || id in seen || row.optString("kind") !in listOf("live","replay") || !Regex("[a-z][a-z0-9-]{0,30}").matches(row.optString("sport"))) return@mapNotNull null
            val src = row.optJSONArray("sources") ?: return@mapNotNull null
            val sources = (0 until minOf(src.length(),4)).mapNotNull { n ->
                val s = src.optJSONObject(n) ?: return@mapNotNull null
                when {
                    s.optString("type") == "hls" && validMedia(s.optString("url")) -> SportsSource("hls", clean(s.optString("label"),70),s.optString("url"))
                    s.optString("type") == "redbull" && videoId.matches(s.optString("videoId")) -> SportsSource("redbull","Red Bull TV",videoId=s.optString("videoId"))
                    s.optString("type") == "buffstreams" && row.optString("kind")=="live" && BuffStreamsPolicy.validPage(s.optString("url")) -> SportsSource("buffstreams","Buffstreams",s.optString("url"))
                    else -> null
                }
            }
            if (sources.isEmpty()) return@mapNotNull null
            seen.add(id)
            // Never retain provider descriptions, pictures, scores or results.
            SportsItem(id,clean(row.optString("title","Sports"),140),row.optString("kind"),row.optString("sport"),row.optString("date").takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }.orEmpty(),sources,clean(row.optString("provider"),60))
        }
    }
}

/** Deliberately contains no URL, signed query, credentials or provider response body. */
class SportsDestinationRejected : IOException("This sports provider changed its playback address.")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object SportsPlaybackFailure {
    internal fun httpMessage(status: Int): String = when (status) {
        401, 403 -> "The provider refused access to this source."
        404, 410 -> "This source is no longer available."
        else -> "The provider could not serve this source."
    }
    fun message(error: PlaybackException): String = message(error,error.errorCode)
    internal fun message(error: Throwable, errorCode: Int): String {
        val causes = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (causes.size < 16) {
            val current = next ?: break
            if (causes.any { it === current }) break
            causes.add(current)
            next = current.cause
        }
        if (causes.any { it is SportsDestinationRejected })
            return "The sports provider changed its stream address. Check for an app update or choose another source."
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let { return httpMessage(it.responseCode) }
        if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            causes.any { it is SocketTimeoutException || it is InterruptedIOException })
            return "The source took too long to respond."
        if (causes.any { it is SportsManifestRejected } || errorCode in setOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
            return "This source has an unreadable or unsupported video format."
        return "This source could not be played. Try another source or retry."
    }
}

class SportsMediaGuard(private val playbackScope: BuffStreamsPlaybackScope? = null) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val r = chain.request()
        val allowed = playbackScope?.allows(r.url.toString()) ?: SportsPolicy.validMedia(r.url.toString())
        val refererAllowed = playbackScope?.validReferer(r.header("Referer")) ?: (r.header("Referer") == null || BuffStreamsPolicy.validEmbed(r.header("Referer")!!))
        if (!allowed || !refererAllowed || r.header("Authorization") != null || r.header("Cookie") != null) throw SportsDestinationRejected()
        val response=chain.proceed(r)
        if (playbackScope == null || r.url.toString() != playbackScope.master || !response.isSuccessful) return response
        try {
            val body=response.body ?: throw SportsManifestRejected()
            if (body.contentLength() > 256*1024) throw SportsManifestRejected()
            val encoding=response.header("Content-Encoding").orEmpty()
            if (encoding.isNotEmpty() && !encoding.equals("identity",true) && !encoding.equals("gzip",true)) throw SportsManifestRejected()
            val bytes=java.io.ByteArrayOutputStream()
            val source=if(encoding.equals("gzip",true)) java.util.zip.GZIPInputStream(body.byteStream()) else body.byteStream()
            source.use { input ->
                val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break;if(bytes.size()+count>256*1024)throw SportsManifestRejected();bytes.write(buffer,0,count) }
            }
            val data=bytes.toByteArray()
            playbackScope.acceptMaster(String(data,Charsets.UTF_8))
            return response.newBuilder().removeHeader("Content-Encoding").removeHeader("Content-Length").body(data.toResponseBody(body.contentType())).build()
        } catch (error: Exception) { response.close();throw error }
    }
}

class SportsClient(private val api: OkHttpClient = OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
        .callTimeout(12,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    fun catalog(context: Context): List<SportsItem> = context.assets.open("sports-catalog.json").bufferedReader().use { SportsPolicy.parse(it.readText()) } + BuffStreams.cached(context)
    suspend fun prepare(source: SportsSource): SportsStream = if(source.type=="buffstreams")BuffStreams().prepare(source) else SportsStream(resolve(source))
    suspend fun resolve(source: SportsSource): String {
        currentCoroutineContext().ensureActive()
        if (source.type == "hls") {
            if (!SportsPolicy.validMedia(source.url)) throw IOException("Unverified sports source.")
            return source.url
        }
        if (source.type != "redbull" || !SportsPolicy.videoId.matches(source.videoId)) throw IOException("Unverified replay.")
        val url = "https://api-player.redbull.com/rbcom/videoresource".toHttpUrl().newBuilder().addQueryParameter("videoId",source.videoId).build()
        return suspendCancellableCoroutine { continuation ->
            val call = api.newCall(Request.Builder().url(url).header("Accept","application/json").build())
            // Closing or replacing a replay stops transport, including a stalled response body.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("This replay could not be reached. Try again or choose another event."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val stream = replayUrl(response)
                            if (continuation.isActive) continuation.resume(stream)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
    }
    private fun replayUrl(response: Response): String {
        if (!response.isSuccessful) throw IOException("This replay is currently unavailable. Try another event.")
        val body = response.body ?: throw IOException("Empty replay response.")
        if (body.contentLength() > 1024*1024) throw IOException("Unreadable replay response.")
        val output = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024*1024) throw IOException("Unreadable replay response.")
                output.write(buffer,0,count)
            }
        }
        val data = JSONObject(String(output.toByteArray(),Charsets.UTF_8))
        if ((data.optJSONArray("playabilityErrors")?.length() ?: 0) > 0 || !SportsPolicy.validMedia(data.optString("videoUrl"))) throw IOException("This replay is unavailable in this location or has expired. Try another event.")
        return data.getString("videoUrl")
    }
}
