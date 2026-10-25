package app.torboxtv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.torboxtv.core.Profile
import app.torboxtv.core.Settings
import app.torboxtv.core.Title
import app.torboxtv.data.StateStore
import app.torboxtv.data.UpdateClient
import app.torboxtv.data.UpdateChannel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import app.torboxtv.ui.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Actual native views driven by remote key events, plus a deterministic
 * same-main-thread lifecycle interruption before metadata can complete. Local fixture
 * state bypasses credential verification solely to test navigation/settings;
 * these tests neither assert live TorBox authentication nor play real media.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSmokeTest {
    @get:Rule
    val activity = ActivityTestRule(MainActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val store: StateStore get() = StateStore(context)
    private val sidebar = listOf("Home", "Movies", "Shows", "Search", "My List", "Sports", "Profiles", "Settings")

    @Before fun resetLocalState() {
        assertTrue(context.getSharedPreferences("torbox_tv_state_v1", Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue(context.getSharedPreferences("torbox_tv_usage_v1", Context.MODE_PRIVATE).edit().clear().commit())
        assertTrue(context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @After fun close() { activity.finishActivity(); resetLocalState() }

    private fun launch(configured: Boolean = false) {
        if (configured) store.saveKey("instrumentation-local-placeholder-not-a-real-api-key")
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
        assertNotNull("The app must have a Fire TV/Android TV launcher intent", intent)
        activity.launchActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Native screen must appear within five seconds", device.wait(Until.hasObject(By.pkg(context.packageName)), 5_000))
    }

    private fun key(code: Int) {
        assertTrue("Remote key injection failed", device.pressKeyCode(code))
        instrumentation.waitForIdleSync()
    }

    private fun requireText(text: String, timeout: Long = 3_000) {
        assertTrue("Missing expected screen text: $text", device.wait(Until.hasObject(By.text(text)), timeout))
    }

    private fun selectSidebar(name: String) {
        val index = sidebar.indexOf(name)
        require(index >= 0)
        // Reaching the rail with Left and walking from Home tests D-pad focus;
        // no UiObject.click(), touch injection or programmatic requestFocus().
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(8) {
            if (device.findObject(By.desc("Home"))?.isFocused == true) return@repeat
            key(KeyEvent.KEYCODE_DPAD_UP)
        }
        assertTrue("Home sidebar control must be reachable with the remote", device.findObject(By.desc("Home"))?.isFocused == true)
        repeat(index) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue("$name sidebar control must receive remote focus", device.findObject(By.desc(name))?.isFocused == true)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test fun launcherSetupAndInvalidKeyAreRemoteOperable() {
        launch()
        requireText("Welcome to TorBox TV")
        assertTrue("First-run API-key field must be accessible", device.wait(Until.hasObject(By.desc("TorBox API key")), 3_000))
        assertTrue("Unconfigured launch must offer focused Sports without credentials", device.findObject(By.desc("Watch Sports without TorBox"))?.isFocused == true)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Movie account setup must remain reachable", device.findObject(By.desc("TorBox API key"))?.isFocused == true)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Connect must be reachable without touch", device.findObject(By.desc("Connect TorBox"))?.isFocused == true)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Enter a valid TorBox API key.")
        assertNull("Empty input must not persist an API key", store.key())
        assertTrue("Invalid setup must return focus to input", device.findObject(By.desc("TorBox API key"))?.isFocused == true)
        key(KeyEvent.KEYCODE_BACK)
        assertTrue("Back from unconfigured setup must exit rather than trap focus", activity.activity.isFinishing)
    }

    @Test fun sportsCatalogOpensAndReturnsWithoutTorBoxCredentials() {
        launch()
        assertNull(store.key())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Live channels")
        requireText("All sports")
        requireText("All providers")
        assertTrue("Expanded sports search must be present", device.hasObject(By.desc("Search sports, channels and events")))
        assertNull("Opening public Sports must never create a TorBox credential", store.key())
        key(KeyEvent.KEYCODE_BACK)
        requireText("Welcome to TorBox TV")
        assertNull(store.key())
    }

    @Test fun unconfiguredKidProfileStillCannotOpenSports() {
        store.saveProfile(Profile("viewer-1", "Kids", kidMode = true))
        launch()
        assertFalse("Kid Mode setup must not offer the Sports bypass", device.hasObject(By.desc("Watch Sports without TorBox")))
        selectSidebar("Sports")
        requireText("Switch to an adult profile to open Sports.")
        assertTrue(store.currentProfile().kidMode)
        assertNull(store.key())
    }

    @Test fun nativeMenusAndBackRemainUsableWhileCatalogLoads() {
        launch(configured = true)
        requireText("Welcome, Viewer 1")
        selectSidebar("My List")
        requireText("Your list is empty. Open a title and choose Add to My List.")
        selectSidebar("Search")
        requireText("Find movies and shows by name")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Search input must be reachable with Right", device.findObject(By.desc("Movie or show title"))?.isFocused == true)
        key(KeyEvent.KEYCODE_BACK)
        requireText("Welcome, Viewer 1")
        selectSidebar("Movies")
        requireText("Find your next favorite")
        selectSidebar("Shows")
        requireText("TV Shows")
        selectSidebar("Profiles")
        requireText("Each viewer has separate progress, My List, and playback preferences")
        selectSidebar("Settings")
        requireText("Preferences for Viewer 1")
        key(KeyEvent.KEYCODE_BACK)
        requireText("Welcome, Viewer 1")
    }

    @Test fun qualitySettingsDialogAndPersistenceWorkWithRemote() {
        store.saveSettings(Settings(maxHeight = 1080, rewindSeconds = 20))
        launch(configured = true)
        selectSidebar("Settings")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("First settings item must receive focus", device.findObject(By.desc("Playback quality    ·    Up to 1080p"))?.isFocused == true)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Playback quality")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("Remote selection must persist 4K preference", 2160, store.settings().maxHeight)
        assertEquals("Changing quality must preserve unrelated rewind setting", 20, store.settings().rewindSeconds)
        activity.finishActivity()
        launch()
        selectSidebar("Settings")
        requireText("Playback quality    ·    Up to 4K")
    }

    @Test fun kidProfileCannotOpenSettingsWithoutParentPin() {
        store.saveProfile(Profile("viewer-1", "Family", kidMode = true))
        launch(configured = true)
        requireText("Welcome, Family")
        selectSidebar("Settings")
        requireText("Parent PIN required")
        requireText("A parent must set up a PIN before changing family controls.")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Welcome, Family")
        assertTrue("Denied settings access must not disable Kid Mode", store.currentProfile().kidMode)
    }

    @Test fun storedMyListIsRenderedByNativeViews() {
        store.toggleWatchlist(Title("tt0088763", "movie", "Back to the Future", year = "1985", genres = listOf("Adventure", "Comedy")))
        launch(configured = true)
        selectSidebar("My List")
        requireText("Back to the Future")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("Saved title card must receive remote focus", device.findObject(By.desc("Back to the Future"))?.isFocused == true)
        assertEquals("Navigation must preserve saved local state", "tt0088763", store.watchlist().single().id)
    }

    @Test fun interruptedDetailsReloadsInsteadOfRenderingIncompleteSeries() {
        val title = Title("tt0000001", "series", "Lifecycle fixture", year = "2020")
        store.toggleWatchlist(title)
        launch(configured = true)
        selectSidebar("My List")
        requireText(title.name)
        instrumentation.runOnMainSync {
            val current = activity.activity
            val root = current.window.decorView
            val card = root.findViewWithTag<View>("title:${title.id}")
            assertNotNull(card)
            // The click enqueues metadata on Dispatchers.Main. Stop and resume
            // before yielding the thread, so no network response can win this race.
            assertTrue(card.performClick())
            instrumentation.callActivityOnPause(current)
            instrumentation.callActivityOnStop(current)
            instrumentation.callActivityOnRestart(current)
            instrumentation.callActivityOnStart(current)
            instrumentation.callActivityOnResume(current)
            fun hasText(view: View, value: String): Boolean {
                if (view is TextView && view.text.toString() == value) return true
                return view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), value) }
            }
            try {
                assertTrue("Returning during title loading must restart verified metadata loading",
                    hasText(root, "Loading title details…"))
                assertFalse("A pending series must not become a terminal empty episode list",
                    hasText(root, "Episode information is unavailable."))
            } finally {
                // Cancel the replacement request before the dispatcher is released.
                instrumentation.callActivityOnPause(current)
                instrumentation.callActivityOnStop(current)
            }
        }
        assertEquals("Lifecycle recovery must preserve the saved title", title, store.watchlist().single())
    }

    @Test fun homeLoadingHasBoundedOutcomeAndNativeScreensAreCaptured() {
        launch(configured = true)
        requireText("Welcome, Viewer 1")
        assertTrue("Collection loading must reach results, empty state or error within twenty seconds",
            device.wait(Until.gone(By.text("Loading titles…")), 20_000))
        assertFalse("Featured artwork must have a terminal loading outcome", device.hasObject(By.text("Finding something worth watching…")))
        val screenshots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue("Native Home screenshot must be written", device.takeScreenshot(File(screenshots, "home.png")))
        selectSidebar("Settings")
        requireText("Preferences for Viewer 1")
        assertTrue("Native Settings screenshot must be written", device.takeScreenshot(File(screenshots, "settings.png")))
        selectSidebar("Search")
        requireText("Find movies and shows by name")
        assertTrue("Native Search screenshot must be written", device.takeScreenshot(File(screenshots, "search.png")))
    }

    private fun focusSetting(prefix: String) {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        for (step in 0 until 30) {
            if (device.findObject(By.descStartsWith(prefix))?.isFocused == true) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        fail("Settings item must be reachable with the remote: $prefix")
    }

    @Test fun updateCheckIsRemoteOperableWithoutTorBoxAndNeverDownloadsWithoutConsent() {
        context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
        launch()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals(UpdateChannel.MANIFEST, chain.request().url.toString())
            assertNull(chain.request().header("Authorization"))
            calls.incrementAndGet()
            val manifest = JSONObject().put("schema", 1).put("package", context.packageName)
                .put("versionCode", 100).put("versionName", "0.2.5")
                .put("apkUrl", UpdateChannel.BASE + "releases/TorBox-TV-0.2.5-test.apk")
                .put("sha256", "a".repeat(64)).put("bytes", 100).put("notes", "Update fixture")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(manifest.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            instrumentation.runOnMainSync {
                val ui = MainActivity::class.java.getDeclaredField("updates").apply { isAccessible = true }.get(activity.activity)
                ui.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(ui, UpdateClient(http))
            }
            selectSidebar("Settings")
            focusSetting("Check for updates")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            requireText("TorBox TV 0.2.5 is available", 5000)
            assertTrue(device.hasObject(By.text(java.util.regex.Pattern.compile("Download update",java.util.regex.Pattern.CASE_INSENSITIVE))))
            assertTrue(device.hasObject(By.text(java.util.regex.Pattern.compile("Later",java.util.regex.Pattern.CASE_INSENSITIVE))))
            key(KeyEvent.KEYCODE_BACK)
            assertEquals("Only metadata may be fetched before consent", 1, calls.get())
            assertNull(store.key())
        } finally { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun automaticUpdatePreferencePersistsWithoutChangingPlaybackSettings() {
        context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE).edit().clear().commit()
        store.saveSettings(Settings(maxHeight = 1080, rewindSeconds = 20))
        launch()
        selectSidebar("Settings")
        focusSetting("Automatic update checks")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Automatic update checks    ·    Off")
        activity.finishActivity()
        launch()
        selectSidebar("Settings")
        focusSetting("Automatic update checks")
        requireText("Automatic update checks    ·    Off")
        assertEquals(1080, store.settings().maxHeight)
        assertEquals(20, store.settings().rewindSeconds)
        assertNull(store.key())
        context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun phoneRemotePairingIsReachableWithTheRemoteWithoutTorBox() {
        launch()
        selectSidebar("Settings")
        focusSetting("Phone remote")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        requireText("Use your phone as a remote")
        assertTrue(device.hasObject(By.desc("Phone remote pairing QR code")))
        assertNull(store.key())
        key(KeyEvent.KEYCODE_BACK)
    }
}
