package app.torboxtv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import app.torboxtv.core.*
import app.torboxtv.core.Target
import app.torboxtv.data.CatalogClient
import app.torboxtv.data.StateStore
import app.torboxtv.data.PortableVault
import app.torboxtv.data.PortableVaultClient
import app.torboxtv.data.TorBoxClient
import app.torboxtv.data.SourceClient
import app.torboxtv.data.AppUpdate
import kotlinx.coroutines.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/** Native TV interface. Its state and network clients are independent of the browser app. */
class MainActivity : Activity() {
    private lateinit var store: StateStore
    private lateinit var art: Artwork
    private lateinit var updates: AppUpdates
    private var availableUpdate: AppUpdate? = null
    private val catalog = CatalogClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pageScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main)
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private lateinit var content: LinearLayout
    private lateinit var scroller: ScrollView
    private val sidebar = mutableMapOf<String, View>()
    private var screen = "Home"
    private var detailTitle: Title? = null
    private var detailsLoaded = false
    private var previousScreen = "Home"
    private var screenGeneration = 0
    private var searchText = ""
    private var lastFocusTag: String? = null
    private var lastScroll = 0
    private var resumeRefresh = false
    private var settingsUnlocked = false
    private val ink = Color.rgb(244, 245, 250)
    private val muted = Color.rgb(164, 172, 193)
    private val accent = Color.rgb(102, 209, 196)
    private val panel = Color.rgb(25, 32, 48)
    private val bg = Color.rgb(9, 14, 25)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = StateStore(this)
        art = Artwork(this)
        updates = AppUpdates(this, { !store.currentProfile().kidMode }) { update ->
            availableUpdate = update
            showUpdateNotice()
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildShell()
        if (store.key().isNullOrBlank()) setup() else showPage("Home")
        intent.getStringExtra("phoneQuery")?.let { searchFromPhone(it.take(160)) }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(14), 0) }
        rail.addView(text("TORBOX", 22, accent, true).apply { letterSpacing = .13f })
        rail.addView(text("TV", 13, muted).apply { letterSpacing = .35f; setPadding(0, dp(1), 0, dp(24)) })
        for (name in listOf("Home", "Movies", "Shows", "Search", "My List", "Sports", "Profiles", "Settings")) {
            val view = button(name, "nav:$name") {
                if (name == "Settings" && store.currentProfile().kidMode) parentGate { settingsUnlocked = true; showPage(name) }
                else showPage(name)
            }.apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(7), 0) }
            view.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                    firstContentFocus(content)?.requestFocus() == true
                } else false
            }
            rail.addView(view, LinearLayout.LayoutParams(-1, dp(39)).apply { bottomMargin = dp(4) })
            sidebar[name] = view
        }
        val space = View(this)
        rail.addView(space, LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(text("Direct to TorBox", 11, muted))
        root.addView(rail, LinearLayout.LayoutParams(dp(145), -1))
        scroller = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isFocusable = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(10), dp(16)) }
        scroller.addView(content)
        root.addView(scroller, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
    }

    private fun newPage(name: String): Int {
        screen = name
        if (name != "Settings") settingsUnlocked = false
        screenGeneration++
        pageScope.cancel()
        pageScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main)
        pageJob?.cancel(); searchJob?.cancel(); art.cancelPending()
        content.removeAllViews()
        showUpdateNotice()
        scroller.scrollTo(0, 0)
        sidebar.forEach { (key, view) -> view.isSelected = key == name }
        if (name == "Setup") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        return screenGeneration
    }

    private fun showUpdateNotice() {
        content.findViewWithTag<View>("app:update")?.let { content.removeView(it) }
        val update = availableUpdate ?: return
        if (store.currentProfile().kidMode) return
        content.addView(button("TorBox TV ${update.version} is available · Update", "app:update") {
            parentGate(force = true) { updates.offer(update) }
        }, 0, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(12) })
    }

    private fun showPage(name: String) {
        if (name == "Sports") {
            if (store.currentProfile().kidMode) { AlertDialog.Builder(this).setMessage("Switch to an adult profile to open Sports.").setPositiveButton("OK", null).show(); return }
            startActivity(android.content.Intent(this, SportsActivity::class.java)); return
        }
        if (store.key().isNullOrBlank() && name !in listOf("Profiles", "Settings")) { setup(); return }
        val samePage = screen == name
        val focusTag = if (samePage) currentFocus?.tag as? String else null
        val scrollPosition = if (samePage) scroller.scrollY else 0
        val generation = newPage(name)
        when (name) {
            "Home" -> home(generation)
            "Movies" -> browse("movie", generation)
            "Shows" -> browse("series", generation)
            "Search" -> search(generation)
            "My List" -> myList()
            "Profiles" -> profiles()
            "Settings" -> settings()
        }
        sidebar[name]?.requestFocus()
        if (focusTag != null) content.post { content.findViewWithTag<View>(focusTag)?.requestFocus(); scroller.scrollTo(0, scrollPosition) }
    }

    private fun heading(title: String, subtitle: String = "") {
        content.addView(text(title, 27, ink, true))
        if (subtitle.isNotBlank()) content.addView(text(subtitle, 13, muted).apply { setPadding(0, dp(6), 0, dp(14)) })
        else content.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
    }

    private fun home(generation: Int) {
        val profile = store.currentProfile()
        heading("Welcome, ${profile.name}", if (profile.kidMode) "Kid Mode · Daily viewing limits active" else "Your next great watch starts here")
        val history = store.history().sortedByDescending { it.updatedMs }.distinctBy { it.target.title.id }
        val continuing = history.filter { !it.completed && it.positionMs > 0 }
        if (continuing.isNotEmpty()) row("Continue Watching", continuing.map { it.target.title }, continuing.associateBy { it.target.title.id })
        val next = history.mapNotNull { h -> if (h.completed) Policies.nextEpisode(h.target) else null }
        if (next.isNotEmpty()) targetRow("Next Up", next)
        val saved = eligible(store.watchlist())
        if (saved.isNotEmpty()) row("My List", saved)
        val hero = FrameLayout(this)
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(186)).apply { bottomMargin = dp(17) })
        hero.addView(text("Finding something worth watching…", 18, muted).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val movies = asyncSection("Movies to explore")
        val shows = asyncSection("Shows to explore")
        pageJob = pageScope.launch {
            supervisorScope {
                launch { loadSection(movies, "movie", profile.kidMode, generation) { titles ->
                    if (titles.isNotEmpty()) hero(hero, titles.first()) else {
                        hero.removeAllViews(); hero.background = rounded(panel, 12)
                        hero.addView(text("Discover something new\nChoose a collection below or search by name.", 20, muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)) }, FrameLayout.LayoutParams(-1, -1))
                    }
                } }
                launch { loadSection(shows, "series", profile.kidMode, generation) }
            }
        }
    }

    private fun hero(host: FrameLayout, title: Title) {
        host.removeAllViews()
        host.background = rounded(panel, 12)
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        host.addView(image, FrameLayout.LayoutParams(-1, -1))
        art.bind(image, title.background.ifBlank { title.poster }, true, title.id)
        host.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(12, 20, 34), Color.argb(220, 12, 20, 34), Color.argb(45, 12, 20, 34))) }, FrameLayout.LayoutParams(-1, -1))
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(18), dp(18), dp(18)) }
        copy.addView(text("FEATURED", 11, accent, true).apply { letterSpacing = .2f })
        copy.addView(text(title.name, 29, ink, true).apply { maxLines = 2 })
        copy.addView(text(listOf(title.year, title.genres.take(2).joinToString(" · ")).filter { it.isNotBlank() }.joinToString("  •  "), 12, muted))
        val open = button("Explore title", "hero:${title.id}") { openDetails(title) }
        copy.addView(open, LinearLayout.LayoutParams(dp(170), dp(42)).apply { topMargin = dp(12) })
        host.addView(copy, FrameLayout.LayoutParams(-1, -1))
    }

    private fun asyncSection(label: String): LinearLayout {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(12)) }
        group.addView(text(label, 19, ink, true))
        group.addView(text("Loading titles…", 13, muted).apply { setPadding(0, dp(12), 0, dp(18)) })
        content.addView(group)
        return group
    }

    private suspend fun loadSection(host: LinearLayout, type: String, kids: Boolean, generation: Int, onLoaded: ((List<Title>) -> Unit)? = null) {
        try {
            val titles = withTimeout(15_000) { catalog.browse(type, if (kids) "Family" else null) }
            if (generation != screenGeneration) return
            val items = eligible(titles)
            replaceSection(host, items)
            onLoaded?.invoke(items)
        } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else { sectionError(host, type, kids, generation); onLoaded?.invoke(emptyList()) } }
        catch (_: Exception) { if (generation == screenGeneration) { sectionError(host, type, kids, generation); onLoaded?.invoke(emptyList()) } }
    }

    private fun sectionError(host: LinearLayout, type: String, kids: Boolean, generation: Int) {
        if (generation != screenGeneration) return
        while (host.childCount > 1) host.removeViewAt(1)
        host.addView(text("Could not load this collection. Check your connection and try again.", 13, muted))
        host.addView(button("Try again", "retry:$type") {
            pageScope.launch { loadSection(host, type, kids, generation) }
        }, LinearLayout.LayoutParams(dp(170), dp(42)).apply { topMargin = dp(8) })
    }

    private fun replaceSection(host: LinearLayout, titles: List<Title>) {
        while (host.childCount > 1) host.removeViewAt(1)
        if (titles.isEmpty()) host.addView(text("No titles available in this collection.", 14, muted))
        else host.addView(posterStrip(titles))
    }

    private fun browse(type: String, generation: Int) {
        val kids = store.currentProfile().kidMode
        heading(if (type == "movie") "Movies" else "TV Shows", if (kids) "Family collections" else "Find your next favorite")
        val genres = if (kids) listOf("Family", "Animation", "Popular", "Adventure", "Comedy") else listOf("Popular", "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller")
        val chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false }
        val line = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(8)); clipChildren = false }
        chips.addView(line)
        content.addView(chips)
        val host = asyncSection("Popular ${if (type == "movie") "movies" else "shows"}")
        fun fetch(genre: String) {
            pageJob?.cancel()
            while (host.childCount > 1) host.removeViewAt(1)
            (host.getChildAt(0) as TextView).text = genre
            host.addView(text("Loading titles…", 13, muted))
            pageJob = pageScope.launch {
                try {
                    val items = withTimeout(15_000) { catalog.browse(type, genre.takeUnless { it == "Popular" }) }
                    if (generation == screenGeneration) replaceSection(host, eligible(items))
                } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else browseError(host, genre, ::fetch) }
                catch (_: Exception) { if (generation == screenGeneration) browseError(host, genre, ::fetch) }
            }
        }
        genres.forEach { genre -> line.addView(button(genre, "genre:$genre") { fetch(genre) }, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(8) }) }
        fetch(genres.first())
    }

    private fun browseError(host: LinearLayout, genre: String, retry: (String) -> Unit) {
        while (host.childCount > 1) host.removeViewAt(1)
        host.addView(text("Collection unavailable. Please try again.", 14, muted))
        host.addView(button("Try again", "retry:genre") { retry(genre) }, LinearLayout.LayoutParams(dp(170), dp(42)))
    }

    private fun search(generation: Int) {
        heading("Search", "Find movies and shows by name")
        val input = field("Movie or show title", false).apply { tag = "search:input"; setText(searchText); setSingleLine(true) }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(18) })
        val status = text("Enter at least two letters using your remote.", 14, muted)
        content.addView(status)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)
        input.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> firstContentFocus(results)?.requestFocus() == true
                KeyEvent.KEYCODE_DPAD_UP -> sidebar.getValue("Search").requestFocus()
                else -> false
            }
        }
        fun run(query: String) {
            searchJob?.cancel()
            if (query.trim().length < 2) { results.removeAllViews(); status.text = "Enter at least two letters using your remote."; return }
            status.text = "Searching…"
            searchJob = pageScope.launch {
                delay(400)
                try {
                    val found = eligible(withTimeout(12_000) { catalog.search(query.trim()) })
                    if (generation != screenGeneration || input.text.toString() != query) return@launch
                    results.removeAllViews()
                    status.text = if (found.isEmpty()) "No matching titles. Try another name." else "${found.size} titles"
                    // Multiple short rows keep every result reachable with directional keys.
                    found.chunked(12).forEach { results.addView(posterStrip(it)) }
                } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e else status.text = "Search timed out. Select Search again to retry." }
                catch (_: Exception) { if (generation == screenGeneration) { status.text = "Search unavailable. Check your connection."; results.removeAllViews(); results.addView(button("Try again", "search:retry") { run(input.text.toString()) }) } }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { searchText = s.toString(); run(searchText) }
            override fun afterTextChanged(s: Editable?) {}
        })
        if (searchText.length >= 2) run(searchText)
    }

    internal fun searchFromPhone(query: String) {
        if (store.key().isNullOrBlank()) return
        if (screen != "Search") showPage("Search")
        content.findViewWithTag<EditText>("search:input")?.apply { setText(query.take(160)); setSelection(text.length); requestFocus() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("phoneHome", false)) showPage("Home")
        else intent.getStringExtra("phoneQuery")?.let { searchFromPhone(it.take(160)) }
    }

    private fun eligible(titles: List<Title>): List<Title> = titles

    private fun myList() {
        heading("My List", "Saved for ${store.currentProfile().name}")
        val titles = eligible(store.watchlist())
        if (titles.isEmpty()) content.addView(text("Your list is empty. Open a title and choose Add to My List.", 16, muted))
        else titles.chunked(12).forEach { content.addView(posterStrip(it)) }
    }

    private fun row(label: String, titles: List<Title>, history: Map<String, History> = emptyMap()) {
        content.addView(text(label, 19, ink, true).apply { setPadding(0, dp(4), 0, dp(3)) })
        content.addView(posterStrip(titles, history))
    }

    private fun targetRow(label: String, targets: List<Target>) {
        content.addView(text(label, 19, ink, true))
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; isFocusable = false }
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(6), dp(8), dp(6), dp(14)) }
        targets.forEach { target ->
            val tile = button("${target.title.name}\nS${target.episode?.season} E${target.episode?.number}\n${target.episode?.name}", "next:${target.key}") { launchPlayer(target) }.apply { maxLines = 3; textSize = 15f; gravity = Gravity.CENTER_VERTICAL }
            line.addView(tile, LinearLayout.LayoutParams(dp(228), dp(96)).apply { rightMargin = dp(12) })
        }
        scroll.addView(line); content.addView(scroll)
    }

    private fun posterStrip(titles: List<Title>, history: Map<String, History> = emptyMap()): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; isFocusable = false }
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(6), dp(10), dp(6), dp(16)) }
        val watched = store.history().filter { it.completed }.map { it.target.title.id }.toSet()
        titles.take(60).forEach { title ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; tag = "title:${title.id}"; isFocusable = true; isClickable = true
                setPadding(dp(3), dp(3), dp(3), dp(5)); background = rounded(panel, 7)
                contentDescription = "${title.name}${if (history.containsKey(title.id)) ", continue watching" else ""}"
                setOnClickListener { openDetails(title) }
                onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                    view.background = rounded(panel, 7, if (focused) accent else Color.TRANSPARENT)
                    view.animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f).setDuration(110).start()
                }
            }
            val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(34, 44, 63)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(poster, LinearLayout.LayoutParams(-1, dp(163)))
            art.bind(poster, title.poster, id = title.id)
            history[title.id]?.let { h ->
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = if (h.durationMs > 0) (h.positionMs * 1000 / h.durationMs).toInt().coerceIn(0, 1000) else 0
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                }, LinearLayout.LayoutParams(-1, dp(3)))
            }
            card.addView(text(title.name, 12, ink, true).apply { maxLines = 2; minLines = 2; setPadding(dp(5), dp(6), dp(4), 0) })
            card.addView(text(if (history.containsKey(title.id)) "Resume" else if (title.id in watched) "Recently watched" else title.year, 10, muted).apply { setPadding(dp(5), dp(1), 0, 0) })
            line.addView(card, LinearLayout.LayoutParams(dp(115), -2).apply { rightMargin = dp(12) })
        }
        scroll.addView(line)
        return scroll
    }

    private fun openDetails(title: Title, preserveReturn: Boolean = false) {
        if (!preserveReturn) previousScreen = screen.takeUnless { it == "Details" || it == "Setup" } ?: "Home"
        detailTitle = title
        detailsLoaded = false
        val generation = newPage("Details")
        heading(title.name, "Loading title details…")
        val status = text("Getting seasons and viewing information.", 16, muted)
        content.addView(status)
        content.addView(button("Back to $previousScreen", "details:back") { showPage(previousScreen) }, LinearLayout.LayoutParams(dp(220), dp(44)))
        pageJob = pageScope.launch {
            try {
                val complete = withTimeout(15_000) { catalog.details(title) }
                if (generation != screenGeneration) return@launch
                detailTitle = complete
                detailsLoaded = true
                content.removeAllViews()
                details(complete)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                if (generation == screenGeneration) detailsFailure(title)
            } catch (_: Exception) { if (generation == screenGeneration) detailsFailure(title) }
        }
    }

    private fun detailsFailure(title: Title) {
        content.removeAllViews()
        heading(title.name, "Title information could not be loaded.")
        content.addView(text("Check your connection, then try again. Playback needs verified title information.", 15, muted))
        content.addView(button("Try again", "details:retry") { openDetails(title, true) }, LinearLayout.LayoutParams(dp(170), dp(44)))
        content.addView(button("Back", "details:back") { showPage(previousScreen) }, LinearLayout.LayoutParams(dp(170), dp(44)))
        content.getChildAt(2)?.requestFocus()
    }

    private fun details(title: Title) {
        val top = FrameLayout(this).apply { background = rounded(panel, 12) }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        top.addView(image, FrameLayout.LayoutParams(-1, -1)); art.bind(image, title.background.ifBlank { title.poster }, true, title.id)
        top.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(12, 20, 34), Color.argb(225, 12, 20, 34), Color.argb(110, 12, 20, 34))) }, FrameLayout.LayoutParams(-1, -1))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(23), dp(20), dp(20), dp(20)) }
        info.addView(text(title.name, 30, ink, true).apply { maxLines = 2 })
        info.addView(text(listOf(title.year, if (title.type == "series") "TV Series" else "Movie", title.genres.take(3).joinToString(" · ")).filter { it.isNotBlank() }.joinToString("  •  "), 12, accent))
        info.addView(text(title.description.ifBlank { "Description unavailable." }, 14, muted).apply { maxLines = 4; setPadding(0, dp(12), 0, dp(14)) })
        val latest = store.history().filter { it.target.title.id == title.id }.maxByOrNull { it.updatedMs }
        val first = if (title.type == "series") title.episodes.filter { it.season > 0 && (it.releasedMs == null || it.releasedMs <= System.currentTimeMillis()) }.minWithOrNull(compareBy<Episode> { it.season }.thenBy { it.number }) else null
        val target = if (latest != null && !latest.completed) Target(title, latest.target.episode) else if (latest?.completed == true) Policies.nextEpisode(Target(title, latest.target.episode)) ?: Target(title, first) else Target(title, first)
        val actions = LinearLayout(this).apply { clipChildren = false }
        var play: TextView? = null
        if (title.type != "series" || target.episode != null) {
            prefetchSources(target)
            play = button(if (latest != null && !latest.completed && latest.positionMs > 0) "Resume" else "Play", "details:play") { launchPlayer(target) }
            actions.addView(play, LinearLayout.LayoutParams(dp(132), dp(43)).apply { rightMargin = dp(8) })
            if (latest != null && latest.positionMs > 0) actions.addView(button("Start over", "details:startover") { confirm("Start from the beginning?", "Your saved position will be replaced when playback begins.") { launchPlayer(target, true) } }, LinearLayout.LayoutParams(dp(132), dp(43)).apply { rightMargin = dp(8) })
        }
        val listed = store.watchlist().any { it.id == title.id }
        actions.addView(button(if (listed) "Remove from My List" else "Add to My List", "details:list") { store.toggleWatchlist(title); detailsRefresh(title) }, LinearLayout.LayoutParams(dp(187), dp(43)))
        info.addView(actions)
        top.addView(info, FrameLayout.LayoutParams(-1, -2))
        content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18); height = dp(278) })
        if (latest != null) {
            content.addView(button("Remove viewing history", "details:history") { confirm("Remove viewing history?", "This removes saved progress for this title in ${store.currentProfile().name}'s profile.") { store.history().filter { it.target.title.id == title.id }.forEach { store.removeHistory(it.target.key) }; detailsRefresh(title) } }, LinearLayout.LayoutParams(dp(224), dp(40)).apply { bottomMargin = dp(14) })
        }
        if (title.type == "series") episodes(title)
        content.addView(button("Back to $previousScreen", "details:back") { showPage(previousScreen) }, LinearLayout.LayoutParams(dp(220), dp(42)).apply { topMargin = dp(12) })
        play?.let { primary -> primary.post { primary.requestFocus() } }
    }

    private var sourcePrefetch: Job? = null
    private fun prefetchSources(target: Target) {
        sourcePrefetch?.cancel()
        sourcePrefetch = pageScope.launch {
            try {
                // Only anonymous source metadata; no account addition, media fetch or signed URL.
                delay(250)
                withContext(Dispatchers.IO) { SourceClient.shared.find(target) }
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { }
        }
    }

    private fun detailsRefresh(title: Title) {
        val focusTag = currentFocus?.tag as? String
        val scrollPosition = scroller.scrollY
        content.removeAllViews(); art.cancelPending(); details(title)
        content.post { content.findViewWithTag<View>(focusTag)?.requestFocus(); scroller.scrollTo(0, scrollPosition) }
    }

    private fun episodes(title: Title) {
        content.addView(text("Episodes", 20, ink, true))
        if (title.episodes.isEmpty()) { content.addView(text("Episode information is unavailable.", 15, muted)); return }
        val seasonScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val seasons = LinearLayout(this).apply { setPadding(dp(3), dp(9), dp(3), dp(12)) }
        seasonScroll.addView(seasons); content.addView(seasonScroll)
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(host)
        fun render(season: Int) {
            host.removeAllViews()
            title.episodes.filter { it.season == season }.sortedBy { it.number }.forEach { episode ->
                val target = Target(title, episode)
                val h = store.history().find { it.target.key == target.key }
                val released = episode.releasedMs == null || episode.releasedMs <= System.currentTimeMillis()
                val label = "${episode.number.toString().padStart(2, '0')}    ${episode.name.ifBlank { "Episode ${episode.number}" }}${if (!released) "    Coming soon" else if (h?.completed == true) "    Watched" else if (h != null && h.positionMs > 0) "    Resume" else ""}"
                host.addView(button(label, "episode:${target.key}") { if (released) launchPlayer(target) else message("Not released yet", "This episode is not yet available to watch.") }.apply {
                    gravity = Gravity.CENTER_VERTICAL; textSize = 15f
                    val focus = onFocusChangeListener
                    onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                        focus?.onFocusChange(view, focused)
                        if (focused && released) prefetchSources(target)
                    }
                }, LinearLayout.LayoutParams(-1, dp(45)).apply { bottomMargin = dp(7) })
            }
        }
        val allSeasons = title.episodes.map { it.season }.distinct().sorted()
        allSeasons.forEach { season -> seasons.addView(button(if (season == 0) "Specials" else "Season $season", "season:$season") { render(season) }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(9) }) }
        val last = store.history().filter { it.target.title.id == title.id }.maxByOrNull { it.updatedMs }?.target?.episode?.season
        render(last?.takeIf { it in allSeasons } ?: allSeasons.firstOrNull { it > 0 } ?: allSeasons.first())
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun launchPlayer(target: Target, startOver: Boolean = false) {
        val issue = Policies.restriction(store.currentProfile(), store.usage(), target)
        if (issue != null) { message("Viewing limit", issue); return }
        lastFocusTag = currentFocus?.tag as? String
        lastScroll = scroller.scrollY
        resumeRefresh = true
        pageJob?.cancel(); searchJob?.cancel(); art.cancelPending()
        PlayerActivity.start(this, target, startOver)
    }

    private fun profiles() {
        heading("Profiles", "Each viewer has separate progress, My List, and playback preferences")
        store.profiles().forEach { profile ->
            val current = profile.id == store.currentProfile().id
            content.addView(button("${profile.name}${if (current) "  ·  Current viewer" else ""}${if (profile.kidMode) "  ·  Kid Mode" else ""}", "profile:${profile.id}") {
                if (!current) {
                    val select = { store.selectProfile(profile.id); showPage("Home") }
                    if (store.currentProfile().kidMode) parentGate(action = select) else select()
                }
            }.apply { gravity = Gravity.CENTER_VERTICAL; textSize = 19f }, LinearLayout.LayoutParams(-1, dp(65)).apply { bottomMargin = dp(10) })
            val edit = LinearLayout(this)
            edit.addView(button("Rename", "profile:rename:${profile.id}") { parentGate { inputDialog("Viewer name", profile.name, false) { name -> if (name.isNotBlank()) { store.saveProfile(profile.copy(name = name.take(30))); showPage("Profiles") } } } }, LinearLayout.LayoutParams(dp(125), dp(40)).apply { rightMargin = dp(9) })
            edit.addView(button(if (profile.kidMode) "Kid Mode: On" else "Kid Mode: Off", "profile:kid:${profile.id}") {
                parentGate {
                    val save = { store.saveProfile(profile.copy(kidMode = !profile.kidMode)); showPage("Profiles") }
                    if (!profile.kidMode && !store.hasPin()) newPin(save) else save()
                }
            }, LinearLayout.LayoutParams(dp(171), dp(40)))
            content.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        }
        content.addView(text("Kid Mode sets daily viewing limits. Catalog titles are not age-rated. Parents should review available titles.", 13, muted))
    }

    private fun settings() {
        if (store.currentProfile().kidMode && !settingsUnlocked) {
            parentGate { settingsUnlocked = true; showPage("Settings") }
            return
        }
        val profile = store.currentProfile()
        val s = store.settings()
        heading("Settings", "Preferences for ${profile.name}")
        setting("Playback quality", if (s.maxHeight >= 2160) "Up to 4K" else "Up to ${s.maxHeight}p") { pick("Playback quality", listOf("720p", "1080p", "4K"), listOf(720, 1080, 2160).indexOf(s.maxHeight)) { index -> update(s.copy(maxHeight = listOf(720, 1080, 2160)[index])) } }
        setting("Resume rewind", "${s.rewindSeconds} seconds") { pick("Rewind when resuming", listOf("0 seconds", "5 seconds", "10 seconds", "20 seconds", "30 seconds"), listOf(0, 5, 10, 20, 30).indexOf(s.rewindSeconds)) { update(s.copy(rewindSeconds = listOf(0, 5, 10, 20, 30)[it])) } }
        setting("Auto-play next episode", if (s.autoNext) "On" else "Off") { update(s.copy(autoNext = !s.autoNext)) }
        setting("Next-episode countdown", "${s.countdownSeconds} seconds") { pick("Next-episode countdown", listOf("5 seconds", "10 seconds", "15 seconds", "30 seconds"), listOf(5, 10, 15, 30).indexOf(s.countdownSeconds)) { update(s.copy(countdownSeconds = listOf(5, 10, 15, 30)[it])) } }
        setting("Still Watching", "After ${s.stillWatchingMinutes} minutes") { pick("Ask if you are still watching", listOf("30 minutes", "60 minutes", "90 minutes", "120 minutes"), listOf(30, 60, 90, 120).indexOf(s.stillWatchingMinutes)) { update(s.copy(stillWatchingMinutes = listOf(30, 60, 90, 120)[it])) } }
        setting("Remote seek step", "${s.seekSeconds} seconds") { pick("Seek step", listOf("10 seconds", "20 seconds", "30 seconds", "60 seconds"), listOf(10, 20, 30, 60).indexOf(s.seekSeconds)) { update(s.copy(seekSeconds = listOf(10, 20, 30, 60)[it])) } }
        content.addView(text("Family controls", 19, accent, true).apply { setPadding(0, dp(17), 0, dp(10)) })
        setting("Parent PIN", if (store.hasPin()) "Change PIN" else "Set PIN") { parentGate(force = true) { newPin { showPage("Settings") } } }
        setting("Daily viewing limit", "${profile.minuteLimit} minutes") { parentGate { pick("Daily viewing limit", listOf("30 minutes", "60 minutes", "90 minutes", "120 minutes", "180 minutes"), listOf(30, 60, 90, 120, 180).indexOf(profile.minuteLimit)) { store.saveProfile(profile.copy(minuteLimit = listOf(30, 60, 90, 120, 180)[it])); showPage("Settings") } } }
        setting("Daily episode limit", "${profile.episodeLimit}") { parentGate { pick("Episodes per day", listOf("1", "2", "3", "4", "6", "10"), listOf(1, 2, 3, 4, 6, 10).indexOf(profile.episodeLimit)) { store.saveProfile(profile.copy(episodeLimit = listOf(1, 2, 3, 4, 6, 10)[it])); showPage("Settings") } } }
        setting("Daily movie limit", "${profile.movieLimit}") { parentGate { pick("Movies per day", listOf("0", "1", "2", "3"), listOf(0, 1, 2, 3).indexOf(profile.movieLimit)) { store.saveProfile(profile.copy(movieLimit = listOf(0, 1, 2, 3)[it])); showPage("Settings") } } }
        setting("Extra viewing time today", "Grant 30 minutes") { parentGate(force = true) { store.grantMinutes(30); message("Extra time added", "30 extra minutes are available for this profile today.") } }
        content.addView(text("Account", 19, accent, true).apply { setPadding(0, dp(17), 0, dp(10)) })
        setting("TorBox API key", "Change securely") { parentGate(force = true) { setup() } }
        setting("Encrypted key recovery", "Set up or unlock on a new Firestick") {
            parentGate(force = true) { setup() }
        }
        setting("Sign out of TorBox", "Remove API key from this device") { parentGate(force = true) { confirm("Sign out?", "Saved viewing history and profiles stay on this device. You will need your API key to play again.") { store.clearKey(); setup() } } }
        content.addView(text("App updates", 19, accent, true).apply { setPadding(0, dp(17), 0, dp(10)) })
        setting("Phone remote", "Scan a QR code") { parentGate(force = true) { (application as TorBoxApplication).phone.showPairing(this) } }
        setting("Check for updates", "TorBox TV ${updates.version}") { parentGate(force = true) { updates.check() } }
        setting("Automatic update checks", if (updates.automaticEnabled) "On" else "Off") {
            parentGate(force = true) {
                updates.automaticEnabled = !updates.automaticEnabled
                showPage("Settings")
                content.post { content.findViewWithTag<View>("setting:Automatic update checks")?.requestFocus() }
            }
        }
        content.addView(text("Video streams directly from TorBox. Profiles and progress are stored on this device. Browser data does not automatically transfer.", 12, muted).apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun setting(label: String, value: String, action: () -> Unit) {
        content.addView(button("$label    ·    $value", "setting:$label") { if (store.currentProfile().kidMode) parentGate(action = action) else action() }.apply { gravity = Gravity.CENTER_VERTICAL; textSize = 15f }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })
    }

    private fun update(settings: Settings) { store.saveSettings(settings); showPage("Settings") }

    private fun setup() {
        newPage("Setup")
        heading("Welcome to TorBox TV", "Sports is ready. Connect TorBox for movies and shows.")
        val sports = button("Watch Sports without TorBox", "setup:sports") { showPage("Sports") }
        if (!store.currentProfile().kidMode) content.addView(sports, LinearLayout.LayoutParams(dp(285), dp(46)).apply { bottomMargin = dp(18) })
        content.addView(text("Already saved your encrypted TorBox key in the apks repository? Enter only your password to connect. Or create the encrypted file once on your phone.", 16, muted).apply { setPadding(0, 0, 0, dp(16)) })
        val status = text("", 14, muted)
        val unlock = button("Unlock saved key with password", "setup:unlock") {
            inputDialog("TorBox vault password", "", true, numericSecret = false) { password ->
                if (password.isBlank()) {
                    status.text = "Enter your vault password."
                } else {
                    status.text = "Unlocking saved key from GitHub…"
                    pageJob?.cancel()
                    pageJob = pageScope.launch {
                        try {
                            val key = withContext(Dispatchers.IO) { PortableVaultClient().unlock(password) }
                            withTimeout(20_000) { TorBoxClient(keyProvider = { key }).verify() }
                            store.saveKey(key)
                            showPage("Home")
                        } catch (e: CancellationException) {
                            if (e !is TimeoutCancellationException) throw e
                            status.text = "The connection timed out. Try again."
                        } catch (e: UserError) {
                            status.text = e.message ?: "Could not unlock the saved key."
                        } catch (_: Exception) {
                            status.text = "Could not reach GitHub or verify TorBox. Check your network and try again."
                        }
                    }
                }
            }
        }
        content.addView(unlock, LinearLayout.LayoutParams(dp(315), dp(50)).apply { bottomMargin = dp(10) })
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val createVault = button("Create encrypted key file on phone", "setup:create-vault") { showVaultSetupQr() }
        content.addView(createVault, LinearLayout.LayoutParams(dp(315), dp(46)).apply { bottomMargin = dp(15) })
        content.addView(text("Or enter the TorBox API key manually:", 14, muted).apply { setPadding(0, 0, 0, dp(9)) })
        val input = field("TorBox API key", true).apply { tag = "setup:key"; setSingleLine(true) }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        val connect = button("Connect TorBox", "setup:connect") {}
        // EditText consumes directional keys for cursor movement. Give the remote
        // explicit exits on this single-line setup field, including Kid Mode where
        // Sports is absent, without interfering with editing within the key.
        input.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> connect.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> createVault.requestFocus()
                KeyEvent.KEYCODE_DPAD_LEFT -> if (input.selectionStart == 0 && input.selectionEnd == 0)
                    sidebar.getValue("Home").requestFocus() else false
                else -> false
            }
        }
        sports.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> unlock.requestFocus()
                KeyEvent.KEYCODE_DPAD_LEFT -> sidebar.getValue("Home").requestFocus()
                else -> false
            }
        }
        unlock.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> createVault.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> (if (sports.parent != null) sports else sidebar.getValue("Home")).requestFocus()
                else -> false
            }
        }
        createVault.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> input.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> unlock.requestFocus()
                else -> false
            }
        }
        connect.setOnKeyListener { _, key, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> input.requestFocus()
                KeyEvent.KEYCODE_DPAD_LEFT -> sidebar.getValue("Home").requestFocus()
                else -> false
            }
        }
        connect.setOnClickListener {
            val key = input.text.toString().trim()
            if (key.length < 10 || key.any { it.isWhitespace() }) { status.text = "Enter a valid TorBox API key."; input.requestFocus(); return@setOnClickListener }
            connect.isEnabled = false; input.isEnabled = false; status.text = "Checking your account…"
            pageJob?.cancel()
            pageJob = pageScope.launch {
                try {
                    withTimeout(20_000) { TorBoxClient(keyProvider = { key }).verify() }
                    store.saveKey(key)
                    input.text.clear()
                    showPage("Home")
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    status.text = "Connection timed out. Check your network and try again."
                    connect.isEnabled = true; input.isEnabled = true; connect.requestFocus()
                } catch (_: Exception) {
                    status.text = "Could not verify this account. Check the key and your connection, then try again."
                    connect.isEnabled = true; input.isEnabled = true; connect.requestFocus()
                }
            }
        }
        content.addView(connect, LinearLayout.LayoutParams(dp(205), dp(46)).apply { bottomMargin = dp(12) })
        if (!store.key().isNullOrBlank()) content.addView(button("Keep current account", "setup:cancel") { showPage("Settings") }, LinearLayout.LayoutParams(dp(205), dp(46)).apply { topMargin = dp(16) })
        content.addView(text("No paid service is required beyond your TorBox account.", 12, muted).apply { setPadding(0, dp(24), 0, 0) })
        if (store.key().isNullOrBlank() && !store.currentProfile().kidMode) sports.requestFocus() else unlock.requestFocus()
    }

    private fun showVaultSetupQr() {
        val url = PortableVault.SETUP_URL
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 480, 480, mapOf(EncodeHintType.MARGIN to 3))
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
            gravity = Gravity.CENTER
        }
        box.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription = "TorBox vault setup QR code" },
            LinearLayout.LayoutParams(dp(226), dp(226)))
        box.addView(text("Scan on your phone. Enter your TorBox API key and a password, download the encrypted vault file, and upload it to the root of to-shreds/apks once.", 15, ink).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8))
        })
        box.addView(text(url, 13, accent).apply { gravity = Gravity.CENTER })
        AlertDialog.Builder(this).setTitle("Set up password recovery").setView(box).setPositiveButton("Done", null).show()
    }

    private fun parentGate(force: Boolean = false, action: () -> Unit) {
        if (screen == "Settings" && settingsUnlocked) { action(); return }
        if (!store.hasPin()) {
            if (store.currentProfile().kidMode) { message("Parent PIN required", "A parent must set up a PIN before changing family controls."); return }
            action(); return
        }
        // A configured PIN protects all family/security changes, including from adult profiles.
        inputDialog("Parent PIN", "", true) { pin ->
            if (store.verifyPin(pin)) {
                if (screen == "Settings") settingsUnlocked = true
                action()
            } else message("PIN not recognized", "Try again with the parent PIN.")
        }
    }

    private fun newPin(done: () -> Unit) {
        inputDialog("Set parent PIN: 4 to 8 digits", "", true) { pin ->
            if (!pin.matches(Regex("[0-9]{4,8}"))) { message("Choose a numeric PIN", "Use 4 to 8 digits."); return@inputDialog }
            inputDialog("Confirm parent PIN", "", true) { confirm ->
                if (pin == confirm) { store.setPin(pin); done() } else message("PINs do not match", "No changes were made. Please try again.")
            }
        }
    }

    private fun inputDialog(title: String, value: String, secret: Boolean, numericSecret: Boolean = true, submit: (String) -> Unit) {
        val input = field(if (secret && numericSecret) "PIN" else title, secret).apply {
            setText(value); setSingleLine(true)
            if (secret && numericSecret) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(10), dp(24), dp(8)); addView(input, LinearLayout.LayoutParams(-1, dp(50))) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Save") { _, _ -> safely { submit(input.text.toString().trim()) } }.setNegativeButton("Cancel", null).show()
        if (secret) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun pick(title: String, values: List<String>, current: Int, apply: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(), current.coerceAtLeast(0)) { dialog, index -> dialog.dismiss(); safely { apply(index) } }.setNegativeButton("Cancel", null).show()
    }
    private fun message(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun confirm(title: String, message: String, action: () -> Unit) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Confirm") { _, _ -> safely(action) }.setNegativeButton("Cancel", null).show() }
    private fun safely(action: () -> Unit) {
        try { action() }
        catch (e: UserError) { message("Could not finish", e.message ?: "Please try again.") }
        catch (_: Exception) { message("Could not finish", "Please try again. If this continues, restart the app.") }
    }

    private fun field(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; contentDescription = hintText; setTextColor(ink); setHintTextColor(muted); textSize = 18f
        setPadding(dp(14), dp(6), dp(14), dp(6)); background = rounded(panel, 8, muted)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_CAP_WORDS
        if (secret) {
            transformationMethod = PasswordTransformationMethod.getInstance(); isSaveEnabled = false
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused -> view.background = rounded(panel, 8, if (focused) accent else muted) }
    }

    private fun button(label: String, focusTag: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; contentDescription = label; tag = focusTag; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink)
        setPadding(dp(15), dp(5), dp(15), dp(5)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isFocusable = true; isClickable = true; background = rounded(panel, 7)
        setOnClickListener { safely(action) }
        onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) Color.rgb(39, 67, 78) else panel, 7, if (focused) accent else Color.TRANSPARENT)
            (view as TextView).setTextColor(if (focused) Color.WHITE else ink)
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun firstContentFocus(view: View): View? {
        if (view.visibility != View.VISIBLE || !view.isEnabled) return null
        if (view.isFocusable) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) firstContentFocus(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun rounded(color: Int, radius: Int, border: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(2), border) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        updates.onResume()
        if (resumeRefresh) {
            resumeRefresh = false
            if (screen == "Details" && detailTitle != null) {
                if (detailsLoaded) { newPage("Details"); details(detailTitle!!) }
                else openDetails(detailTitle!!, preserveReturn = true)
            }
            else if (screen == "Setup") setup() else showPage(screen)
            content.post { content.findViewWithTag<View>(lastFocusTag)?.requestFocus(); scroller.scrollTo(0, lastScroll) }
        }
    }

    override fun onStop() {
        super.onStop()
        updates.onStop()
        settingsUnlocked = false
        pageJob?.cancel(); searchJob?.cancel(); pageScope.cancel(); art.cancelPending()
        if (!isFinishing) resumeRefresh = true
    }

    override fun onDestroy() { updates.destroy(); pageJob?.cancel(); scope.cancel(); art.close(); super.onDestroy() }

    @Deprecated("Native remote Back handling")
    override fun onBackPressed() {
        when {
            screen == "Details" -> showPage(previousScreen)
            screen == "Setup" -> if (!store.key().isNullOrBlank()) showPage("Settings") else super.onBackPressed()
            screen != "Home" -> showPage("Home")
            else -> super.onBackPressed()
        }
    }
}
