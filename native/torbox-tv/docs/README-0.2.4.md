# TorBox TV

Current native release: **0.2.4/code 7**. This revision adds [QR phone-browser control](docs/PHONE-REMOTE.md) and [GitHub APK updates](docs/APP-UPDATES.md), alongside repairs for playback intent, request cancellation, remote focus, source-cache races, duplicate preparation and saved-state recovery. Verified with 165 distinct JVM cases across two runs, 43 regular native cases, both signed-upgrade phases and six phone-browser cases. See [HANDOFF.md](HANDOFF.md) and the [0.2.4 report](docs/BUILD-TEST-REPORT-0.2.4.md); older results below remain historical.

An independent native Kotlin application for Fire TV and Android TV, continuing the preserved 0.2.3 source and 0.2.2 startup behavior. Authenticated TorBox playback and physical Firestick acceptance remain untested. See HANDOFF for the actual build and verification state.

Browsing and playback have no dependency on the website or Render. Optional APK update checks use a separate public GitHub distribution branch. Phone controls stay on the LAN; video travels directly from approved providers to the television.

## Expanded Sports in 0.2.3

Choose **Watch Sports without TorBox** on first launch or Sports in the sidebar. Sports requires an adult profile, but no TorBox key or account. Live channels and full-event replays have catalog-driven sport and provider filters, live search and bounded pages of 48 cards. Provider setup failures leave the bundled channels and replays available. The explanatory availability section has been removed from the Sports interface. Sports video, manifests, segments and media keys travel directly from public provider CDNs to native Media3, with every network exchange guarded; Render is never a destination. The expanded catalog and its public probe evidence are described in [Sports 0.2.3](docs/SPORTS-0.2.3.md).

## Sports in 0.2.0

Open Sports in the sidebar. It works before TorBox setup, with four free live channels and sixteen full padel replays. No additional subscription, login or VPN is configured. Streams travel directly from public sports CDNs to Media3. Default quality is 720p; select 480p, 1080p or Auto, switch audio, Go Live or Start Over. Replays save a separate local bookmark. Sports requires an adult profile. Regional rights and changing channel schedules still apply. The bundled replay collection is a release snapshot, not a promise of every tennis or padel event.

## Buffstreams in 0.2.1

Sports also refreshes live NFL/college-football/CFL/NHL game listings from the user-supplied `mybuffstreams.plus`. Choose American football or Hockey, a game, and Source for available alternate servers. Refresh games updates the list. Source address handles site moves without replacing the APK, provided the player/CDN contract remains supported. Native playback resolves public player metadata and uses Media3 directly; no browser or video relay is introduced. Names and dates are shown without scores or result images. Existing feeds/replays remain available if the provider fails. Football full-game archives are not implemented, and native Firestick acceptance is still pending. See `docs/SPORTS-0.2.1.md`.

## Artwork fix in 0.1.1

Fixes the blank posters by accepting the provider’s exact `live.metahub.space` redirect, adding a title-specific alternate size/one retry and removing undecodable cache files. This update uses the same signing certificate as 0.1.0, so install it over the old APK to retain your state.

## Implemented

- Original dark television interface with sidebar, featured artwork, poster rails and visible remote focus.
- Live movie/show discovery, genre browsing, search, details, seasons and released episodes.
- Per-viewer My List, Continue Watching, Next Up and watched indicators.
- Remote-based TorBox setup, Android Keystore encrypted credentials and protected Parent PIN.
- Independent cached-source discovery, conservative movie/episode identity checks, simple alternatives and explicit uncached preparation.
- Media3 fullscreen playback, remote seeking, supported audio/subtitle tracks, resume rewind, Start Over, auto-next countdown and Still Watching.
- Bounded recovery, cancellation, progress persistence, Home/suspension handling and playback-only screen-awake behavior.
- Two editable viewer profiles, Kid Mode viewing allowances and television settings.

Kid Mode controls viewing time and episode/movie allowances. It does not guarantee age-appropriate catalog content. QR phone control is available. Browser-state synchronization, cloud profiles and transcoding are not implemented. The first release is usable independently of browser data.

## Install and test

See [phone-only sideloading instructions](docs/SIDELOAD.md). Enter your API key in the application; never put it in chat, source code or an issue. Test playback using a title you are entitled to access.

Primary device: Amazon Fire TV Stick 4K Plus (2025), Fire OS 8 / Android 11 / API 30. The native Android APK does not target Vega OS devices. This application requires Android API 26 or later, no Google Play Services, and a compatible hardware decoder for the selected source. See [HANDOFF](HANDOFF.md) for verification limits and current blockers.

## Build and verification

The supplied APK was built with JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, SDK 36 and build tools 35.0.0. Media3 is pinned to 1.11.1. The checksum-pinned Gradle wrapper is included.

```sh
python3 scripts/check_boundaries.py
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

The optional anonymous integration check uses `TORBOX_TV_LIVE_METADATA=1` with `testDebugUnitTest`. It exercises public catalog/source services, not TorBox authentication or streaming. Android tests require a running API 30 or later emulator/device:

```sh
./gradlew connectedDebugAndroidTest
```

Historical 0.2.3 results: **126 JVM tests passed, one optional live metadata test skipped, lint zero errors/31 warnings, APK signature passed with the original certificate**. All sixteen startup scenarios and eight Sports scenarios passed. Android tests compiled but were not run on a device. The APK contains the exact final catalog: 39 live channels, 51 live source choices and 157 dated full replays across 26 sports categories. Details are in HANDOFF and [Sports 0.2.3](docs/SPORTS-0.2.3.md); historical reports retain their original evidence.

## GitHub Actions and releases

`.github/workflows/android.yml` is authored for the independent native source repository. It checks JVM/lint/boundaries and API 30 Android cases, but produces CI-only APKs with a temporary test identity. It must not publish those as user updates. The separate native repository remains unavailable; this revision was exercised on the isolated ProjectStatus verification branch, with exact results and run limits in HANDOFF and the 0.2.4 report.

The original-signed APK update channel is published on the website repository's separate native-updates branch, without changing website main. Prepare future verified original-signed channel files with scripts/prepare_update.py as described in [APP-UPDATES](docs/APP-UPDATES.md). Preserve all prior versioned artifacts and original signing identity.

## Documentation

- [Architecture and project state](HANDOFF.md)
- [Version changes](CHANGELOG.md)
- [Physical device acceptance checklist](docs/ACCEPTANCE.md)
- [Safe MovieBox reference review](docs/REFERENCE-REVIEW.md)
- [Toolchain evidence](docs/BUILD-ENVIRONMENT.md)
- [Runtime dependency notices](docs/THIRD-PARTY-NOTICES.md)
