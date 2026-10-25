package app.torboxtv.data

import app.torboxtv.core.Target
import app.torboxtv.core.Title
import app.torboxtv.core.Policies
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in PUBLIC metadata only. Does not read credentials, call TorBox, add torrents or play video. */
class LiveMetadataTest {
    @Test fun liveCatalogAndIndependentSourceContracts() = runBlocking {
        assumeTrue("Set TORBOX_TV_LIVE_METADATA=1 to run public-service smoke tests", System.getenv("TORBOX_TV_LIVE_METADATA") == "1")
        withTimeout(75_000) {
            val catalog = CatalogClient()
            val browsed = catalog.browse("movie")
            assertTrue("Live movie browse was empty", browsed.isNotEmpty())
            assertTrue(browsed.all { it.type == "movie" && it.id.matches(Regex("tt[0-9]{5,12}")) })
            val title = catalog.details(Title("tt0113497", "movie", "Jumanji"))
            assertEquals("tt0113497", title.id)
            assertEquals("Jumanji", title.name)
            val searched = catalog.search("Jumanji")
            assertTrue("Known title missing from live search", searched.any { it.id == title.id })
            assertTrue(searched.all { CatalogClient.searchRelevant(it.name, "Jumanji") })
            val target = Target(title)
            val sources = SourceClient().find(target)
            assertTrue("All live independent source providers returned no verified candidate", sources.isNotEmpty())
            assertTrue(sources.all { Policies.sourceIdentity(it, target) && it.hash.matches(Regex("[a-f0-9]{40}")) })
            println("PUBLIC LIVE SMOKE: browse=${browsed.size}, search=${searched.size}, IMDb=${title.id}, verifiedSources=${sources.size}, providers=${sources.map { it.provider }.distinct().joinToString()}")
        }
    }
}
