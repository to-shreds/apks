package app.torboxtv.core

import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Device-independent decisions shared by browsing, discovery and playback. */
object Policies {
    fun validApiKey(value: String): Boolean = value.trim().let { it.length in 16..512 && it.none { character -> character.isWhitespace() || character.isISOControl() } }
    private val episodeTag = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?!\\d)|(?<![a-z0-9])(\\d{1,2})x(\\d{1,3})(?!\\d)")
    private val multiEpisode = Regex("(?i)(?:e\\d{1,3}[ ._-]*(?:e\\d|[-+]\\d)|\\d{1,2}x\\d{1,3}[ ._-]*(?:x\\d|[-+]\\d))")
    private val seasonTag = Regex("(?i)(?<![a-z0-9])(?:s|season[ ._-]*)(\\d{1,2})(?![a-z0-9])")
    private val yearTag = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val extensions = Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpeg|mpg)$")
    private val lowQuality = Regex("(?i)(?<![a-z0-9])(cam|hdcam|telesync|telecine|hdts|3d)(?![a-z0-9])")
    private val englishAudio = Regex("(?i)(?<![a-z0-9])(eng|english)(?![a-z0-9])")
    private val multipleAudio = Regex("(?i)(?<![a-z0-9])(multi|dual)(?![a-z0-9])")
    private val foreignOnly = Regex("(?i)(?<![a-z0-9])(vff|vfq|french|rus|russian|hindi|ita|italian|tamil|telugu)(?![a-z0-9])")
    private val familyGenres = setOf("family", "kids", "children", "children's")
    private val excludedKidGenres = setOf("adult", "horror", "thriller", "crime", "war")

    fun normalizeSettings(value: Settings): Settings = value.copy(
        maxHeight = when (value.maxHeight) { 720, 1080, 2160 -> value.maxHeight; else -> 1080 },
        rewindSeconds = value.rewindSeconds.coerceIn(0, 60),
        countdownSeconds = value.countdownSeconds.coerceIn(0, 60),
        stillWatchingMinutes = value.stillWatchingMinutes.coerceIn(0, 240),
        seekSeconds = value.seekSeconds.coerceIn(5, 120)
    )

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun sameTitlePrefix(name: String, title: Title): Boolean {
        val clean = normalized(name.substringAfterLast('/').substringAfterLast('\\'))
        val wanted = normalized(title.name)
        if (wanted.isBlank() || !(clean == wanted || clean.startsWith("$wanted "))) return false
        val remainder = clean.removePrefix(wanted).trim()
        if (remainder.isEmpty()) return true
        // The first word after the catalog title must be release data, not an unrelated sequel subtitle.
        val first = remainder.substringBefore(' ')
        return first.matches(Regex("(?:19|20)\\d{2}|s\\d{1,2}|s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3}|season|complete|720p|1080p|2160p|4k|bluray|brrip|bdrip|webrip|web|hdtv|dvdrip|remux"))
    }

    private fun releaseRemainder(name: String, title: Title): String = normalized(name.substringAfterLast('/').substringAfterLast('\\'))
        .removePrefix(normalized(title.name)).trim()

    private fun conflictingEpisodeName(name: String, target: Target): Boolean {
        val current = target.episode ?: return false
        val tag = episodeTag.find(name) ?: return false
        val tail = " ${normalized(name.substring(tag.range.last + 1))} "
        val currentName = normalized(current.name)
        return target.title.episodes.any { other ->
            val otherName = normalized(other.name)
            other.season == current.season && other.number != current.number && otherName.length >= 4 &&
                otherName != currentName && !currentName.contains(otherName) && tail.contains(" $otherName ")
        }
    }

    private fun seriesYearMatches(name: String, target: Target): Boolean {
        val knownYears = yearTag.findAll(target.title.year).map { it.value.toInt() }.toList()
        if (knownYears.isEmpty()) return true
        val release = releaseRemainder(name, target.title)
        val beforeEpisode = release.substringBefore(Regex("\\bs\\d{1,2}(?:e\\d{1,3})?\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\bseason\\b").find(release)?.value ?: "\u0000")
        val sourceYear = yearTag.find(beforeEpisode)?.value?.toInt() ?: return true
        // A release-name year disambiguates the series premiere, not the episode's air date.
        // Accepting every year in a show's run admits overlapping remakes such as UK/US Ghosts.
        // Sources named with an episode-air year are deliberately rejected unless independently verified.
        return sourceYear == knownYears.first()
    }

    fun sourceIdentity(source: Source, target: Target): Boolean {
        if (!sameTitlePrefix(source.name, target.title) || lowQuality.containsMatchIn(source.name)) return false
        val episode = target.episode
        if (episode == null) {
            if (target.title.type != "movie" || episodeTag.containsMatchIn(source.name) || seasonTag.containsMatchIn(source.name)) return false
            val year = yearTag.find(target.title.year)?.value
            // A known catalog year is required in the release name to distinguish remakes.
            return year != null && yearTag.find(releaseRemainder(source.name, target.title))?.value == year
        }
        if (multiEpisode.containsMatchIn(source.name) || !seriesYearMatches(source.name, target) || conflictingEpisodeName(source.name, target)) return false
        val tags = episodePairs(source.name)
        if (tags.isNotEmpty()) return tags.size == 1 && tags.single() == Pair(episode.season, episode.number)
        val seasons = seasonTag.findAll(source.name).map { it.groupValues[1].toInt() }.toSet()
        return seasons.size == 1 && seasons.single() == episode.season
    }

    fun fileMatches(file: VideoFile, target: Target): Boolean {
        val name = file.name.substringAfterLast('/').substringAfterLast('\\')
        if (!extensions.containsMatchIn(name) || Regex("(?i)(?<![a-z0-9])(sample|trailer|featurette)(?![a-z0-9])").containsMatchIn(name)) return false
        val episode = target.episode
        if (episode != null) {
            if (multiEpisode.containsMatchIn(name) || conflictingEpisodeName(name, target)) return false
            val tags = episodePairs(name)
            if (tags.size != 1 || tags.single() != Pair(episode.season, episode.number)) return false
            // Names with a title prefix must agree. Season/episode-only files are common in verified season packs.
            val firstTag = episodeTag.find(name) ?: return false
            val prefix = normalized(name.substring(0, firstTag.range.first))
            return prefix.isBlank() || (sameTitlePrefix(name, target.title) && seriesYearMatches(name, target))
        }
        return sourceIdentity(Source("", name.removeSuffix(name.substringAfterLast('.', "").let { ".$it" }), "file"), target)
    }

    private fun episodePairs(name: String): List<Pair<Int, Int>> = episodeTag.findAll(name).map {
        if (it.groupValues[1].isNotEmpty()) Pair(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        else Pair(it.groupValues[3].toInt(), it.groupValues[4].toInt())
    }.toList()

    private fun releaseAttributes(name: String): String {
        val markers = listOfNotNull(yearTag.find(name), episodeTag.find(name), seasonTag.find(name),
            Regex("(?i)(?<![a-z0-9])(?:720p|1080p|2160p|4k)(?![a-z0-9])").find(name))
        val marker = markers.minByOrNull { it.range.first } ?: return name
        return name.substring(marker.range.last + 1)
    }

    fun rankSources(values: List<Source>, settings: Settings): List<Source> {
        val max = normalizeSettings(settings).maxHeight
        return values.asSequence().filter { it.hash.matches(Regex("(?i)[a-f0-9]{40}")) }
            .filter { it.height <= max && !lowQuality.containsMatchIn(it.name) }
            .filter { releaseAttributes(it.name).let { attributes -> !foreignOnly.containsMatchIn(attributes) || englishAudio.containsMatchIn(attributes) || multipleAudio.containsMatchIn(attributes) } }
            .distinctBy { it.hash.lowercase(Locale.ROOT) }
            .sortedWith(compareByDescending<Source> { releaseAttributes(it.name).let { attributes -> if (englishAudio.containsMatchIn(attributes)) 2 else if (multipleAudio.containsMatchIn(attributes)) 1 else 0 } }
                .thenByDescending { it.height > 0 }
                .thenByDescending { it.height }
                .thenByDescending { Regex("(?i)(x264|h[ ._-]?264|avc|aac)").containsMatchIn(it.name) }
                .thenBy { Regex("(?i)(dovi|dolby[ ._-]?vision|hdr|hevc|x265|h[ ._-]?265)").containsMatchIn(it.name) }
                .thenBy { it.size.takeIf { n -> n > 0 } ?: Long.MAX_VALUE }
                .thenBy { it.hash }).toList()
    }

    fun resumeMs(history: History?, settings: Settings): Long {
        if (history == null || history.completed || history.positionMs <= 0) return 0
        val position = if (history.durationMs > 0) history.positionMs.coerceAtMost(history.durationMs) else history.positionMs
        return (position - normalizeSettings(settings).rewindSeconds * 1000L).coerceAtLeast(0)
    }

    fun nextEpisode(target: Target, nowMs: Long = System.currentTimeMillis()): Target? {
        val current = target.episode ?: return null
        return target.title.episodes.asSequence()
            .filter { it.season >= 0 && it.number > 0 && (current.season == 0 || it.season > 0) }
            .filter { it.season > current.season || it.season == current.season && it.number > current.number }
            .filter { it.releasedMs == null || it.releasedMs <= nowMs }
            .distinctBy { Pair(it.season, it.number) }
            .sortedWith(compareBy<Episode> { it.season }.thenBy { it.number })
            .firstOrNull()?.let { Target(target.title, it) }
    }

    fun isFamilyTitle(title: Title): Boolean {
        val genres = title.genres.map { it.lowercase(Locale.ROOT).trim() }.toSet()
        return genres.any { it in familyGenres } && genres.none { it in excludedKidGenres }
    }

    fun restriction(profile: Profile, usage: Usage, target: Target): String? {
        if (!profile.kidMode) return null
        val allowance = (profile.minuteLimit.coerceAtLeast(0).toLong() + usage.grantedMinutes.coerceAtLeast(0)) * 60_000L
        if (usage.watchedMs >= allowance) return "Today's viewing time is used up. Ask a parent for more time."
        if (target.episode != null && target.key !in usage.chargedEpisodes && usage.chargedEpisodes.size >= profile.episodeLimit.coerceAtLeast(0))
            return "Today's episode limit is reached. You can finish an episode you already started."
        if (target.episode == null && target.title.id !in usage.chargedMovies && usage.chargedMovies.size >= profile.movieLimit.coerceAtLeast(0))
            return "Today's movie limit is reached. You can finish a movie you already started."
        return null
    }

    fun chargeUsage(profile: Profile, usage: Usage, target: Target, deltaMs: Long, durationMs: Long): Usage {
        // A tick is actual elapsed playback, never the seek distance or media position.
        val increment = deltaMs.coerceIn(0, 60_000)
        if (increment == 0L) return usage
        val oldItem = usage.itemWatchedMs[target.key] ?: 0
        val nextItem = saturatingAdd(oldItem, increment)
        val chargeThreshold = if (target.episode == null) 600_000L else
            if (durationMs > 0) minOf(300_000L, (durationMs / 5).coerceAtLeast(1)) else 300_000L
        val chargeItem = nextItem >= chargeThreshold
        return usage.copy(
            watchedMs = saturatingAdd(usage.watchedMs, increment),
            chargedEpisodes = if (target.episode != null && chargeItem) usage.chargedEpisodes + target.key else usage.chargedEpisodes,
            chargedMovies = if (target.episode == null && chargeItem) usage.chargedMovies + target.title.id else usage.chargedMovies,
            itemWatchedMs = (usage.itemWatchedMs + (target.key to nextItem)).entries.toList().takeLast(500).associate { it.toPair() }
        )
    }

    private fun saturatingAdd(first: Long, second: Long): Long = if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    /** A damaged active-profile pointer must never select an unrestricted profile. */
    fun safeProfileSelection(profiles: List<Profile>, selectedId: String?, kidGuard: Boolean, corrupt: Boolean): Profile {
        val selected = profiles.firstOrNull { it.id == selectedId }
        if (selected != null && (!kidGuard || selected.kidMode) && (!corrupt || selected.kidMode)) return selected
        if (kidGuard || corrupt) return profiles.firstOrNull { it.kidMode } ?:
            Profile("recovery-kid", "Family recovery", true, minuteLimit = 0, episodeLimit = 0, movieLimit = 0)
        return profiles.firstOrNull() ?: Profile("viewer-1", "Viewer 1")
    }
}

/** Only a salted verifier is persisted. Compatible with Fire OS Android 7 and later. */
object PinSecurity {
    const val ITERATIONS = 120_000
    fun valid(pin: String): Boolean = pin.matches(Regex("[0-9]{4,8}"))
    fun derive(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(valid(pin) && salt.size == 24 && iterations in 100_000..500_000)
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun verify(pin: String, salt: ByteArray, expected: ByteArray, iterations: Int = ITERATIONS): Boolean {
        if (!valid(pin) || salt.size != 24 || expected.size != 32 || iterations !in 100_000..500_000) return false
        val actual = derive(pin, salt, iterations)
        return try { MessageDigest.isEqual(expected, actual) } finally { actual.fill(0) }
    }
}

/** Exactly one recovery attempt per distinct source, with an overall cap. No network side effects. */
class PlaybackRecovery(private val maxAttempts: Int = 3) {
    private val attempted = linkedSetOf<String>()
    fun claim(sourceKey: String): Boolean {
        if (sourceKey.isBlank() || sourceKey in attempted || attempted.size >= maxAttempts.coerceIn(0, 10)) return false
        attempted += sourceKey
        return true
    }
    fun reset() = attempted.clear()
    fun attempts(): Int = attempted.size
}

/** Generation-scoped countdown cancels stale callbacks after Back, Stop or a new title. */
class AutoNextCoordinator {
    private var generation = 0L
    private var next: Target? = null
    private var deadline = 0L
    fun begin(target: Target, settings: Settings, nowMs: Long): Long? {
        cancel()
        if (!settings.autoNext) return null
        next = Policies.nextEpisode(target, nowMs) ?: return null
        deadline = nowMs + Policies.normalizeSettings(settings).countdownSeconds * 1000L
        return generation
    }
    fun remainingSeconds(token: Long, nowMs: Long): Int? {
        if (token != generation || next == null) return null
        return ((deadline - nowMs).coerceAtLeast(0) + 999).div(1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun take(token: Long, nowMs: Long): Target? {
        if (token != generation || nowMs < deadline) return null
        val result = next ?: return null
        cancel()
        return result
    }
    fun cancel() { generation++; next = null; deadline = 0 }
}
