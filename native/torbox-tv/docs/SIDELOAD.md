# Install TorBox TV from an Android phone

Version 0.2.3 is a test build; check HANDOFF and the build report for its completed build results. Real Firestick playback remains unverified. These steps require an Android phone and Wi-Fi; no computer or Android Studio is needed. The current delivery is an APK download, not an Amazon Appstore listing.

Install the supplied original-certificate update over 0.2.2 or an earlier local APK without uninstalling. Profiles, settings, PIN, key and history stay on the device. Verify the matching certificate and APK checksum in the current build report.

1. Download **TorBox-TV-0.2.3-test.apk** to your Android phone from the provided attachment. Install [Easy Fire Tools](https://play.google.com/store/apps/details?id=de.agondev.easyfiretools) from Google Play on the phone.
2. Connect the phone and Fire TV to the same home Wi-Fi network. On Fire TV, open **Settings → My Fire TV → About**. If Developer Options is hidden, select the device-name entry seven times to reveal it. Return to **My Fire TV → Developer Options** and enable **ADB Debugging**.
3. Open **About → Network** and note the Fire TV's IP address. In Easy Fire Tools, select the discovered Fire TV or enter that address in its settings. Connect, then accept the debugging authorization shown on the television.
4. In Easy Fire Tools, open its sideload/custom APK area, select the downloaded APK and install it. Menu wording can vary with the phone app version. Use the custom APK picker, since TorBox TV is not already a phone application to copy.
5. On Fire TV, open **Your Apps & Channels** and choose **TorBox TV**. For Sports, select **Watch Sports without TorBox**, or Sports in the sidebar. No key is needed. To watch movies or shows, enter your TorBox API key using the remote keyboard; it is verified before saving and encrypted on the device. Never send it in chat.

After installation, turn off ADB Debugging if you do not need it. No browser app or Render change is needed. The APK targets Android-based Fire TV, particularly the **Fire TV Stick 4K Plus (2025)**; it is not for Vega OS devices.

Begin with a cached movie, then a specific cached show episode. Confirm actual title/episode, sound, focus and seeking. Test Resume, Home/return, audio/subtitle tracks and auto-next before everyday family use. The complete checklist is [ACCEPTANCE.md](ACCEPTANCE.md).

Keep this APK for rollback. A later GitHub test build may have a different test signing certificate; Android will then require uninstalling this local build before installing it. Uninstalling deletes only TorBox TV's local profiles/key/history, so enter the key again. Android also normally blocks installing an older version over a newer one. The original browser app's data is independent.

The current APK SHA-256 is recorded in the supplied **SHA256SUMS-0.2.3.txt** and build report. Keep the old 0.2.2 APK and source archive as the rollback checkpoint.

Sources: [Amazon debugging/connection instructions](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html), [Easy Fire Tools publisher listing](https://play.google.com/store/apps/details?id=de.agondev.easyfiretools), [Amazon device specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html?v=ftvstick4kplus).
