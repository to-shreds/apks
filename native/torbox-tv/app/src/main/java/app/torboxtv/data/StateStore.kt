package app.torboxtv.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.torboxtv.core.*
import app.torboxtv.core.Target
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Atomic, profile-local snapshots; credentials never enter the JSON document. */
class StateStore internal constructor(
    private val preferences: SharedPreferences,
    private val usagePreferences: SharedPreferences,
    private val credentialAlias: String
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("torbox_tv_state_v1", Context.MODE_PRIVATE),
        context.applicationContext.getSharedPreferences("torbox_tv_usage_v1", Context.MODE_PRIVATE),
        "${context.applicationContext.packageName}.torbox.credentials.v1"
    )

    private data class Snapshot(val profiles: List<Profile>, val selectedId: String, val documents: JSONObject)

    fun profiles(): List<Profile> = synchronized(lock) { load().profiles }
    fun currentProfile(): Profile = synchronized(lock) { val state = load(); state.profiles.first { it.id == state.selectedId } }
    fun selectProfile(id: String) = synchronized(lock) {
        val state = load()
        if (state.profiles.none { it.id == id }) throw UserError("That viewer profile is no longer available.")
        save(state.copy(selectedId = id))
    }
    fun saveProfile(profile: Profile) = synchronized(lock) {
        if (!profile.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || profile.name.trim().isEmpty()) throw UserError("Enter a valid viewer name.")
        val safe = profile.copy(name = profile.name.trim().take(64), minuteLimit = profile.minuteLimit.coerceIn(0, 1440),
            episodeLimit = profile.episodeLimit.coerceIn(0, 1000), movieLimit = profile.movieLimit.coerceIn(0, 100))
        val state = copyForMutation(load())
        val list = if (state.profiles.any { it.id == safe.id }) state.profiles.map { if (it.id == safe.id) safe else it }
            else (state.profiles + safe).also { if (it.size > 8) throw UserError("This device supports up to eight viewers.") }
        if (!state.documents.has(safe.id)) state.documents.put(safe.id, emptyDocument())
        save(state.copy(profiles = list))
    }

    fun settings(): Settings = synchronized(lock) { decodeSettings(document(load()).getJSONObject("settings")) }
    fun saveSettings(value: Settings) = mutateDocument { it.put("settings", encodeSettings(Policies.normalizeSettings(value))) }
    fun history(): List<History> = synchronized(lock) {
        val array = document(load()).getJSONArray("history")
        (0 until array.length()).mapNotNull { index -> runCatching { decodeHistory(array.getJSONObject(index)) }.getOrNull() }
            .sortedByDescending { it.updatedMs }.take(HISTORY_LIMIT)
    }
    fun record(value: History) = mutateDocument { doc ->
        val safe = value.copy(positionMs = value.positionMs.coerceAtLeast(0), durationMs = value.durationMs.coerceAtLeast(0), updatedMs = value.updatedMs.coerceAtLeast(0))
        val old = doc.getJSONArray("history")
        val values = (0 until old.length()).mapNotNull { runCatching { decodeHistory(old.getJSONObject(it)) }.getOrNull() }
        val next = (values.filterNot { it.target.key == safe.target.key } + safe).sortedByDescending { it.updatedMs }.take(HISTORY_LIMIT)
        doc.put("history", JSONArray().apply { next.forEach { put(encodeHistory(it)) } })
    }
    fun removeHistory(key: String) = mutateDocument { doc ->
        val array = doc.getJSONArray("history")
        doc.put("history", JSONArray().apply {
            for (i in 0 until array.length()) {
                val value = runCatching { decodeHistory(array.getJSONObject(i)) }.getOrNull()
                if (value != null && value.target.key != key) put(encodeHistory(value))
            }
        })
    }
    fun watchlist(): List<Title> = synchronized(lock) {
        val array = document(load()).getJSONArray("watchlist")
        (0 until array.length()).mapNotNull { runCatching { decodeTitle(array.getJSONObject(it)) }.getOrNull() }.take(WATCHLIST_LIMIT)
    }
    fun toggleWatchlist(title: Title) = mutateDocument { doc ->
        val array = doc.getJSONArray("watchlist")
        val values = (0 until array.length()).mapNotNull { runCatching { decodeTitle(array.getJSONObject(it)) }.getOrNull() }
        if (values.size >= WATCHLIST_LIMIT && values.none { it.id == title.id && it.type == title.type })
            throw UserError("My List is full. Remove a saved title before adding another.")
        val next = if (values.any { it.id == title.id && it.type == title.type }) values.filterNot { it.id == title.id && it.type == title.type }
            else listOf(title) + values
        doc.put("watchlist", JSONArray().apply { next.take(WATCHLIST_LIMIT).forEach { put(encodeTitle(it)) } })
    }

    fun usage(): Usage = synchronized(lock) { readUsage(load()) }
    fun recordWatched(target: Target, deltaMs: Long, durationMs: Long) = synchronized(lock) {
        val state = load()
        val profile = state.profiles.first { it.id == state.selectedId }
        val previous = readUsage(state)
        writeUsage(state.selectedId, Policies.chargeUsage(profile, previous, target, deltaMs, durationMs))
    }
    fun resetUsage() = synchronized(lock) { writeUsage(load().selectedId, Usage(today())) }
    fun grantMinutes(minutes: Int) = synchronized(lock) {
        val state = load()
        val previous = readUsage(state)
        writeUsage(state.selectedId, previous.copy(grantedMinutes = (previous.grantedMinutes.toLong() + minutes.coerceIn(0, 240)).coerceAtMost(1440).toInt()))
    }

    fun key(): String? = synchronized(lock) {
        val encoded = runCatching { preferences.getString(KEY, null) }.getOrNull() ?: return@synchronized null
        runCatching {
            val json = JSONObject(encoded)
            if (json.getInt("version") != 1) return@runCatching null
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            if (iv.size != 12) return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(CREDENTIAL_AAD.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP))
            try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
        }.getOrNull()
    }
    fun saveKey(value: String) = synchronized(lock) {
        val clean = value.trim()
        if (!Policies.validApiKey(clean)) throw UserError("Enter the complete TorBox API key.")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(CREDENTIAL_AAD.toByteArray(Charsets.UTF_8))
            val plaintext = clean.toByteArray(Charsets.UTF_8)
            val ciphertext = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
            val json = JSONObject().put("version", 1).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            if (!preferences.edit().putString(KEY, json.toString()).commit()) throw UserError("Your key could not be saved. Please try again.")
        } catch (e: UserError) { throw e }
        catch (_: Exception) { throw UserError("Secure storage is unavailable on this device. Restart the app and try again.") }
    }
    fun clearKey() = synchronized(lock) { if (!preferences.edit().remove(KEY).commit()) throw UserError("The key could not be removed. Please try again.") }

    fun hasPin(): Boolean = synchronized(lock) { preferences.contains(PIN) }
    fun setPin(pin: String) = synchronized(lock) {
        if (!PinSecurity.valid(pin)) throw UserError("Use a PIN with four to eight numbers.")
        val salt = ByteArray(24).also { random.nextBytes(it) }
        val verifier = PinSecurity.derive(pin, salt)
        val json = JSONObject().put("version", 1).put("iterations", PIN_ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).put("verifier", Base64.encodeToString(verifier, Base64.NO_WRAP))
        if (!preferences.edit().putString(PIN, json.toString()).commit()) throw UserError("The PIN could not be saved. Please try again.")
        verifier.fill(0)
    }
    fun verifyPin(pin: String): Boolean = synchronized(lock) {
        if (!PinSecurity.valid(pin)) return@synchronized false
        val raw = runCatching { preferences.getString(PIN, null) }.getOrNull() ?: return@synchronized false
        runCatching {
            val json = JSONObject(raw)
            if (json.getInt("version") != 1) return@runCatching false
            val iterations = json.getInt("iterations")
            if (iterations !in 100_000..500_000) return@runCatching false
            val salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP)
            val expected = Base64.decode(json.getString("verifier"), Base64.NO_WRAP)
            if (salt.size != 24 || expected.size != 32) return@runCatching false
            try { PinSecurity.verify(pin, salt, expected, iterations) } finally { expected.fill(0) }
        }.getOrDefault(false)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(credentialAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(credentialAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    private fun mutateDocument(action: (JSONObject) -> Unit) = synchronized(lock) { val state = copyForMutation(load()); action(document(state)); save(state) }
    private fun copyForMutation(state: Snapshot): Snapshot = state.copy(documents = JSONObject(state.documents.toString()))
    private fun document(state: Snapshot): JSONObject = state.documents.getJSONObject(state.selectedId)
    private fun defaultState(): Snapshot {
        val profiles = listOf(Profile("viewer-1", "Viewer 1"), Profile("viewer-2", "Viewer 2"))
        return Snapshot(profiles, profiles.first().id, JSONObject().apply { profiles.forEach { put(it.id, emptyDocument()) } })
    }
    private fun load(): Snapshot {
        val current = runCatching { preferences.getString(CURRENT, null) }.getOrNull()
        // Only a genuinely untouched store is first launch. A missing primary
        // with a backup or profile guards must follow the corruption recovery
        // path, otherwise a partial preference loss silently unlocks Kid Mode.
        if (current == null && !preferences.contains(CURRENT) && !preferences.contains(PREVIOUS) &&
            !preferences.contains(KID_GUARD) && !preferences.contains(PROFILE_GUARD)) return defaultState()
        val primary = runCatching { decodeCachedSnapshot(current ?: "") }.getOrNull()
        val fallback = if (primary == null) runCatching { decodeSnapshot(preferences.getString(PREVIOUS, null) ?: "") }.getOrNull() else null
        val state = primary ?: fallback ?: defaultState()
        val guard = runCatching { preferences.getBoolean(KID_GUARD, primary == null) }.getOrDefault(true)
        val preferred = if (primary != null) state.selectedId else runCatching { preferences.getString(PROFILE_GUARD, null) }.getOrNull()
        val selected = Policies.safeProfileSelection(state.profiles, preferred, guard, primary == null)
        val profiles = if (state.profiles.any { it.id == selected.id }) state.profiles else state.profiles + selected
        val documents = if (!state.documents.has(selected.id)) JSONObject(state.documents.toString()).put(selected.id, emptyDocument()) else state.documents
        return state.copy(profiles = profiles, selectedId = selected.id, documents = documents)
    }
    private fun save(state: Snapshot) {
        val serialized = encodeSnapshot(state).toString()
        val previous = runCatching { preferences.getString(CURRENT, null) }.getOrNull()
        val editor = preferences.edit().putString(CURRENT, serialized)
            .putBoolean(KID_GUARD, state.profiles.first { it.id == state.selectedId }.kidMode)
            .putString(PROFILE_GUARD, state.selectedId)
        // Preserve the last valid snapshot even if this write is repairing a damaged current one.
        if (previous != null && runCatching { decodeSnapshot(previous) }.isSuccess) editor.putString(PREVIOUS, previous)
        if (!editor.commit()) throw UserError("Your changes could not be saved. Please try again.")
        cachedRaw = serialized
        cachedSnapshot = state
    }
    private fun decodeCachedSnapshot(raw: String): Snapshot {
        if (raw == cachedRaw) cachedSnapshot?.let { return it }
        return decodeSnapshot(raw).also { cachedRaw = raw; cachedSnapshot = it }
    }
    private fun readUsage(state: Snapshot): Usage {
        val key = "${state.selectedId}.current"
        val raw = runCatching { usagePreferences.getString(key, null) }.getOrNull()
        val backupKey = "${state.selectedId}.previous"
        // The embedded document is the legacy initial value, not a safe reset
        // when the independent usage store still has evidence of prior writes.
        val value = if (raw == null && !usagePreferences.contains(key) && !usagePreferences.contains(backupKey)) decodeUsage(document(state).getJSONObject("usage")) else {
            val primary = runCatching { decodeUsage(JSONObject(raw ?: "")) }.getOrNull()
            val backup = runCatching { decodeUsage(JSONObject(usagePreferences.getString(backupKey, null) ?: "")) }.getOrNull()
            primary ?: backup?.takeIf { it.day == today() } ?: Usage(today(), watchedMs = Long.MAX_VALUE)
        }
        return if (value.day == today()) value else Usage(today())
    }
    private fun writeUsage(id: String, usage: Usage) {
        val currentKey = "$id.current"
        val old = runCatching { usagePreferences.getString(currentKey, null) }.getOrNull()
        val editor = usagePreferences.edit().putString(currentKey, encodeUsage(usage).toString())
        if (old != null && runCatching { decodeUsage(JSONObject(old)) }.isSuccess) editor.putString("$id.previous", old)
        if (!editor.commit()) throw UserError("Viewing time could not be saved. Pause playback and try again.")
    }
    private fun encodeSnapshot(value: Snapshot): JSONObject = JSONObject().put("version", 1).put("selected", value.selectedId)
        .put("profiles", JSONArray().apply { value.profiles.forEach { profile -> put(JSONObject()
            .put("id", profile.id).put("name", profile.name).put("kidMode", profile.kidMode)
            .put("minuteLimit", profile.minuteLimit).put("episodeLimit", profile.episodeLimit).put("movieLimit", profile.movieLimit)) } })
        .put("documents", value.documents)
    private fun decodeSnapshot(raw: String): Snapshot {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val array = json.getJSONArray("profiles")
        require(array.length() in 1..9)
        val profiles = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            val id = it.getString("id"); val name = it.getString("name")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && name.isNotBlank())
            Profile(id, name.take(64), it.getBoolean("kidMode"), it.getInt("minuteLimit").coerceIn(0, 1440),
                it.getInt("episodeLimit").coerceIn(0, 1000), it.getInt("movieLimit").coerceIn(0, 100))
        } }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        val selected = json.getString("selected")
        require(profiles.any { it.id == selected })
        val documents = json.getJSONObject("documents")
        profiles.forEach { profile ->
            val doc = documents.getJSONObject(profile.id)
            decodeSettings(doc.getJSONObject("settings")); decodeUsage(doc.getJSONObject("usage"))
            require(doc.getJSONArray("history").length() <= HISTORY_LIMIT && doc.getJSONArray("watchlist").length() <= WATCHLIST_LIMIT)
        }
        return Snapshot(profiles, selected, documents)
    }
    private fun emptyDocument(): JSONObject = JSONObject().put("settings", encodeSettings(Settings())).put("history", JSONArray())
        .put("watchlist", JSONArray()).put("usage", encodeUsage(Usage(today())))
    private fun encodeSettings(value: Settings): JSONObject = JSONObject().put("maxHeight", value.maxHeight).put("rewindSeconds", value.rewindSeconds)
        .put("autoNext", value.autoNext).put("countdownSeconds", value.countdownSeconds).put("stillWatchingMinutes", value.stillWatchingMinutes).put("seekSeconds", value.seekSeconds)
    private fun decodeSettings(value: JSONObject): Settings = Policies.normalizeSettings(Settings(value.getInt("maxHeight"), value.getInt("rewindSeconds"),
        value.getBoolean("autoNext"), value.getInt("countdownSeconds"), value.getInt("stillWatchingMinutes"), value.getInt("seekSeconds")))
    private fun encodeUsage(value: Usage): JSONObject = JSONObject().put("day", value.day).put("watchedMs", value.watchedMs)
        .put("episodes", JSONArray(value.chargedEpisodes.toList())).put("movies", JSONArray(value.chargedMovies.toList()))
        .put("items", JSONObject().apply { value.itemWatchedMs.forEach { (key, watched) -> put(key, watched) } }).put("grantedMinutes", value.grantedMinutes)
    private fun decodeUsage(value: JSONObject): Usage {
        val day = value.getString("day"); require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val watched = value.getLong("watchedMs"); require(watched >= 0)
        fun strings(array: JSONArray): Set<String> { require(array.length() <= 500); return (0 until array.length()).map { array.getString(it) }.toSet() }
        val items = value.getJSONObject("items")
        require(items.length() <= 500)
        val watchedItems = items.keys().asSequence().associateWith { items.getLong(it).also { amount -> require(amount >= 0) } }
        return Usage(day, watched, strings(value.getJSONArray("episodes")), strings(value.getJSONArray("movies")), watchedItems,
            value.optInt("grantedMinutes", 0).coerceIn(0, 1440))
    }
    private fun encodeTitle(value: Title): JSONObject = JSONObject().put("id", value.id).put("type", value.type).put("name", value.name)
        .put("description", value.description).put("year", value.year).put("poster", value.poster).put("background", value.background)
        .put("genres", JSONArray(value.genres)).put("episodes", JSONArray().apply { value.episodes.take(2000).forEach { put(encodeEpisode(it)) } })
    private fun decodeTitle(value: JSONObject): Title {
        val genres = value.optJSONArray("genres") ?: JSONArray()
        val episodes = value.optJSONArray("episodes") ?: JSONArray()
        val type = value.getString("type"); require(type == "movie" || type == "series")
        return Title(value.getString("id"), type, value.getString("name"), value.optString("description"), value.optString("year"),
            value.optString("poster"), value.optString("background"), (0 until genres.length().coerceAtMost(100)).map { genres.getString(it) },
            (0 until episodes.length().coerceAtMost(2000)).map { decodeEpisode(episodes.getJSONObject(it)) })
    }
    private fun encodeEpisode(value: Episode): JSONObject = JSONObject().put("season", value.season).put("number", value.number)
        .put("name", value.name).apply { value.releasedMs?.let { put("releasedMs", it) } }
    private fun decodeEpisode(value: JSONObject): Episode = Episode(value.getInt("season"), value.getInt("number"), value.getString("name"),
        if (value.has("releasedMs") && !value.isNull("releasedMs")) value.getLong("releasedMs") else null)
    private fun historyTitle(target: Target): Title {
        val current = target.episode ?: return target.title.copy(episodes = emptyList())
        val upcoming = target.title.episodes.asSequence().filter {
            it.season > current.season || it.season == current.season && it.number >= current.number
        }.sortedWith(compareBy<Episode> { it.season }.thenBy { it.number }).take(160).toList()
        return target.title.copy(episodes = (listOf(current) + upcoming).distinctBy { Pair(it.season, it.number) })
    }
    private fun encodeHistory(value: History): JSONObject = JSONObject().put("title", encodeTitle(historyTitle(value.target)))
        .apply { value.target.episode?.let { put("episode", encodeEpisode(it)) } }.put("positionMs", value.positionMs)
        .put("durationMs", value.durationMs).put("updatedMs", value.updatedMs).put("completed", value.completed)
    private fun decodeHistory(value: JSONObject): History = History(Target(decodeTitle(value.getJSONObject("title")),
        value.optJSONObject("episode")?.let { decodeEpisode(it) }), value.getLong("positionMs").coerceAtLeast(0),
        value.getLong("durationMs").coerceAtLeast(0), value.getLong("updatedMs").coerceAtLeast(0), value.getBoolean("completed"))

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    companion object {
        private val lock = Any()
        private val random = SecureRandom()
        private var cachedRaw: String? = null
        private var cachedSnapshot: Snapshot? = null
        private const val CURRENT = "snapshot.current"
        private const val PREVIOUS = "snapshot.previous"
        private const val KID_GUARD = "snapshot.kid_guard"
        private const val PROFILE_GUARD = "snapshot.profile_guard"
        private const val KEY = "credential.aesgcm.v1"
        private const val PIN = "parent.pin.v1"
        private const val PIN_ITERATIONS = 120_000
        private const val CREDENTIAL_AAD = "torbox-tv-api-key-v1"
        private const val HISTORY_LIMIT = 80
        private const val WATCHLIST_LIMIT = 200
    }
}
