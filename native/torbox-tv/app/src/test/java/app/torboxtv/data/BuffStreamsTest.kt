package app.torboxtv.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class BuffStreamsTest {
    private val page="https://mybuffstreams.plus/cfb/home-away/12345"
    private val game="""<div onclick="window.changeStream(111)">Server1</div><div onclick="window.changeStream(222)">Server2</div><iframe src="https://gooz.aapmains.net/new-stream-embed/111"></iframe>"""
    private fun response(request:Request, body:String)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("text/html".toMediaType())).build()
    @Test fun listingsUseNamesAndDatesOnlyAndExcludeFinishedGames() {
        val html="""<a class="competition" title="Buffstreams Home &amp; Team vs Away Team" href="$page"><span class="competition-cell-score">SCORE SPOILER 21-14</span><time datetime="2026-10-10 23:30:00">11:30 PM</time><img src="result.jpg"></a>
        <a class="competition" title="Buffstreams Other Team vs Third Team" href="/nhl/other-third/999"><span>Final</span></a>
        <a class="competition" title="Buffstreams Bad Team vs Third Team" href="https://evil.test/cfb/bad-third/999"></a>
        <a class="competition" title="Buffstreams Home &amp; Team vs Away Team" href="$page"></a>
        <a class="competition" title="Buffstreams Home Team vs Away Team 21-14" href="/cfb/home-away/888"></a>"""
        val items=BuffStreamsPolicy.catalog(html,BuffStreamsPolicy.DEFAULT_BASE,"2026-10-10")
        assertEquals(1,items.size);assertEquals("College football · Home & Team vs Away Team",items[0].title)
        assertEquals("football",items[0].sport);assertEquals("2026-10-10",items[0].date)
        assertFalse(items.toString().contains("SPOILER"));assertFalse(items.toString().contains("result.jpg"));assertFalse(items.toString().contains("21-14"))
    }
    @Test fun nflAndCollegeAndCflAreAmericanFootballWhileNhlRemainsHockey() {
        val html=listOf("nfl","cfb","cfl","nhl").mapIndexed{n,league->"""<a class="competition" title="Buffstreams Home Team vs Away Team" href="/$league/home-away/${n+1}"><span>In Progress</span></a>"""}.joinToString("")
        val items=BuffStreamsPolicy.catalog(html,BuffStreamsPolicy.DEFAULT_BASE,"2026-10-10")
        assertEquals(listOf("football","football","football","hockey"),items.map{it.sport})
        assertEquals(4,items.map{it.id}.distinct().size)
    }
    @Test fun addressChangesAllowHttpsBuffstreamsRootsAndRejectOtherDestinations() {
        assertEquals("https://mybuffstreams.plus",BuffStreamsPolicy.base(" https://mybuffstreams.plus/ "))
        assertEquals("https://buffstreams.today",BuffStreamsPolicy.base("https://buffstreams.today"))
        for(url in listOf("http://mybuffstreams.plus","https://mybuffstreams.plus:444","https://user:pass@mybuffstreams.plus","https://mybuffstreams.plus.evil.test","https://127.0.0.1","https://mybuffstreams.plus/home6","https://mybuffstreams.plus/?key=secret")) {
            try{BuffStreamsPolicy.base(url);fail(url)}catch(_:IllegalArgumentException){}
        }
        assertFalse(BuffStreamsPolicy.validPage("https://mybuffstreams.plus/cfb/../account"))
    }
    @Test fun serverSelectionIsBoundedAndDoesNotExecuteProviderCode() {
        assertEquals(listOf("111","222"),BuffStreamsPolicy.serverIds(game))
        assertEquals(listOf("111"),BuffStreamsPolicy.serverIds("""<iframe src="https://gooz.aapmains.net/new-stream-embed/111"></iframe>"""))
        try{BuffStreamsPolicy.serverIds("""<iframe src="https://gooz.aapmains.net.evil.test/new-stream-embed/111"></iframe>""");fail()}catch(_:IOException){}
        assertEquals("https://chatgpt.hereisman.net/playlist/111/load-playlist",BuffStreamsPolicy.master("""const source = "https://chatgpt.hereisman.net/playlist/111/load-playlist";""","111"))
        for(url in listOf("http://chatgpt.hereisman.net/playlist/111/load-playlist","https://chatgpt.hereisman.net.evil.test/playlist/111/load-playlist","https://chatgpt.hereisman.net/playlist/222/load-playlist")) {
            try{BuffStreamsPolicy.master("const source = \"$url\";","111");fail(url)}catch(_:IOException){}
        }
    }
    @Test fun nativeResolutionUsesFreshServerChoiceAndPublicHeadersOnly()=runBlocking {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor{chain->
            val r=chain.request();synchronized(requests){requests.add(r)}
            response(r,if(r.url.host=="mybuffstreams.plus")game else "const source = \"https://chatgpt.hereisman.net/playlist/222/load-playlist\";")
        }.build()
        val stream=BuffStreams(http).prepare(SportsSource("buffstreams","Server2",page,streamIndex=1))
        assertEquals("https://chatgpt.hereisman.net/playlist/222/load-playlist",stream.url)
        assertEquals("https://gooz.aapmains.net/new-stream-embed/222",stream.headers["Referer"])
        assertEquals(2,stream.alternatives!!.size);assertEquals(1,stream.sourceIndex)
        assertEquals(listOf("mybuffstreams.plus","gooz.aapmains.net"),requests.map{it.url.host})
        assertTrue(requests.all{it.header("Authorization")==null&&it.header("Cookie")==null})
    }
    @Test fun unsupportedRedirectIsRejectedBeforeContactingItsDestination()=runBlocking {
        var calls=0
        val http=OkHttpClient.Builder().followRedirects(false).addInterceptor{chain->calls++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found").header("Location","https://evil.test/").body("".toResponseBody()).build()}.build()
        try{BuffStreams(http).prepare(SportsSource("buffstreams","Buffstreams",page));fail()}catch(_:IOException){}
        assertEquals(1,calls)
    }
    @Test fun cancelledSelectionCancelsItsPublicMetadataCall()=runBlocking {
        val active=AtomicReference<Call>()
        val http=OkHttpClient.Builder().addInterceptor{chain->active.set(chain.call());while(!chain.call().isCanceled())Thread.sleep(5);throw IOException("Cancelled")}.build()
        val opening=launch {BuffStreams(http).prepare(SportsSource("buffstreams","Buffstreams",page))}
        withTimeout(2000){while(active.get()==null)delay(5)}
        opening.cancelAndJoin();assertTrue(active.get().isCanceled())
    }
    @Test fun oversizedMetadataIsRejectedInsteadOfRetainingIt()=runBlocking {
        val http=OkHttpClient.Builder().addInterceptor{chain->response(chain.request(),"x".repeat(300001))}.build()
        try{BuffStreams(http).prepare(SportsSource("buffstreams","Buffstreams",page));fail()}catch(e:IOException){assertTrue(e.message.orEmpty().contains("large"))}
    }
    @Test fun mediaSegmentsStayInTheTwoVerifiedProviderAccounts() {
        assertTrue(SportsPolicy.validMedia("https://foresthawkblue46.270b29c3f31524af69f0d729a4646332.us.r2.cloudflarestorage.com/scripts/segment.txt?signature=public"))
        assertTrue(SportsPolicy.validMedia("https://islandforeststone60.1953611ea5fddadb06c2c04ab7719218.us.r2.cloudflarestorage.com/scripts/segment.txt"))
        for(url in listOf("https://foresthawkblue46.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.us.r2.cloudflarestorage.com/a","https://foresthawkblue46.270b29c3f31524af69f0d729a4646332.us.r2.cloudflarestorage.com.evil.test/a","https://user:pass@red.redirector1.space/a","http://chatgpt.hereisman.net/a"))assertFalse(url,SportsPolicy.validMedia(url))
    }
}
