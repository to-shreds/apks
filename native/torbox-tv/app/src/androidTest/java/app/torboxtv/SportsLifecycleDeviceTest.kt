package app.torboxtv

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.Button
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.torboxtv.data.SportsClient
import app.torboxtv.data.SportsItem
import app.torboxtv.data.SportsPolicy
import app.torboxtv.ui.SportsPlayerActivity
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity/ExoPlayer controls with an inert, cancellable transport. No provider is contacted. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SportsLifecycleDeviceTest {
    @get:Rule val activity = ActivityTestRule(SportsPlayerActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as Application
    private val device get() = UiDevice.getInstance(instrumentation)
    private val shuttingDown = AtomicBoolean(false)
    private val calls = mutableListOf<Call>()
    private val metadataCalls = mutableListOf<Call>()
    private val metadataGate = AtomicBoolean(false)
    private val metadataEntered = CountDownLatch(1)
    private lateinit var channel: SportsItem
    private lateinit var media: OkHttpClient
    private lateinit var metadata: OkHttpClient
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreStarted(owner: Activity) {
            if (owner is SportsPlayerActivity) {
                field("media").set(owner, media)
                field("client").set(owner, SportsClient(metadata))
            }
        }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityResumed(owner: Activity) = Unit
        override fun onActivityPaused(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(owner: Activity) = Unit
    }

    private fun field(name: String) = SportsPlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch (failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw it }
        return result.get()
    }
    private fun player() = field("player").get(activity.activity) as? ExoPlayer
    private fun waitFor(copy: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) { if (ui(condition)) return; Thread.sleep(20) }
        fail(copy)
    }
    private fun button(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) button(root.getChildAt(index), label)?.let { return it }
        return null
    }
    private fun press(label: String) = ui {
        val target = button(activity.activity.window.decorView, label)
        assertNotNull("Missing actual player control: $label", target)
        assertTrue(target!!.performClick())
    }

    @Before fun setup() {
        for (name in listOf("torbox_tv_state_v1", "torbox_tv_usage_v1", "sports-provider", "sports-resume")) {
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        shuttingDown.set(false)
        media = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(calls) { calls += chain.call() }
            // Preserve the actual player and loading lifecycle without downloading any video.
            while (!shuttingDown.get() && !chain.call().isCanceled()) Thread.sleep(2)
            throw IOException("Neutral sports fixture cancelled")
        }.build()
        metadata = OkHttpClient.Builder().addInterceptor { chain ->
            check(chain.request().url.host == "api-player.redbull.com")
            synchronized(metadataCalls) { metadataCalls += chain.call() }
            metadataEntered.countDown()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!metadataGate.get() && !shuttingDown.get() && !chain.call().isCanceled() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(2)
            if (!metadataGate.get() || chain.call().isCanceled()) throw IOException("Delayed metadata fixture cancelled")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("{\"videoUrl\":\"https://play.redbull.com/replay-fixture.m3u8\"}".toResponseBody()).build()
        }.build()
        channel = SportsClient().catalog(context).first { it.kind == "live" && it.sources.size > 1 && it.sources.all { source -> source.type == "hls" } }
        application.registerActivityLifecycleCallbacks(callbacks)
        activity.launchActivity(Intent(context, SportsPlayerActivity::class.java).putExtra("sportsId", channel.id))
        waitFor("Sports player did not open") { player() != null }
        assertNull("Sports must not create a TorBox key", app.torboxtv.data.StateStore(context).key())
    }

    @After fun cleanup() {
        activity.finishActivity()
        application.unregisterActivityLifecycleCallbacks(callbacks)
        shuttingDown.set(true)
        metadataGate.set(true)
        synchronized(calls) { calls.forEach { it.cancel() } }
        synchronized(metadataCalls) { metadataCalls.forEach { it.cancel() } }
        media.dispatcher.executorService.shutdown()
        media.connectionPool.evictAll()
        metadata.dispatcher.executorService.shutdown()
        metadata.connectionPool.evictAll()
        assertTrue("All observed media destinations must remain approved", synchronized(calls) { calls.all { SportsPolicy.validMedia(it.request().url.toString()) } })
    }

    private fun backgroundAndReturn() {
        val previous = ui { player() }
        assertTrue(device.pressHome())
        waitFor("Home must release the Sports player") { player() == null && !field("foreground").getBoolean(activity.activity) }
        context.startActivity(Intent(context, SportsPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        waitFor("Returning must create a fresh paused player") { player() != null && player() !== previous && field("foreground").getBoolean(activity.activity) }
        ui { assertFalse("Returning from Home must remain paused", player()!!.playWhenReady) }
    }

    @Test fun explicitRetryAfterHomeRequestsPlayback() {
        backgroundAndReturn()
        val paused = ui { player() }
        press("Retry")
        waitFor("Retry did not replace the player") { player() != null && player() !== paused }
        ui { assertTrue("An explicit Retry must play after the lifecycle paused playback", player()!!.playWhenReady) }
    }

    @Test fun sourceChangesPreserveTheCurrentPlayPauseIntentAfterHome() {
        backgroundAndReturn()
        fun choose(index: Int, shouldPlay: Boolean) {
            ui { if (shouldPlay) player()!!.play() else player()!!.pause() }
            val previous = ui { player() }
            press("Source")
            assertTrue(device.wait(Until.hasObject(By.text(channel.sources[index].label)), 3000))
            device.findObject(By.text(channel.sources[index].label)).click()
            waitFor("Source choice did not replace the player") { player() != null && player() !== previous }
            ui { assertEquals("Changing source must preserve play/pause intent", shouldPlay, player()!!.playWhenReady) }
        }
        choose(1, true)
        choose(0, false)
        choose(1, true)
    }

    private fun pendingReplay() {
        activity.finishActivity()
        val replay = SportsClient().catalog(context).first { it.kind == "replay" && it.sources.all { source -> source.type == "redbull" } }
        activity.launchActivity(Intent(context, SportsPlayerActivity::class.java).putExtra("sportsId", replay.id))
        assertTrue("Replay must reach its delayed public metadata request", metadataEntered.await(5, TimeUnit.SECONDS))
        ui { assertNull("There must be no player yet while metadata is pending", player()) }
    }
    private fun remote(code: Int) = ui {
        activity.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    private fun completeReplay() {
        metadataGate.set(true)
        waitFor("Replay metadata did not create the player") { player() != null }
    }

    @Test fun remotePauseDuringReplayResolutionPreventsLateAutoplay() {
        pendingReplay()
        remote(KeyEvent.KEYCODE_MEDIA_PAUSE)
        completeReplay()
        ui { assertFalse("Late metadata must honor the earlier remote pause", player()!!.playWhenReady) }
    }

    @Test fun toggleDuringReplayResolutionUsesRequestedPlayIntent() {
        pendingReplay()
        remote(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        completeReplay()
        ui { assertFalse("Toggle during opening must pause its pending autoplay request", player()!!.playWhenReady) }
        remote(KeyEvent.KEYCODE_MEDIA_PLAY)
        ui { assertTrue("An explicit Play must still resume the prepared replay", player()!!.playWhenReady) }
    }

    @Test fun screenOffDuringReplayResolutionPreventsLateAutoplay() {
        pendingReplay()
        ui { (field("interruptionReceiver").get(activity.activity) as BroadcastReceiver).onReceive(context, Intent(Intent.ACTION_SCREEN_OFF)) }
        completeReplay()
        ui { assertFalse("A late replay response must not override screen-off intent", player()!!.playWhenReady) }
    }
}
