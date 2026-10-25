package app.torboxtv.data

import android.content.SharedPreferences
import app.torboxtv.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercise actual production snapshot recovery and writes with fault-injected preferences. */
class StateStoreReliabilityTest {
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = synchronized(values) { values.toMap() }
        override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }
        override fun getString(key: String?, defValue: String?): String? = synchronized(values) { if (values.containsKey(key)) values[key] as String? else defValue }
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(values) { @Suppress("UNCHECKED_CAST") (values[key] as Set<String>?)?.toMutableSet() ?: defValues }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(values) { values[key] as Int? ?: defValue }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(values) { values[key] as Long? ?: defValue }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(values) { values[key] as Float? ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(values) { values[key] as Boolean? ?: defValue }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor { changes[requireNotNull(key)] = value; return this }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean = synchronized(values) {
                if (clear) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                true
            }
            override fun apply() { commit() }
        }
    }
    private class Fixture {
        val state = MemoryPreferences()
        val usage = MemoryPreferences()
        fun store() = StateStore(state, usage, "unused-no-keystore-test")
        fun kid(): StateStore = store().also { it.saveProfile(Profile("viewer-1", "Family", kidMode = true, minuteLimit = 30)) }
    }
    private val movie = Target(Title("tt0088763", "movie", "Back to the Future", year = "1985"))

    @Test fun missingCurrentSnapshotRecoversSavedHistoryAndKidGuard() {
        val f = Fixture(); val store = f.kid()
        store.record(History(movie, 75_000, 6_000_000, 123_456))
        store.saveSettings(Settings(maxHeight = 720)) // last-good copy contains the progress
        f.state.edit().remove("snapshot.current").commit()
        val recovered = f.store()
        assertTrue("A missing primary is corruption, not first launch", recovered.currentProfile().kidMode)
        assertEquals("Family", recovered.currentProfile().name)
        assertEquals(75_000L, recovered.history().single().positionMs)
    }

    @Test fun losingBothSnapshotsRetainsExistingParentalGuard() {
        val f = Fixture(); f.kid()
        f.state.edit().remove("snapshot.current").remove("snapshot.previous").commit()
        val recovered = f.store()
        assertTrue(recovered.currentProfile().kidMode)
        assertEquals(0, recovered.currentProfile().minuteLimit)
        assertNotNull(Policies.restriction(recovered.currentProfile(), recovered.usage(), movie))
    }

    @Test fun missingUsagePrimaryRecoversDailyAllowanceInsteadOfResettingIt() {
        val f = Fixture(); val store = f.kid()
        store.recordWatched(movie, 60_000, 6_000_000)
        store.recordWatched(movie, 30_000, 6_000_000)
        f.usage.edit().remove("viewer-1.current").commit()
        assertEquals("A lost current usage entry must recover its last-good value", 60_000L, f.store().usage().watchedMs)
    }

    @Test fun corruptBackupWithMissingPrimaryDoesNotReplenishAllowance() {
        val f = Fixture(); f.kid()
        f.usage.edit().putString("viewer-1.previous", "broken").remove("viewer-1.current").commit()
        val store = f.store()
        assertNotNull(Policies.restriction(store.currentProfile(), store.usage(), movie))
    }

    @Test fun untouchedFirstLaunchStillStartsWithUsableAdultProfiles() {
        val f = Fixture(); val store = f.store()
        assertFalse(store.currentProfile().kidMode)
        assertEquals(listOf("viewer-1", "viewer-2"), store.profiles().map { it.id })
        assertEquals(0L, store.usage().watchedMs)
        assertTrue(store.history().isEmpty())
    }

    @Test fun concurrentStoresPreserveDistinctBookmarksAndWatchlistEntries() {
        val f = Fixture(); val pool = Executors.newFixedThreadPool(12)
        try {
            val tasks = (0 until 72).map { index -> pool.submit {
                val target = Target(Title("tt${1000000 + index}", "movie", "Fixture $index", year = "2020"))
                f.store().record(History(target, index * 1000L, 6_000_000, index.toLong() + 1))
                f.store().toggleWatchlist(target.title)
            } }
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(72, f.store().history().size)
            assertEquals(72, f.store().watchlist().size)
            assertEquals((0 until 72).map { "tt${1000000 + it}" }.toSet(), f.store().watchlist().map { it.id }.toSet())
            f.store().selectProfile("viewer-2")
            assertTrue(f.store().history().isEmpty())
            assertTrue(f.store().watchlist().isEmpty())
            f.store().selectProfile("viewer-1")
            assertEquals(72, f.store().history().size)
        } finally { pool.shutdownNow() }
    }
}
