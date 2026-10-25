# TorBox TV current handoff

**Current checkpoint: 0.2.6/code9 LAN pairing fix, 2026-10-25 UTC.** Source in `to-shreds/apks` branch `fix/torbox-tv-phone-remote-lan`, under `native/torbox-tv`. Historical original signed 0.2.4 and Buffstreams 0.2.5 are preserved. The separate website at `to-shreds/torbox-web-player` main is untouched.

## Completed 0.2.6 work

The user scanned a valid native phone QR which opened `http://10.5.0.2:43409/` on their phone, but the page could not load. The native `PhoneRemote.showPairing` code placed Android `activeNetwork` first and accepted any network reporting Wi-Fi/Ethernet transport. Android VPN networks can simultaneously report Wi-Fi transport; the selected address could therefore be a tunnel IPv4 such as 10.5.0.2. This is a confirmed code defect and a plausible explanation of the user's symptom. The actual Firestick IP and physical phone connection have not yet been observed.

Added `LanAddressSelector`, which rejects VPN and tunnel interfaces even if they report Wi-Fi and selects private IPv4 on a physical Wi-Fi/Ethernet link, preferring Wi-Fi. `PhoneRemote.showPairing` now uses the selector, reports no usable physical network instead of publishing a misleading URL, and shows the chosen LAN IP in its TV pairing dialog to diagnose subnet or VPN issues. Existing token-bound localhost-style HTTP command protocol, timeouts, background closure, searches and all Sports/Buffstreams changes are unchanged. Tests exercise the exact VPN-plus-Wi-Fi misselection and assert the Android-emulator QR IP belongs to a non-VPN physical link.

## Verification and delivery

[GitHub Actions run 40153312661](https://github.com/to-shreds/apks/actions/runs/40153312661) completed **successfully**. Gradle unit suite, Android lint, native boundary check and normal test builds passed. An accelerated Android 11 emulator at TV-sized resolution ran 47 Android tests successfully, with two reserved original-certificate upgrade cases skipped, no failed cases. Debug APK compiled and passed `aapt` package/version inspection, `apksigner verify`, ZIP integrity and hash verification.

Published [`TorBox-TV-0.2.6-phone-remote-fix-test.apk`](https://github.com/to-shreds/apks/blob/main/TorBox-TV-0.2.6-phone-remote-fix-test.apk) to `to-shreds/apks` root. Exact APK: 7,332,558 bytes, SHA-256 `70e1330bafb85721d40d18fd13dfe9746e9f45525e90710cdeb5602e0a9d8d7d`; accompanying `.sha256` file published. Package identity `app.torboxtv.lanfix`, versionCode9, versionName `0.2.6`, label `TorBox TV 0.2.6 Test`. This is a **separate debug-signed test app** that does NOT replace 0.2.4 or 0.2.5, and it does not inherit their saved settings. The original app signing key remains unavailable; do not claim a seamless update or modify the original update manifest.

## Remaining physical acceptance

Install the new APK using APK Shelf without uninstalling prior versions. Open Settings > Phone remote, confirm the displayed Firestick LAN IP matches Fire OS Settings > Network, scan QR while the TV app is foreground, and verify page load plus search/key controls on a phone connected to the same Wi-Fi. If it still cannot load with a matching IP, check phone/TV VPN LAN access, guest/client isolation and firewall/router policy. Verify live Buffstreams playback separately on the Firestick. No physical TV/phone verification has been performed. The fix addresses wrong-interface selection; it does not bypass VPN or router isolation.

## Historical 0.2.5 candidate notes (superseded by 0.2.6 release above)

0.2.5/code8 is a Buffstreams repair candidate continuing the original 0.2.4 app. It is NOT a published installable update. Original signing-key recovery is a release blocker after workspace maintenance removed the prior scratch signing directory. The certificate cannot be regenerated from the existing APK. Do not replace signing identity, tell the user to uninstall, publish a new update manifest or claim in-place install without explicitly resolving this. Prior 0.2.4 source/APK are preserved unchanged. Historical handoff: docs/HANDOFF-0.2.4.md.

The reproduced defect is current Buffstreams host rotation downstream of the verified master. Five current servers across two events produce successful master/variant responses; two bounded segment probes returned206 MPEG-TS. Old global sports host rules reject at least one downstream URL in every observed chain. The old player maps all Media3 failures to a geographic-block claim even when the app itself rejects a destination.

The repair uses a bounded per-playback capability scope seeded only by the selected game's exact trusted master. The guard observes the master response Media3 actually receives, then accepts only declared extensionless variant URLs with the same game ID, and matching canonical-base64 game segment paths on the exact declared segment host. Rotating pl.*.space / a-or-b.*.space roles are only allowed after introduction by this master, never globally. A public-only DNS wrapper checks all answers for Buffstreams only. Cookies, Authorization, unknown routes, cross-game IDs, URL credentials, HTTP, private/reserved DNS and Render destinations remain denied. No video relay or TorBox key is used. Global SportsPolicy remains unchanged.

Failures now distinguish rejected changed addresses, HTTP failures, timeout and unsupported format without disclosing URLs. Terminal errors cancel obsolete watchdogs. Nine new JVM regressions cover13 provider chains/39 segment URLs, rotation/scope isolation, credentials and negative hosts, bounded gzip and DNS. A new native Media3 case exercises synthetic TS carried under .txt/text/plain and extensionless HLS, rendered video/audio tracks, progression, pause/resume and rotation. Verification results are pending and must be recorded before delivery.

Source CI APKs use a temporary identity only; they are not user updates. The preserved updater channel still serves original-signed0.2.4. Settings QR remote, existing TorBox cached-only playback, explicit preparation, state formats, adult Sports without TorBox and the independent website remain preserved. No production source should contain signing keys.
