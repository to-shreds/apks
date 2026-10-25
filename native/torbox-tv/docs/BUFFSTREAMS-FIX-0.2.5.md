# Buffstreams0.2.5 repair candidate

Current release is blocked by unavailable original signing material. Do not install or publish an unsigned/temporary-signer candidate as an update. See HANDOFF for continuation and the versioned verification report for results.

The exact provider protocol observed is:

- trusted master: chatgpt.hereisman.net/playlist/{id}/load-playlist
- declared variant: HTTPS pl.{rotating-label}.space or exact master host, /playlist/{same-id}/{declared-segment-host}/caxi
- media: HTTPS exact declared a/b.{rotating-label}.space, /scripts/{canonical-base64-id}/p{digits}_{sequence}.txt

The new scope is created for every selected player, bounded to12 variants and256KiB decoded master metadata, replaced atomically on master refresh and never shared across games. No JavaScript executes and no media is proxied. New provider protocols, keys/external-audio/init-map URIs and unrelated redirects remain unsupported instead of opening arbitrary destinations.

Synthetic native fixture: buffstreams-fixture.ts,27824bytes,SHA-256 d0dba90daa8646ff42581ab74ef4a7435bcd37f76b5cf697ee0dce13db1b28ca. Derived by ffmpeg -t6 -c copy -f mpegts from the existing documented synthetic player-fixture.mp4; actual GOP/B-frame span9.021333s. No provider video is bundled. Native test transport is synthetic; provider probes independently establish publicHTTP200 manifests and two206 bounded TS prefixes, not physical playback.
