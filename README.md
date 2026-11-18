# MazLens

An independent Android viewer for Mazda/Kenwood dashcams, built with Kotlin and Jetpack Compose.

**Version 0.9.0 · Android 8.0+ · Russian interface**

MazLens connects directly to a recorder over Wi-Fi Direct. It can show the front or rear camera, list recordings, download an original to the phone, and play or share saved files without a recorder connection. There is no account, server or analytics service.

This is an early public release. It is not affiliated with Mazda or JVCKENWOOD.

## Compatibility

| Recorder | Status |
| --- | --- |
| Mazda/Kenwood `C9K1_V6_580`, firmware `1.01` | Front/rear live video, archive listing and a front-camera download confirmed on hardware |
| `C9KA_V6_580` | Model identifier recognized; not tested on hardware |
| Other recorders | Not verified |

A matching car model or Wi-Fi network name alone does not establish compatibility. Same-session camera switching, rear-camera downloads, settings changes, password changes and remote deletion still need hardware checks. Automated tests use simulated recorders and synthetic video.

## Use

Download the signed APK from [GitHub Releases](https://github.com/skpntsv/MazLens/releases/latest).

1. Park the car and turn on the recorder. Enable its Wi-Fi function.
2. Open **Подключение**, allow nearby-device access, find the recorder and connect.
3. Enter the recorder password and select **Войти с паролем**. Android Wi-Fi pairing and the recorder's own login are separate steps.
4. Open **Камеры** for a live preview, or **Записи** to load the archive and download a recording.
5. Open **На телефоне** to play downloaded videos, seek, or share an original through Android.
6. Open **Настройки** and select **Прочитать настройки** to load recording, parking and sound options. Edit the form, then confirm **Применить** to save and verify the result. Standard values only fill the form; they do not reset the password or delete recordings.

Password changes use a separate confirmed action. If the result is uncertain, enter the active password manually before further operations. In **Записи**, remote deletion requires confirmation and an updated archive check; it never removes a downloaded phone copy.

Access diagnostics are available under **Диагностика**. On the tested firmware, a model-only session can return the recorder's settings, including its password. This behavior is firmware-specific and is not a promise of password recovery on other models.

Current limits:

- Live preview stops after 30 seconds.
- Download the whole original before offline playback; one foreground transfer at a time.
- Leaving the app or losing the connection cancels a transfer. There is no resume or background download yet.
- No SD-card formatting, maps or batch downloads/deletion.
- Settings require a complete supported snapshot; missing or unknown values block editing. A changed snapshot must be reread before applying a draft.
- A recorder may pause recording while a viewer is connected. Its GPS/motion restrictions still apply.

MazLens uses the application ID `app.mazlens` and installs separately from the earlier DVR Direct prototype. Its private history and downloaded files are not migrated automatically. Share any files you want to keep from the old app before uninstalling it.

## Build

Install JDK 21 or newer and an Android SDK with platform 37 and build tools 36.0.0. Set `ANDROID_HOME`, or set `sdk.dir` in an untracked `local.properties`. Open this folder in Android Studio, or run:

```sh
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The Gradle Wrapper and dependency versions are pinned; the first build needs internet access. The project targets Android API 36 and compiles against API 37.

To format code:

```sh
./gradlew spotlessApply
```

To run device tests on an emulator:

```sh
./gradlew :app:connectedDebugAndroidTest
```

For an optimized release build:

```sh
./gradlew :app:lintRelease :app:assembleRelease
```

The release APK is unsigned. Sign it using Android Studio's **Generate Signed App Bundle or APK** with your own release key. Keep the key and passwords outside this repository. Updates require the same application ID and signing key; a debug APK is not a release artifact.

## Code layout

One Android application module, organized by responsibility:

- `feature/` and `ui/`: Compose screens and reusable presentation components.
- `app/`: UI state, lifecycle and operation coordination.
- `core/protocol`, `core/archive`, `core/operation`: recorder communication, archive parsing and operation ownership.
- `core/connection`, `core/network`: Android Wi-Fi Direct and socket routing.
- `data/`: private history, report export and atomic recording storage.
- `video/`: live and offline Media3 players.

Screens do not create sockets or write files. A single operation owns a recorder session through cancellation and cleanup. Downloads become visible only after size/checksum validation and atomic publication.

KMP/CMP is a future direction, not a current target. The protocol and archive logic avoid Android APIs, but still use JVM facilities (`java.net`, `java.io`, `org.json`). A port will need platform implementations for networking, storage and media; no unused multiplatform scaffolding is included.

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks and change scope, [PRIVACY.md](PRIVACY.md) for local data handling, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependencies and test media.

## License

MIT. See [LICENSE](LICENSE).
