#!/usr/bin/env python3
"""Fail CI when the native application's architectural isolation boundaries regress.

This static check is deliberately independent of TorBox credentials/network access.
It supplements unit/device tests; it does not demonstrate real playback or prove
that an external repository has remained untouched.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
errors = []


def require(condition, message):
    if not condition:
        errors.append(message)


manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
app = manifest.find("application")
require(app is not None, "Missing Android application declaration")
if app is not None:
    require(app.get(ANDROID + "allowBackup") == "false", "Device state/credentials must not enter Android backups")
    require(app.get(ANDROID + "usesCleartextTraffic") == "false", "Production network traffic must require TLS")
    activities = {a.get(ANDROID + "name"): a for a in app.findall("activity")}
    main = activities.get(".ui.MainActivity")
    player = activities.get(".ui.PlayerActivity")
    require(main is not None and main.get(ANDROID + "exported") == "true", "TV launcher activity must be exported")
    require(player is not None and player.get(ANDROID + "exported") == "false", "Player must not accept external playback intents")
    if main is not None:
        categories = {c.get(ANDROID + "name") for c in main.findall("intent-filter/category")}
        require("android.intent.category.LEANBACK_LAUNCHER" in categories, "Missing native TV launcher entry")
    require(app.get(ANDROID + "banner"), "Missing TV launcher banner")
features = {f.get(ANDROID + "name"): f for f in manifest.findall("uses-feature")}
touch = features.get("android.hardware.touchscreen")
require(touch is not None and touch.get(ANDROID + "required") == "false", "TV must not require a touchscreen")

source_files = list((ROOT / "app/src/main").rglob("*.kt")) + list((ROOT / "app/src/main").rglob("*.java"))
for path in source_files:
    content = path.read_text()
    relative = path.relative_to(ROOT)
    for pattern, message in [
        (r"\bandroid\.webkit\b|\bWebView\b", "Native app must not contain a WebView wrapper"),
        (r"https?://[^\s\"']*(?:onrender\.com|github\.io|git\.chatgpt-team\.site)", "App must not depend on the browser deployment or a project media proxy"),
        (r"torbox-web-player" if path.name != "Updates.kt" else r"https://to-shreds\.github\.io", "Native runtime must not reach into the browser application"),
        (r"\b(?:HttpLoggingInterceptor|BODY_LOGGING|setLogLevel)\b", "HTTP/body logging risks exposing the TorBox key and signed video URLs"),
        (r"\b(?:Log\.[vdiew]|println|printStackTrace)\s*\([^\n]*(?:apiKey|token|credential|password|playbackUrl|streamUrl)", "Sensitive runtime values must not be logged"),
        (r"(?i)authorization\s*[:=]\s*[\"']Bearer [A-Za-z0-9_-]{20,}", "A credential appears to be hardcoded"),
    ]:
        require(not re.search(pattern, content), f"{relative}: {message}")

gradle = (ROOT / "app/build.gradle.kts").read_text()
for coordinate in ("com.google.android.gms", "com.google.firebase", "com.android.billingclient"):
    require(coordinate not in gradle, f"Unexpected paid/Google Play dependency: {coordinate}")
require(bool(re.search(r"minSdk\s*=\s*26\b", gradle)), "Recheck documented Fire OS 8 Android API 30 compatibility after changing minimum SDK")
require("androidx.media3:media3-exoplayer" in gradle, "Expected native playback dependency is missing")
updates = (ROOT / "app/src/main/java/app/torboxtv/data/Updates.kt").read_text()
require('https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/' in updates,
        "APK updates must use the separate public GitHub download channel")
require('followRedirects(false)' in updates and 'UpdateChannel.CERTIFICATE' in updates,
        "Updater must reject redirects and enforce the original APK signer")
require(not re.search(r'TorBoxClient|SourceClient|MediaHostGuard|Authorization', updates),
        "APK updater must remain separate from accounts and media transport")

for path in ROOT.rglob("*"):
    if not path.is_file() or any(part in {".git", ".gradle", "build"} for part in path.relative_to(ROOT).parts):
        continue
    require(path.suffix.lower() not in {".jks", ".keystore", ".p12", ".pem"}, f"Private signing/key material must not be committed: {path.relative_to(ROOT)}")

if errors:
    for error in errors:
        print("FAIL:", error, file=sys.stderr)
    raise SystemExit(1)
print(f"PASS: native isolation/privacy/TV manifest checks ({len(source_files)} source files).")
