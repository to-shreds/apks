package app.torboxtv.data

import app.torboxtv.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Bounded, synthetic concurrency and adversarial input audit. No provider network calls. */
class StressAuditTest {
    @Test fun concurrentTitlesKeepSourceIdentityAndEvictWithoutCrossContamination() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val client = SourceClient(providerTasks = { target ->
            listOf<suspend () -> List<Source>?>(
                {
                    val current = active.incrementAndGet(); peak.updateAndGet { maxOf(it,current) }
                    try {
                        delay(5)
                        listOf(Source(target.title.id.removePrefix("tt").padStart(40,'0'), target.title.name+".1995.720p.mkv", "Fixture", height=720))
                    } finally { active.decrementAndGet() }
                },
                { delay(10); emptyList() },
                { delay(2); throw java.io.IOException("fixture provider unavailable") }
            )
        })
        repeat(4) { wave ->
            (0 until 80).map { index -> async(Dispatchers.Default) {
                val id=wave*80+index
                val target=Target(Title("tt${1000000+id}","movie","AuditTitle$id",year="1995"))
                val result=client.find(target)
                assertEquals(1,result.size)
                assertEquals((1000000+id).toString().padStart(40,'0'),result.single().hash)
                assertTrue(Policies.sourceIdentity(result.single(),target))
                assertEquals(result,client.find(target))
            } }.awaitAll()
        }
        assertEquals(0,active.get())
        assertTrue(peak.get()>1)
        println("AUDIT: 320 concurrent-title discoveries plus 320 cached/re-discovery checks passed; active provider jobs=0; peak=${peak.get()}")
    }

    @Test fun repeatedCancellationLeavesNoProviderJobsAndNextPlaybackStillWorks() = runBlocking {
        val active=AtomicInteger()
        val target=Target(Title("tt0113497","movie","Jumanji",year="1995"))
        val first=Source("a".repeat(40),"Jumanji.1995.720p.mkv","Fixture",height=720)
        val client=SourceClient(providerTasks={
            listOf<suspend () -> List<Source>?>(
                { listOf(first) },
                { active.incrementAndGet();try { awaitCancellation() } finally { active.decrementAndGet() } }
            )
        })
        repeat(200) {
            client.invalidate(target)
            val gotBatch=CompletableDeferred<Unit>()
            val job=launch(Dispatchers.Default) { client.find(target) { gotBatch.complete(Unit);awaitCancellation() } }
            withTimeout(2000){gotBatch.await()}
            job.cancelAndJoin()
            assertEquals(0,active.get())
            assertEquals(listOf(first),client.find(target){true})
        }
        println("AUDIT: 200 cancel/reopen cycles passed; active provider jobs=0")
    }

    @Test fun hostileDestinationsNeverBecomeNativeMedia() {
        var checks=0
        repeat(1000) { n ->
            for(url in listOf("https://torbox-web-player-key.onrender.com/$n.ts", "https://play.redbull.com.evil.example/$n", "https://play.redbull.com@$n.evil.example/video", "https://user:secret@play.redbull.com/$n", "http://play.redbull.com/$n", "https://play.redbull.com:444/$n", "file:///tmp/$n", "https://api.torbox.app/$n")) {
                assertFalse(url,SportsPolicy.validMedia(url));assertFalse(url,MediaHostGuard.valid(url));checks+=2
            }
            assertTrue(SportsPolicy.validMedia("https://play.redbull.com/$n.m3u8"))
            assertTrue(MediaHostGuard.valid("https://store.tb-cdn.io/$n.mp4"))
        }
        println("AUDIT: $checks rejected media-policy assertions and 2000 accepted CDN controls passed")
    }
}
