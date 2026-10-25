# Fire TV updates from GitHub

After installing 0.2.4 once, TorBox TV can check for a newer APK without a TorBox key, GitHub login, computer or ADB connection. It checks while the main app is open, at most once every 24 hours. An available update appears as a button above the current page. Settings also has **Check for updates** and **Automatic update checks**. Turning checks off does not disable the manual check.

Choose **Download update**, wait for its progress and verification, then choose **Install** and approve the device's own installer. On first use, Fire TV may require allowing TorBox TV under Settings > My Fire TV > Developer options > Install unknown apps. The app opens the relevant Android settings when supported; Fire OS menu wording varies. It cannot silently install an APK or bypass the device's permission. Install over the existing app. Do not uninstall it, because uninstalling removes local settings and history.

Checks download only a small public JSON file. APK bytes download directly from GitHub only after consent. Neither operation uses Render, TorBox, account credentials, a video proxy or a background service. Offline or failed checks do not interrupt the app. Downloads cancel when the app is backgrounded; a partial file is never passed to the installer. There is one bounded private update cache, exposed read-only to the installer through a narrow FileProvider.

The channel is `to-shreds/torbox-web-player`, branch `native-updates`, directory `native/updates/`. This separate distribution branch does not modify the website's main branch, runtime or media transport. The private ProjectStatus repository cannot serve unauthenticated Firesticks. The independent native source remains in its versioned ZIP and ProjectStatus artifacts until its separate repository is available.

The JSON has schema, package, versionCode, versionName, apkUrl, bytes, SHA-256 and notes. Only the exact public GitHub owner/branch/directory is accepted, with no redirects, credentials or query strings. Before installation, the app checks the complete size and SHA-256, actual APK package and version, strictly newer versionCode and original signing certificate `a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf`. A different CI signer, older APK or corrupted file is rejected.

## Publishing the next verified native update

Increment VERSION and Android versionName/versionCode together. Complete JVM, lint, native device and packaging checks, then sign with the existing original key outside source and CI. Prepare the public channel files:

```bash
python3 scripts/prepare_update.py /absolute/path/to/signed.apk --sdk /absolute/path/to/android-sdk --output-dir /absolute/path/to/channel --notes 'Describe the changes'
```

The script verifies the actual APK signature, package and source version, calculates its hash and writes the APK plus latest.json. Publish those generated `native/updates/` files together to the existing `native-updates` branch. Preserve prior versioned APKs. Do not overwrite a version with different bytes, publish an unsigned APK, put signing material in GitHub, or publish a CI temporary-key build as an app update. Verify the public JSON and APK anonymously after publishing. GitHub's raw-content cache may take a few minutes to refresh.

Physical Fire TV permission menus and installation approval require device acceptance. Android instrumentation verifies the package archive/signature checks, restricted file sharing and native installer handoff/cancel; it does not substitute for that physical Fire TV check.

Platform references: https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls(), https://developer.android.com/reference/androidx/core/content/FileProvider and https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE.
