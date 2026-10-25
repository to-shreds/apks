package app.torboxtv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read public listings/player metadata only. Never execute provider JavaScript. */
object BuffStreamsPolicy {
    const val DEFAULT_BASE = "https://mybuffstreams.plus"
    const val EMBED_HOST = "gooz.aapmains.net"
    const val MASTER_HOST = "chatgpt.hereisman.net"
    private val rootHost = Regex("(?:www\\.)?[a-z0-9-]*buffstreams[a-z0-9-]*\\.[a-z]{2,24}")
    private val pagePath = Regex("/(nfl|cfb|cfl|nhl)/[a-z0-9-]{1,140}/[0-9]{1,9}")
    private fun plain(u: HttpUrl) = u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null
    fun validListing(value: String): Boolean = try { val u=value.toHttpUrl();value.length<=500 && plain(u) && rootHost.matches(u.host) } catch (_:Exception) { false }
    fun base(value: String): String {
        val u=value.trim().toHttpUrl()
        require(validListing(value.trim()) && u.encodedPath=="/") { "Enter the HTTPS Buffstreams address without a page path, password or query." }
        return u.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    }
    fun validPage(value: String) = validListing(value) && pagePath.matches(value.toHttpUrl().encodedPath)
    fun embed(id: String): String { require(Regex("[0-9]{1,9}").matches(id));return "https://$EMBED_HOST/new-stream-embed/$id" }
    fun validEmbed(value: String): Boolean = try { val u=value.toHttpUrl();plain(u)&&u.host==EMBED_HOST&&Regex("/new-stream-embed/[0-9]{1,9}").matches(u.encodedPath) } catch (_:Exception) { false }
    fun validMaster(value: String, id: String): Boolean = try { val u=value.toHttpUrl();plain(u)&&u.host==MASTER_HOST&&u.encodedPath=="/playlist/$id/load-playlist" } catch (_:Exception) { false }
    private fun attribute(raw: String, name: String) = Regex("(?:^|\\s)"+Regex.escape(name)+"\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",RegexOption.IGNORE_CASE).find(raw)?.let { it.groupValues[1].ifEmpty {it.groupValues[2]} }.orEmpty()
    private fun unescape(raw: String) = raw.replace("&amp;","&").replace("&quot;","\"").replace("&#039;","'").replace("&#39;","'").replace("&apos;","'")
    fun catalog(html: String, origin: String, today: String): List<SportsItem> {
        require(validListing(origin))
        val base=origin.toHttpUrl();val seen=mutableSetOf<String>();val result=mutableListOf<SportsItem>()
        for(match in Regex("<a\\b([^>]*)>([\\s\\S]*?)</a>",RegexOption.IGNORE_CASE).findAll(html)) {
            if("competition" !in attribute(match.groupValues[1],"class").split(Regex("\\s+")))continue
            if(Regex(">\\s*(?:Final|Finished|FT)\\s*<",RegexOption.IGNORE_CASE).containsMatchIn(match.groupValues[2]))continue
            val url=base.resolve(unescape(attribute(match.groupValues[1],"href")))?.toString() ?: continue
            if(!validPage(url)||url.toHttpUrl().host!=base.host)continue
            val path=url.toHttpUrl().pathSegments;val genre=path[0];val id="buff-$genre-${path.last()}"
            val title=unescape(attribute(match.groupValues[1],"title")).removePrefix("Buffstreams ").trim()
            if(Regex("\\b[0-9]{1,3}\\s*[-:]\\s*[0-9]{1,3}\\b|\\b(?:wins?|won|beats?|defeats?|final|highlights?|replay)\\b",RegexOption.IGNORE_CASE).containsMatchIn(title))continue
            if(!Regex(".{2,70} vs .{2,70}").matches(title)||!seen.add(id))continue
            val league=when(genre){"nfl"->"NFL";"cfb"->"College football";"cfl"->"CFL";else->"NHL"}
            val date=Regex("<time\\b([^>]*)>",RegexOption.IGNORE_CASE).find(match.groupValues[2])?.let{attribute(it.groupValues[1],"datetime").take(10)}?.takeIf{Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)} ?: today
            // Only the title attribute and schedule date are retained, never scores/body HTML/images.
            result.add(SportsItem(id,clean("$league · $title",140),"live",if(genre=="nhl")"hockey" else "football",date,listOf(SportsSource("buffstreams","Buffstreams",url))))
            if(result.size==96)break
        }
        return result
    }
    fun serverIds(html: String): List<String> {
        val iframe=Regex("<iframe\\b([^>]*)>",RegexOption.IGNORE_CASE).findAll(html).map{attribute(it.groupValues[1],"src")}.firstOrNull{validEmbed(it)} ?: throw IOException("This game uses an unsupported player. Try another game.")
        val first=iframe.toHttpUrl().pathSegments.last()
        val ids=Regex("window\\.changeStream\\(([0-9]{1,9})\\)").findAll(html).map{it.groupValues[1]}.distinct().take(4).toList()
        return if(ids.isEmpty())listOf(first) else ids.takeIf{first in it} ?: throw IOException("The game server list changed. Refresh games and try again.")
    }
    fun master(html: String, id: String): String {
        val url=Regex("const\\s+source\\s*=\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
        if(url==null||!validMaster(url,id))throw IOException("This game uses an unsupported stream. Try another server or game.")
        return url
    }
}

data class SportsStream(val url: String, val headers: Map<String,String> = emptyMap(), val alternatives: List<SportsSource>? = null, val sourceIndex: Int = 0, val playbackScope: BuffStreamsPlaybackScope? = null)

class BuffStreams(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS)
    .callTimeout(8,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    private data class Document(val url: String,val html: String)
    private suspend fun document(initial: String, embedded: Boolean = false): Document {
        var url=initial
        repeat(3) { hop ->
            if(!(if(embedded)BuffStreamsPolicy.validEmbed(url) else BuffStreamsPolicy.validListing(url)))throw IOException("The sports provider redirected to an unsupported address.")
            val request=Request.Builder().url(url).header("Accept","text/html").header("User-Agent","Mozilla/5.0").build()
            val pair=suspendCancellableCoroutine<Pair<String?,String>> { continuation ->
                val call=http.newCall(request);continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object: Callback {
                    override fun onFailure(call: Call,e: IOException){if(continuation.isActive)continuation.resumeWithException(IOException("The game provider could not be reached. Refresh games or try again."))}
                    override fun onResponse(call: Call,response: Response){response.use {
                        try {
                            if(response.code in listOf(301,302,303,307,308)){
                                val next=response.header("Location")?.let{response.request.url.resolve(it)}?.toString() ?: throw IOException("Incomplete game-provider redirect.")
                                if(continuation.isActive)continuation.resume(next to "")
                            }else{
                                if(!response.isSuccessful)throw IOException("The game provider is currently unavailable. Try again later.")
                                val body=response.body ?: throw IOException("The game provider returned no listing.")
                                if(body.contentLength()>300000)throw IOException("The game listing was too large.")
                                val bytes=ByteArrayOutputStream();body.byteStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;if(bytes.size()+n>300000)throw IOException("The game listing was too large.");bytes.write(buffer,0,n)}}
                                if(continuation.isActive)continuation.resume(null to bytes.toString("UTF-8"))
                            }
                        }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                    }}
                })
            }
            if(pair.first==null)return Document(url,pair.second)
            if(hop==2)throw IOException("The game provider redirected too many times.")
            url=pair.first!!
        }
        throw IOException("The game provider could not load.")
    }
    suspend fun catalog(context: Context): List<SportsItem> = withTimeout(18000) {
        val requestedBase=configuredBase(context)
        val doc=document(requestedBase)
        val today=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
        val items=BuffStreamsPolicy.catalog(doc.html,doc.url,today)
        if(items.isEmpty()&&!doc.html.contains("competition"))throw IOException("The game listing format changed. Existing sports channels remain available.")
        val json=JSONObject().put("items",JSONArray().apply{items.forEach{item->put(JSONObject().put("id",item.id).put("title",item.title).put("kind",item.kind).put("sport",item.sport).put("date",item.date).put("sources",JSONArray().put(JSONObject().put("type","buffstreams").put("label","Buffstreams").put("url",item.sources[0].url))))}})
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("sports-provider",Context.MODE_PRIVATE).edit().putString("catalog",json.toString()).putLong("cachedAt",System.currentTimeMillis()).putString("catalogBase",requestedBase).commit()
        }
        items
    }
    suspend fun prepare(source: SportsSource): SportsStream = withTimeout(14000) {
        if(source.type!="buffstreams"||!BuffStreamsPolicy.validPage(source.url))throw IOException("Unverified game source.")
        val page=document(source.url);val ids=BuffStreamsPolicy.serverIds(page.html)
        if(source.streamIndex !in ids.indices)throw IOException("This server is no longer listed. Refresh games or try another server.")
        val id=ids[source.streamIndex];val embed=BuffStreamsPolicy.embed(id);val player=document(embed,true)
        val master=BuffStreamsPolicy.master(player.html,id)
        SportsStream(master,mapOf("Referer" to embed,"User-Agent" to "Mozilla/5.0"),ids.indices.map{SportsSource("buffstreams","Buffstreams Server ${it+1}",page.url,streamIndex=it)},source.streamIndex,BuffStreamsPlaybackScope(id,master))
    }
    companion object {
        fun configuredBase(context: Context): String = try {BuffStreamsPolicy.base(context.getSharedPreferences("sports-provider",Context.MODE_PRIVATE).getString("base",BuffStreamsPolicy.DEFAULT_BASE)!!)}catch(_:Exception){BuffStreamsPolicy.DEFAULT_BASE}
        fun setBase(context: Context,value: String) {val base=BuffStreamsPolicy.base(value);context.getSharedPreferences("sports-provider",Context.MODE_PRIVATE).edit().clear().putString("base",base).apply()}
        fun cached(context: Context): List<SportsItem> = try {
            val prefs=context.getSharedPreferences("sports-provider",Context.MODE_PRIVATE);val age=System.currentTimeMillis()-prefs.getLong("cachedAt",0)
            if(age !in 0..(12*60*60*1000L)||prefs.getString("catalogBase","")!=configuredBase(context))emptyList() else SportsPolicy.parse(prefs.getString("catalog","{\"items\":[]}")!!).filter{it.sources.all{s->s.type=="buffstreams"}}
        }catch(_:Exception){emptyList()}
    }
}
