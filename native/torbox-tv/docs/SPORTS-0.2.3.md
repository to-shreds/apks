# TorBox TV 0.2.3 expanded Sports

This change is based on the complete authoritative 0.2.2 source archive. It preserves the startup source batching/prefetch, movie/episode validation, encrypted account key, local profile/settings/PIN/history formats, and native Buffstreams connector. The independent web application has its own release and publication process.

## Account-free entry and browsing

First launch focuses **Watch Sports without TorBox** for an adult profile. The Sports sidebar route runs before the movie account gate. Local Profiles and Settings can also open before account setup, so an existing Kid Mode profile can still be changed through its existing Parent PIN rules. Movie and show account checks remain in place. Both Sports activities recheck Kid Mode before playback; opening the public catalog never writes a TorBox key.

The catalog supplies sport and provider choices. Search matches channel/event title, sport and provider, rather than media URLs, descriptions or scores. Live and replay tabs retain independent item types. Pages contain at most 48 cards, limiting Android view creation as the catalog grows. Changing a filter or search returns to the first page. Refreshing Buffstreams preserves bundled providers and cards; failure cannot empty the bundled catalog. Existing replay bookmarks, Start over, 480p/720p/1080p/Auto, audio choices, Go live and alternate game servers remain.

The explanatory availability section has been removed from the Sports screen. Only selection counts and actual loading/error states remain. No result images, scores, descriptions or provider HTML enter the interface.

## Expanded catalog

The native bundle has **39 live channels and 51 direct live source choices**, compared with four channels previously. This includes NFL, NBA, MLB, FIFA+, NHL/FloHockey, NASCAR/Formula 1/FloRacing/NHRA, tennis, pickleball, beIN, rugby, cricket, golf, combat/wrestling, Billiard TV, outdoor and action-sports channels. These are channel feeds; a channel name does not imply coverage of every league game. The existing refreshing Buffstreams game browser remains separate from the bundle.

Three additional verified feeds, **fubo Sports Network, SURFER TV and Pac-12 Insider**, are included only in the native APK. Their HLS/media probes succeeded without account credentials, but browser-origin permissions were missing. Native Media3 does not require browser CORS permissions. The independent website therefore has 36 bundled live channels rather than these 39.

The replay bundle now has **157 full sessions**, including the sixteen earlier padel sessions and 141 newly verified events across thirteen sports. It adds ice hockey, tennis, American football, motorsport, cycling, surfing, water and winter sports, skateboarding, athletics, soccer and climbing alongside padel. Full hockey includes a Buffalo Sabres game; names/dates are retained without results or imagery. The American football archive consists of public Red Bull full events, not a promised NFL full-season archive. Each new entry passed public resource, HLS master/variant and media-byte probes. Replay URLs are resolved freshly from the anonymous player API at Play time; no signed stream URL is stored in the catalog.

## Direct media boundary

SportsClient reads the bundled catalog, anonymous public replay metadata and validated Buffstreams listing/player metadata. It has no TorBox credential provider and no application server endpoint. SportsPlayerActivity uses an OkHttp Media3 data source with a network interceptor on every manifest, variant, segment, media-key and redirect exchange. That interceptor requires HTTPS, normal port 443, no URL user information, an approved exact provider destination, no Authorization or Cookie header, and the existing restricted public game Referer where present. Unsupported redirects stop before issuing the redirected HTTP request or receiving media bytes; connection setup may already have occurred.

No Sports request is sent to Render. There is no media proxy, project relay, transcoding service, browser wrapper or server playlist rewriting. New CDN destinations must be supported by actual public manifest/variant/media probe evidence before they enter the policy. Catalog size is bounded to 600 accepted rows and four sources per row.

## Verification state

Gradle `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` completed successfully in **1 minute 58 seconds**. Actual generated JVM XML reports **127 registered, 126 passed, zero failures/errors and one optional live metadata check skipped**. All eight Sports checks and sixteen startup scenarios passed. Lint reports **zero errors and 31 warnings**. Native isolation/privacy boundaries pass. App and instrumentation APKs compiled, but instrumentation was not run on a physical device or emulator.

New JVM checks cover combined sport/provider/search filtering, old-provider fallback compatibility, bounded expanded catalog parsing, and rejection of backend/credential requests before transport. Added Android scenarios cover remote opening and returning from Sports with no account and rejection for an unconfigured Kid Mode profile. These device scenarios are compiled only.

The completed APK is `app.torboxtv` version **0.2.3**, versionCode **6**, minSdk **26**, targetSdk **35**, compileSdk **36**. APK size is **6,993,926 bytes**; SHA-256 `341468c84946aed57f08b9ee0a114ef9760f25d5c06fc8ef87bed96feab1e0d5`. V2 signature verifies with the original certificate SHA-256 `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`. ZIP CRC passes. The bundled asset matches the frozen source byte for byte, with SHA-256 `2161721861cf002b62d5e0c81af90c0c4e911ff7bbf6cb1da25a6b317612c2fe`: **196 items, 39 live, 51 live choices, 157 dated replays, 26 sport categories**. Actual build, manifest, signature, lint and JVM evidence are under `docs/evidence/0.2.3/`.

Ten runtime files covering account/source discovery, state, policy, movie and Sports playback, Buffstreams and artwork remain byte-identical to 0.2.2; `source-boundaries.json` records their SHA-256 values. Public feed/media probes were performed independently of the APK, without a TorBox key. No actual account/video/Firestick timing result is claimed. The separate native repository is still unavailable, so versioned source and APK artifacts remain authoritative until it exists.

## Physical acceptance

Install the new higher-version APK over 0.2.2 without uninstalling. On an adult profile, clear only the TorBox account key if testing account-free entry; leave profiles/history/PIN intact. Open Sports from the first-launch action, filter by several sports/providers, search and page through replay results. Play a new live channel and a replay, verify audio, quality, seek/resume, Back, Home and reopening. Check that Kid Mode cannot open Sports. Real Firestick playback and authenticated TorBox timing remain physical-device checks, and public probe success does not establish every channel's availability on every device/location.
