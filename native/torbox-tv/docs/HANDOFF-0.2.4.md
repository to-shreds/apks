# TorBox TV handoff

## Current state and controlling sources

**0.2.4/code 7 is ready as an original-certificate signed test update.** Native stress testing, repairs, QR phone control and GitHub APK updates are implemented and verified. This continues the preserved 0.2.3 project. ProjectStatus/projects/torbox-tv/STATUS.md controls readiness; the versioned source ZIP controls implementation while the separate native repository remains unavailable. Previous handoff: docs/HANDOFF-0.2.3.md.

The user APK is TorBox-TV-0.2.4-test.apk, 11,590,823 bytes, SHA-256 `c49d0de7577b7e0097fdf2b111e14b8523da064fe181b3b7e882d88610e24a7b`. Package app.torboxtv, minimum API 26, original certificate `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`. Install over the existing app without uninstalling or clearing data. Private signing material remains outside source, evidence, CI and downloads.

## Completed repairs and features

- Playback retains latest pause/toggle/screen-off/HDMI intent while links/preparation are pending. Native controls update recovery intent; source changes preserve position and clear obsolete watchdogs. Old callbacks cannot control a newer player.
- Source discovery establishes bounded ownership before suspension, protects newer/invalidation generations and retries partial outages. Persistent atomic operation claims prevent duplicate uncertain creation across clients. Failed durable claims prevent network writes.
- Missing primary snapshot/usage records recover backups and preserve child restrictions. Encryption, PIN and serialization formats remain unchanged. Incomplete interrupted details reload on return. Setup and native search fields have explicit remote-focus exits.
- Sports cancels actual HTTP work and preserves pause/retry/source intent and replay bookmarks. All 39 channels, 51 live choices and 157 dated replays across 26 categories remain. Adult Sports needs no TorBox key. The unwanted availability section remains removed.
- Updates check automatically while main is open, at most daily; Settings offers manual checks and a toggle. Explicit download/progress and native install approval follow strict size/hash/package/newer-version/original-certificate checks. See docs/APP-UPDATES.md.
- Settings QR pairs a phone browser with a bounded private-IPv4 LAN server for native search, navigation and playback. It continues across Activities and closes on background/disconnect/timeout. Stale text, expired commands and closing sessions are rejected. No credentials, files or media are served. See docs/PHONE-REMOTE.md.

## Verification completed

165 distinct JVM cases passed across two runs: normal suite 164 passed and one optional live skip, zero failures/errors; the live case passed separately with 49 catalog entries, 7 relevant search results and 37 identity-validated sources, without account operations or video. Six Chromium phone-page cases passed locally and in CI. Lint: zero errors, 40 warnings, one hint. Boundary checks passed.

43 regular Android cases passed on accelerated API 30 x86_64 at 1920x1080/density 320. The 45-entry report skips two explicit upgrade phases. Both upgrade phases then passed: install original 0.2.3, seed synthetic encrypted key/PIN/two profiles/history/My List/settings, replace with the exact final APK and verify records survive. Actual Activities, Media3, Keystore/preferences, QR decode, native search, phone playback and installer handoff are exercised.

Tested app/build/test source: ProjectStatus verify/torbox-tv-0.2.4 commit `147dd8e8f49316c06fdcf122b84a613a12baa5b8`. The 51-file docs/evidence/0.2.4/tested-source-hashes-r5.json freezes these files; final packaging changes documentation/evidence only.

Normal suite run 40060681225 passed the app tests but failed afterward when cleanup tried to uninstall a test package Gradle had already removed. Do not label that whole run green. Separate signed-upgrade run 40061903081 completed successfully. Corrected verification workflows are preserved at commit `2de198a4c308beb15b06296449dc8a431a37f287` and in evidence. Earlier emulator disk/display, compilation and harness problems are resolved; no current build blocker remains. See docs/BUILD-TEST-REPORT-0.2.4.md and docs/evidence/0.2.4/.

## Distribution and preservation

Anonymous update channel: to-shreds/torbox-web-player, separate native-updates branch, commit `ff4c149bfc6d110de3976e204c8e83b1ec94b145`. Manifest: https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/latest.json . Its complete APK was anonymously downloaded with HTTP 200 and matched the final size/hash. Website main remains `52303d8b33ef30fc37bc8f748cd322865f8ce982`; this native release did not modify it. No native source/signing key is public.

No video relay, transcoding, Render dependency, WebView wrapper or new paid service. Media travels directly from approved providers to native Media3; phone traffic carries controls only. Preserve cached-only automatic Play/Resume/recovery/next episode, explicit uncached preparation, title/year/episode/file identity, language/container checks, independent file IDs, bounded attempts and uncertain-operation reconciliation.

Original 0.2.3 APK SHA-256 `341468c84946aed57f08b9ee0a114ef9760f25d5c06fc8ef87bed96feab1e0d5`; source ZIP `07575f09e4fd9946ab008fae0ecc0ab4f73535a96b4c2d8e6bfb533fb8d935f8`. Both unchanged. Android generally rejects a lower versionCode; uninstalling for rollback erases state. Do not promise a lossless downgrade.

## Next physical acceptance

Install over 0.2.3 on the Firestick, then check actual video/audio, remote navigation, Home/Back, resume/auto-next, parental behavior, phone camera/Wi-Fi pairing and Fire OS install-permission wording. Authenticated TorBox first-frame latency and audible output are unmeasured on physical hardware. Synthetic 120-second H.264/AAC media establishes native lifecycle behavior, not provider startup speed. Fixture provenance: docs/PLAYER-FIXTURE.md. This app does not target Vega OS.

Pinned toolchain: JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, SDK 36/build tools 35.0.0, Media3 1.11.1. Source CI uses a temporary test identity for CI-only APKs. Publish future updates with the original signing identity after verification using scripts/prepare_update.py.
