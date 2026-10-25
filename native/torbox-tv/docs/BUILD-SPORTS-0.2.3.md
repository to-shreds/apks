# TorBox TV 0.2.3 build report

The independent native Sports update is compiled and signed with the original certificate. Install it over the existing local APK without uninstalling.

## Delivered behavior

- 39 live channels, 51 direct live source choices and 157 dated full replays across 26 sports categories. The bundle previously had four live channels and sixteen padel replays.
- A focused **Watch Sports without TorBox** action on first launch, and Sports in the sidebar. No TorBox key or account is required for Sports. Adult-profile/Parent PIN protection remains.
- Sport/provider filters, live search and 48-card pages. The explanatory availability section is removed.
- Preserved Buffstreams live-game refresh, configurable site address and alternate servers, plus Sports quality/audio/Go live/replay resume/Start over.
- Native-only public fubo Sports Network, SURFER TV and Pac-12 Insider feeds, whose media probes passed but browser permissions were missing.
- Every media request goes directly to an approved public provider CDN. No video, manifest, segment or media key uses Render. The guard keeps exact HTTPS hosts and rejects credentials and unsupported redirects.
- The 0.2.2 startup improvements, source/account/policy/state/history/settings/PIN formats and movie playback are preserved. Ten core runtime files are byte-identical to the source baseline.

## Actual verification

| Check | Result |
| --- | --- |
| Gradle assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug | BUILD SUCCESSFUL, 1 minute 58 seconds |
| JVM generated XML | 127 registered, 126 passed, 0 failures/errors, 1 optional metadata check skipped |
| Sports / startup scenarios | All 8 Sports and all 16 startup checks passed |
| Android lint | 0 errors, 31 warnings |
| Native isolation/privacy boundaries | Passed |
| Native app and device-test APK compilation | Passed |
| Instrumentation execution | Not run on a device/emulator |
| APK v2 signature and original-certificate match | Passed |
| APK ZIP CRC / manifest identity | Passed |
| Packaged catalog equals final source bytes | Passed: 196 items, 39 live, 51 live choices, 157 dated replays, 26 categories |
| Actual Firestick playback and authenticated TorBox timing | Not run |

All 141 new replay entries have official event dates matched by exact resource ID, and public resource/master/variant/media probes passed. The earlier sixteen padel replay identities/bookmarks remain. Replay additions include a full Buffalo Sabres game and public Red Bull American football sessions; no complete NFL full-game archive is claimed. Channel names describe feeds rather than guaranteed coverage of a particular league game. Scores, results, provider HTML and imagery are omitted.

## Package identity

- Package: `app.torboxtv`; version **0.2.3**, Android versionCode **6**.
- Minimum Android API **26**, target **35**, compile **36**.
- APK size **6,993,926 bytes**.
- APK SHA-256: `341468c84946aed57f08b9ee0a114ef9760f25d5c06fc8ef87bed96feab1e0d5`.
- Original certificate SHA-256: `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`.
- Final catalog SHA-256: `2161721861cf002b62d5e0c81af90c0c4e911ff7bbf6cb1da25a6b317612c2fe`.

Build toolchain: Gradle **8.13**, Corretto JDK **17.0.20.12.1**, SDK 36 revision 2, build tools **35.0.0**. Dependencies, architecture and original signing identity remain unchanged. The private signing key, SDK/JDK/Gradle caches, build outputs and local build configuration are excluded from the source ZIP.

The source archive includes updated HANDOFF/README/CHANGELOG, Sports and installation/acceptance documentation, authored workflow, JVM/device tests and actual evidence under `docs/evidence/0.2.3/`. The independent native repository remains unavailable; no native Actions or GitHub Release publication is claimed. The website remains independently released. Keep the 0.2.2 source and APK for rollback. Android normally blocks in-place downgrades, and uninstalling erases this app's local data.
