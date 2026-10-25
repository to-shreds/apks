# TorBox TV portable encrypted key vault (0.2.7)

## One-time enrollment

On a phone or computer, open [the standalone Vault Setup page](https://to-shreds.github.io/Misc/TorBoxVault/), or select **Create encrypted key file on phone** on the Firestick and scan its QR code. Paste the TorBox API key, choose and confirm an app password, then select **Download encrypted key file**. The browser performs the encryption locally using Web Crypto; no TorBox key, password or decrypted payload is sent to a website or the app source repository.

Upload the downloaded **`torbox-tv.vault.json`** to the *root* of your public [`to-shreds/apks`](https://github.com/to-shreds/apks/upload/main) repository. The file contains random PBKDF2 salt, random AES-GCM nonce and ciphertext only. Its expected filename is fixed. Keep this file in the root and replace it whenever changing TorBox API keys or the app password. The repository is public, but the API key is not.

On any fresh installation of TorBox TV Stable, select **Unlock saved key with password**, type the app password, and press Save. The Firestick downloads the encrypted file over HTTPS, decrypts it on-device, verifies the account through TorBox, then stores the recovered key using its existing Android Keystore-protected AES-GCM storage. It discards the entered password rather than saving it. No TorBox API key needs to be entered on the Firestick.

The existing manual API-key option and credential-free Sports page remain available if the file has not been uploaded or GitHub is unreachable.

## Format and behavior

JSON format: `torbox-tv-portable-key-v1`, `iterations=180000`, and base64-encoded `salt` (16 bytes), `iv` (12 bytes) and `ciphertext` (AES-256-GCM). Derivation is PBKDF2-HMAC-SHA256 and the GCM associated data is `torbox-tv-portable-vault-v1`. The web helper and native app share a deterministic golden interoperability fixture tested in Node and JUnit. New salts and IVs are generated for every encrypted file.

The native client reads only the exact hardcoded HTTPS raw GitHub file, does not follow redirects, has network timeouts and a 4 KiB body cap, and never logs secrets. Incorrect passwords and modified files are rejected. The password can be chosen for household convenience, but using a longer password prevents trivial offline guessing when the ciphertext is public.

## Stable native updates

Version `0.2.7` uses Android package `app.torboxtv.stable`, with a new permanent signing identity backed up in the private `to-shreds/ProjectStatus/projects/torbox-tv/private/stable-signing` location. The permanent signer's SHA-256 certificate fingerprint is `143948886131206f40b2a1f42826fb548c143488fa91f1ce4cac7b65ff84b507`. Future *same-package*, same-signature updates retain the existing key, settings and history without requiring password entry again. The updater uses `https://raw.githubusercontent.com/to-shreds/apks/main/torbox-tv-stable-latest.json` and validates signatures before install.

Older 0.2.4, 0.2.5 and 0.2.6 builds used different signing identities or application IDs. Version 0.2.7 installs alongside them; Android will not automatically migrate their encrypted saved data. This is the single intended changeover to the durable `app.torboxtv.stable` identity. Do not rotate the permanent signer or change the package ID in a routine release.

No video is proxied through Render, GitHub or the vault page. The vault JSON is small; only the login credential is recovered, and no TorBox library contents are exposed.
