package app.torboxtv.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

class SportsTest {
    @Test fun bundledCatalogIncludesFreeTennisAndPadelArchives() {
        val items=SportsPolicy.parse(File("src/main/assets/sports-catalog.json").readText())
        assertEquals(39,items.count{it.kind=="live"})
        assertEquals(51,items.filter{it.kind=="live"}.sumOf{it.sources.size})
        assertEquals(157,items.count{it.kind=="replay"})
        assertTrue(items.any{it.sport=="tennis"&&it.kind=="live"})
        assertTrue(items.filter{it.kind=="replay"}.all{it.sources.all{s->s.type=="redbull"&&SportsPolicy.videoId.matches(s.videoId)}})
        assertTrue(items.filter{it.kind=="live"}.all{it.provider.isNotBlank()&&it.sources.all{s->s.type=="hls"&&SportsPolicy.validMedia(s.url)}})
        assertTrue(items.any{it.id=="nfl-channel"&&it.sport=="football"})
        assertTrue(items.any{it.id=="fubo-sports"}&&items.any{it.id=="surfer-tv"}&&items.any{it.id=="pac-12-insider"})
        assertTrue(items.any{it.id=="flohockey"}&&items.any{it.id=="billiard-tv"})
        assertTrue(items.filter{it.kind=="replay"}.map{it.sport}.distinct().size>=13)
        assertTrue(items.any{it.kind=="replay"&&it.sport=="hockey"&&it.title.contains("Sabres")})
        assertTrue(items.any{it.kind=="replay"&&it.sport=="football"})
        assertEquals(items.size,items.map{it.id}.distinct().size)
    }
    @Test fun sportsMediaPolicyRejectsCredentialsCleartextPortsAndLookalikes() {
        assertTrue(SportsPolicy.validMedia("https://play.redbull.com/test.m3u8"))
        assertTrue(SportsPolicy.validMedia("https://rbmn-live.akamaized.net/live.m3u8"))
        for(url in listOf("http://play.redbull.com/a","https://play.redbull.com.evil.test/a","https://evilplay.redbull.com/a","https://user:pass@play.redbull.com/a","https://play.redbull.com:444/a","https://api.torbox.app/a","https://n1.tb-cdn.io/a"))assertFalse(url,SportsPolicy.validMedia(url))
    }
    @Test fun malformedCatalogCannotIntroduceNewDestinations() {
        val raw="""{"items":[{"id":"bad","title":"bad","kind":"live","sport":"tennis","sources":[{"type":"hls","url":"https://evil.test/a"}]}]}"""
        assertTrue(SportsPolicy.parse(raw).isEmpty())
    }
    @Test fun resumeRewindsAndNeverRestartsFinishedReplays() {
        assertEquals(55000L,SportsPolicy.resume(60000,200000))
        for(pair in listOf(90000L to 100000L,100000L to 100000L,-10L to 200000L,5000L to 200000L,60000L to 0L))assertEquals(0L,SportsPolicy.resume(pair.first,pair.second))
    }
    @Test fun catalogSearchCombinesSportProviderAndWordsWithoutSearchingUrls() {
        val source=SportsSource("hls","Public channel","https://rbmn-live.akamaized.net/not-a-search-term.m3u8")
        val rows=listOf(
            SportsItem("one","Mountain Bike World Cup","replay","cycling","2026-10-11",listOf(source),"Red Bull TV"),
            SportsItem("two","Football Channel","live","football","",listOf(source),"CBS Sports"),
            SportsItem("three","Road Cycling","replay","cycling","",listOf(source),"Other provider")
        )
        assertEquals(listOf("one"),SportsBrowse.select(rows,"replay","cycling","Red Bull TV","bike world").map{it.id})
        assertEquals(listOf("two"),SportsBrowse.select(rows,"live",query="american football").map{it.id})
        assertTrue(SportsBrowse.select(rows,"replay",query="not-a-search-term").isEmpty())
        assertTrue(SportsBrowse.select(rows,"replay","cycling","CBS Sports").isEmpty())
        assertEquals(listOf("football","cycling"),SportsBrowse.sports(rows))
        assertEquals(listOf("CBS Sports","Other provider","Red Bull TV"),SportsBrowse.providers(rows))
    }
    @Test fun providerDefaultsRetainOlderCatalogAndRefreshedGameCompatibility() {
        assertEquals("Red Bull TV",SportsBrowse.provider(SportsItem("old","Replay","replay","padel","",listOf(SportsSource("redbull","Replay")))))
        assertEquals("Buffstreams",SportsBrowse.provider(SportsItem("game","Home vs Away","live","football","",listOf(SportsSource("buffstreams","Buffstreams Server 2")))))
        assertEquals("Channel",SportsBrowse.provider(SportsItem("old-live","Channel","live","mixed","",listOf(SportsSource("hls","Channel")))))
    }
    @Test fun expandedCatalogCategoriesRemainBoundedAndRejectUnsafeFields() {
        val valid="""{"items":[{"id":"cycling","title":"Ride","kind":"replay","sport":"cycling","provider":"Red Bull TV","sources":[{"type":"hls","label":"Ride","url":"https://play.redbull.com/a.m3u8"}]},{"id":"bad","kind":"live","sport":"<script>","sources":[{"type":"hls","url":"https://play.redbull.com/a.m3u8"}]}]}"""
        val items=SportsPolicy.parse(valid)
        assertEquals(1,items.size)
        assertEquals("Red Bull TV",items[0].provider)
        assertEquals("Cycling",SportsBrowse.sportName(items[0].sport))
        val repeated=(0..699).joinToString(","){"""{"id":"item-$it","title":"Ride","kind":"live","sport":"cycling","sources":[{"type":"hls","url":"https://play.redbull.com/a.m3u8"}]}"""}
        assertEquals(600,SportsPolicy.parse("{\"items\":[$repeated]}").size)
    }
    @Test fun mediaGuardRejectsBackendAndCredentialRequestsBeforeTheyReachTransport() {
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor(SportsMediaGuard()).addInterceptor { chain ->
            calls++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("ok".toResponseBody()).build()
        }.build()
        val allowed="https://play.redbull.com/a.m3u8"
        http.newCall(Request.Builder().url(allowed).build()).execute().close()
        assertEquals(1,calls)
        val rejected=listOf(
            Request.Builder().url("https://torbox-web-player-key.onrender.com/stream").build(),
            Request.Builder().url("https://play.redbull.com.evil.test/a").build(),
            Request.Builder().url("https://user:pass@play.redbull.com/a").build(),
            Request.Builder().url(allowed).header("Authorization","Bearer test").build(),
            Request.Builder().url(allowed).header("Cookie","session=test").build(),
            Request.Builder().url(allowed).header("Referer","https://evil.test").build()
        )
        rejected.forEach { request -> try {http.newCall(request).execute().close();fail("Guard accepted ${request.url.host}")} catch(_:IOException) {} }
        assertEquals("Rejected requests must never reach even the mocked transport",1,calls)
    }
}
