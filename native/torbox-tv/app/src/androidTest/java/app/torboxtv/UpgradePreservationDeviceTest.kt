package app.torboxtv

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.torboxtv.core.History
import app.torboxtv.core.Profile
import app.torboxtv.core.Settings
import app.torboxtv.core.Target
import app.torboxtv.core.Title
import app.torboxtv.data.StateStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed on original signed 0.2.3, install the signed 0.2.4 APK with -r, then verify.
 * This class uses only APIs present in both versions and only synthetic local state.
 */
@RunWith(AndroidJUnit4::class)
class UpgradePreservationDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val target=Target(Title("tt0088763","movie","Back to the Future",year="1985"))
    private val key="signed-upgrade-fixture-no-real-account"
    @Before fun explicitUpgradeWorkflowOnly(){assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeFixture")=="true")}
    @Test fun seed(){
        assertEquals(6L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
        val store=StateStore(context)
        store.saveKey(key);store.setPin("2468")
        store.saveSettings(Settings(maxHeight=1080,rewindSeconds=20,autoNext=false))
        store.record(History(target,45_000,120_000,123_456))
        store.toggleWatchlist(target.title)
        store.selectProfile("viewer-2");store.saveSettings(Settings(rewindSeconds=5))
        store.selectProfile("viewer-1")
        store.saveProfile(Profile("viewer-1","Upgrade fixture",kidMode=true,minuteLimit=30))
        assertEquals(key,store.key());assertTrue(store.verifyPin("2468"))
    }
    @Test fun verify(){
        assertEquals(7L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
        val store=StateStore(context)
        assertEquals(key,store.key());assertTrue(store.hasPin());assertTrue(store.verifyPin("2468"));assertFalse(store.verifyPin("1234"))
        assertTrue(store.currentProfile().kidMode);assertEquals("Upgrade fixture",store.currentProfile().name);assertEquals(30,store.currentProfile().minuteLimit)
        assertEquals(1080,store.settings().maxHeight);assertEquals(20,store.settings().rewindSeconds);assertFalse(store.settings().autoNext)
        assertEquals(45_000L,store.history().single().positionMs);assertEquals(target,store.history().single().target)
        assertEquals(target.title,store.watchlist().single())
        store.selectProfile("viewer-2");assertTrue(store.history().isEmpty());assertEquals(5,store.settings().rewindSeconds)
        store.selectProfile("viewer-1");assertEquals(45_000L,store.history().single().positionMs)
        assertFalse(context.getSharedPreferences("torbox_tv_state_v1",Context.MODE_PRIVATE).all.values.joinToString().contains(key))
    }
}
