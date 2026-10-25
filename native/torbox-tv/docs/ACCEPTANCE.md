# Test-build acceptance

The test build must compile, pass the JVM tests and lint, and pass
`python3 scripts/check_boundaries.py` before it is published. Compiling an
instrumentation APK is distinct from running its tests. Every release should
record both separately in HANDOFF.md, with the build/run identifiers and results.

## Automated checks

JVM tests cover deterministic source identity and ranking, configuration bounds,
resume, playback timing and recovery, next-episode cancellation, history/settings
serialization and parental restrictions. Network tests use local fixtures and
are not proof of live TorBox behavior. The architectural check rejects a web
wrapper, known project proxy dependencies, Google Play service dependencies,
credential logging patterns, exported playback intents and cleartext traffic.

`RemoteSmokeTest` uses D-pad, Select and Back rather than taps. It exercises the
native launcher, first-run setup, remote focus and browse/settings navigation.
Its bounded waits expose an indefinitely blocked initial screen. It deliberately
avoids entering an API key or making an uncached preparation request.

An AOSP emulator can exercise Android behavior. It cannot establish Fire TV
launcher behavior, Fire OS remote mappings, hardware decoder compatibility or
actual TorBox playback. A synthetic test media stream must be identified as
synthetic in all results.

## Physical Fire TV Stick acceptance

Install the versioned APK and record its version, Fire OS version and device
model. Use only the Fire TV remote throughout the checks below. Enter the TorBox
API key on the device rather than in a support chat or issue.

1. Launch from Your Apps. Confirm the first-run setup fits the TV screen, shows
   a focused **Watch Sports without TorBox** action for an adult profile, and supports API-key entry with the remote.
   Phone-assisted pairing is not implemented in this version. After saving the
   key, verify that later launches do not wait for a TorBox health check to browse.
2. Traverse the sidebar, poster rails, Search and Settings with the directional
   pad. Confirm every primary control is reachable, focus is visible, and Back
   returns to the previous meaningful screen. Search for a known movie and show
   and confirm the display matches current catalog metadata.
3. Open a show, select a specific season and episode, and play a cached source.
   Confirm the title, season and episode on screen match the actual video. Open
   alternative sources and verify the presentation is readable and remote driven.
4. Select a title without a cached source. Confirm the app does not prepare or
   add anything without the explicit Prepare action. Cancel preparation and
   verify there is no late playback launch or repeated request loop.
5. During actual playback, test Play/Pause, Left/Right, Fast Forward/Rewind,
   Select and Back. Confirm audio/subtitle selection works for a source with
   multiple tracks. Run past the normal display timeout while playing and verify
   the TV stays awake. Pause and check normal inactivity behavior.
6. Watch for at least two minutes, leave the player, relaunch and Resume.
   Confirm the configured rewind applies. Start Over and confirm it starts at
   zero. Remove a Continue Watching entry and verify it stays removed after
   restart without erasing other history or My List.
7. Complete an episode, observe the configured countdown and Cancel it. Complete
   another episode and permit auto-next. Verify the next video is the intended
   episode and playback remains fullscreen. Disable auto-next and verify no
   automatic launch occurs.
8. Use the shortest supported Still Watching interval, verify the prompt stops
   unattended playback and confirm remote interaction resumes correctly. Change
   quality, seek and countdown preferences and verify them after force-stop.
9. Press Home during playback, return, disconnect Wi-Fi, reconnect and retry.
   Verify playback/progress survives according to the documented behavior,
   recovery is bounded, and no stale action replaces a newer selection. If a
   source fails, verify an actionable message appears with viable alternatives.
10. Set a Parent PIN, enter Kid Mode, exhaust the daily time, episode and movie
    limits, and verify switching back or changing restrictions requires the PIN.
    Relaunch and confirm the same profile and restrictions remain active.
    Kid Mode follows the browser project's viewing-allowance concept. It does
    not guarantee age suitability or restrict a title using a guessed rating.
11. With no TorBox key, open Sports from setup and the sidebar. Browse live and
    replay tabs, choose several sports/providers, search and change pages. Play
    a live channel and a full replay, then verify quality/audio/seek/resume,
    Go live, Start over, Back and Home/return. Sports must remain inaccessible
    from a Kid Mode profile. A provider listing failure must leave bundled
    channels/replays available. Inspect network destinations if available:
    manifests, media keys and video bytes must travel directly to the provider
    CDNs and no Render media endpoint may be contacted.

## Isolation evidence

The existing `to-shreds/torbox-web-player` repository and Render service are
read-only references. Native changes, workflow builds and releases belong only
to `to-shreds/torbox-tv`. The native runtime must stream signed media URLs directly
from TorBox and must not route bytes through Render or GitHub. Record the actual
dependency review in HANDOFF.md; this checklist alone does not prove isolation.

## Result vocabulary

Use **passed**, **failed** or **not run**, with a precise evidence reference.
Never describe a source file, a compiled test APK, or a mocked stream as a passed
device test. A published test build is usable for validation, but live TorBox
playback and physical Fire TV acceptance remain unverified until performed.
