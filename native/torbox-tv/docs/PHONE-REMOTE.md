# Phone browser remote

On the TV, open **Settings > Phone remote > Scan a QR code**. Scan it with your phone camera and open the link. Pairing closes the TV dialog automatically. The phone browser provides live text search for movies/shows and Sports, directional controls, OK, Back, Home, Play/pause and seek controls. Search results and video stay on the TV. No phone app or account is required.

Both devices must be on the same home Wi-Fi or reachable local Ethernet/Wi-Fi network. The Firestick hosts this small control page itself on its private IPv4 address. Guest/client isolation, a VPN that blocks LAN traffic, or an IPv6-only network may prevent pairing. The page and commands stay on the LAN and use no Render relay. The browser may ask to allow a local-network connection. The update check's GitHub download channel is separate.

Pairing starts only when chosen on the TV. The QR code contains a random session capability in its URL fragment, which is not sent in the document request. Commands require that capability and the exact local origin and host. The server has bounded request sizes, connection counts, timeouts and command rate; it serves no files, TorBox credentials, PINs, account data or video. The control connection uses local HTTP, so use it on a trusted home network. Nobody is given a general URL opener or a credential/PIN typing API.

Movies and shows still require TorBox connected on the TV. Sports works without a TorBox key, and Kid Mode/parental restrictions remain enforced. Phone text populates the same native search fields; the existing discovery, identity checks, cached-only playback and parental rules continue to apply. Results are not copied into the phone page.

The session continues between the app's browsing, Sports and player screens. It closes when TorBox TV goes into the background, when **Disconnect phone** is selected, when the TV pairing dialog is canceled, or after six hours. Open Phone remote and scan again to reconnect. New sessions invalidate old QR codes. Search requests carry a per-browser sequence so delayed old typing cannot replace newer text.

Native tests cover the actual QR bitmap/decoder, private-IP server, typed native searches, Sports without credentials, child-profile rejection, Home disconnection and paired playback controls. Browser tests drive the mobile control page independently. Physical phone/Firestick Wi-Fi and camera acceptance still require a real-device check.

Development browser check: install Playwright 1.62.1 in a temporary directory, set NODE_PATH to that directory's node_modules and run `node --test test/browser/phone-remote.cjs`. CHROME_EXECUTABLE may point to an installed Chromium/Chrome binary. These are test-only tools; the native app and phone page have no Node or Playwright dependency.
