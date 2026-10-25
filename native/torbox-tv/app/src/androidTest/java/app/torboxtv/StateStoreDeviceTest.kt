package app.torboxtv

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.torboxtv.core.*
import app.torboxtv.data.StateStore
import app.torboxtv.data.PersistentOperationLedger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the actual Android Keystore and SharedPreferences, never a real API key. */
@RunWith(AndroidJUnit4::class)
class StateStoreDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("torbox_tv_state_v1", Context.MODE_PRIVATE)
    private val usagePreferences get() = context.getSharedPreferences("torbox_tv_usage_v1", Context.MODE_PRIVATE)
    private val movie = Target(Title("tt0088763", "movie", "Back to the Future", year = "1985"))

    @Before @After fun reset() {
        assertTrue(preferences.edit().clear().commit())
        assertTrue(usagePreferences.edit().clear().commit())
    }

    @Test fun keystoreCredentialRoundTripsWithoutPlaintextPreferences() {
        val fixture = "instrumentation-placeholder-no-live-account-credentials"
        val store = StateStore(context)
        store.saveKey(fixture)
        assertEquals(fixture, StateStore(context).key())
        val storedValues = preferences.all.values.joinToString("\n")
        assertFalse("The API key must be encrypted before persistence", storedValues.contains(fixture))
        val envelope = JSONObject(preferences.getString("credential.aesgcm.v1", "")!!)
        assertEquals(1, envelope.getInt("version"))
        assertTrue(envelope.getString("iv").isNotBlank())
        assertTrue(envelope.getString("data").isNotBlank())
        store.clearKey()
        assertNull(StateStore(context).key())
        assertFalse(preferences.contains("credential.aesgcm.v1"))
    }

    @Test fun credentialTamperingFailsClosedWithoutCrashing() {
        val store = StateStore(context)
        store.saveKey("instrumentation-placeholder-no-live-account-credentials")
        val envelope = JSONObject(preferences.getString("credential.aesgcm.v1", "")!!)
        envelope.put("data", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(preferences.edit().putString("credential.aesgcm.v1", envelope.toString()).commit())
        assertNull("AES-GCM authentication must reject changed ciphertext", StateStore(context).key())
        assertTrue(preferences.edit().putString("credential.aesgcm.v1", "broken-json").commit())
        assertNull(StateStore(context).key())
    }

    @Test fun separateInstancesPersistHistorySettingsAndIsolateProfiles() {
        val first = StateStore(context)
        first.saveSettings(Settings(maxHeight = 720, rewindSeconds = 20, autoNext = false, countdownSeconds = 30))
        first.record(History(movie, 75_000, 6_000_000, 123_456))
        first.toggleWatchlist(movie.title)
        val second = StateStore(context)
        assertEquals(75_000L, second.history().single().positionMs)
        assertEquals(movie, second.history().single().target)
        assertEquals(720, second.settings().maxHeight)
        assertEquals(20, second.settings().rewindSeconds)
        assertFalse(second.settings().autoNext)
        assertEquals(movie.title, second.watchlist().single())
        second.selectProfile("viewer-2")
        assertTrue(StateStore(context).history().isEmpty())
        assertTrue(StateStore(context).watchlist().isEmpty())
        assertEquals(Settings(), StateStore(context).settings())
        second.saveSettings(Settings(rewindSeconds = 5))
        second.selectProfile("viewer-1")
        assertEquals(20, StateStore(context).settings().rewindSeconds)
        second.removeHistory(movie.key)
        assertTrue(StateStore(context).history().isEmpty())
        assertEquals("History removal must not delete My List", movie.title, second.watchlist().single())
    }

    @Test fun pinVerifierAcceptsOnlyCorrectPinAndRejectsCorruption() {
        val store = StateStore(context)
        store.setPin("2468")
        assertTrue(StateStore(context).hasPin())
        assertTrue(StateStore(context).verifyPin("2468"))
        assertFalse(StateStore(context).verifyPin("2469"))
        assertFalse(StateStore(context).verifyPin(""))
        val stored = preferences.getString("parent.pin.v1", "")!!
        assertFalse("Parent PIN must not be persisted as plaintext", stored.contains("2468"))
        val damaged = JSONObject(stored).put("verifier", "AAAA")
        assertTrue(preferences.edit().putString("parent.pin.v1", damaged.toString()).commit())
        assertTrue("Corruption must preserve the configured-PIN gate", StateStore(context).hasPin())
        assertFalse(StateStore(context).verifyPin("2468"))
    }

    @Test fun corruptCurrentSnapshotCannotSilentlyUnlockKidProfile() {
        val store = StateStore(context)
        store.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30))
        store.saveSettings(Settings(maxHeight = 720))
        assertTrue(preferences.getBoolean("snapshot.kid_guard", false))
        assertTrue(preferences.edit().putString("snapshot.current", "broken-json").commit())
        assertTrue("Recovering a saved snapshot must remain restricted", StateStore(context).currentProfile().kidMode)
        assertTrue(preferences.edit().putString("snapshot.previous", "also-broken").commit())
        val recovered = StateStore(context).currentProfile()
        assertTrue("Losing both snapshots must never choose an unrestricted viewer", recovered.kidMode)
        assertEquals(0, recovered.minuteLimit)
        assertNotNull(Policies.restriction(recovered, StateStore(context).usage(), movie))
    }

    @Test fun missingCurrentSnapshotRecoversHistoryAndRemainsInKidMode() {
        val store = StateStore(context)
        store.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30))
        store.record(History(movie, 75_000, 6_000_000, 123_456))
        store.saveSettings(Settings(maxHeight = 720))
        assertTrue(preferences.edit().remove("snapshot.current").commit())
        val recovered = StateStore(context)
        assertTrue(recovered.currentProfile().kidMode)
        assertEquals("Family", recovered.currentProfile().name)
        assertEquals(75_000L, recovered.history().single().positionMs)
        assertTrue(preferences.edit().remove("snapshot.previous").commit())
        val restricted = StateStore(context)
        assertTrue(restricted.currentProfile().kidMode)
        assertNotNull(Policies.restriction(restricted.currentProfile(), restricted.usage(), movie))
    }

    @Test fun missingUsagePrimaryDoesNotResetDailyAllowance() {
        val store = StateStore(context)
        store.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30))
        store.recordWatched(movie, 60_000, 6_000_000)
        store.recordWatched(movie, 30_000, 6_000_000)
        assertTrue(usagePreferences.edit().remove("viewer-1.current").commit())
        assertEquals(60_000L, StateStore(context).usage().watchedMs)
        assertTrue(usagePreferences.edit().putString("viewer-1.previous", "broken").commit())
        val restricted = StateStore(context)
        assertNotNull(Policies.restriction(restricted.currentProfile(), restricted.usage(), movie))
    }

    @Test fun preparationClaimIsAtomicAcrossPersistentLedgerInstances() {
        val marker = "instrumentation-concurrent-claim-no-account"
        val ledger = PersistentOperationLedger(context)
        ledger.clear(marker)
        val workers = Executors.newFixedThreadPool(8)
        try {
            repeat(4) {
                ledger.clear(marker)
                val start = CountDownLatch(1)
                val claims = (0 until 64).map {
                    workers.submit<Boolean> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        PersistentOperationLedger(context).claim(marker)
                    }
                }
                start.countDown()
                assertEquals("Only one Activity/client can claim an addition", 1,
                    claims.count { it.get(10, TimeUnit.SECONDS) })
                assertTrue(PersistentOperationLedger(context).contains(marker))
                assertFalse("Recreating a client cannot repeat an uncertain addition",
                    PersistentOperationLedger(context).claim(marker))
            }
            ledger.clear(marker)
            assertTrue("Explicitly cleared preparation can be claimed again", PersistentOperationLedger(context).claim(marker))
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            ledger.clear(marker)
        }
    }

    @Test fun watchedTimePersistsAcrossInstancesAndSeekDoesNotChargePosition() {
        val first = StateStore(context)
        first.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30))
        first.recordWatched(movie, 1_500, 6_000_000)
        first.recordWatched(movie, 500, 6_000_000)
        assertEquals(2_000L, StateStore(context).usage().watchedMs)
        assertTrue("Brief playback must not spend the movie allowance", StateStore(context).usage().chargedMovies.isEmpty())
        first.record(History(movie, 4_000_000, 6_000_000, 123_457))
        assertEquals("Saved media position must not become viewing time", 2_000L, StateStore(context).usage().watchedMs)
        first.grantMinutes(30)
        assertEquals(30, StateStore(context).usage().grantedMinutes)
        repeat(10) { first.recordWatched(movie, 60_000, 6_000_000) }
        assertEquals("The ten-minute movie threshold must persist its count", setOf(movie.title.id), StateStore(context).usage().chargedMovies)
        first.selectProfile("viewer-2")
        assertEquals(0L, StateStore(context).usage().watchedMs)
        first.selectProfile("viewer-1")
        assertEquals(602_000L, StateStore(context).usage().watchedMs)
    }

    @Test fun damagedUsageCannotResetKidAllowanceAndPastDayRollsOver() {
        val first = StateStore(context)
        first.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30))
        first.recordWatched(movie, 2_000, 6_000_000)
        first.recordWatched(movie, 1_000, 6_000_000)
        assertTrue(usagePreferences.edit().putString("viewer-1.current", "broken").commit())
        assertEquals("Recover a prior valid usage snapshot", 2_000L, StateStore(context).usage().watchedMs)
        assertTrue(usagePreferences.edit().putString("viewer-1.previous", "also-broken").commit())
        val restricted = StateStore(context)
        assertNotNull("Losing both usage snapshots must not reset the viewing limit", Policies.restriction(restricted.currentProfile(), restricted.usage(), movie))
        first.resetUsage()
        val yesterday = JSONObject(usagePreferences.getString("viewer-1.current", "")!!)
            .put("day", "2000-01-01").put("watchedMs", 100_000)
        assertTrue(usagePreferences.edit().putString("viewer-1.current", yesterday.toString()).commit())
        assertEquals("Usage from an earlier calendar day resets allowance", 0L, StateStore(context).usage().watchedMs)
    }
}
