# TorBox TV handoff

## Expanded account-free Sports 0.2.3 · 2026-10-11

Current compiled candidate **0.2.3**, Android versionCode **6**, is based on the complete original-certificate 0.2.2 source archive. Keep 0.2.2 APK/source for rollback. The independent website has its own source/version/publication; the native runtime contains no website, Render endpoint, video proxy or browser wrapper.

The native bundle grows from four live channels and sixteen padel replays to **39 live channels, 51 direct live source choices and 157 dated full replays across 26 sport categories**. The 141 new full sessions passed anonymous public resource, HLS master/variant and media-byte probes. Every new date is matched to an official live-card resource ID; dates range from 2022-10-21 to 2026-10-10. Titles retain session/team/year identities without scores, result descriptions or images. The old sixteen padel replay IDs remain so their bookmarks are not lost. Replays include one full Buffalo Sabres hockey game and Red Bull American football events; a complete NFL game archive is not implemented. NFL/NBA/MLB/FIFA+/NHL/FloHockey and numerous motorsport, golf, rugby/cricket, tennis/pickleball, combat/wrestling, billiards and outdoor/action channels are channel feeds, not a guarantee that a particular league game airs. The existing live Buffstreams event/browser/source-address connector is retained unchanged.

Three added feeds are native-only: fubo Sports Network, SURFER TV and Pac-12 Insider. Their direct public HLS/media probes passed, but browser-origin permissions were missing. The separately maintained website therefore has 36 live channels. Native Media3 does not depend on browser CORS. Every native network exchange is still guarded: HTTPS, standard port, no URL credentials, no Authorization/Cookie, exact approved CDN hosts and the existing narrowly scoped Buffstreams Referer/R2 accounts. New live hosts are the exact tested set; no broad Amagi/CloudFront/Akamai wildcard was added. There are 49 exact approved hosts plus the unchanged two-account R2 rule. No video, playlist, media key or segment is sent through Render.

First launch now offers and focuses **Watch Sports without TorBox** on an adult profile. The Sports route still runs before the movie account gate; local Profiles/Settings can be opened before setup, retaining existing Parent PIN controls. Both Sports activities check Kid Mode before opening/playing. Movies/shows still require their normal account setup. Sports browsing adds catalog-driven sport/provider filters and live title/sport/provider search, with a maximum of 48 cards per page. The explanatory availability section was removed. Existing quality/audio/Go live/replay resume/Start over, foreground cancellation and screen-awake behavior remain.

Verification completed: Gradle `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` **BUILD SUCCESSFUL in 1 minute 58 seconds**. Actual JVM XML: **127 registered, 126 passed, zero failures/errors, one optional metadata check skipped**. Eight Sports and sixteen startup scenarios passed. Lint: **zero errors, 31 warnings**. Native boundaries pass. App and instrumentation APK compilation passed; device instrumentation, actual Firestick video/audio/remote behavior and authenticated TorBox playback/timing remain **not run**. Public CDN probes establish public media responses, not device playback. Read-only direct-media review found no inspected-source blocker.

Compiled package `app.torboxtv`, versionName 0.2.3/versionCode 6, minimum API 26/target 35/compile 36; APK **6,993,926 bytes**, SHA-256 `341468c84946aed57f08b9ee0a114ef9760f25d5c06fc8ef87bed96feab1e0d5`. V2 signature passes with the original certificate `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`; install over prior local APKs without uninstalling. ZIP CRC passes. The packaged final sports asset equals source byte for byte, SHA-256 `2161721861cf002b62d5e0c81af90c0c4e911ff7bbf6cb1da25a6b317612c2fe`, with all 196 rows and all 157 replay dates. Toolchain: Gradle 8.13, Corretto JDK 17.0.20.12.1, SDK 36 revision 2/build tools 35.0.0. Original private signing material stays outside source and artifacts.

Actual evidence is in `docs/evidence/0.2.3/`; feature/limits in `docs/SPORTS-0.2.3.md`; physical checks in `docs/ACCEPTANCE.md`. Ten account/source/state/policy/movie/Sports-player/Buffstreams/artwork runtime files remain byte-identical to 0.2.2, with SHA evidence under `source-boundaries.json`. No startup policy or local movie/episode/history/key/settings/PIN format change is introduced by this Sports release. The authored CI filename now matches 0.2.3, but the separate native repository still cannot be accessed; no native Actions/release publication is claimed. Versioned APK/source artifacts remain authoritative until that repository exists.

Next: install the 0.2.3 update without uninstalling, test Sports with no TorBox key, browse the expanded filters/search/pages and play a live channel plus a full replay on the actual Firestick. Confirm quality/audio/Back/Home/resume and Kid Mode protection. Retain the independent website and previous native source/APKs.

## Startup improvements 0.2.2 · 2026-10-11

Current candidate **0.2.2**, Android versionCode **5**, is based on the authoritative Library 0.2.1 source ZIP. That newer ZIP retained Buffstreams, despite the older ProjectStatus record still describing 0.2.0. Original sports assets, provider/media guards, encrypted key/settings/history/PIN formats and exact movie/episode/year/language policies are preserved.

Anonymous source responses now arrive as bounded cumulative batches. The first fully validated cached choice can open before slower public providers finish and before alternative versions are prepared. Each public provider has its own deadline; TorBox validation remains inside the foreground caller's separate deadline. A process-shared, bounded five-minute public metadata cache retains partial discovery without treating it as complete. Failed discovery is not cached as a successful empty response. Cached rows are revalidated against the current target.

Title details and focused released episode rows prefetch anonymous metadata only. Auto-next metadata warming runs during the last three minutes of active playback and refreshes after four minutes when needed, so a normal-length episode does not outlive an early preload. Prefetch never reads the TorBox account, adds a torrent, obtains a signed playback URL or downloads video. Page/Activity cancellation stops obsolete work.

Only the first ready choice is prepared before initial playback. Other cached versions are checked after the player becomes ready. Foreground source changes, recovery and Stop cancel that optional work. Failed initial links can search remaining provider results immediately rather than waiting for an unavailable fallback job. Automatic validation/preparation stays bounded to five actually examined cached candidates and three playback sources; cancelled old-target callbacks cannot consume the next target's budget. No-cache-hit batches skip account pagination entirely.

Fixed-label startup durations can be read through Android logcat tag `TorBoxStartup`: source batch, cached choice, playback link and first rendered frame. Logs contain no title, source name, URL, credential or response body. ExoPlayer's startup buffer remains unchanged. Video still travels directly from TorBox to the device; Render and the independent website are not native dependencies.

Verification completed: app/instrumentation APK compilation and Gradle checks passed; JVM **123 registered, 122 passed, zero failures/errors, one optional check skipped**; all sixteen new startup scenarios passed. Lint has **zero errors, forty warnings**. Native boundaries pass. APK v2 certificate matches prior versions exactly; compiled package/version/SDK and ZIP integrity passed. APK SHA-256: `904292756d4d9337395896297c413a954d793e47c42987dd3e4420f2f7953d38` (6977086 bytes). Full evidence is in `docs/STARTUP-0.2.2.md` and `docs/evidence/0.2.2/`. Actual account/video/Firestick timing is not yet measured; the authored device scenarios compiled but were not executed. Separate-native-repository publication and physical-device acceptance remain unresolved.

Next: install the independently versioned 0.2.2 APK as an update, without uninstalling, using the completed build report. Compare cached movie/episode start times and resume/next behavior on the device. Do not claim sub-second performance from virtual-time tests.


## Buffstreams live connector 0.2.1 · 2026-10-10

Current candidate **0.2.1**, Android versionCode **4**, retains the complete 0.2.0 implementation and adds native Buffstreams live game browsing. The user supplied `https://mybuffstreams.plus` and noted changing domains. Sports refreshes its current public NFL/college-football/CFL/NHL listings on entry and on Refresh games. Source address changes only this provider setting; validated HTTPS redirects are bounded. It needs no TorBox key, extra account/subscription, VPN configuration, browser or Render. Football and NHL entries are game streams, not a promised archive or guaranteed live NFL coverage test.

Only event names/schedule dates are retained. Finished events and result-bearing titles are omitted; scores, result images and provider HTML are never displayed or persisted. A separate public listing cache expires after twelve hours and is tied to the configured source address. Refresh preserves card focus when possible. Changing the source leaves movie credentials/history/settings and the original sports assets untouched.

On Play, fresh page/player metadata yields up to four server choices without executing provider JavaScript. Native Media3 uses the public player Referer and user agent. Masters/variants stay on two exact verified hosts; segments stay in the two probed R2 accounts. Every media exchange rejects non-HTTPS destinations, unsupported accounts/hosts, Authorization/Cookie and invalid Referer. No signed segment URL is persisted. Unrecognized provider contract changes fail clearly rather than expanding trust automatically. Metadata has byte/time/redirect limits and cancellation cancels the OkHttp call. The existing quality/audio/Go Live, adult-profile and foreground/lifecycle rules remain.

The app and instrumentation APK compile. Final JVM results: **107 registered, 106 passed, zero failures/errors, one optional check skipped**, including nine new parser/resolution/redirect/cancellation/size/media-policy scenarios. Lint: **zero errors, 32 warnings**. Boundaries pass on thirteen Kotlin files. APK v2 signature matches 0.1.0/0.1.1/0.2.0 exactly; package/version/SDK and the final result-title guard are verified in the built APK. APK SHA-256: `6d7c05ae4ef7a2861921dd5fc6333c483e1d5106bfd12996cfc6b64297eb86ca` (7,096,254 bytes).

Public probes of two college-football servers returned masters, variants and MPEG-TS bytes. A bounded probe identified 1280x720 H.264/AAC. Actual Firestick playback/audio/remote/lifecycle remains unverified, and the thirteen device scenarios are still compiled only. The separate native GitHub repo still returns 404, so versioned source/APK artifacts control this implementation. The previous build tools had pointed into an unavailable workspace; tools were restored from official distributions and build-only Work proxy CAs, without weakening Android TLS or changing the signing key.

**Web and archive limits:** the provider rejects GitHub Pages Origin requests, restricts segment CORS to its own player and excludes GitHub Pages from its frame-ancestor allowlist. No working Buffstreams web integration is established; the independent web runtime remains sports.1 with documentation-only findings. No full Buffstreams football replay archive has been verified or added. Do not introduce a video-byte relay or claim these parts complete.

Next: install 0.2.1 over the existing APK, open Sports, select American football, and test a live listed game and its alternate servers on the Firestick. If only the site address moves, change Source address and Refresh games; a changed player/CDN contract can require a connector update. Preserve old APKs/source and the independent browser project. Find a separately verifiable football replay source and a browser-permitted live source for the remaining original scope.

## Sports 0.2.0 · 2026-10-10

Current version **0.2.0**, Android versionCode **3**. Full existing native implementation retained; this adds a separate native Sports catalog/player usable before TorBox setup. Four free live feeds and sixteen full padel replay sessions are bundled. The archive is a release snapshot; no automatic event-guide refresh is claimed. No additional subscription, account, VPN, browser or Render is needed by Sports. Region/provider availability can still vary.

SportsClient resolves public replay IDs through the current anonymous Red Bull player API. Separate exact HTTPS media guards check every network exchange and forbid Authorization/Cookie. Source metadata never renders scores, descriptions or result images. Default 720p, quality/audio controls, Go Live, local replay bookmarks/Start Over, bounded startup/buffering failure, Retry and channel navigation are implemented. Sports is limited to adult profiles, so Kid Mode movie/episode allowances cannot be bypassed. Foreground generation guards and release on Stop prevent late/background playback. Existing TorBox validators/state/artwork remain.

Build and instrumentation compilation passed. JVM **98 registered, 97 passed, zero failures/errors, one optional metadata check skipped**; lint has zero errors. Boundary script passed on twelve Kotlin files. Signature matches 0.1.0/0.1.1 exactly; install as an update without uninstalling. See docs/SPORTS-0.2.0.md and docs/evidence/0.2.0/ for exact checksums/lint/toolchain. Real public streams/replay played in the independent browser, but Firestick Media3/remote/audio/lifecycle acceptance and thirteen device tests remain unexecuted. The independent code repository is still unavailable. Overall readiness remains BLOCKED on that publication and actual device acceptance, not on compiling the Sports feature.

The separate browser Sports update uses release-1.2.0-sports.1. It is independently tested/published; no shared runtime or release mechanism is introduced. Render remains untouched with the earlier workspace gate. Old APK/source versions remain available for rollback, and private signing material remains outside the source archive.

Next action: install 0.2.0 over the existing APK and verify Sports video/audio/remote/seek/resume/Home/Back behavior on the actual Firestick. Preserve this complete source; do not rebuild the native project from scratch while waiting for repository access.


Updated 2026-10-10 UTC. Current version **0.1.1**, Android versionCode **2**. Overall state remains BLOCKED on independent GitHub repository creation/Actions publication and authenticated device acceptance; the artwork fix has compiled and passed its available automated checks.

## Current artwork patch

The user installed 0.1.0 and supplied screenshots showing blank posters for Unabomber, East of Eden and War. Live read-only probes of their exact IDs (`tt6933238`, `tt21097264`, `tt37972251`) followed `images.metahub.space` to **`live.metahub.space`**, returning valid JPEGs at both small and medium sizes. The native network interceptor rejected that exact live host. The independent website had the same missing host in its image CSP. These are concrete policy failures; missing title metadata was not the cause of the screenshots.

The native patch adds the exact live host to shared artwork URL validation, retaining HTTPS/port/credential checks and redirect validation on every network exchange. It provides an exact-ID medium-size fallback and one delayed retry, within a 28-second foreground artwork deadline. Offscreen laziness, three download slots, 12 MB memory/40 MB disk limits, 8 MB response limits and navigation cancellation remain. Failed bitmap decodes remove the invalid cache file so it cannot poison later loads. Catalog loading and media/source operations are unchanged.

The user expressly authorized a matching website artwork fix in this later request. That web patch is maintained in the existing web repository independently: its HTML image CSP now includes the exact live host; its image loader uses the same title-specific size fallback and one delayed retry; its frontend cache token is `release-1.2.0-artwork.1`. No shared release mechanism or runtime TV dependency was introduced. The original 0.1.0 implementation stage left the web app untouched; the later authorized image patch is the narrow exception. No Render branch/configuration/service operation or playback/storage/authentication contract was changed by this artwork work.

## Current verification and install

- APK and instrumentation APK compiled: BUILD SUCCESSFUL in 3m 32s on a freshly provisioned isolated toolchain.
- JVM: **94 registered, 93 passed, 0 failures/errors, 1 optional live metadata test skipped**. Eight new artwork tests cover the real host, lookalikes/credentials/cleartext, exact-ID fallback/deduplication, success without extra calls, alternate-size recovery, bounded retry, cancellation and empty plans.
- Android lint: **0 errors, 18 warnings**. Native boundary script passed on 9 Kotlin files.
- The three real public image pairs returned valid JPEGs, decoded by Pillow. This checks public artwork availability, not APK display or TorBox playback.
- APK v2 signature verified. Certificate SHA-256 matches 0.1.0 exactly: `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`.
- Current APK: **TorBox-TV-0.1.1-test.apk**, 6,924,314 bytes, SHA-256 `4a80d26b5c38d0fcd3f1ff54c2ce6c77aadc41b07ae0cd167fcbec913fd24aad`.
- Install 0.1.1 over the earlier local 0.1.0 APK; its higher versionCode and matching certificate preserve TV profiles/key/history/settings. Do not uninstall first. Future CI test signing still differs unless a durable private signing configuration is established.
- Thirteen Android instrumentation tests remain compiled but not run. No new emulator attempt or actual authenticated TorBox playback occurred. User screenshots confirm the old app's displayed UI, not verification of this new APK or the device model/Fire OS.

Current evidence is under `docs/evidence/0.1.1/`; the preceding 0.1.0 evidence and APK/source archive remain available for rollback. The independent `to-shreds/torbox-tv` repository still returns 404 and creation is not exposed by Work. Archive the new APK/source as versioned ProjectStatus artifacts and update its STATUS last. Do not restart the app implementation.

## Next action

Install this APK update and reopen Movies and Shows to confirm the three posters. Separately create the independent repository with an initial main branch and Work access so the existing workflow can run. Continue the remaining playback/remote/parental acceptance checklist; do not call the full app family-ready solely from artwork checks.

## Architectural baseline retained from 0.1.0

The following is the original architectural handoff and historical 0.1.0 test evidence. Current version/results are above.


Updated 2026-10-10 UTC. Version 0.1.0, Android versionCode 1. Current overall state: **BLOCKED** on independent GitHub repository creation/publication and device acceptance. A compiled, signed test APK is available; the family-ready playback success criterion is not yet established.

## Controlling sources and strict separation

The completed native source tree and versioned source archive control this implementation until `to-shreds/torbox-tv` is created and populated. All native files, workflow and release configuration belong to this new project alone.

Before implementation, the actual ProjectStatus README and web-player STATUS, HANDOFF, README and relevant source were read. The browser repo's main commit was checked before and after work: `9fc15eccb9a3ffefc6e63ac76e982bfa8d738790`. Its current documented frontend is 1.2.0; its documentation reports live Render backend 1.1.0 with a pending workspace gate. No assumption that the documented pending backend deployment occurred was made, and no deployment was attempted.

The existing repository was read only. Its local checkout remains clean. No existing web code, branch, Action, release, HTML, service worker, settings, authentication, status or deployment was changed. No Render service operation was performed. No browser storage was read or migrated. The native app contains no WebView, web frontend or existing Render endpoint. TV development, builds and rollback cannot redeploy the browser application.

The MovieBox APK was safely inspected through ZIP/resource/manifest reads only. It was not run, installed or decompiled, and no proprietary code, assets or fonts were copied. See `docs/REFERENCE-REVIEW.md`. The dark/sidebar/poster/focus design is original. The reference was mobile portrait, so no exact television runtime match is asserted.

## Native architecture

`MainActivity` provides a landscape TV sidebar and programmatic native Android views. Live catalog loading is independent of TorBox checks. Each page has cancellation/generation guards, bounded loading and retry states. `Artwork` loads visible posters with three concurrent downloads, a 12 MB memory cache, 40 MB disk cache and bounded image responses. Focus borders and small focus animations expose remote selection.

`Catalog` uses anonymous Cinemeta metadata/catalog/search endpoints, with a relevance-checked fallback. Title details provide current episode lists and release dates. `SourceClient` concurrently queries Zilean, Torrentio and two configured StremThru endpoints with time/request bounds, identity checks and deduplication. No paid metadata key or hosting is required. These public services can fail or change independently; errors and fallback are expected. Torrentio returned HTTP 403 in one environment probe and is not a prerequisite for successful discovery.

`TorBoxClient` talks directly to the official TorBox API. User verification, cache checks, account reconciliation, torrent creation and signed stream-link generation are independent of the browser backend. Exact returned file IDs are selected after filename validation; provider file indices are never trusted. Existing owned torrents are reconciled before additions. Automatic additions use `add_only_if_cached=true`; uncached preparation is a separate explicit user action. At most five candidates are attempted and three automatic compatible alternatives retained.

A persistent, account-hashed operation ledger records a create operation before the POST. An uncertain creation is not blindly retried after interruption or restart. Account listing is bounded to 1,000 torrents; larger accounts fail closed with an actionable cleanup message. Explicit preparation polls read-only status at most twelve times and has an overall deadline, without repeatedly adding the torrent.

`PlayerActivity` owns a Media3 ExoPlayer, native PlayerView and media session. HTTP video requests and redirects are guarded to HTTPS TorBox CDN domains, excluding the API host. Video never travels through Render, GitHub, a project server or a metadata provider. Signed playback URLs are held in memory only. Existing TorBox link contracts may include credential-bearing query parameters, so URLs and response bodies are not logged or persisted. No transcoding or media proxy exists.

Playback source recovery is bounded to three distinct sources, with initial-start and buffering watchdogs. Cancelled selections cannot launch a late player. Home/suspension saves progress, cancels pending work and releases the player; return reinitializes paused. Audio unplug/screen interruptions pause playback. Active playback keeps the screen awake. Seeking, track selection, resume rewind, Start Over, auto-next, cancelable countdown and Still Watching are implemented. Actual watched-time accounting excludes seek jumps and delayed background ticks.

## Identity and compatibility decisions

Wrong-title prevention takes precedence over filling an empty alternative list. Movies require consistent normalized title and release year; trailers/samples, sequel collisions and ambiguous files are rejected. Episodes require exact season/episode identity and conservative title matching; multi-episode ambiguity and competing known episode names are rejected. An explicit TV release year must equal the catalog premiere year, preventing overlapping remake identities such as Ghosts. This can reject legitimate torrents labeled only by an episode's air year. Missing-year releases still require the remaining provider/file identity checks. A mislabeled provider/file cannot be made mathematically certain without viewing it, so physical playback must confirm actual identity.

Source ranking respects the profile quality limit, preserves resolution bands, favors practical H.264/AAC sources and rejects known foreign-only release suffixes when English/MULTI/DUAL is absent. It does not infer language from words in the title such as Russian Doll. ExoPlayer prefers an English audio track when present. Actual spoken language, decoder behavior, HDR/audio passthrough and subtitle rendering require device tests.

Amazon's current specification identifies Fire TV Stick 4K Plus (2025), AFTMA08C15, as Fire OS 8 / Android 11 / API 30 with 2 GB RAM and 32-bit Android. This app has minSdk 26, targetSdk 35, compileSdk 36 and no Google Play Services/native ABI-specific app libraries. Do not confuse this device with Vega-based models. Hardware codec support does not guarantee every source's container, profile, audio or bitrate will work. The APK's launcher/leanback launcher and signature were verified from its compiled manifest.

## State, credentials and parental controls

`StateStore` maintains two named viewer profiles, settings, per-profile history/My List and daily usage locally. Android Keystore AES-256-GCM protects the TorBox key; backup is disabled and setup blocks screenshots/autofill/IME learning. Parent PIN uses a salted PBKDF2 verifier and constant-time comparison. Corrupt PIN/key state fails closed. Primary plus last-good state, separate parental guards and serialized reload/write operations keep two Activity instances consistent. Usage lives separately to avoid serializing the catalog on every playback tick.

History is bounded to 80 entries and My List to 200; an over-limit list gives an error rather than silently evicting entries. Episode metadata is bounded in stored history, and live details remain the canonical episode source. Continue Watching can be removed individually. Browser state does not sync automatically. Initial setup uses masked remote entry with verification before saving; no phone-pairing relay or sync server was introduced.

Kid Mode preserves the browser's viewing-allowance concept: daily minutes, episode/movie limits, parent override and PIN-protected changes/profile exits. It is not an age-rating filter. Episode charging occurs at five minutes or 20% of duration, movie charging at ten minutes, and the already charged item can finish. Parent overrides and midnight reset are covered by policy tests. Actual Android persistence/enforcement tests are authored but not executed.

## Completed verification

Final local build command: `TORBOX_TV_LIVE_METADATA=1` with Gradle `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`. Result: **BUILD SUCCESSFUL**, 49 seconds for the final incremental verification. An actual installable APK was produced, not just source files.

| Check | Actual result |
|---|---|
| Policy/configuration/identity/ranking/resume/auto-next/parental tests | 66 passed |
| Playback timing and quota clock tests | 4 passed |
| API/mock-server contract and error handling tests | 15 passed |
| Real anonymous native catalog/search/detail/source discovery check | 1 passed, 16.539 seconds |
| Total JVM tests | 86 passed, 0 failed, 0 errored, 0 skipped |
| Android lint | 0 errors, 25 warnings; task passed |
| Native/privacy/TV boundary script | Passed |
| Debug app and instrumentation APK compilation | Passed |
| Android remote/state/Keystore instrumentation execution | Not run; 13 methods authored |
| APK signature / launcher / SDK inspection | Passed, APK v2 signature |
| Authenticated TorBox creation, cache/link and video playback | Not run |
| Physical Fire TV / visual render / codec tests | Not run |
| GitHub Actions and GitHub Release | Not run/published |

The software API 30 emulator failed to boot within 180 seconds on a host without `/dev/kvm`; it was stopped. No emulator screenshot, installed-app UI result or device-test pass is claimed. Test fixtures, syntax checks and a compiled instrumentation APK are not real playback evidence. The anonymous live test uses no TorBox key and performs no torrent addition or video download. Lint warnings primarily concern known newer dependency versions/API usage and deliberate synchronous local persistence; errors were resolved without suppressing them wholesale.

Evidence is archived in `docs/evidence/0.1.0/`; see `docs/BUILD-TEST-REPORT.md`. APK size: 7,030,466 bytes. SHA-256: `e0da66cb0d6b74df156e81ab233ea15786ce9598c04207b365512a9466df5df6`. Signing certificate SHA-256: `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`.

## Build, release and rollback

Pinned toolchain: JDK 17.0.20, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, SDK 36, build tools 35.0.0, Media3 1.11.1. The official Gradle distribution checksum and generated wrapper are included. Tooling was provisioned in an isolated directory; no global Android install or old project was modified.

The new Actions workflow builds/tests/lints/signature-checks the APK, runs API 30 Android tests on an accelerated runner, archives evidence and publishes `v0.1.0` as a prerelease only after both jobs pass. Main pushes and version tags can publish; an existing version is retained. Bump VERSION, Android versionName/versionCode and the artifact filename together. No workflow was installed or run in the browser repository.

The current APK is debuggable and signed with a local Android test key. Its matching private signing checkpoint is preserved separately and excluded from the source archive and GitHub artifacts. CI uses its own cached test key; local-to-CI switching may require one uninstall and reentry of credentials/settings. Cache expiry can change a CI test key. Establish a durable private release signing secret before relying on in-place upgrades. Never commit a keystore. Android refuses a normal install downgrade; uninstalling first permits rollback but erases this app's local data. Keep earlier APK versions available and document this limitation. No production signing or backup/import system is claimed.

## Blockers and next steps

1. The current GitHub connection returns 404 for `to-shreds/torbox-tv`, and tools expose content/Git writes but no repository creation. Create that independent repository with an initial README/main branch and give Work access. Upload this completed source tree; do not restart implementation or reuse the browser repository.
2. Run the authored Actions workflow. Inspect all build and Android-test results, fix concrete failures and publish the versioned prerelease only when required jobs pass. No existing browser release/service is involved.
3. Install the current test APK from a phone and enter the key on the Fire TV. Perform `docs/ACCEPTANCE.md`: actual cached movie/episode identity, audio/subtitle/seek/resume, Home/Back, network interruption, auto-next, focus, screen-awake and parental limits. Record device/Fire OS and results. Do not call the app family-ready before these pass.
4. Resolve any real playback or UI issues from that evidence. Keep this source/APK checkpoint and prior versions. Consider phone pairing or secure profile transfer only after the independent first release is stable.

## Official compatibility references

- [Amazon Fire TV Stick 4K Plus specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html?v=ftvstick4kplus)
- [AGP 8.11 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-11-0-release-notes)
- [Android Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [TorBox API documentation](https://www.postman.com/torbox/torbox/overview)
- [Android emulator runner](https://github.com/ReactiveCircus/android-emulator-runner)
