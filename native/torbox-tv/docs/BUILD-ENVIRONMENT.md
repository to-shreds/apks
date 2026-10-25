# Build environment evidence

Verified on 2026-10-10 UTC. This records tooling verification, not an application build or a physical-device playback test. Application test results and release readiness must be recorded separately after their tasks complete.

## Pinned application build chain

| Component | Version/configuration |
|---|---|
| JDK | OpenJDK 17.0.20 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.11.1 |
| Kotlin Android plugin | 2.2.21 |
| Android compile SDK | 36, platform revision 2 |
| Android build tools | 35.0.0 |
| App minimum SDK | 26 |
| App target SDK | 35 |
| Media3 | 1.11.1 |

The initial environment had Java but no Android SDK, Gradle installation, Android emulator, or relevant dependency cache. Tooling was provisioned into an isolated scratch directory. Existing project directories and system installations were not modified.

The JDK already contained its compiler and archive-tool modules. Local `javac` and `jar` launchers use those modules. Checks returned `javac 17.0.20`, `jar 17.0.20`, and Gradle 8.13 using that JDK. Android `aapt2`, `d8`, and `apksigner` also ran successfully.

## Download integrity

The Gradle archive was retrieved from the official distribution endpoint and verified against its official SHA-256 file. The wrapper was generated with that verified Gradle distribution. The wrapper properties pin the distribution checksum and allow 60 seconds for network operations.

| Artifact | Verified digest |
|---|---|
| Gradle 8.13 binary distribution, SHA-256 | `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` |
| Generated Gradle wrapper JAR, SHA-256 | `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |
| Android 36 platform revision 2, SDK-index SHA-1 | `2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576` |
| Android build tools 35.0.0, SDK-index SHA-1 | `2cfaa0bbb2336e9ec18ed3ecea84fa2e2af607bc` |

The SDK SHA-1 values were checked against the package checksums supplied in Google's current repository index. SHA-1 here reflects the SDK index's existing integrity field.

## Network and execution checks

Ordinary HTTPS requests succeeded against Android developer documentation, the Google SDK repository, Maven Central, and Gradle's official endpoints. Java HTTPS requests to the exact AGP 8.11.1 and Kotlin 2.2.21 POMs returned HTTP 200 after using the environment's proxy configuration. Inherited Gradle proxy options initially pointed to an unreachable endpoint. A local launch helper replaces those options with the current environment proxy. This configuration is local build tooling only and is not included in the application or repository.

An initial Gradle wrapper-generation URL probe failed before the inherited proxy overrides were corrected, despite successful archive download and checksum verification. Wrapper generation was completed with that probe disabled. The committed wrapper properties retain URL validation and checksum verification. The wrapper-generation task passed. After correcting the inherited proxy settings, `gradle help --info` configured both the root project and Android application successfully, using AGP 8.11.1 and Kotlin 2.2.21, and ended with `BUILD SUCCESSFUL in 1m 20s`. This confirms plugin dependency resolution and build-script configuration, but does not establish that the app compiled.

No hardware acceleration device was available for Android emulation. Any subsequent software-emulator results must be labeled separately from Fire TV device testing.

## SDK and wrapper licensing

Android SDK components remain external build dependencies. Their license digests were recorded from the official SDK repository's license text for local tooling. No Android SDK binaries or SDK license-acceptance files are committed to this application repository. A fresh build environment must accept the applicable Android SDK terms through its normal SDK installation process.

The Gradle wrapper JAR contains its Apache License 2.0 notice. That exact embedded text is preserved in [GRADLE-WRAPPER-LICENSE.txt](GRADLE-WRAPPER-LICENSE.txt), with the source Gradle distribution's [NOTICE](GRADLE-NOTICE.txt). These build-tool notices do not replace the separate attribution requirements for runtime libraries distributed in the APK.

Official sources:

- [Gradle 8.13 distribution](https://downloads.gradle.org/distributions/gradle-8.13-bin.zip)
- [Gradle distribution checksum](https://downloads.gradle.org/distributions/gradle-8.13-bin.zip.sha256)
- [Gradle wrapper documentation](https://docs.gradle.org/8.13/userguide/gradle_wrapper.html)
- [Google Android SDK package index](https://dl.google.com/android/repository/repository2-1.xml)
- [Android SDK terms](https://developer.android.com/studio/terms)

## Reproduction

With a JDK 17 installation, Android platform 36, build tools 35.0.0, and accepted SDK licenses:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
```

Set `JAVA_HOME` and `ANDROID_HOME` to the relevant installations. The same command is suitable for GitHub Actions once its Android environment is configured. Network restrictions, compilation results, and test results must be assessed from the actual task output.
