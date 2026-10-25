# TorBox TV 0.2.0 sports test build

Android versionCode 3; package app.torboxtv. APK size 7,068,765 bytes. APK SHA-256: `a138625b2cc6f62023be709d427e344f22711fb66ba0b9f5a0519c72251a3d1d`.

Adds native Sports browsing and Media3 playback, with T2 tennis, Red Bull TV, Pickleball TV and NHL classics/coverage, plus sixteen full 2026 Premier Padel sessions from Paris, Malaga, Bordeaux and Valladolid. It opens before TorBox setup. No additional subscription, login, VPN, browser or Render is configured. The replay catalog is a release snapshot, not an automatic event guide or complete tennis archive. Provider regional rights still apply.

Default quality is 720p, with 480p/1080p/Auto choices, native audio controls, Go Live, replay bookmarks/Start Over, Retry and return to other channels. Browsing contains no scores or result descriptions/images. Sports requires an adult profile. Existing artwork fix, TorBox keys/settings/movie history and playback policy are preserved. Network clients keep public sports/API traffic separate from TorBox credentials and validate every media network exchange, including redirects.

App and instrumentation APK compiled. JVM: 98 registered, 97 passed, zero failures/errors, one optional metadata check skipped. Lint: zero errors, 32 warnings, predominantly existing API/localization warnings and new untranslated interface strings. Native boundary checks pass on twelve Kotlin files. No physical Firestick or instrumentation execution is claimed; the existing thirteen device scenarios still need a working device/runner.

APK v2 signature verified. Signing certificate SHA-256: `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`, matching 0.1.0/0.1.1 exactly. Install over the existing APK without uninstalling to retain local state. It remains a debuggable test APK. Independent GitHub repository/Actions release remains unavailable; current versioned source/evidence and APK are preserved as downloadable artifacts.

Public source probes confirmed masters, variants and initial video segments for T2/Red Bull live and a padel replay, plus Pickleball/NHL masters. Actual external Chrome playback started for T2, Red Bull live and a Paris replay in the independently maintained browser app. Those checks do not establish Firestick decoder, remote, audio or native lifecycle acceptance. Test those on the television.

Build toolchain: JDK 17.0.20, Gradle 8.13, AGP 8.11.1, Kotlin 2.2.21, SDK 36/build tools 35.0.0, min SDK 26, target SDK 35, Media3 1.11.1. All native sources and raw JUnit/lint/build evidence are included in the source archive. Private signing material is excluded. Earlier versions remain archived; normal Android downgrade installation is restricted, and uninstalling to downgrade erases local app state.
