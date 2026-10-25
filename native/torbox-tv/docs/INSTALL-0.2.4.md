# Install TorBox TV 0.2.4

Install **TorBox-TV-0.2.4-test.apk** over the existing TorBox TV app. Do not uninstall the old app or clear its data. The package remains `app.torboxtv`, versionCode is 7, and the original signing certificate is retained.

For this first updater-enabled release, use your existing Firestick APK installer or ADB replacement install:

```bash
adb install -r TorBox-TV-0.2.4-test.apk
```

If Android reports a signing mismatch, stop and keep the installed app. Do not uninstall it to force the update. This package is signed with the original certificate `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`.

## Future updates

The app checks GitHub while its main screen is open, at most once per day. An available update appears above the current page. You can also use **Settings > Check for updates** at any time or turn automatic checks off. Choose Download update, wait for verification, choose Install, and approve the Fire TV installer. On first use, allow TorBox TV under Fire TV's Install unknown apps setting when prompted. Updates download directly from GitHub, with no TorBox key or Render relay.

## Phone browser remote

On the TV open **Settings > Phone remote > Scan a QR code**. Scan with your phone camera and open the link. Both devices must be on the same home Wi-Fi. Type to search movies/shows or Sports, and use directional, OK, Back, Home, playback and seek buttons. Results and video stay on the TV. TorBox must be connected on the TV for movies/shows; adult Sports works without its key.

Keep TorBox TV open. Leaving the app or choosing Disconnect phone closes pairing; scan again to reconnect. Guest-network isolation or a VPN may block local control. No phone app is required.

## Preserving data and previous versions

A same-certificate replacement retains the app's encrypted key, PIN, profiles, progress and settings. Previous 0.2.3 files are archived unchanged, but Android normally blocks installing a lower versionCode over a higher one. Uninstalling to downgrade erases local state. Keep your current installation until the replacement is installed successfully.

This is a signed test update. Automated results are in the accompanying build report. Actual Firestick Wi-Fi/camera pairing, permission-menu wording, audio/video and authenticated TorBox startup timing still require a physical-device check.
