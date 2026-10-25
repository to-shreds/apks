# TorBox TV 0.2.2 startup improvements

This independent native update is based on the current 0.2.1 source archive, including its Buffstreams connector. Movies and episodes can open the first safely matched cached version before the slowest source provider returns and before spare versions are prepared. The source cache is shared across details and playback instead of being recreated on each start.

Anonymous source metadata is prefetched for the displayed title, the focused released episode and an upcoming episode near the end of current playback. Prefetch does not add torrents, inspect the private account, create signed links or fetch media. Complete and partial cache results remain distinct; provider outages do not become complete empty results. Retry can refresh unsuccessful metadata.

Fallback versions are prepared only after playback readiness and remain cancellable. No-cache-hit batches do not scan the account. Exact title/year/episode/file and language checks, cached-only automatic additions, five candidate attempts, three playback attempts, resume positions, state/PIN formats, sports media policies and the direct TorBox video path remain. ExoPlayer buffering was not reduced without device evidence.

Completed verification: 123 JVM tests registered, 122 passed, zero failures/errors, one optional live metadata check skipped. All sixteen new startup regressions passed. They test a fast accepted batch with slow providers still pending, cancellation, identity filtering, partial/complete cache reuse and concurrency, provider outages, a longer independent verification budget, public request isolation, first-choice preparation, unsafe-file fallback and exact automatic-attempt receipts.

The first-batch test uses virtual time: accepting the available safe batch completes at zero simulated milliseconds while the slow provider is still pending. This demonstrates removal of the wait, not real playback speed. Other API tests use intercepted local responses. No live account credential was supplied; authenticated TorBox streaming and actual Firestick first-frame/audio/remote/lifecycle performance are not claimed.

The app and instrumentation APK compiled successfully. The requested Gradle tasks completed with BUILD SUCCESSFUL in 3m 5s. Android lint has zero errors and forty warnings. Native boundaries pass on thirteen source files. The compiled APK is app.torboxtv, version 0.2.2/code 5, min SDK 26 and target SDK 35. APK v2 signature matches the original signing certificate exactly. ZIP CRC integrity passes. Existing physical-device acceptance and separate repository publication remain independent of these local checks.

APK size: 6,977,086 bytes. SHA-256: `904292756d4d9337395896297c413a954d793e47c42987dd3e4420f2f7953d38`. Certificate SHA-256: `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`. Raw checks, signatures, manifest, JUnit XML and final build/lint output are under docs/evidence/0.2.2. The authored thirteen instrumentation scenarios compiled but were not executed.

Use Android logcat tag TorBoxStartup to compare source-batch, cached-choice, playback-link and first-frame durations. Only fixed stage labels and elapsed milliseconds are logged. No URLs, keys, source names or response bodies are logged or persisted by these diagnostics.
