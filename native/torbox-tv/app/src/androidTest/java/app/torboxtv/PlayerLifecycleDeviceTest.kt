package app.torboxtv

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.UiDevice
import app.torboxtv.core.Source
import app.torboxtv.core.Choice
import app.torboxtv.data.JsonHttp
import app.torboxtv.data.SourceClient
import app.torboxtv.data.TorBoxClient
import app.torboxtv.ui.PlayerActivity
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity, Media3, remote events and lifecycle; all API/media bytes are local fixtures.
 * Reflection installs test transport before onStart and the media factory after onStart.
 * No production test endpoint, host exception, credential or signing change is introduced.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleDeviceTest {
    @get:Rule val activityRule = ActivityTestRule(PlayerActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as Application
    private val device get() = UiDevice.getInstance(instrumentation)
    private val source = Source("a".repeat(40), "Jumanji.1995.720p.H264.AAC.mp4", "local lifecycle fixture", 1_000_000, 720)
    private val item get() = JSONObject().put("id", 9).put("hash", source.hash)
        .put("download_finished", true).put("download_present", true)
        .put("files", JSONArray().put(JSONObject().put("id", 77).put("name", source.name).put("size", source.size)))
    private var current: PlayerActivity? = null
    private var gate = CountDownLatch(1)
    private var linkEntered = CountDownLatch(1)
    private val linkCalls = AtomicInteger()
    private val unexpectedRequests = AtomicInteger()
    private lateinit var http: OkHttpClient
    private lateinit var bytes: ByteArray
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreStarted(activity: Activity) {
            if (activity !is PlayerActivity) return
            current = activity
            set(activity, "sourceClient", SourceClient(providerTasks = {
                listOf<suspend () -> List<Source>?>({ listOf(source) })
            }))
            set(activity, "torbox", TorBoxClient({ "synthetic-instrumentation-key-no-account" }, http = JsonHttp(http)))
        }
        override fun onActivityPostStarted(activity: Activity) {
            if (activity !is PlayerActivity) return
            val native = get<ExoPlayer>(activity, "player")
            // Keep the real app's player/listeners/native view and decoder. Only its
            // transport is replaced with the original synthetic video in the test APK.
            field(native, "mediaSourceFactory").set(native,
                DefaultMediaSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) }))
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before fun setup() {
        for (name in listOf("torbox_tv_state_v1", "torbox_tv_usage_v1"))
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        bytes = instrumentation.context.assets.open("player-fixture.mp4").use { it.readBytes() }
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "api.torbox.app")
            val data: Any = when (request.url.pathSegments.last()) {
                "checkcached" -> JSONObject().put(source.hash, JSONObject().put("hash", source.hash))
                "mylist" -> if (request.url.queryParameter("id") != null) item else JSONArray().put(item)
                "requestdl" -> {
                    linkCalls.incrementAndGet(); linkEntered.countDown()
                    check(gate.await(12, TimeUnit.SECONDS)) { "Fixture link gate timed out" }
                    "https://n1.tb-cdn.io/fixture.mp4"
                }
                else -> { unexpectedRequests.incrementAndGet(); error("Unexpected fixture API path") }
            }
            if (request.method != "GET") unexpectedRequests.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(JSONObject().put("success", true).put("data", data).toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    @After fun cleanup() {
        gate.countDown()
        current?.let { onMain { it.finish() } }
        activityRule.finishActivity()
        app.unregisterActivityLifecycleCallbacks(callbacks)
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        assertEquals("No preparation POST or unexpected endpoint is permitted", 0, unexpectedRequests.get())
    }

    private fun launch() {
        val title = JSONObject().put("id", "tt0113497").put("type", "movie").put("name", "Jumanji").put("year", "1995")
        activityRule.launchActivity(Intent(context, PlayerActivity::class.java)
            .putExtra("target", JSONObject().put("title", title).toString()))
        assertTrue("Real Activity must reach its delayed signed-link request", linkEntered.await(8, TimeUnit.SECONDS))
    }

    private fun player(): ExoPlayer = get(current!!, "player")
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, timeout: Long = 10_000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            var matches = false
            onMain { matches = predicate() }
            if (matches) return
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < until)
        fail(message)
    }
    private fun openFixture() { gate.countDown(); await("Synthetic Media3 video must become ready") { player().playbackState == Player.STATE_READY } }
    private fun remote(code: Int) = onMain {
        current!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        current!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test fun remotePauseWhileLinkIsPendingMustSurviveItsCompletion() {
        launch()
        remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
        openFixture()
        onMain { assertFalse("Late link completion must not override the remote pause", player().playWhenReady) }
    }

    @Test fun screenOffReceiverWhileLinkIsPendingMustPreventAutoplay() {
        launch()
        onMain { get<BroadcastReceiver>(current!!, "interruptionReceiver").onReceive(context, Intent(Intent.ACTION_SCREEN_OFF)) }
        openFixture()
        onMain { assertFalse("Screen-off intent must survive delayed media preparation", player().playWhenReady) }
    }

    @Test fun toggleWhileOpeningMustPauseTheRequestedPlayback() {
        launch()
        remote(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        openFixture()
        onMain { assertFalse("Toggle must pause a pending start even though isPlaying is still false", player().playWhenReady) }
    }

    @Test fun nativePauseUpdatesRecoveryIntentAndPlaybackCanResume() {
        launch(); openFixture()
        await("Fixture video must actually play") { player().isPlaying && player().currentPosition > 150 }
        onMain { player().pause() }
        instrumentation.waitForIdleSync()
        onMain { assertFalse("PlayerView/MediaSession pause must update recovery intent", get<Boolean>(current!!, "desiredPlay")) }
        remote(KeyEvent.KEYCODE_MEDIA_PLAY)
        await("Remote play must resume the same native video") { player().isPlaying }
        onMain { assertTrue(get(current!!, "desiredPlay")) }
    }

    @Test fun terminalFailureMustNotRestartFromAnOldStartupDeadline() {
        launch(); openFixture()
        await("Fixture video must actually play") { player().isPlaying }
        val before = linkCalls.get()
        onMain {
            set(current!!, "openingElapsed", SystemClock.elapsedRealtime() - 30_000L)
            method(current!!, "fail", String::class.java).invoke(current, "Synthetic terminal failure")
        }
        SystemClock.sleep(1_700)
        onMain {
            assertEquals("Failed playback must stay stopped until an explicit action", "Synthetic terminal failure", get<TextView>(current!!, "stageLabel").text.toString())
            assertFalse(player().playWhenReady)
        }
        assertEquals("An expired timer from the old source must not fetch a new signed link", before, linkCalls.get())
    }

    @Test fun fortyRapidVersionReopensKeepPositionAndHonorTheLastPause() {
        launch(); openFixture()
        await("Fixture video must actually advance") { player().isPlaying && player().currentPosition > 150 }
        onMain {
            player().seekTo(45_000)
            val candidate = get<Choice>(current!!, "choice")
            val open = method(current!!, "openChoice", Choice::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            repeat(40) { open.invoke(current, candidate, true, true) }
            current!!.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        await("Only the final version request must settle into ready playback") { player().playbackState == Player.STATE_READY }
        onMain {
            assertFalse("Earlier autoplay requests must not win after the final pause", player().playWhenReady)
            assertTrue("Rapid reopen must preserve the current position", kotlin.math.abs(player().currentPosition - 45_000) < 500)
        }
    }

    @Test fun homeDuringPendingLinkCancelsItAndReturnStaysPaused() {
        launch()
        val original = current!!
        assertTrue(device.pressHome())
        await("Home must stop and release the native player") { get<Boolean>(original, "stopped") && get<ExoPlayer?>(original, "player") == null }
        gate.countDown()
        // ActivityTestRule's launch intent carries task-clearing flags. Reusing
        // it would destroy this task and explicitly start a new playback session.
        // Restore the existing task just as selecting the app in Recents does.
        context.startActivity(Intent(original.intent).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        await("Returning must restore the existing Activity") { current === original && !get<Boolean>(original, "stopped") }
        await("Returning must prepare the current media") { current != null && !get<Boolean>(current!!, "stopped") && get<ExoPlayer?>(current!!, "player")?.playbackState == Player.STATE_READY }
        onMain {
            assertSame("Home return must restore the existing Activity", original, current)
            assertFalse("Returning from Home must never autoplay an obsolete start", player().playWhenReady)
        }
    }

    @Test fun pairedPhoneCanPauseAnOpeningVideoAndResumeTheNativePlayer() {
        launch()
        val remote=(context.applicationContext as app.torboxtv.ui.TorBoxApplication).phone
        try {
            onMain { remote.showPairing(current!!) }
            var link:String?=null;onMain{link=remote.pairingUrl}
            assertNotNull(link)
            val url=java.net.URI(link!!)
            fun send(body:String){
                java.net.Socket(url.host,url.port).use{socket->
                    socket.soTimeout=4000
                    val bytes=body.toByteArray()
                    val header="POST /api HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nOrigin: http://${url.host}:${url.port}\r\nX-TorBox-Remote: ${url.fragment}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray());socket.getOutputStream().write(bytes)
                    assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                }
            }
            send("{\"action\":\"hello\"}");send("{\"action\":\"key\",\"key\":\"PAUSE\"}")
            openFixture()
            onMain{assertFalse("Paired phone pause must survive delayed playback preparation",player().playWhenReady)}
            send("{\"action\":\"key\",\"key\":\"PLAY\"}")
            await("Phone Play must resume actual Media3 playback"){player().isPlaying}
        } finally {onMain{remote.disconnect()}}
    }

    private fun field(value: Any, name: String): Field {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing fixture field $name")
    }
    @Suppress("UNCHECKED_CAST") private fun <T> get(value: Any, name: String): T = field(value, name).get(value) as T
    private fun set(value: Any, name: String, item: Any?) = field(value, name).set(value, item)
    private fun method(value: Any, name: String, vararg types: Class<*>) =
        value.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
}
