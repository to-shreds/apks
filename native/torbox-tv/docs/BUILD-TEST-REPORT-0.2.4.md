# TorBox TV 0.2.4 build and verification

**Ready as a signed test update.** Native APK stress testing, repairs, rebuild, original-signature upgrade preservation, GitHub updates and QR phone-browser control are completed. Physical Firestick acceptance and authenticated playback timing remain unmeasured. The independent website was preserved.

## Deliverable identity

| Item | Value |
|---|---|
| User APK | TorBox-TV-0.2.4-test.apk |
| Package/version | app.torboxtv, 0.2.4, versionCode 7 |
| Bytes | 11,590,823 |
| SHA-256 | c49d0de7577b7e0097fdf2b111e14b8523da064fe181b3b7e882d88610e24a7b |
| Original certificate SHA-256 | a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf |
| APK signature | v2/v3 verified; one original signer |
| Android | Minimum API 26, target 35, compile 36 |
| Toolchain | JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, build tools 35.0.0, Media3 1.11.1 |

Install over the existing app without uninstalling or clearing data. Previous 0.2.3 artifacts and original certificate/data formats are preserved. SHA256SUMS-0.2.4.txt inventories the final APK, complete source ZIP, install guide and report. No signing key or account credential is packaged.

## Changes

Playback honors latest pause/toggle/screen-off/interruption intent while source links or explicit preparation are pending, preserves position on source changes and clears obsolete startup/recovery work. Old callbacks cannot affect newer players. Source discovery has bounded ownership and generation-safe cache publication; partial outages remain retryable. Persistent atomic operation claims prevent duplicate uncertain creation across clients; failed persistence prevents the creation request.

Saved-state recovery restores missing primary snapshot/usage records from backup without dropping child restrictions. Encryption, PIN and record formats are unchanged. Interrupted incomplete details reload on return. Setup/search fields have explicit remote-focus exits. Sports cancellation stops actual HTTP requests; Retry/source changes and pending pause/toggle/screen-off preserve intent. Replay labels/bookmarks and the existing 39 channels, 51 live choices and 157 dated replays across 26 categories remain. Adult Sports works without a TorBox key and the removed availability text stays removed.

GitHub updater: automatic foreground checks at most daily, manual Settings checks/toggle, explicit APK download/progress, complete size/SHA-256/actual package/version/original-certificate validation, native installer permission/approval. Old, different-signer or corrupt packages are rejected. No TorBox credential, Render relay or silent installation is used.

Phone remote: Settings QR opens a private-IPv4 LAN control page for native movie/show or Sports search, navigation, playback and seeking. Pairing survives internal screen transitions and closes on background/disconnect/timeout. Requests/workers/rate/sizes are bounded, with exact local Origin/Host and a random session capability. Stale search and expired queued commands cannot execute later. No credentials, PINs, arbitrary URLs, files or video are served.

## Results

| Verification | Actual result |
|---|---|
| JVM suite | 165 registered: 164 passed, one optional live skip, zero failures/errors |
| Separate live metadata | Passed in 10.33 seconds: 49 catalog entries, 7 relevant searches, 37 validated sources; no account writes/media |
| Distinct JVM cases across both runs | **165 passed** |
| Regular Android instrumentation | **43 passed**, zero failures/errors; 45 report entries include two explicit-upgrade skips |
| Original-signed upgrade | **Both phases passed**, original 0.2.3 to exact final 0.2.4 APK |
| Chromium phone page | **Six passed** locally and in CI |
| Lint | Zero errors, 40 warnings, one hint |
| Boundaries | Passed for 17 guarded source files |
| Package checks | Original signature, CRC, frozen source identity, catalog preservation, phone asset identity, no duplicate DEX classes/private keys, original archive hashes |
| Public update channel | Manifest and full APK anonymously HTTP 200; final bytes/SHA-256 match |

Android ran on accelerated API 30 x86_64 at 1920x1080/density 320. Tests use actual Activities, Media3, Keystore/preferences, remote navigation, QR decode, typed native searches, paired phone playback and native installer handoff/cancel. Media is a documented synthetic 120-second H.264/AAC fixture. Upgrade seeding uses synthetic local records, never a real account.

Stress includes 40 rapid player source reopens, 320 discoveries/cache checks, 80 concurrent provider requests, 200 cancel/reopen cycles, 16,000 forbidden media destinations plus 2,000 valid controls, 120 canceled Sports HTTP calls, 50 phone sessions, 72 state writers and 256 persistent operation claims. These bounded adversarial scenarios do not measure unlimited soak or provider bandwidth.

The signed-upgrade sequence installed original 0.2.3, seeded an encrypted synthetic key/PIN/two profiles/history/My List/settings, replaced the APK with adb install -r and verified those exact records in 0.2.4. Seed/verify phases passed in 4.494/1.785 seconds.

## Evidence and CI interpretation

Tested source: ProjectStatus verification commit `147dd8e8f49316c06fdcf122b84a613a12baa5b8`. The 51-file docs/evidence/0.2.4/tested-source-hashes-r5.json fixes app/build/test identity. Final packaging changes documentation/evidence only. The source ZIP bundles JUnit, lint, build/native/upgrade logs, package/signature checks, browser results and public HTTP verification under docs/evidence/0.2.4/.

- [Normal suite run 40060681225](https://github.com/to-shreds/ProjectStatus/actions/runs/40060681225), job 120773287000: JVM/lint/browser and 43 native cases passed. The overall job failed afterward because cleanup tried to uninstall the test package Gradle had already removed. No blanket green-run claim is made. Native artifact 12547901119 has SHA-256 e84680287d1d52c88422871bb91961f64901057f8562803666d7981473864801.
- [Signed upgrade run 40061903081](https://github.com/to-shreds/ProjectStatus/actions/runs/40061903081), job 120777032460: completed successfully; both phases passed. Artifact 12547454217 contains both passing logs.
- Corrected workflow definitions are preserved at commit 2de198a4c308beb15b06296449dc8a431a37f287 and in evidence. Earlier emulator disk/display, stale incremental compilation and harness setup problems were recovered. No unresolved build/test blocker remains.

Original-APK instrumentation reproduced missing-primary snapshot/usage failures before repair. Earlier candidate/failure evidence remains separately preserved and is not described as the final run. The separate native GitHub repository remains unavailable; final source ZIP and ProjectStatus artifacts control native implementation. That does not block delivery or anonymous update checks.

## Published update channel

Repository to-shreds/torbox-web-player, separate native-updates branch, commit ff4c149bfc6d110de3976e204c8e83b1ec94b145.

Manifest: https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/latest.json

APK: https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/releases/TorBox-TV-0.2.4-test.apk

The complete public APK was downloaded anonymously and matched the final hash/size. No native source/private signing material was published there. Website main remains 52303d8b33ef30fc37bc8f748cd322865f8ce982. No website runtime changes were made in this native revision. No GitHub token is embedded in the app.

## Preservation and physical acceptance

All video/manifests/segments/media keys travel directly from approved providers to native Media3. Render carries no video; phone commands stay on the LAN. Automatic Play/Resume/recovery/next episode remains cached-only; uncached preparation requires an explicit choice. Identity, language/container, independent file IDs and bounded reconciliation rules remain intact.

Original 0.2.3 APK SHA-256: 341468c84946aed57f08b9ee0a114ef9760f25d5c06fc8ef87bed96feab1e0d5. Source ZIP: 07575f09e4fd9946ab008fae0ecc0ab4f73535a96b4c2d8e6bfb533fb8d935f8. Both remain unchanged. Android generally blocks lower versionCode; uninstalling for downgrade removes data.

Install the signed test update now. Physical Firestick audio/video, actual camera/Wi-Fi pairing, Fire OS permission wording, authenticated TorBox first-frame timing, quality switching, resume/auto-next and parental behavior need real-device acceptance. No instant-start guarantee for every provider or production-scale load is claimed. This APK targets Android/Fire OS, not Vega OS. Follow the install guide for the first replacement, then use Settings for future updates and QR phone control.
