# Third-party dependencies

The original application code is separate from the supplied MovieBox reference. No MovieBox assets/code/fonts/binaries are included.

Runtime/build dependencies retain their respective licenses and notices. The source repository should remain private until its owner's licensing decision and distribution review; this notice does not relicense the project's original code.

| Dependency | Version | Upstream license/source |
|---|---|---|
| AndroidX Media3 | 1.11.1 | Apache 2.0, [AndroidX Media](https://github.com/androidx/media) |
| ZXing core, local QR generation/decoding | 3.5.4 | Apache 2.0, [ZXing](https://github.com/zxing/zxing) |
| AndroidX libraries, including core/activity support transitively | Resolved by Gradle | Apache 2.0, [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| OkHttp / Okio | 4.12.0 / transitive | Apache 2.0, [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| Kotlin / kotlinx.coroutines | 2.2.21 / 1.10.2 | Apache 2.0, [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Gradle wrapper | 8.13 | Apache 2.0, bundled [license](GRADLE-WRAPPER-LICENSE.txt) and [notice](GRADLE-NOTICE.txt) |

Test-only dependencies include JUnit 4, MockWebServer, org.json, kotlinx-coroutines-test and AndroidX Test/UI Automator. They are not bundled as production application features. Android platform components are external build dependencies. Required SDK terms are handled by the build environment.

Real catalog artwork is fetched at runtime from metadata providers, not packaged as owned original artwork. Provider artwork rights and public endpoint availability remain with their respective owners. The application introduces no paid proxy, database, storage or metadata subscription.
