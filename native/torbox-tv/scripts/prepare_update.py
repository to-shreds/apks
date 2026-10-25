#!/usr/bin/env python3
"""Prepare a verified, original-certificate APK for the public GitHub update branch.

Run only after native tests and final signing. No keys or credentials are read.
This prepares files for review/publication; it does not push to GitHub.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://raw.githubusercontent.com/to-shreds/torbox-web-player/native-updates/native/updates/"
CERT = "a624a6387fdd119fec69897b8386fbdcb4eeb073987c7abc7ac18626b426cedf"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True, help="Directory to receive native/updates/")
    parser.add_argument("--notes", required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    tools = args.sdk / "build-tools/35.0.0" if args.sdk else None
    if tools is None or not tools.is_dir():
        parser.error("Provide --sdk or ANDROID_HOME with build-tools 35.0.0")
    data = args.apk.read_bytes()
    if not 0 < len(data) <= 100 * 1024 * 1024:
        parser.error("APK is empty or too large")
    signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(args.apk)], text=True)
    if re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", signature) != [CERT]:
        parser.error("APK must have the original TorBox TV signing certificate, not a CI test identity")
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "app.torboxtv":
        parser.error("Wrong application package")
    code, version = int(match[2]), match[3]
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    if version != (ROOT / "VERSION").read_text().strip() or code != int(re.search(r"versionCode\s*=\s*(\d+)", gradle)[1]):
        parser.error("APK must match this verified source's VERSION and versionCode")
    directory = args.output_dir / "native/updates"
    destination = directory / f"releases/TorBox-TV-{version}-test.apk"
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.read_bytes() != data:
        parser.error("A different APK already exists at this version; increment the version instead")
    shutil.copyfile(args.apk, destination)
    manifest = dict(schema=1, package="app.torboxtv", versionCode=code, versionName=version,
                    apkUrl=BASE + "releases/" + destination.name, sha256=hashlib.sha256(data).hexdigest(),
                    bytes=len(data), notes=args.notes[:1500])
    (directory / "latest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
