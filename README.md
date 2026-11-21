# Anubis

Anubis is a free Android app for the **DG-Lab Coyote 3** e-stim unit and **Pawprints** wireless buttons. It puts clear controls, live readouts and hard safety limits in front of you, with a big STOP that is always on screen.

**Current release: v5.3** · version 5.3 (102) · Android 8.0 (API 26) or newer · Bluetooth LE

[**Download Anubis v5.3 (APK)**](https://github.com/anubis-actual/Anubis/releases/tag/v5.3) · [Website](https://anubis-actual.github.io/Anubis/) · [Overview deck (PDF)](docs/deck/Anubis-Overview-v5.2.pdf) · [Overview deck (PPTX)](docs/deck/Anubis-Overview-v5.2.pptx)

> Anubis is an independent project. It is **not affiliated with, endorsed by, or supported by DG-Lab**. "DG-Lab", "Coyote" and "Pawprints" are names of their respective owners and are used only to describe compatibility.

## Features

- **Safety first.** A gold STOP button that stops both channels and every mode at once. Pressing either dial on the Coyote is also an instant full STOP. Optional volume-key STOP (hold 3 s).
- **Max Output per channel.** Auto, games, Remote and Pawprints never go above it. Soft start ramps output up gently instead of jumping.
- **Live / Manual.** Level dial with live output, Max Output lines on the output graph, Ramp and Sweep per channel, Link to drive A and B together. Turning a Coyote dial adjusts that channel.
- **Auto.** Eight profiles with tile-based settings (Session, Character, Variation & channels). Hand over channel A, B, or both, and take each one back separately.
- **Patterns.** Library, step builder and hand-drawn sketch. Imports DG-LAB `.pulse` and `.zip` waveforms. Shuffle mode.
- **Draw.** Touch-and-drag output with one or two fingers, record and loop.
- **Audio.** Drive output from an audio file, your phone's audio (Android 10+) or the microphone, with gain, equalizer and output shaping.
- **Pawprints.** Connect up to 6 wireless buttons; map presses, double/triple presses, holds and tilts to actions with rules.
- **Games.** Dot Chase (works with Pawprints) and Trivia.
- **Partner remote.** One-on-one sessions by one-time code or QR. You approve each partner, your Max Output always holds and your STOP always wins.
- **Overlay.** A floating bubble with live levels and STOP over other apps; keeps running in the background.
- **Coyote BLE log** with Copy and Share for troubleshooting (Settings → Device).

See the [overview deck](docs/deck/Anubis-Overview-v5.2.pdf) for screenshots and details.

## Download and install (sideload)

Anubis is distributed as an APK on GitHub Releases, not through the Play Store.

1. Open the [v5.3 release](https://github.com/anubis-actual/Anubis/releases/tag/v5.3) on your Android phone and download `Anubis-v5.3-release.apk`.
2. **Verify the file** (recommended). Checksums are in the [Verify](#verify) section below.
3. **Allow installs from this source.** When you open the APK, Android asks for permission to install unknown apps from your browser or file manager. Allow it for that app (Settings → Apps → Special app access → Install unknown apps).
4. Tap **Install**, then open Anubis. You can turn the "install unknown apps" permission back off afterwards.
5. On first launch, read and accept the safety agreement, then the *Before you start* note, then choose **Start with tutorial** or **Start without tutorial**. You can change it later at Settings → Appearance → Tutorial → Tutorial mode.

## Verify

SHA-256 of `Anubis-v5.3-release.apk`:

```
12761e15c530df64aa71039e2c171e4836b130c3a3fc5454021dd5837fa84d17
```

- On a computer: `sha256sum Anubis-v5.3-release.apk` (Linux), `shasum -a 256 Anubis-v5.3-release.apk` (macOS), or `certutil -hashfile Anubis-v5.3-release.apk SHA256` (Windows).
- On Android: any file-hash app works.

Release signing certificate (SHA-256): `7d01ca6bdf081381b817eb45e67fcb7ae114a501aa0cfcfc617bc76e01f55652`
(check with `apksigner verify --print-certs Anubis-v5.3-release.apk`).

**Updates:** every release is signed with the same key, so a newer APK installs over the one you have and keeps your settings.

## Safety note

This is the *Before you start* note shown in the app on first run (and re-readable at Settings → Data → Safety → Safety note):

> **Before you start**
>
> Start at the lowest strength and increase slowly so you can feel how it lands.
>
> Keep electrodes below the waist and off the chest. Don't use this with a pacemaker or other implanted device, or on broken or irritated skin, and don't use it in water, while driving or operating machinery, or while you're asleep.
>
> The gold STOP button, or a press of either Coyote dial, cuts output immediately.

Use of this app is at your own risk. Always follow the device manufacturer's safety instructions.

## Repository layout

```
web/            The app UI: a single-file web app (index.html) plus its title font
android/        Android wrapper: WebView host, Bluetooth LE (Coyote 3, Pawprints), services, overlay
  src/          Java sources (package com.anubis.app)
  variants/live Release-build variant hooks
  res/          Icons, colors, theme
  AndroidManifest.tmpl.xml
build.sh        Command-line build script (no Gradle)
docs/           Project website (GitHub Pages) and the overview deck
```

## Building from source

Requirements: JDK 17 or newer, the Android SDK with `platforms;android-36` and `build-tools;36.0.0`, and `python3`.

```bash
export ANDROID_SDK=/path/to/android-sdk
./build.sh                      # produces dist/Anubis-5.3-unsigned.apk
```

To sign with your own key:

```bash
KEYSTORE=/path/to/your.jks KEY_ALIAS=yourkey KS_PASS_FILE=/path/to/password.txt ./build.sh
```

The build copies `web/index.html` into the APK as an asset, compiles the Java wrapper with `javac` and `d8`, packages resources with `aapt2`, then aligns and (optionally) signs with `apksigner`. An APK signed with your own key cannot update the official release; uninstall the official build first.

The partner remote uses a Firebase Realtime Database with anonymous sign-in. The web config in `web/index.html` is the public client config already shipped inside the app. Forks should point it at their own Firebase project.

## License

No license has been chosen yet. Until one is added, all rights are reserved. The bundled Uncial Antiqua font is licensed under the SIL Open Font License 1.1 (see `web/UncialAntiqua-OFL.txt`).
