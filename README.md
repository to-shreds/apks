# APK Shelf library

APK Shelf on Fire TV lists APKs from two sources in this repository.

- **Smaller APKs:** Add file → Upload files. Upload ordinary .apk files directly into the repository root. GitHub's browser upload limit is 25 MiB.
- **Large APKs:** Open [Releases](https://github.com/to-shreds/apks/releases), edit the existing **Apk library** release, and upload the .apk as a Release asset. Release assets can be up to 2 GiB each.

After uploading, open APK Shelf on Fire TV and choose **Refresh**. Version 1.0.2 and later show both sources in one list. You don't need a rebuild for each new upload.

## TorBox TV Stable 0.2.7

[**TorBox-TV-0.2.7.apk**](TorBox-TV-0.2.7.apk) is the latest TorBox TV Firestick app. It includes the Buffstreams repair, phone remote LAN fix and one-time password recovery for the TorBox API key. It uses package `app.torboxtv.stable` and a permanent signing identity backed up privately. Later versions with the same identity will update in place and retain settings and saved credentials.

**To avoid typing your TorBox API key on every fresh installation:**

1. On your phone, open [TorBox Vault Setup](https://to-shreds.github.io/Misc/TorBoxVault/), paste your TorBox API key and choose an app password. Download `torbox-tv.vault.json`.
2. Upload that **encrypted file** to the root of this repository, beside the APKs. Do not upload the plaintext key or password.
3. In a fresh TorBox TV Stable installation, choose **Unlock saved key with password** and enter that password once. The app verifies TorBox and saves the key locally, encrypted using Android Keystore.

Manual key entry still works, and Sports still works without TorBox. The portable vault format uses PBKDF2-HMAC-SHA256 and AES-256-GCM; the public file contains ciphertext only. The app does not store your vault password. The encrypted `torbox-tv.vault.json` file is user-created and is not shipped with this repository.

Version 0.2.7 is a **one-time separate installation** from earlier 0.2.4 through 0.2.6 builds, since those versions used different Android package/signing identities. Older APKs remain archived. Future `app.torboxtv.stable` updates are distributed through [the dedicated signed update manifest](torbox-tv-stable-latest.json).

**Latest APK Shelf (1.0.3):** [APK-Shelf-1.0.3.apk](APK-Shelf-1.0.3.apk) is in this root folder, so existing APK Shelf users can select it to install the update. [Direct download](https://raw.githubusercontent.com/to-shreds/apks/main/APK-Shelf-1.0.3.apk).

This repository and its published release assets are public. Only upload software you are entitled to redistribute.
