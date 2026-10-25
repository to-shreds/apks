package app.torboxtv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlsTest {
    private val id = "tt6933238"
    private val small = "https://images.metahub.space/poster/small/$id/img"
    private val medium = "https://images.metahub.space/poster/medium/$id/img"
    @Test fun acceptsActualLiveProviderRedirectAndDirectMetadata() {
        assertTrue(ArtworkUrls.valid("https://live.metahub.space/poster/small/$id/img"))
        assertTrue(ArtworkUrls.valid("https://live.metahub.space/background/medium/$id/img"))
        assertTrue(ArtworkUrls.valid(small))
    }
    @Test fun stillRejectsImpersonationCredentialsPortsAndCleartext() {
        for (url in listOf("http://live.metahub.space/a", "https://live.metahub.space.evil.test/a", "https://evil.metahub.space/a", "https://user:key@live.metahub.space/a", "https://live.metahub.space:8443/a", "file:///tmp/poster")) assertFalse(url, ArtworkUrls.valid(url))
    }
    @Test fun fallbackKeepsExactTitleAndDeduplicatesMedium() {
        assertEquals(listOf(small, medium), ArtworkUrls.candidates(small, id))
        assertEquals(listOf(medium), ArtworkUrls.candidates(medium, id))
        assertEquals(listOf(medium), ArtworkUrls.candidates("", id))
        assertEquals(emptyList<String>(), ArtworkUrls.candidates("https://evil.test/image", "wrong"))
    }
    @Test fun successfulPrimaryDoesNotFetchAlternates() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals("bitmap", recoverArtwork(listOf(small, medium), read = { calls.add(it); "bitmap" }, pause = { fail("Unexpected retry") }))
        assertEquals(listOf(small), calls)
    }
    @Test fun failedOrUndecodablePrimaryRecoversWithOtherSize() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals("bitmap", recoverArtwork(listOf(small, medium), read = { calls.add(it); if (it == medium) "bitmap" else null }, pause = { fail("Unexpected retry") }))
        assertEquals(listOf(small, medium), calls)
    }
    @Test fun transientFailureRetriesOnceAndEventuallyStops() = runBlocking {
        val calls = mutableListOf<String>(); var waits = 0
        assertNull(recoverArtwork<String>(listOf(small, medium), read = { calls.add(it); null }, pause = { waits++ }))
        assertEquals(listOf(small, medium, small), calls); assertEquals(1, waits)
        calls.clear()
        assertEquals("recovered", recoverArtwork(listOf(small), read = { calls.add(it); if (calls.size == 2) "recovered" else null }, pause = {}))
        assertEquals(2, calls.size)
    }
    @Test fun cancelledScreenNeverStartsAnotherRequest() = runBlocking {
        var calls = 0
        try { recoverArtwork<String>(listOf(small, medium), read = { calls++; throw CancellationException("Screen closed") }, pause = {}); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
    @Test fun emptyPlanDoesNoWork() = runBlocking {
        assertNull(recoverArtwork<String>(emptyList(), read = { fail("Unexpected download"); null }, pause = { fail("Unexpected retry") }))
    }
}
