# 0.1.1 artwork build and test report

2026-10-10 UTC. The current APK and Android instrumentation APK compiled successfully with the pinned toolchain: JDK 17, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, compile SDK 36, build tools 35.0.0 and Media3 1.11.1. Final output: BUILD SUCCESSFUL in 3m 32s. Initial host compiler detection was resolved using an isolated JDK launcher/toolchain path; no system or original browser build configuration was changed.

| Check | Actual result |
|---|---|
| PoliciesTest | 66 passed |
| PlaybackTimingTest | 4 passed |
| NetworkTest | 15 passed using contract fixtures |
| ArtworkUrlsTest | 8 passed |
| LiveMetadataTest | 1 optional test skipped in this run; earlier 0.1.0 live result remains archived |
| Total | 94 registered, 93 passed, no failures/errors, 1 skipped |
| Android lint | 0 errors, 18 warnings; passed |
| Native boundary script | Passed, 9 Kotlin source files |
| APK signature | v2 verified; certificate matches 0.1.0 |
| Manifest | app.torboxtv, version 0.1.1/code 2, min SDK 26, target SDK 35 |
| Android instrumentation | APK compiled; 13 methods not executed |

Read-only real artwork probes followed the exact three reported title IDs from images.metahub.space to live.metahub.space, downloaded small/medium JPEGs and decoded them successfully. The old native guard and website CSP excluded the live host. The new tests permit that exact host while rejecting lookalikes, cleartext, credentials and nonstandard ports. They also verify exact-ID alternate sizes, bounded retry and cancellation. The probes do not test TorBox account operations, video playback or APK image rendering on Fire TV.

APK: TorBox-TV-0.1.1-test.apk, 6,924,314 bytes. SHA-256: 4a80d26b5c38d0fcd3f1ff54c2ce6c77aadc41b07ae0cd167fcbec913fd24aad. Certificate SHA-256: a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf. Install over 0.1.0 to preserve state.

JUnit XML, lint XML, sanitized build output, public image probe details and machine-readable counts are in evidence/0.1.1/. Physical-device display/playback, Android instrumentation execution and independent GitHub Actions publication remain unverified. The user's 0.1.0 UI screenshots document the reported symptom. They are not proof that the new APK has already been tested on that device.
