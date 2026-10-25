# Native playback test fixture

`app/src/androidTest/assets/player-fixture.mp4` is the existing TorBox web project's original synthetic 120-second H.264 video with generated 440 Hz AAC audio. It is copied byte for byte from `test/fixtures/fixture.mp4`; no movie, show or provider media was downloaded. The fixture exists only in the instrumentation APK.

SHA-256: `ace9b8761e3d6f4c540c3df6ebb5ca2556fef482bafa5584c5654dad9954e405`

The tests retain the real Activity, Media3 player, listeners and decoder while replacing API/media transport with in-process synthetic data. They do not contact TorBox or Sports providers, weaken production media guards, or establish real-account/physical-Firestick performance.
