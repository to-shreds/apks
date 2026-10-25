# Changelog

## 0.2.4 native reliability revision

- Add a QR-paired local phone-browser remote with live search, directional controls and playback controls. Keep all video on the TV and all controls on the LAN.
- Add automatic GitHub update checks, a Settings check/toggle, approved downloads with progress, original-signature verification and the native Fire TV installer handoff.
- Keep first-run API-key setup and the sidebar reachable with the remote, including in Kid Mode.
- Preserve pause/screen-off intent while a movie link opens; stop obsolete media and reset playback deadlines during source transitions and terminal failures.
- Prevent old or invalidated discovery operations from replacing newer cached metadata, and retry providers after partial outages.
- Atomically record uncertain torrent creation across client instances so overlapping preparations cannot send duplicate creation requests.
- Recover missing primary profile/history and viewing-allowance records from existing backups without losing child-profile restrictions.
- Reload incomplete title details after returning from the background.
- Cancel actual public replay HTTP work on navigation; restore explicit Sports Retry and preserve source-switch playback intent.
- Preserve catalog identities, existing state formats, direct provider media delivery and the original APK signing certificate. Bump Android versionCode to 7.

## 0.2.3 expanded sports

Expand independent public live-channel and full-event replay discovery. Add catalog-driven sport/provider selection and live text search, with 48-card pages for television browsing. Remove the availability explanation from Sports. Make first launch offer a focused **Watch Sports without TorBox** action; retain adult-profile enforcement and allow local profile/settings access before account setup. Movie/show setup and the 0.2.2 startup changes remain intact. Every native media request still goes directly to an approved provider CDN, never Render. Update Android versionCode to 6 and the authored CI artifact name to match this version.

## 0.2.2

- Begin playback with the first verified cached source without waiting for slower discovery or extra alternatives.
- Reuse public source metadata and prefetch focused title/episode and near-end next-episode metadata.
- Keep background fallbacks cancellable and maintain source identity, cached-only preparation and attempt limits.
- Add duration-only startup diagnostics; retain the 0.2.1 sports/Buffstreams connector and original signed upgrade path.

## 0.2.0 sports · 2026-10-10

Add independent native Sports browsing and Media3 playback before TorBox setup. Bundle T2 tennis, Red Bull TV, Pickleball TV and NHL classics/coverage, plus sixteen full Premier Padel sessions from four 2026 tournaments. Replays use the current anonymous Red Bull player API; streams go directly to public sports CDNs, without subscriptions, login, VPN, browser or Render. Default quality is 720p, with lower/upper/automatic selection, native audio controls, Go Live, replay bookmarks, Start Over, bounded failure and remote channel switching. Sports requires an adult profile so Kid Mode limits cannot be bypassed.

The APK and instrumentation APK compiled; 97 active JVM tests passed with one optional metadata test skipped. Lint has zero errors. Actual Firestick playback and the existing thirteen instrumentation scenarios remain unverified. Use the matching-signature update over the existing APK.

## 0.1.1 artwork fix · 2026-10-10

Accept the provider’s exact HTTPS live.metahub.space redirect, which had caused blank posters. Add an exact-title medium-size fallback and one bounded retry; remove undecodable cache files. Preserve image concurrency/cache limits, remote behavior, playback and local state.

Compiled and v2-signature verified with the same certificate as 0.1.0. All 93 active JVM tests passed, including eight new artwork regressions; one optional metadata check skipped. Lint passed with zero errors. Android device and authenticated playback checks remain outstanding. Install over 0.1.0; previous artifacts remain archived.

## 0.1.0 test build · 2026-10-10

Initial independent native Fire TV application. Adds artwork-based browsing and details, remote navigation/search, encrypted TorBox setup, viewer profiles and local lists/history, native Media3 playback, conservative source matching and cached selection, explicit uncached preparation, configurable resume/auto-next/Still Watching, bounded recovery and Kid Mode viewing allowances.

Includes 86 passing JVM tests, clean Android lint, a compiled 13-test Android instrumentation suite, native boundary checks, a checksum-pinned build wrapper and a GitHub Actions build/emulator/prerelease workflow.

The APK compiled locally and its signature verified. GitHub workflow execution/publication is pending creation/access to the independent repository. Authenticated TorBox playback, physical Firestick behavior and rendered UI quality are not yet verified. The local software emulator did not boot, so the Android tests did not execute. Phone pairing and browser-state sync are deferred.
