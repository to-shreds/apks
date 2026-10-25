package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import app.torboxtv.core.Choice
import app.torboxtv.core.Episode
import app.torboxtv.core.History
import app.torboxtv.core.PlaybackTiming
import app.torboxtv.core.Policies
import app.torboxtv.core.Source
import app.torboxtv.core.Target
import app.torboxtv.core.Title
import app.torboxtv.core.UserError
import app.torboxtv.data.MediaHostGuard
import app.torboxtv.data.PersistentOperationLedger
import app.torboxtv.data.SourceClient
import app.torboxtv.data.StateStore
import app.torboxtv.data.TorBoxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native fullscreen player. Media bytes go from the verified TorBox CDN to Media3. */
@UnstableApi
class PlayerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: StateStore
    private lateinit var torbox: TorBoxClient
    private lateinit var target: Target
    private lateinit var playerView: PlayerView
    private lateinit var stages: LinearLayout
    private lateinit var stageLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var stageSpinner: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var versionsButton: Button
    private lateinit var trackBar: LinearLayout
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var mediaClient: OkHttpClient? = null
    private var request: Job? = null
    private var alternatives: Job? = null
    private var nextPrefetch: Job? = null
    private var nextWarmedKey: String? = null
    private var nextWarmedAt = 0L
    private var warmedFor: String? = null
    private var startupElapsed = 0L
    private val sourceClient = SourceClient.shared
    private var timer: Job? = null
    private var countdown: Job? = null
    private var countdownDialog: AlertDialog? = null
    private var decisionDialog: AlertDialog? = null
    private val timing = PlaybackTiming()
    private var generation = 0
    private var stopped = true
    private var initialized = false
    private var ready = false
    private var recovering = false
    private var desiredPlay = true
    private var hasOpened = false
    private var savedPosition = 0L
    private var savedDuration = 0L
    private var watchedSinceCheck = 0L
    private var choice: Choice? = null
    private var choices: List<Choice> = emptyList()
    private var sources: List<Source> = emptyList()
    private var preparingSource: Source? = null
    private val attemptedHashes = linkedSetOf<String>()
    private var examinedHashes = java.util.Collections.synchronizedSet(linkedSetOf<String>())
    private var openingElapsed = 0L
    private var bufferingElapsed: Long? = null
    private var lastHistoryElapsed = 0L
    private var lastAdvanceElapsed = 0L
    private var completed = false
    private var progressObserved = false
    private var stillWatchingBlocked = false
    private var stillDialogShowing = false
    private var parentDialogShowing = false
    private var receiverRegistered = false
    private val interruptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val disconnected = intent?.action == AudioManager.ACTION_HDMI_AUDIO_PLUG &&
                intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1) == 0
            if (disconnected || intent?.action == Intent.ACTION_SCREEN_OFF) {
                desiredPlay = false
                player?.pause()
                saveProgress()
                cancelCountdown()
                keepAwake(false)
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = StateStore(this)
        torbox = TorBoxClient({ store.key() }, PersistentOperationLedger(this))
        val decoded = runCatching { decodeTarget(state?.getString("target") ?: intent.getStringExtra(EXTRA_TARGET).orEmpty()) }.getOrNull()
        if (decoded == null) { finish(); return }
        target = decoded
        val previous = store.history().find { it.target.key == target.key }
        savedPosition = if (intent.getBooleanExtra(EXTRA_START_OVER, false)) 0L
            else Policies.resumeMs(previous, store.settings())
        savedDuration = previous?.durationMs ?: 0L
        if (state != null) {
            savedPosition = state.getLong("position", savedPosition)
            savedDuration = state.getLong("duration", savedDuration)
            desiredPlay = state.getBoolean("play", false)
            watchedSinceCheck = state.getLong("watched", 0L)
            stillWatchingBlocked = state.getBoolean("stillBlocked", false)
        }
        makeViews()
        initialized = true
    }

    private fun makeViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            controllerShowTimeoutMs = 4500
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setShowSubtitleButton(false)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowRewindButton(true)
            setShowFastForwardButton(true)
            isFocusable = true
            isFocusableInTouchMode = false
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        trackBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(0xCC11141BL.toInt())
        }
        trackBar.addView(action("Audio") { tracks(C.TRACK_TYPE_AUDIO) })
        trackBar.addView(action("Subtitles") { tracks(C.TRACK_TYPE_TEXT) })
        trackBar.addView(action("Versions") { showVersions() })
        root.addView(trackBar, FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP))
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            trackBar.visibility = if (visibility == View.VISIBLE && ready) View.VISIBLE else View.GONE
        })
        stages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
            setBackgroundColor(0xDD0C1018L.toInt())
        }
        titleLabel = TextView(this).apply {
            text = target.label
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        stages.addView(titleLabel, LinearLayout.LayoutParams(-1, -2))
        stageSpinner = ProgressBar(this)
        stages.addView(stageSpinner, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(24) })
        stageLabel = TextView(this).apply { textSize = 19f; setTextColor(0xFFD2D8E6L.toInt()); gravity = Gravity.CENTER }
        stages.addView(stageLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER }
        retryButton = action("Try again") {
            val pending = preparingSource
            if (pending != null) prepareExplicit(pending, add = false)
            else {
                if (completed) { savedPosition = 0; completed = false; progressObserved = false }
                attemptedHashes.clear(); examinedHashes = java.util.Collections.synchronizedSet(linkedSetOf()); desiredPlay = true; loadSources()
            }
        }
        versionsButton = action("Choose another version") { showVersions() }
        actions.addView(retryButton)
        actions.addView(versionsButton)
        actions.addView(action("Back to title") { finish() })
        stages.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        root.addView(stages, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        stage("Finding a ready version…")
    }

    private fun action(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { click() }
        setOnFocusChangeListener { view, focused ->
            view.scaleX = if (focused) 1.06f else 1f
            view.scaleY = if (focused) 1.06f else 1f
        }
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    override fun onStart() {
        super.onStart()
        if (!initialized) return
        stopped = false
        makePlayer()
        val interrupts = IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(interruptionReceiver, interrupts, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(interruptionReceiver, interrupts)
        receiverRegistered = true
        startTimer()
        // Returning from Home never resumes unexpectedly. The remote Play button resumes.
        if (hasOpened && choice != null) openChoice(choice!!, autoplay = false)
        else loadSources()
    }

    private fun makePlayer() {
        if (player != null) return
        val mediaHttp = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor(MediaHostGuard()).build()
        mediaClient = mediaHttp
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(mediaHttp)))
            .setSeekBackIncrementMs(store.settings().seekSeconds * 1000L)
            .setSeekForwardIncrementMs(store.settings().seekSeconds * 1000L)
            .build().also { p ->
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                p.setHandleAudioBecomingNoisy(true)
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!currentMedia(p)) return
                        if (isPlaying && !mayPlay()) { p.pause(); keepAwake(false); return }
                        if (isPlaying) { progressObserved = true; lastAdvanceElapsed = SystemClock.elapsedRealtime() }
                        keepAwake(isPlaying)
                        timing.reset(p.currentPosition.coerceAtLeast(0), SystemClock.elapsedRealtime())
                        if (!isPlaying) saveProgress()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        // PlayerView and MediaSession can start playback without Activity key events.
                        // Enforce the same restrictions at the native player boundary.
                        if (player !== p || stopped) return
                        if (playWhenReady) {
                            if (!mayPlay()) { p.pause(); desiredPlay = false }
                            else desiredPlay = true
                        } else {
                            desiredPlay = false
                            bufferingElapsed = null
                            cancelCountdown()
                        }
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!currentMedia(p)) return
                        when (playbackState) {
                            Player.STATE_READY -> {
                                ready = true
                                recovering = false
                                bufferingElapsed = null
                                openingElapsed = 0
                                stages.visibility = View.GONE
                                trackBar.visibility = if (playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
                                playerView.requestFocus()
                                warmAfterStart()
                            }
                            Player.STATE_BUFFERING -> {
                                if (ready && desiredPlay && bufferingElapsed == null) bufferingElapsed = SystemClock.elapsedRealtime()
                            }
                            Player.STATE_ENDED -> ended()
                        }
                    }
                    override fun onRenderedFirstFrame() { if (currentMedia(p)) startupMark("first_frame") }
                    override fun onPlayerError(error: PlaybackException) {
                        if (!currentMedia(p)) return
                        // Provider URLs can contain credentials. Do not log raw Media3 failures.
                        recover("This version could not play on this device.")
                    }
                })
            }
        playerView.player = player
        mediaSession = MediaSession.Builder(this, player!!).build()
    }

    private fun stage(message: String, error: Boolean = false) {
        stageLabel.text = message
        stages.visibility = View.VISIBLE
        stageSpinner.visibility = if (error) View.GONE else View.VISIBLE
        retryButton.visibility = if (error) View.VISIBLE else View.GONE
        retryButton.text = when { preparingSource != null -> "Check preparation"; completed -> "Play again"; else -> "Try again" }
        versionsButton.visibility = if (error || sources.isNotEmpty()) View.VISIBLE else View.GONE
        trackBar.visibility = View.GONE
        if (error) versionsButton.requestFocus()
    }

    private fun restriction(): String? {
        val profile = store.currentProfile()
        return Policies.restriction(profile, store.usage(), target)
    }

    private fun mayPlay(): Boolean {
        restriction()?.let { parentGate(it); return false }
        if (stillWatchingBlocked) { stillWatching(); return false }
        return !stopped
    }

    private fun startupMark(stage: String) {
        // Fixed stage labels and durations only. Never log title, source, response or URL.
        if (startupElapsed > 0) android.util.Log.i("TorBoxStartup", "stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - startupElapsed}")
    }

    private fun clearPlaybackDeadlines() {
        openingElapsed = 0
        bufferingElapsed = null
    }

    // Stop an obsolete media request without turning an intended start into a pause.
    // Keeping native playWhenReady in sync also makes MediaSession pause work while
    // discovery or a signed-link request is still pending.
    private fun stopMediaForTransition() {
        clearPlaybackDeadlines()
        player?.stop()
        player?.playWhenReady = desiredPlay
        keepAwake(false)
    }

    private fun currentMedia(p: ExoPlayer): Boolean = player === p && !stopped &&
        p.currentMediaItem?.mediaId == generation.toString()

    private fun loadSources() {
        cancelCountdown()
        if (stopped) return
        val blocked = restriction()
        if (blocked != null) { parentGate(blocked); return }
        alternatives?.cancel(); alternatives = null
        request?.cancel()
        val token = ++generation
        val selectedTarget = target
        val examined = examinedHashes
        val settings = store.settings()
        saveProgress()
        ready = false
        recovering = false
        stopMediaForTransition()
        startupElapsed = SystemClock.elapsedRealtime()
        stage("Finding a ready version…")
        request = scope.launch {
            try {
                var candidate: Choice? = null
                val found = withContext(Dispatchers.IO) { withTimeout(25_000) {
                    sourceClient.find(selectedTarget) { batch ->
                        withContext(Dispatchers.Main.immediate) {
                            if (current(token)) { sources = batch; stage("Checking a ready version…"); startupMark("source_batch") }
                        }
                        val ranked = Policies.rankSources(batch, settings).filter { it.hash !in examined && it.hash !in attemptedHashes }
                        val remainingAttempts = 5 - examined.size
                        if (ranked.isNotEmpty() && remainingAttempts > 0) {
                            candidate = torbox.choices(selectedTarget, ranked, cachedOnly = true, maxChoices = 1,
                                maxAttempts = remainingAttempts, onExamined = { examined.add(it.hash) }).firstOrNull()
                        }
                        candidate != null
                    }
                } }
                if (!current(token)) return@launch
                sources = found
                val first = candidate
                if (first == null) {
                    sourceClient.invalidate(selectedTarget)
                    fail("No ready version could be matched safely. Choose a version to prepare, or try again later.")
                }
                else {
                    choices = listOf(first)
                    startupMark("cached_choice")
                    openChoice(first, desiredPlay)
                }
            } catch (_: TimeoutCancellationException) {
                if (current(token)) fail("Checking ready versions took too long. Try again or choose another version.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(token)) fail((error as? UserError)?.message ?: "Ready versions could not be checked. Try again or choose another version.")
            }
        }
    }

    /** Fallback checks and next-episode metadata cannot delay the first playable frame. */
    private fun warmAfterStart() {
        val first = choice ?: return
        val marker = "${target.key}:${first.source.hash}:$generation"
        if (stopped || warmedFor == marker) return
        warmedFor = marker
        val selectedTarget = target
        val token = generation
        val examined = examinedHashes
        val settings = store.settings()
        alternatives?.cancel()
        alternatives = scope.launch {
            try {
                delay(1000)
                val found = withContext(Dispatchers.IO) { sourceClient.find(selectedTarget) }
                if (!current(token)) return@launch
                sources = found
                val remaining = Policies.rankSources(found, settings).filter { row -> row.hash !in examined && choices.none { it.source.hash == row.hash } }
                val remainingAttempts = 5 - examined.size
                if (remaining.isEmpty() || remainingAttempts <= 0) return@launch
                val more = withContext(Dispatchers.IO) { withTimeout(25_000) { torbox.choices(selectedTarget, remaining,
                    cachedOnly = true, maxChoices = 2, maxAttempts = remainingAttempts,
                    onExamined = { examined.add(it.hash) }) } }
                if (current(token)) choices = (choices + more).distinctBy { "${it.source.hash}:${it.file.id}" }.take(3)
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { /* The playing source remains authoritative if optional fallbacks fail. */ }
        }
    }

    private fun prefetchNextIfClose(p: ExoPlayer) {
        val settings = store.settings()
        if (!p.isPlaying || !settings.autoNext || p.duration <= 0 || p.duration - p.currentPosition > 180000) return
        val next = Policies.nextEpisode(target, System.currentTimeMillis()) ?: return
        val now = SystemClock.elapsedRealtime()
        if (nextWarmedKey == next.key && now - nextWarmedAt < 240000) return
        nextPrefetch?.cancel()
        val refresh = nextWarmedKey == next.key
        nextWarmedKey = next.key
        nextWarmedAt = now
        nextPrefetch = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (refresh) sourceClient.invalidate(next)
                    sourceClient.find(next)
                }
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { }
        }
    }

    private fun current(token: Int) = token == generation && !stopped && !isFinishing

    private fun openChoice(candidate: Choice, autoplay: Boolean = true, manual: Boolean = false) {
        if (stopped) return
        restriction()?.let { parentGate(it); return }
        alternatives?.cancel(); alternatives = null
        if (manual) attemptedHashes.clear()
        if (candidate.source.hash !in attemptedHashes && attemptedHashes.size >= 3) {
            fail("Three versions have been tried. Choose another version or try again.")
            return
        }
        saveProgress()
        attemptedHashes.add(candidate.source.hash)
        choice = candidate
        preparingSource = null
        desiredPlay = autoplay
        ready = false
        recovering = false
        completed = false
        hasOpened = true
        val token = ++generation
        stopMediaForTransition()
        request?.cancel()
        stage(if (savedPosition > 0) "Resuming your video…" else "Opening your video…")
        val selectedTarget = target
        request = scope.launch {
            try {
                val mediaUrl = withContext(Dispatchers.IO) { withTimeout(18_000) { torbox.link(candidate, selectedTarget) } }
                if (!current(token)) return@launch
                startupMark("playback_link")
                val p = player ?: return@launch
                openingElapsed = SystemClock.elapsedRealtime()
                p.setMediaItem(MediaItem.Builder().setMediaId(token.toString()).setUri(mediaUrl).build(), savedPosition.coerceAtLeast(0))
                p.prepare()
                // Pause, HDMI disconnect and screen-off during the request must win.
                p.playWhenReady = desiredPlay
                timing.reset(savedPosition, openingElapsed)
            } catch (_: TimeoutCancellationException) {
                if (current(token)) recover("This version took too long to open. Trying another ready version…")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(token)) {
                    val message = (error as? UserError)?.message
                    val accountProblem = message != null && listOf("API key", "limiting requests", "slow down",
                        "plan does not permit", "account is too large").any { message.contains(it, ignoreCase = true) }
                    if (accountProblem) fail(message!!)
                    else recover(message ?: "This version is no longer available. Trying another ready version…", message)
                }
            }
        }
    }

    private fun recover(reason: String, failureMessage: String? = null) {
        if (stopped || recovering) return
        alternatives?.cancel(); alternatives = null
        val p = player
        if (ready && p != null) savedPosition = p.currentPosition.coerceAtLeast(0)
        saveProgress()
        recovering = true
        ready = false
        stopMediaForTransition()
        val next = choices.firstOrNull { it.source.hash !in attemptedHashes }
        if (next == null && attemptedHashes.size < 3) {
            stage(reason)
            loadSources()
            return
        }
        if (next == null || attemptedHashes.size >= 3) {
            recovering = false
            fail(failureMessage ?: "Playback stopped after ${attemptedHashes.size.coerceAtLeast(1)} version(s). Choose another version or try again.")
            return
        }
        stage(reason)
        openChoice(next, desiredPlay)
    }

    private fun fail(message: String) {
        ++generation
        request?.cancel()
        alternatives?.cancel(); alternatives = null
        desiredPlay = false
        ready = false
        recovering = false
        stopMediaForTransition()
        stage(message, error = true)
        // A simple remote-accessible chooser is always offered on automatic failure.
    }

    private fun showVersions() {
        if (stopped || decisionDialog?.isShowing == true) return
        val readyChoices = choices.distinctBy { "${it.source.hash}:${it.file.id}" }
        val uncached = sources.filter { source -> readyChoices.none { it.source.hash == source.hash } }.distinctBy { it.hash }.take(12)
        if (readyChoices.isEmpty() && uncached.isEmpty()) {
            decisionDialog = AlertDialog.Builder(this).setTitle("No versions available yet")
                .setMessage("Try checking again. Nothing has been downloaded.")
                .setPositiveButton("Check again") { _, _ -> attemptedHashes.clear(); examinedHashes = java.util.Collections.synchronizedSet(linkedSetOf()); desiredPlay = true; loadSources() }
                .setNegativeButton("Back", null).show()
            return
        }
        player?.pause()
        val labels = readyChoices.mapIndexed { index, row -> "${index + 1}. ${row.label}" } +
            uncached.mapIndexed { index, row -> "${readyChoices.size + index + 1}. ${if (row.height > 0) "${row.height}p" else "Video"} · Prepare first" }
        decisionDialog = AlertDialog.Builder(this).setTitle("Choose a version")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index < readyChoices.size) openChoice(readyChoices[index], autoplay = true, manual = true)
                else confirmPrepare(uncached[index - readyChoices.size])
            }.setNegativeButton("Back", null).show()
    }

    private fun confirmPrepare(source: Source) {
        decisionDialog = AlertDialog.Builder(this).setTitle("Prepare this version?")
            .setMessage("This may add and download a video in your TorBox account. It can take time. It happens only when you choose Prepare.")
            .setPositiveButton("Prepare") { _, _ -> prepareExplicit(source) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun prepareExplicit(source: Source, add: Boolean = true) {
        if (stopped) return
        cancelCountdown()
        alternatives?.cancel(); alternatives = null
        restriction()?.let { parentGate(it); return }
        request?.cancel()
        saveProgress()
        preparingSource = source
        val token = ++generation
        val selectedTarget = target
        ready = false
        recovering = false
        desiredPlay = true
        stopMediaForTransition()
        stage(if (add) "Preparing your chosen version…" else "Checking your chosen version…")
        request = scope.launch {
            try {
                val prepared = withTimeout(90_000) {
                    var result = if (add) withContext(Dispatchers.IO) { torbox.prepare(selectedTarget, source) }
                        else withContext(Dispatchers.IO) { torbox.status(selectedTarget, source) }
                    if (result?.cached == true) result else {
                        // This poll exists only after the user has explicitly chosen preparation.
                        // Every status request is read-only; interruption never resends the POST.
                        for (step in 1..12) {
                            if (!current(token)) return@withTimeout null
                            stage("TorBox is preparing this version… ($step of 12 checks)")
                            delay(5000)
                            result = withContext(Dispatchers.IO) { torbox.status(selectedTarget, source) }
                            if (result?.cached == true) break
                        }
                        result
                    }
                }
                if (!current(token)) return@launch
                if (prepared == null || !prepared.cached) {
                    fail("TorBox is preparing this version. You can check again later.")
                    decisionDialog = AlertDialog.Builder(this@PlayerActivity).setTitle("Preparing in TorBox")
                        .setMessage("You can leave this page. No duplicate request will be sent.")
                        .setPositiveButton("Check status") { _, _ -> prepareExplicit(source, add = false) }
                        .setNegativeButton("Back to title") { _, _ -> finish() }.show()
                } else {
                    choices = listOf(prepared) + choices.filter { it.source.hash != source.hash }
                    openChoice(prepared, autoplay = desiredPlay, manual = true)
                }
            } catch (_: TimeoutCancellationException) {
                if (current(token)) fail("The preparation response took too long. Check status later; it will not be repeated automatically.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (current(token)) fail((error as? UserError)?.message ?: "The preparation could not be confirmed. Check again later; an interrupted addition will not be repeated automatically.") }
        }
    }

    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            while (!stopped) {
                delay(1000)
                val p = player ?: continue
                val now = SystemClock.elapsedRealtime()
                if (ready && !completed) prefetchNextIfClose(p)
                val watched = timing.sample(p.currentPosition.coerceAtLeast(0), now, p.isPlaying)
                if (watched > 0) {
                    lastAdvanceElapsed = now
                    watchedSinceCheck += watched
                    store.recordWatched(target, watched, p.duration.takeIf { it > 0 } ?: savedDuration)
                    restriction()?.let { parentGate(it) }
                    val interval = store.settings().stillWatchingMinutes * 60_000L
                    if (interval > 0 && watchedSinceCheck >= interval && decisionDialog?.isShowing != true) stillWatching()
                }
                if (ready && !completed && now - lastHistoryElapsed >= 5000) {
                    saveProgress()
                    lastHistoryElapsed = now
                }
                if (!ready && openingElapsed > 0 && !recovering && now - openingElapsed >= 25_000 && stages.visibility == View.VISIBLE) {
                    openingElapsed = 0
                    recover("This version took too long to start. Trying another ready version…")
                }
                val bufferStart = bufferingElapsed
                if (bufferStart != null && p.playWhenReady && now - bufferStart >= 20_000) {
                    bufferingElapsed = null
                    recover("The connection stalled. Trying another ready version…")
                }
                keepAwake(p.isPlaying && now - lastAdvanceElapsed <= 1500)
            }
        }
    }

    private fun stillWatching() {
        player?.pause()
        desiredPlay = false
        stillWatchingBlocked = true
        if (stillDialogShowing || parentDialogShowing || stopped) return
        decisionDialog?.dismiss()
        stillDialogShowing = true
        decisionDialog = AlertDialog.Builder(this).setTitle("Still watching?")
            .setMessage("Choose Keep watching to continue.")
            .setPositiveButton("Keep watching") { _, _ ->
                watchedSinceCheck = 0
                stillWatchingBlocked = false
                desiredPlay = true
                player?.play()
            }
            .setNegativeButton("Back to title") { _, _ -> finish() }
            .setOnDismissListener { stillDialogShowing = false }.show()
    }

    private fun parentGate(message: String) {
        player?.pause()
        desiredPlay = false
        cancelCountdown()
        // A track/version dialog must never keep playback running past its allowance.
        if (parentDialogShowing || stopped) return
        decisionDialog?.dismiss()
        parentDialogShowing = true
        val pin = EditText(this).apply {
            hint = "Parent PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        decisionDialog = AlertDialog.Builder(this).setTitle("Parent permission needed")
            .setMessage(message).setView(pin).setPositiveButton("Add 30 minutes", null)
            .setNegativeButton("Back to title") { _, _ -> finish() }
            .setOnDismissListener { parentDialogShowing = false }.create().also { dialog ->
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (store.verifyPin(pin.text.toString())) {
                            store.grantMinutes(30)
                            dialog.dismiss()
                            if (restriction() == null) {
                                if (ready) { desiredPlay = true; player?.play() }
                                else { attemptedHashes.clear(); examinedHashes = java.util.Collections.synchronizedSet(linkedSetOf()); desiredPlay = true; loadSources() }
                            } else fail("This title needs a parent to change Kid Mode or its viewing limits in Settings.")
                        } else { pin.error = "That PIN did not match"; pin.text.clear() }
                    }
                }
                dialog.show()
            }
    }

    private fun saveProgress() {
        if (!initialized || !hasOpened) return
        // A failed open must not rewrite a previously saved resume point.
        if (!progressObserved && !completed) return
        val p = player
        if (ready && p != null) {
            savedPosition = p.currentPosition.coerceAtLeast(0)
            savedDuration = p.duration.takeIf { it > 0 } ?: savedDuration
        }
        store.record(History(target, savedPosition, savedDuration, System.currentTimeMillis(), completed))
    }

    private fun ended() {
        if (completed || stopped) return
        completed = true
        ready = true
        saveProgress()
        keepAwake(false)
        val next = Policies.nextEpisode(target, System.currentTimeMillis())
        if (!desiredPlay || !store.settings().autoNext || next == null) {
            stage("Finished. Choose Back to title to continue browsing.", error = true)
            return
        }
        countdownDialog = AlertDialog.Builder(this).setTitle("Up next")
            .setMessage(next.label)
            .setPositiveButton("Play now") { _, _ -> cancelCountdown(); playNext(next) }
            .setNegativeButton("Stay here") { _, _ -> cancelCountdown() }
            .setOnCancelListener { cancelCountdown() }.show()
        countdown = scope.launch {
            for (seconds in store.settings().countdownSeconds.coerceAtLeast(0) downTo 1) {
                countdownDialog?.setMessage("${next.label}\n\nStarting in $seconds seconds")
                delay(1000)
            }
            if (!stopped && countdownDialog?.isShowing == true) {
                countdownDialog?.dismiss()
                countdownDialog = null
                playNext(next)
            }
        }
    }

    private fun playNext(next: Target) {
        target = next
        titleLabel.text = next.label
        savedPosition = 0
        savedDuration = 0
        completed = false
        progressObserved = false
        choices = emptyList()
        sources = emptyList()
        choice = null
        attemptedHashes.clear()
        examinedHashes = java.util.Collections.synchronizedSet(linkedSetOf())
        desiredPlay = true
        openingElapsed = 0
        loadSources()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        countdownDialog?.dismiss()
        countdownDialog = null
    }

    private fun tracks(type: Int) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type }
        val selections = groups.flatMap { group -> (0 until group.length).map { index -> group to index } }
        if (selections.isEmpty()) {
            decisionDialog = AlertDialog.Builder(this).setMessage(if (type == C.TRACK_TYPE_TEXT) "This version has no embedded subtitles." else "This version has no alternate audio tracks.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = mutableListOf(if (type == C.TRACK_TYPE_TEXT) "Off" else "Automatic")
        labels += selections.mapIndexed { index, (group, item) ->
            val format = group.getTrackFormat(item)
            format.label ?: format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() } ?: "Track ${index + 1}"
        }
        decisionDialog = AlertDialog.Builder(this).setTitle(if (type == C.TRACK_TYPE_TEXT) "Subtitles" else "Audio")
            .setItems(labels.toTypedArray()) { _, index ->
                val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                if (index == 0) builder.setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT)
                else {
                    val (group, item) = selections[index - 1]
                    builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, item))
                }
                p.trackSelectionParameters = builder.build()
            }.setNegativeButton("Back", null).show()
    }

    private fun keepAwake(playing: Boolean) {
        if (playing && !stopped) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && decisionDialog?.isShowing != true && countdownDialog?.isShowing != true) {
            val p = player
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (mayPlay()) { desiredPlay = true; p?.play() }; return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { desiredPlay = false; p?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (desiredPlay) { desiredPlay = false; p?.pause(); cancelCountdown() }
                    else if (mayPlay()) { desiredPlay = true; p?.play() }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { p?.seekBack(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p?.seekForward(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (ready && !playerView.isControllerFullyVisible) { p?.seekBack(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (ready && !playerView.isControllerFullyVisible) { p?.seekForward(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (ready && !playerView.isControllerFullyVisible) { playerView.showController(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (ready && playerView.isControllerFullyVisible) playerView.hideController() else finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (!initialized) { super.onSaveInstanceState(out); return }
        saveProgress()
        out.putLong("position", savedPosition)
        out.putLong("duration", savedDuration)
        out.putLong("watched", watchedSinceCheck)
        out.putBoolean("stillBlocked", stillWatchingBlocked)
        out.putBoolean("play", false)
        // Auto-next changes the target within this Activity, so retain the current target too.
        out.putString("target", encodeTarget(target))
        super.onSaveInstanceState(out)
    }

    override fun onStop() {
        if (!initialized) { super.onStop(); return }
        stopped = true
        alternatives?.cancel(); alternatives = null
        nextPrefetch?.cancel(); nextPrefetch = null
        ++generation
        request?.cancel()
        timer?.cancel()
        if (receiverRegistered) { unregisterReceiver(interruptionReceiver); receiverRegistered = false }
        cancelCountdown()
        decisionDialog?.dismiss()
        decisionDialog = null
        saveProgress()
        desiredPlay = false
        player?.pause()
        keepAwake(false)
        playerView.player = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        mediaClient?.dispatcher?.cancelAll()
        mediaClient?.connectionPool?.evictAll()
        mediaClient?.dispatcher?.executorService?.shutdown()
        mediaClient = null
        ready = false
        openingElapsed = 0
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_START_OVER = "startOver"
        fun start(activity: Activity, target: Target, startOver: Boolean = false) {
            activity.startActivity(Intent(activity, PlayerActivity::class.java)
                .putExtra(EXTRA_TARGET, encodeTarget(target)).putExtra(EXTRA_START_OVER, startOver))
        }
        private fun episodeJson(episode: Episode) = JSONObject().put("season", episode.season).put("number", episode.number)
            .put("name", episode.name).put("releasedMs", episode.releasedMs ?: JSONObject.NULL)
        private fun encodeTarget(target: Target): String = JSONObject().put("title", JSONObject()
            .put("id", target.title.id).put("type", target.title.type).put("name", target.title.name)
            .put("description", target.title.description).put("year", target.title.year)
            .put("poster", target.title.poster).put("background", target.title.background)
            .put("genres", JSONArray(target.title.genres))
            .put("episodes", JSONArray().also { array -> target.title.episodes.forEach { array.put(episodeJson(it)) } }))
            .put("episode", target.episode?.let { episodeJson(it) } ?: JSONObject.NULL).toString()
        private fun decodeEpisode(json: JSONObject) = Episode(json.getInt("season"), json.getInt("number"), json.optString("name"),
            json.optLong("releasedMs").takeIf { !json.isNull("releasedMs") && it > 0 })
        private fun decodeTarget(raw: String): Target {
            val root = JSONObject(raw)
            val json = root.getJSONObject("title")
            val episodes = json.optJSONArray("episodes") ?: JSONArray()
            val genres = json.optJSONArray("genres") ?: JSONArray()
            val title = Title(json.getString("id"), json.getString("type"), json.getString("name"),
                json.optString("description"), json.optString("year"), json.optString("poster"), json.optString("background"),
                (0 until genres.length()).map { genres.getString(it) },
                (0 until episodes.length()).map { decodeEpisode(episodes.getJSONObject(it)) })
            return Target(title, root.optJSONObject("episode")?.let { decodeEpisode(it) })
        }
    }
}
