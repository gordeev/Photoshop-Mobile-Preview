# Photoshop Mobile Preview

[Русский](README.ru.md) · [Download release](https://github.com/gordeev/Photoshop-Mobile-Preview/releases/latest)

Live preview of the Photoshop canvas on a phone, inspired by Adobe Preview CC.
Edits, undo and document switches send an sRGB JPEG of the canvas, rather than a screenshot of the desktop.
Preview camera cutouts, rounded corners and the combined danger zone while designing mobile images and interfaces.

## Components

| Directory | Purpose |
| --- | --- |
| `plugin/` | Photoshop UXP panel; sends frames directly to Android or to the browser relay |
| `android/` | PS Preview: full-screen Android viewer with zoom and simulated cutouts |
| `android-overlay/` | PS Overlay: cutouts, danger zones and a reference image over other apps |
| `server/` | Python browser relay for iPhone and other browsers; shared cutout profiles |

The plugin follows the Photoshop interface language; Android apps follow the device language and the browser viewer follows the browser language. Russian is used for `ru`; other languages use English.

## Install

### Photoshop plugin

Requires **Photoshop 24.4 or newer** on macOS or Windows, with the Imaging API available.

1. Download `MobilePreview-1.0.0.ccx` from [Releases](https://github.com/gordeev/Photoshop-Mobile-Preview/releases/latest).
2. Double-click it and install through Creative Cloud Desktop. Confirm the installation of a plugin from outside the Marketplace.
3. Open **Plugins → Mobile Preview** in Photoshop.

To load the source instead, install **UXP Developer Tools** through Creative Cloud, enable Developer Mode in Photoshop's plugin preferences and in UXP Developer Tools, then restart Photoshop. In UXP Developer Tools choose **Add Plugin**, select `plugin/manifest.json` and click **Load**.

### Android viewer

Requires **Android 7.0+**.

1. Download and open `PSPreview.apk` from the release. Allow installation from the browser/file manager when Android asks.
2. Open **PS Preview**. The waiting screen lists the phone's address, for example `192.168.1.25:8765`.
3. Connect the computer and phone to the same Wi-Fi network. Enter that address in the Photoshop panel and click **Start**.
4. Edit the document. **Send once** sends a single frame without starting live updates.

Keep PS Preview open. The phone screen stays awake while the app is in the foreground.
A VPN such as Tailscale or ZeroTier also works when both devices can reach each other; use the address shown for that network.

### iPhone / browser viewer

Requires **Python 3** on the computer with Photoshop. No Python packages are needed.

1. Download `MobilePreview-server-1.0.0.zip` from the release and extract it, or use `server/` from this repository.
2. On macOS run `server/Start server.command`; on Windows run `server/Start server.bat`. Alternatively:
   ```sh
   python3 server/preview_server.py
   ```
   On Windows, use `python` or `py` if `python3` is unavailable. Enable **Add Python to PATH** when installing Python.
3. Open the printed `http://<computer-address>:8765` URL on the phone, on the same network.
4. In the Photoshop panel use **`localhost:8765`** and click **Start**.

On iPhone, Safari's **Share → Add to Home Screen** gives a full-screen viewer. In a normal browser tab, simulated cutouts are relative to the browser viewport.
To serve a different port, set the `PORT` environment variable and enter that port in the plugin too.

To send to Android and the browser at once, use comma-separated addresses, for example `localhost:8765, 192.168.1.25:8765`.

### Optional: PS Overlay

Requires **Android 8.0+**. It works independently of Photoshop and the viewer.

1. Install `PSOverlay.apk` from the release and open **PS Overlay**.
2. Tap **Allow display over other apps**, grant Android's overlay permission, then return to the app.
3. Allow notifications when prompted and tap **Start overlay**.
4. Select a cutout, danger zone and rounded corners. Optionally choose a PNG/reference image, adjust opacity and select **Stretch** or **Fit**.

Touches pass through to the underlying app. Switch cutouts or stop the overlay from its persistent notification. Normal screenshots include the overlay. Android 12+ limits overlay opacity to allow touch passthrough; some system screens hide overlays.

## Viewer controls

| Gesture / control | Action |
| --- | --- |
| Tap the center | Show / hide the menu; it also hides after 10 seconds |
| Tap the left / right edge | Previous / next cutout |
| Volume keys (Android) | Previous / next cutout |
| Double-tap | Fit ↔ 1:1 pixels |
| Pinch (Android) | Zoom; drag to pan |
| Width / Height | Fit the image to that dimension |
| Danger zone | Red union of the areas covered by the simulated cutouts and enabled corners |

There are 20 profiles including no cutout. Cutouts scale with screen width and rotate with the device. `≈` marks approximate shapes. These are design aids, not device certification; the phone's physical camera cutout remains visible. Zoom is retained between same-sized incoming frames.

## Troubleshooting and network privacy

- If connection fails, check the address, keep the viewer/server open and allow the server through the computer firewall on your private network. Guest Wi-Fi may block communication between devices.
- Frames travel over **unencrypted HTTP/WebSocket, without authentication**. Use a trusted private network or a private VPN. Do not expose port 8765 to the public Internet. Anyone able to reach the relay can read or replace its current preview.
- Frames are held in memory. There is no cloud service or account. The plugin stores connection addresses and image size locally; Android stores viewing preferences and the selected overlay image URI.
- Start with 1600 px. Choose 2400–4000 px for more detail at the cost of slower updates. Preview color is converted to sRGB; it is not a calibrated color-proofing workflow.
- Release APKs use a separate release signing key. If an older development APK is already installed with another key, uninstall it first; doing so removes its saved settings.

## Build from source

Android builds use Bash, JDK 17+, Android SDK **platform android-34**, Android SDK Build Tools 35+ (including `aapt2`, `d8`, `zipalign`, `apksigner`) and `zip`. No Gradle or external Java dependencies are required. On Windows use a Linux/WSL environment with the matching SDK tools.

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
bash android/build.sh
bash android-overlay/build.sh
```

The scripts write `PSPreview.apk` and `PSOverlay.apk` to the repository root. Without signing variables they use an ignored development keystore with the standard Android debug password. **Never distribute debug builds as release updates.**

For release builds, provide an existing keystore with alias `pspreview` and a private file whose first line is its password (the key password must match):

```sh
export KEYSTORE=/absolute/path/outside/repository/release.keystore
export SIGNING_PASSWORD_FILE=/absolute/path/outside/repository/release-password.txt
bash android/build.sh
bash android-overlay/build.sh
```

Keep both files outside the repository, restrict file permissions and keep a separate secure backup. Future Android updates must use the same signing key. Keys and passwords are never release assets.

Package the plugin in **UXP Developer Tools → Mobile Preview → ⋯ → Package**. For a portable source package, zip only the files inside `plugin/` with `manifest.json` at the archive root. Release downloads also include that source ZIP as a developer-mode fallback.

Checks (Python and Node.js, no third-party test packages):

```sh
python3 -m unittest discover -s tests
node tests/plugin.test.js
```

This is an independent project, not an Adobe product.
