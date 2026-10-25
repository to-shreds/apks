package app.torboxtv.core

import org.junit.Assert.*
import org.junit.Test

class PoliciesTest {
    private val movie = Target(Title("tt0113497", "movie", "Jumanji", year = "1995"))
    private val series = Title("tt0386676", "series", "The Office", year = "2005–2013", episodes = listOf(
        Episode(1, 1, "Pilot", 1), Episode(1, 2, "Diversity Day", 2), Episode(2, 1, "The Dundies", 3)))
    private val episode = Target(series, series.episodes[0])
    private val kid = Profile("child", "Child", kidMode = true)
    private fun source(name: String, height: Int = 1080, hash: String = "a".repeat(40)) = Source(hash, name, "test", 1000, height)

    @Test fun exactMovieMatches() { assertTrue(Policies.sourceIdentity(source("Jumanji.1995.1080p.BluRay.x264"), movie)) }
    @Test fun movieRemakeRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.2017.1080p"), movie)) }
    @Test fun sequelSubtitleRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.Welcome.to.the.Jungle.1995.1080p"), movie)) }
    @Test fun substringTitleRejected() { assertFalse(Policies.sourceIdentity(source("Not.Jumanji.1995.1080p"), movie)) }
    @Test fun movieMissingYearRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.1080p"), movie)) }
    @Test fun movieUnknownCatalogYearRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.1995.1080p"), movie.copy(title = movie.title.copy(year = "")))) }
    @Test fun laterExpectedYearCannotHideWrongPrimaryYear() { assertFalse(Policies.sourceIdentity(source("Jumanji.2017.1995.1080p"), movie)) }
    @Test fun yearInsideTitleNotConfusedWithRelease() {
        val target = Target(Title("tt0062622", "movie", "2001: A Space Odyssey", year = "1968"))
        assertTrue(Policies.sourceIdentity(source("2001.A.Space.Odyssey.1968.1080p"), target))
        assertFalse(Policies.sourceIdentity(source("2001.A.Space.Odyssey.2001.1080p"), target))
    }
    @Test fun punctuationAndDiacriticsNormalized() {
        val target = Target(Title("test", "movie", "Amélie & Friends", year = "2001"))
        assertTrue(Policies.sourceIdentity(source("Amelie.and.Friends.2001.1080p"), target))
    }
    @Test fun movieSeasonFileRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.1995.S01E01.1080p"), movie)) }
    @Test fun exactEpisodeMatches() { assertTrue(Policies.sourceIdentity(source("The.Office.S01E01.1080p"), episode)) }
    @Test fun alternateEpisodeTagMatches() { assertTrue(Policies.sourceIdentity(source("The.Office.1x01.1080p"), episode)) }
    @Test fun wrongEpisodeRejected() { assertFalse(Policies.sourceIdentity(source("The.Office.S01E02.1080p"), episode)) }
    @Test fun wrongSeasonRejected() { assertFalse(Policies.sourceIdentity(source("The.Office.S02E01.1080p"), episode)) }
    @Test fun episodeNumbersCannotPrefixLongerEpisode() { assertFalse(Policies.sourceIdentity(source("The.Office.S01E010.1080p"), episode)) }
    @Test fun seasonPackAccepted() { assertTrue(Policies.sourceIdentity(source("The.Office.S01.Complete.1080p"), episode)) }
    @Test fun textualSeasonPackAccepted() { assertTrue(Policies.sourceIdentity(source("The.Office.Season.1.Complete"), episode)) }
    @Test fun wrongSeasonPackRejected() { assertFalse(Policies.sourceIdentity(source("The.Office.S02.Complete"), episode)) }
    @Test fun ambiguousSeriesPackRejected() { assertFalse(Policies.sourceIdentity(source("The.Office.S01.S02.Complete"), episode)) }
    @Test fun multiEpisodeFilesRejected() {
        listOf("S01E01E02", "S01E01-E02", "S01E01-02", "S01E01_S01E02", "S01E01_S01E01", "1x01-02").forEach {
            assertFalse(it, Policies.sourceIdentity(source("The.Office.$it.1080p"), episode))
            assertFalse(it, Policies.fileMatches(VideoFile(1, "$it.mkv"), episode))
        }
    }
    @Test fun similarlyNamedSeriesRemakeRejected() {
        assertFalse(Policies.sourceIdentity(source("The.Office.2001.S01E01.1080p"), episode))
        assertTrue(Policies.sourceIdentity(source("The.Office.2005.S01E01.1080p"), episode))
    }
    @Test fun remakeYearInsideOriginalSeriesRunRejected() {
        val target = Target(Title("uk-ghosts", "series", "Ghosts", year = "2019–2023"), Episode(1, 1, "Who Do You Think You Are?"))
        assertFalse(Policies.sourceIdentity(source("Ghosts.2021.S01E01.1080p"), target))
        assertFalse(Policies.fileMatches(VideoFile(1, "Ghosts.2021.S01E01.mkv"), target))
        assertTrue(Policies.sourceIdentity(source("Ghosts.2019.S01E01.1080p"), target))
    }
    @Test fun episodeAirYearCannotOverrideSeriesPremiereIdentity() {
        val target = Target(series, Episode(9, 1, "New Guys", 1_347_753_600_000))
        assertFalse(Policies.sourceIdentity(source("The.Office.2012.S09E01.1080p"), target))
        assertFalse(Policies.fileMatches(VideoFile(1, "The.Office.2012.S09E01.mkv"), target))
        assertTrue(Policies.sourceIdentity(source("The.Office.2005.S09E01.1080p"), target))
    }
    @Test fun sourceExplicitDifferentEpisodeNameRejected() {
        assertFalse(Policies.sourceIdentity(source("The.Office.S01E01.Diversity.Day.1080p"), episode))
        assertFalse(Policies.fileMatches(VideoFile(1, "The.Office.S01E01.Diversity.Day.mkv"), episode))
    }
    @Test fun splitEpisodeConflictingPartRejected() {
        val title = series.copy(episodes = listOf(Episode(5, 14, "Stress Relief Part 1"), Episode(5, 15, "Stress Relief Part 2")))
        val target = Target(title, title.episodes[0])
        assertFalse(Policies.fileMatches(VideoFile(1, "The.Office.S05E14.Stress.Relief.Part.2.mkv"), target))
        assertTrue(Policies.fileMatches(VideoFile(1, "The.Office.S05E14.Stress.Relief.Part.1.mkv"), target))
    }
    @Test fun unnumberedEpisodeFileRejected() { assertFalse(Policies.fileMatches(VideoFile(1, "episode 1.mp4"), episode)) }
    @Test fun exactFileFromSeasonPackMatches() { assertTrue(Policies.fileMatches(VideoFile(1, "folder/S01E01.mkv"), episode)) }
    @Test fun unrelatedTitleFileRejectedEvenWithRightNumber() { assertFalse(Policies.fileMatches(VideoFile(1, "Friends.S01E01.mkv"), episode)) }
    @Test fun sampleAndNonvideoFilesRejected() {
        assertFalse(Policies.fileMatches(VideoFile(1, "The.Office.S01E01.sample.mkv"), episode))
        assertFalse(Policies.fileMatches(VideoFile(2, "The.Office.S01E01.srt"), episode))
        assertFalse(Policies.fileMatches(VideoFile(3, "The.Office.S01E01.nfo"), episode))
    }
    @Test fun camReleasesRejected() { assertFalse(Policies.sourceIdentity(source("Jumanji.1995.HDCAM"), movie)) }
    @Test fun qualityPreferenceAndDuplicateHashesApplied() {
        val values = listOf(source("Jumanji.1995.2160p", 2160), source("Jumanji.1995.720p", 720, "b".repeat(40)),
            source("Jumanji.1995.1080p", 1080, "c".repeat(40)), source("duplicate", 1080, "c".repeat(40)))
        val ranked = Policies.rankSources(values, Settings(maxHeight = 1080))
        assertEquals(listOf(1080, 720), ranked.map { it.height })
    }
    @Test fun malformedHashExcluded() { assertTrue(Policies.rankSources(listOf(source("name", hash = "invalid")), Settings()).isEmpty()) }
    @Test fun knownQualityPrecedesUnknown() { assertEquals(720, Policies.rankSources(listOf(source("unknown", 0), source("720p", 720, "b".repeat(40))), Settings())[0].height) }
    @Test fun explicitForeignOnlyReleasesExcluded() {
        listOf("VFF", "VFQ", "FRENCH", "RUS", "RUSSIAN", "HINDI", "ITA", "ITALIAN", "TAMIL", "TELUGU").forEach {
            assertTrue(it, Policies.rankSources(listOf(source("Jumanji.1995.1080p.$it")), Settings()).isEmpty())
        }
    }
    @Test fun dualAndMultiReleasesRemainCandidatesWithoutEnglishGuarantee() {
        assertEquals(1, Policies.rankSources(listOf(source("Jumanji.1995.1080p.FRENCH.MULTI")), Settings()).size)
        assertEquals(1, Policies.rankSources(listOf(source("Jumanji.1995.1080p.RUS.DUAL")), Settings()).size)
    }
    @Test fun explicitEnglishPreferredOverUnknownLanguage() {
        val ranked = Policies.rankSources(listOf(source("Jumanji.1995.1080p", 1080), source("Jumanji.1995.720p.ENG", 720, "b".repeat(40))), Settings())
        assertEquals(720, ranked.first().height)
    }
    @Test fun languageTagsDoNotMatchSubstringsInOrdinaryTitles() {
        assertEquals(1, Policies.rankSources(listOf(source("The.Russian.Doll.1995.1080p")), Settings()).size)
        assertEquals(1, Policies.rankSources(listOf(source("Rushing.1995.1080p")), Settings()).size)
    }
    @Test fun resumeRewindsSavedPosition() { assertEquals(50_000L, Policies.resumeMs(History(movie, 60_000, 120_000, 1), Settings())) }
    @Test fun resumeCannotBecomeNegative() { assertEquals(0L, Policies.resumeMs(History(movie, 3_000, 120_000, 1), Settings())) }
    @Test fun completedHistoryStartsOver() { assertEquals(0L, Policies.resumeMs(History(movie, 119_000, 120_000, 1, true), Settings())) }
    @Test fun resumeClampsOutOfRangeSavedPosition() { assertEquals(110_000L, Policies.resumeMs(History(movie, 200_000, 120_000, 1), Settings())) }
    @Test fun nextEpisodeSortedAcrossSeasonBoundary() { assertEquals(2, Policies.nextEpisode(episode, 100)?.episode?.number); assertEquals(2, Policies.nextEpisode(Target(series, series.episodes[1]), 100)?.episode?.season) }
    @Test fun autoNextSkipsFutureAndSpecials() {
        val title = series.copy(episodes = listOf(Episode(0, 5, "Special"), Episode(1, 2, "Future", 1000), Episode(1, 3, "Available", 3)))
        assertEquals(3, Policies.nextEpisode(Target(title, series.episodes[0]), 100)?.episode?.number)
    }
    @Test fun movieHasNoAutoNext() { assertNull(Policies.nextEpisode(movie, 100)) }
    @Test fun settingsClampedAndOffModesPreserved() {
        val safe = Policies.normalizeSettings(Settings(9999, -1, true, 0, 0, 999))
        assertEquals(1080, safe.maxHeight); assertEquals(0, safe.rewindSeconds); assertEquals(0, safe.countdownSeconds)
        assertEquals(0, safe.stillWatchingMinutes); assertEquals(120, safe.seekSeconds)
    }
    @Test fun adultProfileHasNoQuotaRestriction() { assertNull(Policies.restriction(Profile("a", "Adult"), Usage("2026-10-10", Long.MAX_VALUE), movie)) }
    @Test fun kidModeUsesAllowancesWithoutClaimingAgeRatings() { assertNull(Policies.restriction(kid, Usage("2026-10-10"), movie)) }
    @Test fun timeLimitBlocksAlreadyChargedItem() { assertNotNull(Policies.restriction(kid, Usage("2026-10-10", 3_600_000, chargedEpisodes = setOf(episode.key)), episode)) }
    @Test fun countLimitAllowsSameChargedEpisodeToFinish() {
        val usage = Usage("2026-10-10", chargedEpisodes = setOf(episode.key, "two", "three"))
        assertNull(Policies.restriction(kid, usage, episode))
        assertNotNull(Policies.restriction(kid, usage, Target(series, series.episodes[1])))
    }
    @Test fun episodeCountsAtFiveMinutesWithLongRuntime() {
        var usage = Usage("2026-10-10")
        repeat(4) { usage = Policies.chargeUsage(kid, usage, episode, 60_000, 1_800_000) }
        assertTrue(usage.chargedEpisodes.isEmpty())
        usage = Policies.chargeUsage(kid, usage, episode, 60_000, 1_800_000)
        assertEquals(setOf(episode.key), usage.chargedEpisodes)
    }
    @Test fun shortEpisodeCountsAtTwentyPercent() {
        val usage = Policies.chargeUsage(kid, Usage("2026-10-10"), episode, 60_000, 300_000)
        assertEquals(setOf(episode.key), usage.chargedEpisodes)
    }
    @Test fun movieCountsOnlyAfterTenWatchedMinutes() {
        var usage = Usage("2026-10-10")
        repeat(9) { usage = Policies.chargeUsage(kid, usage, movie, 60_000, 1_200_000) }
        assertTrue(usage.chargedMovies.isEmpty())
        usage = Policies.chargeUsage(kid, usage, movie, 60_000, 1_200_000)
        assertEquals(setOf(movie.title.id), usage.chargedMovies)
    }
    @Test fun seekDistanceAndNegativeTicksNeverCountAsWatchedTime() {
        val empty = Usage("2026-10-10")
        assertEquals(empty, Policies.chargeUsage(kid, empty, episode, -1, 1_800_000))
        assertEquals(60_000L, Policies.chargeUsage(kid, empty, episode, 1_800_000, 1_800_000).watchedMs)
    }
    @Test fun grantExtendsTimeWithoutErasingItemCounts() {
        val usage = Usage("2026-10-10", 3_600_000, chargedEpisodes = setOf(episode.key), grantedMinutes = 15)
        assertNull(Policies.restriction(kid, usage, episode))
        assertEquals(setOf(episode.key), usage.chargedEpisodes)
    }
    @Test fun usageCannotOverflow() { assertEquals(Long.MAX_VALUE, Policies.chargeUsage(kid, Usage("2026-10-10", Long.MAX_VALUE - 1), episode, 1000, 10_000).watchedMs) }
    @Test fun corruptionCannotSwitchKidToAdult() {
        val profiles = listOf(Profile("adult", "Adult"), kid)
        assertTrue(Policies.safeProfileSelection(profiles, "adult", true, true).kidMode)
        assertTrue(Policies.safeProfileSelection(profiles, "missing", false, true).kidMode)
    }
    @Test fun corruptedStateWithoutKidProfileIsLockedRecovery() {
        val recovered = Policies.safeProfileSelection(listOf(Profile("adult", "Adult")), "missing", true, true)
        assertTrue(recovered.kidMode); assertEquals(0, recovered.minuteLimit)
        assertNotNull(Policies.restriction(recovered, Usage("2026-10-10"), movie))
    }
    @Test fun legitimateAdultSelectionRemainsAdult() { assertFalse(Policies.safeProfileSelection(listOf(Profile("adult", "Adult"), kid), "adult", false, false).kidMode) }
    @Test fun recoveryNeverRetriesSameSourceAndHasGlobalBound() {
        val recovery = PlaybackRecovery(2)
        assertTrue(recovery.claim("first")); assertFalse(recovery.claim("first"))
        assertTrue(recovery.claim("second")); assertFalse(recovery.claim("third")); assertEquals(2, recovery.attempts())
    }
    @Test fun staleAutoNextCallbacksCannotStartAfterCancel() {
        val coordinator = AutoNextCoordinator(); val token = coordinator.begin(episode, Settings(), 100)!!
        coordinator.cancel(); assertNull(coordinator.take(token, 50_000))
    }
    @Test fun autoNextFiresOnlyOnceAfterCountdown() {
        val coordinator = AutoNextCoordinator(); val token = coordinator.begin(episode, Settings(), 100)!!
        assertEquals(10, coordinator.remainingSeconds(token, 100)); assertNull(coordinator.take(token, 10_099))
        assertEquals(2, coordinator.take(token, 10_100)?.episode?.number); assertNull(coordinator.take(token, 10_101))
    }
    @Test fun newCountdownInvalidatesOlderTitleToken() {
        val coordinator = AutoNextCoordinator(); val old = coordinator.begin(episode, Settings(), 100)!!
        val fresh = coordinator.begin(episode, Settings(countdownSeconds = 0), 200)!!
        assertNull(coordinator.take(old, 100_000)); assertNotNull(coordinator.take(fresh, 200))
    }
    @Test fun disabledAutoNextCreatesNoCountdown() { assertNull(AutoNextCoordinator().begin(episode, Settings(autoNext = false), 100)) }
    @Test fun credentialsRejectTruncationAndEmbeddedWhitespace() {
        assertTrue(Policies.validApiKey("a".repeat(32))); assertFalse(Policies.validApiKey("short"))
        assertFalse(Policies.validApiKey("a".repeat(16) + "\nb")); assertFalse(Policies.validApiKey("a".repeat(513)))
    }
    @Test fun parentPinIsSaltedAndWrongPinRejected() {
        val salt = ByteArray(24) { it.toByte() }; val proof = PinSecurity.derive("1234", salt)
        assertEquals(32, proof.size); assertTrue(PinSecurity.verify("1234", salt, proof)); assertFalse(PinSecurity.verify("4321", salt, proof))
        assertFalse(proof.contentEquals(PinSecurity.derive("1234", ByteArray(24) { (it + 1).toByte() })))
    }
    @Test fun malformedPinProofFailsClosed() {
        assertFalse(PinSecurity.verify("1234", ByteArray(2), ByteArray(32)))
        assertFalse(PinSecurity.verify("1234", ByteArray(24), ByteArray(2)))
        assertFalse(PinSecurity.verify("1234", ByteArray(24), ByteArray(32), 1))
        assertFalse(PinSecurity.valid("12a4")); assertFalse(PinSecurity.valid("123"))
    }
}
