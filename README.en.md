# AirBridge Android 1.1.0

AirBridge Android is a fully local HomePod audio sender. The Android UI, foreground capture service, device discovery and native AirPlay engine all run inside the APK. It does not require the Windows app or a separately started backend.

## Features

- Android 9 and newer (`minSdk 28`, no `maxSdk`) with guarded APIs for later Android versions.
- Native DNS-SD discovery of `_airplay._tcp` receivers.
- HomePod stereo grouping through `tsid`, `gpn` and `igl` metadata.
- One shared RTP timeline and PTP clock for an existing HomePod stereo pair.
- Android 10+ playback capture through MediaProjection.
- Android 9 microphone fallback and an always-available local test tone.
- HomePod volume control, notification controls and background foreground-service playback.
- User-selectable 100–2000 ms audio buffering (50 ms steps, default 500 ms), persisted locally and applied on the next connection.
- A frame-aligned bounded native queue that waits for prefill, refills after underruns, and fades transitions over 5 ms to reduce clicks. Overflow recovery is counted rather than silently dropping arbitrary incoming chunks.
- Selectable 44.1/48 kHz, stereo, 16-bit audio. PCM payload rates are 1411.2/1536 kbps; ALAC preserves the same decoded precision with a content-dependent compressed bitrate. No 24-bit or 96/192 kHz support is claimed.
- Automatic / PCM / ALAC codec selection and live negotiated-format, queue depth, underrun and overflow reporting.
- Single capture producer; test tones are mixed into the existing timeline instead of inserting additional PCM blocks. Test-only playback is paced by the selected sample rate and continues silence to flush its tail.

The buffer setting controls local prefill and the AirPlay latency request (`max(150, buffer_ms)`). These are separate stages, so the displayed buffer is **not** a measurement of end-to-end audible delay. HomePod may impose a different playback buffer. Increasing buffering also increases audible delay. This is audio jitter buffering, not the Windows wired-speaker delay-sync feature.

## Validation for 1.1.0

25 Rust tests pass, including prefill at both rates and 100/500/1000/2000 ms, jitter, underrun recovery, overflow bounds, stereo frame integrity and independent bit-exact ALAC decoding. Android lint reports no issues. Release-APK instrumentation on the Android 16 emulator verifies settings persistence, settings passed to the native engine, and JNI queue duration/validation. The APK installs over 1.0.0 using the same signing certificate.

This release has not undergone physical Android phone + HomePod listening tests or Android 17 device validation. These automated results do not guarantee elimination of crackling on every network or phone.

Some Android apps opt out of playback capture, and protected media cannot be captured. This is enforced by Android. The test tone can be used to verify the HomePod connection independently.

## Build

Requirements: JDK 17, Android SDK Platform 34, Android NDK r30, Rust and `cargo-ndk`.

```powershell
./build-android.ps1
```

The development-signed APK is generated under `app/build/outputs/apk/release/`. Distributed APKs in `../dist` are separately signed with the existing AirBridge release certificate; the private signing key is excluded from source archives.

## License

The AirPlay engine is derived from AirFlash and is distributed under GPL-3.0-or-later. The corresponding source, attribution and `LICENSE-GPLv3.txt` are included with this project and the accompanying source package.
