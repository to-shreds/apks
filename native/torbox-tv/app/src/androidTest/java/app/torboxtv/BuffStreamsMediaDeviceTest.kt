package app.torboxtv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import app.torboxtv.data.BuffStreamsPlaybackScope
import app.torboxtv.data.BuffStreamsPolicy
import app.torboxtv.data.SportsDestinationRejected
import app.torboxtv.data.SportsMediaGuard
import app.torboxtv.data.SportsPolicy
import app.torboxtv.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual Media3 HLS parsing, TS extraction, audio decoding and visible video rendering.
 * Only transport is replaced: provider-shaped HTTPS URLs return synthetic local fixture bytes.
 * This deliberately does not claim public DNS/TLS, provider access or physical Firestick success.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BuffStreamsMediaDeviceTest {
    @get:Rule val host = ActivityTestRule(MainActivity::class.java, false, false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private val clients = mutableListOf<OkHttpClient>()
    private lateinit var bytes: ByteArray
    private var updatePreferenceExisted = false
    private var updatePreferenceValue = true
    private val game = "58500"
    private val master = "https://chatgpt.hereisman.net/playlist/$game/load-playlist"

    private fun <T> ui(block: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { value.set(block()) } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
        return value.get()
    }
    private fun waitFor(copy: String, failure: AtomicReference<PlaybackException>, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("$copy: ${it.errorCodeName}", it) }
            if (ui(condition)) return
            Thread.sleep(25)
        }
        fail(copy)
    }
    @Before fun setup() {
        for (name in listOf("torbox_tv_state_v1", "torbox_tv_usage_v1"))
            assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        val updates = context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE)
        updatePreferenceExisted = updates.contains("automatic")
        updatePreferenceValue = updates.getBoolean("automatic", true)
        assertTrue(updates.edit().putBoolean("automatic", false).commit())
        bytes = instrumentation.context.assets.open("buffstreams-fixture.ts").use { it.readBytes() }
        host.launchActivity(Intent(context, MainActivity::class.java))
    }
    private fun releasePlayer() = ui { view?.player = null; player?.release(); player = null; view = null }
    @After fun cleanup() {
        releasePlayer()
        host.finishActivity()
        clients.forEach { it.dispatcher.cancelAll(); it.dispatcher.executorService.shutdown(); it.connectionPool.evictAll() }
        val edit = context.getSharedPreferences("torbox_tv_updates_v1", Context.MODE_PRIVATE).edit()
        if (updatePreferenceExisted) edit.putBoolean("automatic", updatePreferenceValue) else edit.remove("automatic")
        assertTrue(edit.commit())
    }

    @Test fun providerShapedExtensionlessPlaylistsAndTextTsRenderAcrossHostRotation() {
        // First show that the former global policy rejects the current variant before transport.
        val firstVariant = "https://pl.whywelove2.space/playlist/$game/b.backstore2.space/caxi"
        assertFalse(SportsPolicy.validMedia(firstVariant))
        var legacyReachedTransport = false
        val legacy = OkHttpClient.Builder().addInterceptor(SportsMediaGuard()).addInterceptor {
            legacyReachedTransport = true
            throw AssertionError("Legacy guard must reject this host")
        }.build().also { clients.add(it) }
        try {
            legacy.newCall(okhttp3.Request.Builder().url(firstVariant).header("Referer", BuffStreamsPolicy.embed(game)).build()).execute().close()
            fail("Original policy accepted the changed provider")
        } catch (_: SportsDestinationRejected) { }
        assertFalse(legacyReachedTransport)

        var previousScope: BuffStreamsPlaybackScope? = null
        var previousVariant: String? = null
        for ((playlistHost, segmentHost) in listOf("pl.whywelove2.space" to "b.backstore2.space", "pl.kamfir5.space" to "a.newstore4.space")) {
            val variant = "https://$playlistHost/playlist/$game/$segmentHost/caxi"
            val segment = "https://$segmentHost/scripts/NTg1MDA=/p1_0.txt"
            val scope = BuffStreamsPlaybackScope(game, master)
            assertFalse("An unannounced host must not enter a new playback", scope.allows(variant))
            previousVariant?.let { assertFalse("A new player must not inherit the previous host", scope.allows(it)) }
            val seen = mutableListOf<String>()
            val transport = OkHttpClient.Builder().addInterceptor(SportsMediaGuard(scope)).addInterceptor { chain ->
                val request = chain.request()
                assertNull(request.header("Authorization")); assertNull(request.header("Cookie"))
                assertEquals(BuffStreamsPolicy.embed(game), request.header("Referer"))
                synchronized(seen) { seen.add(request.url.toString()) }
                val (content, type) = when (request.url.toString()) {
                    master -> ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=160x90\n$variant\n".toByteArray() to "application/vnd.apple.mpegurl")
                    variant -> ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:9.021333,\n$segment\n#EXT-X-ENDLIST\n".toByteArray() to "application/vnd.apple.mpegurl")
                    segment -> (bytes to "text/plain")
                    else -> throw AssertionError("Undeclared fixture destination")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic fixture")
                    .body(content.toResponseBody(type.toMediaType())).build()
            }.build().also { clients.add(it) }
            val failure = AtomicReference<PlaybackException>()
            val rendered = AtomicBoolean(false)
            ui {
                view = PlayerView(host.activity).apply { useController = false }
                host.activity.setContentView(view)
                player = ExoPlayer.Builder(host.activity).setMediaSourceFactory(DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(transport).setDefaultRequestProperties(mapOf("Referer" to BuffStreamsPolicy.embed(game), "User-Agent" to "Mozilla/5.0"))))
                    .build().also { native ->
                        view!!.player = native
                        native.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { failure.set(error) }
                            override fun onRenderedFirstFrame() { rendered.set(true) }
                        })
                        native.setMediaItem(MediaItem.Builder().setUri(master).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                        native.prepare(); native.play()
                    }
            }
            waitFor("Scoped HLS must decode audio/video, render a frame and advance", failure) {
                val native = player!!
                native.playbackState == Player.STATE_READY && native.currentPosition >= 1200 && rendered.get() &&
                    native.audioFormat != null && native.videoFormat != null &&
                    native.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } &&
                    native.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            }
            val paused = ui { player!!.pause(); player!!.currentPosition }
            Thread.sleep(300)
            ui { assertFalse(player!!.playWhenReady); assertTrue("Pause must hold its position", player!!.currentPosition <= paused + 250); player!!.play() }
            waitFor("The parsed TS stream must resume", failure) { player!!.currentPosition >= paused + 500 }
            assertTrue(synchronized(seen) { seen.containsAll(listOf(master, variant, segment)) })
            previousScope?.let { assertFalse("Separate game sessions cannot learn each other's rotated host", it.allows(variant)) }
            previousScope = scope
            previousVariant = variant
            releasePlayer()
        }
    }
}
