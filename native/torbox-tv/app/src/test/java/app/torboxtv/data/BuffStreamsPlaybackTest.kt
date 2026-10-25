package app.torboxtv.data

import androidx.media3.common.PlaybackException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPOutputStream

class BuffStreamsPlaybackTest {
    private val id="58500"
    private val master="https://chatgpt.hereisman.net/playlist/$id/load-playlist"
    private val variant="https://pl.whywelove2.space/playlist/$id/b.backstore2.space/caxi"
    private val segment="https://b.backstore2.space/scripts/NTg1MDA=/p1792859678642743493_2076.txt"
    private fun manifest(url: String=variant)="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS=\"avc1.64001f, mp4a.40.2\"\n$url\n"
    private fun request(url: String)=Request.Builder().url(url).header("Referer",BuffStreamsPolicy.embed(id)).header("User-Agent","Mozilla/5.0").build()
    private fun response(r:Request,body:ByteArray,code:Int=200)=Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()
    private fun failure(cause:Throwable?=null,code:Int=PlaybackException.ERROR_CODE_IO_UNSPECIFIED)=SportsPlaybackFailure.message(IOException("https://secret.test/?token=private",cause),code)

    @Test fun capturedMasterVariantAndSegmentsTraverseTheActualGuard() {
        val scope=BuffStreamsPlaybackScope(id,master)
        val seen=mutableListOf<String>()
        val http=OkHttpClient.Builder().addInterceptor(SportsMediaGuard(scope)).addInterceptor { chain ->
            val r=chain.request();seen.add(r.url.toString())
            assertNull(r.header("Cookie"));assertNull(r.header("Authorization"))
            assertEquals(BuffStreamsPolicy.embed(id),r.header("Referer"))
            when(r.url.toString()) {
                master -> response(r,manifest().toByteArray())
                variant -> response(r,"#EXTM3U\n#EXTINF:5.0,\n$segment\n".toByteArray())
                segment -> response(r,ByteArray(376){if(it%188==0)0x47 else 0},206)
                else -> throw AssertionError("Unexpected transport destination")
            }
        }.build()
        assertFalse(scope.allows(variant));assertFalse(SportsPolicy.validMedia(variant))
        val m=http.newCall(request(master)).execute().use { it.body!!.string() }
        val v=m.lineSequence().first { it.startsWith("https://") }
        val playlist=http.newCall(request(v)).execute().use { it.body!!.string() }
        val s=playlist.lineSequence().first { it.startsWith("https://") }
        http.newCall(request(s)).execute().use { assertEquals(206,it.code);assertEquals(0x47,it.body!!.bytes()[188].toInt()) }
        assertEquals(listOf(master,variant,segment),seen)
    }
    @Test fun allThirteenCapturedProviderVariantsAndThirtyNineSegmentsPassOnlyInTheirOwnScope() {
        val data=javaClass.getResourceAsStream("/buffstreams-protocol-fixtures.json")!!.bufferedReader().use { JSONObject(it.readText()).getJSONArray("chains") }
        var childCount=0
        for(index in 0 until data.length()) {
            val row=data.getJSONObject(index);val game=row.getString("id");val root=row.getString("master");val variant=row.getString("variant")
            val children=row.getString("variantBody").lineSequence().filter { it.startsWith("https://") }.toSet()
            val scope=BuffStreamsPlaybackScope(game,root)
            val http=OkHttpClient.Builder().addInterceptor(SportsMediaGuard(scope)).addInterceptor { chain ->
                val r=chain.request()
                when(r.url.toString()) {
                    root -> response(r,row.getString("masterBody").toByteArray())
                    variant -> response(r,row.getString("variantBody").toByteArray())
                    in children -> response(r,byteArrayOf(0x47),206)
                    else -> throw AssertionError("Undeclared destination reached transport")
                }
            }.build()
            fun get(url:String)=http.newCall(Request.Builder().url(url).header("Referer",BuffStreamsPolicy.embed(game)).header("User-Agent","Mozilla/5.0").build()).execute()
            assertFalse(scope.allows(variant))
            get(root).use { assertEquals(row.getString("masterBody"),it.body!!.string()) }
            get(variant).use { assertEquals(row.getString("variantBody"),it.body!!.string()) }
            for(child in children) { get(child).use { assertEquals(206,it.code) };childCount++ }
            // Previous global policy denied at least one downstream exchange in every captured chain.
            assertTrue(!SportsPolicy.validMedia(variant) || children.any { !SportsPolicy.validMedia(it) })
        }
        assertEquals(13,data.length());assertEquals(39,childCount)
    }
    @Test fun rotatingMasterReplacesCapabilitiesRatherThanAccumulatingHosts() {
        val scope=BuffStreamsPlaybackScope(id,master)
        scope.acceptMaster(manifest())
        val rotated="https://pl.kamfir5.space/playlist/$id/a.newstore4.space/caxi"
        scope.acceptMaster(manifest(rotated))
        assertTrue(scope.allows(rotated))
        assertTrue(scope.allows("https://a.newstore4.space/scripts/NTg1MDA=/p1792859678642743493_1.txt"))
        assertFalse(scope.allows(variant));assertFalse(scope.allows(segment))
        assertFalse(BuffStreamsPlaybackScope(id,master).allows(rotated))
        assertFalse(BuffStreamsPlaybackScope("58513","https://chatgpt.hereisman.net/playlist/58513/load-playlist").allows(rotated))
        assertFalse(SportsPolicy.validMedia(rotated))
    }
    @Test fun unknownHostsGamesPathsAndSensitiveHeadersNeverReachFixtureTransport() {
        val scope=BuffStreamsPlaybackScope(id,master);scope.acceptMaster(manifest())
        var reached=0
        val http=OkHttpClient.Builder().addInterceptor(SportsMediaGuard(scope)).addInterceptor { reached++;response(it.request(),byteArrayOf()) }.build()
        val bad=listOf(
            request("https://pl.whywelove2.space.evil.test/playlist/$id/b.backstore2.space/caxi"),
            request(variant.replace("https:","http:")),request(variant.replace("/$id/","/58513/")),
            request("https://pl.whywelove2.space/admin"),request(variant.replace("b.backstore2.space","a.newvegan6.space")),request(segment.replace("NTg1MDA=","NTg1MDA%3D")),request("https://a.newvegan6.space/scripts/NTg1MDA=/p1_1.txt"),
            request(segment.replace("NTg1MDA=","NTg1MTM=")),request(segment.replace("/scripts/","/account/")),
            request("https://torbox-web-player-key.onrender.com/stream"),request("https://127.0.0.1/scripts/NTg1MDA=/p1_1.txt"),
            request(segment+"?token=secret"),request(segment+"#fragment"),request(segment.replace("https://","https://user:pass@")),
            request(segment).newBuilder().header("Authorization","Bearer secret").build(),
            request(segment).newBuilder().header("Cookie","session=secret").build(),
            request(segment).newBuilder().header("Referer",BuffStreamsPolicy.embed("58513")).build())
        bad.forEach { try { http.newCall(it).execute().close();fail(it.url.toString()) } catch (_: SportsDestinationRejected) {} }
        assertEquals(0,reached)
    }
    @Test fun trustedMasterCannotIntroducePrivateLookalikeCrossGameOrOtherProtocolRoutes() {
        val scope=BuffStreamsPlaybackScope(id,master)
        for(v in listOf("http://pl.whywelove2.space/playlist/$id/b.backstore2.space/caxi",
            "https://pl.whywelove2.space.evil.test/playlist/$id/b.backstore2.space/caxi",
            "https://127.0.0.1/playlist/$id/b.backstore2.space/caxi",
            "https://pl.whywelove2.space/playlist/58513/b.backstore2.space/caxi",
            "https://pl.whywelove2.space/playlist/$id/127.0.0.1/caxi",
            "https://pl.whywelove2.space/playlist/$id/x.onrender.com/caxi",
            "https://user:pass@pl.whywelove2.space/playlist/$id/b.backstore2.space/caxi",
            "https://pl.whywelove2.space/playlist/$id/b%2ebackstore2.space/caxi")) {
            try { scope.acceptMaster(manifest(v));fail(v) } catch (_: SportsDestinationRejected) {}
        }
        assertFalse(scope.allows(variant))
    }
    @Test fun masterBodiesAreBoundedAndUnsupportedUriTagsCannotIntroduceExtraTraffic() {
        val scope=BuffStreamsPlaybackScope(id,master)
        for(body in listOf("not a playlist",manifest()+"#EXT-X-KEY:METHOD=AES-128,URI=\"https://evil.test/key\"",manifest()+"x".repeat(256*1024),"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n")) {
            try { scope.acceptMaster(body);fail("Invalid master accepted") } catch (_: SportsManifestRejected) {}
        }
        assertFalse(scope.allows(variant))
    }
    @Test fun guardedGzipMasterIsBoundedDecodedAndRemainsReadableByMedia3() {
        val scope=BuffStreamsPlaybackScope(id,master)
        val output=java.io.ByteArrayOutputStream();GZIPOutputStream(output).use { it.write(manifest().toByteArray()) }
        val http=OkHttpClient.Builder().addInterceptor(SportsMediaGuard(scope)).addInterceptor {
            response(it.request(),output.toByteArray()).newBuilder().header("Content-Encoding","gzip").build()
        }.build()
        http.newCall(request(master)).execute().use { assertNull(it.header("Content-Encoding"));assertEquals(manifest(),it.body!!.string()) }
        assertTrue(scope.allows(segment))
        val huge=java.io.ByteArrayOutputStream();GZIPOutputStream(huge).use { it.write("x".repeat(256*1024+1).toByteArray()) }
        val oversized=OkHttpClient.Builder().addInterceptor(SportsMediaGuard(BuffStreamsPlaybackScope(id,master))).addInterceptor {
            response(it.request(),huge.toByteArray()).newBuilder().header("Content-Encoding","gzip").build()
        }.build()
        try { oversized.newCall(request(master)).execute().close();fail("Compressed oversized body accepted") } catch (_: SportsManifestRejected) {}
    }
    @Test fun policyTimeoutFormatAndHttpErrorsHaveAccurateNonSensitiveMessages() {
        assertTrue(failure(IOException("private URL",SportsDestinationRejected())).contains("changed its stream address"))
        assertTrue(failure(IOException("private URL",SocketTimeoutException("private"))).contains("too long"))
        assertTrue(failure(SportsManifestRejected()).contains("unsupported"))
        assertTrue(failure(code=PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).contains("unsupported"))
        for(status in listOf(401,403))assertTrue(SportsPlaybackFailure.httpMessage(status).contains("refused access"))
        for(status in listOf(404,410))assertTrue(SportsPlaybackFailure.httpMessage(status).contains("no longer"))
        assertTrue(SportsPlaybackFailure.httpMessage(503).contains("could not serve"))
        val fallback=failure(IOException("https://secret.test/?token=private"))
        assertFalse(fallback.contains("secret"));assertFalse(fallback.contains("private"));assertFalse(fallback.contains("location"))
    }
    @Test fun dnsRejectsPrivateReservedAndMixedAnswersWithoutUsingRealDns() {
        val bad=listOf("0.0.0.0","10.1.2.3","100.64.1.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","192.0.2.1","198.18.1.1","198.51.100.1","203.0.113.1","224.0.0.1","240.0.0.1","::1","fe80::1","fc00::1","2001:db8::1","2002:c000:0201::1")
        for(ip in bad) {
            val dns=SportsPublicDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("1.1.1.1"),InetAddress.getByName(ip)) })
            try { dns.lookup("pl.fixture.space");fail(ip) } catch (_: UnknownHostException) {}
        }
        for(ip in listOf("1.1.1.1","8.8.8.8","2606:4700:4700::1111"))assertEquals(1,SportsPublicDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName(ip)) }).lookup("pl.fixture.space").size)
    }
}
