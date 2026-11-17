## Context

MazLens is a native Android viewer for Mazda/Kenwood dashcams. It communicates directly with a recorder over Wi-Fi Direct, uses Kotlin and Jetpack Compose with a Russian-language interface, and keeps recordings and diagnostics in app-private storage. There is no backend, account system, or analytics service.

Use `CONTRIBUTING.md` for contribution scope and validation policy, `PRIVACY.md` for data-handling constraints, and `THIRD_PARTY_NOTICES.md` for dependency attribution and synthetic-media provenance. Keep recorder-compatibility claims grounded in the hardware evidence described in `README.md`.

## Commands and workflow guidance

Run the checked-in Gradle Wrapper from the repository root. Android tasks belong to `:app`; Spotless tasks belong to the root project.

- Format Kotlin, Gradle Kotlin DSL, and Java changes with `./gradlew spotlessApply`.
- Run affected JVM tests during development with `./gradlew :app:testDebugUnitTest`.
- Before submitting, run the CI-equivalent checks:

```sh
./gradlew --no-daemon spotlessCheck \
  :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

- Run `./gradlew :app:connectedDebugAndroidTest` on an emulator for UI, lifecycle, permission, FileProvider, or playback changes.
- Find the debug APK at `app/build/outputs/apk/debug/app-debug.apk`. Release builds enable code and resource shrinking and produce an unsigned APK; check release lint and assembly as well as debug builds.

The CI definition is `.github/workflows/android.yml`. It checks pushes and pull requests and builds debug-signed app and instrumentation-test APKs; it does not execute emulator tests, sign release APKs, or publish releases.

## Testing instructions

- Put JVM tests in `app/src/test/java/app/mazlens/`. Use JUnit 4, injected socket fakes or loopback recorder servers, and temporary directories to exercise protocol, parsing, storage, credentials, and operation ownership without a recorder.
- Put Android-dependent and Compose UI tests in `app/src/androidTest/java/app/mazlens/`. Use AndroidJUnit4 and Compose test rules; exercise actual decoding, seeking, and FileProvider access where those are the behavior under test.
- Keep the suite small and risk-driven: protect authentication, live playback, archive transfer, file integrity, offline playback/sharing, and cancellation. Add a regression test for a reproduced bug or a new user-visible behavior with meaningful failure risk; extend an existing scenario when it already covers the journey.
- Test failure and interruption paths at the owning boundary: malformed or fragmented replies, invalid tokens, timeouts, disconnects, cancellation during reads or publication, incomplete files, and stale callbacks. Preserve the invariant that failure cannot publish a partial recording or overwrite a valid original.
- Do not add tests solely for getters, copy changes, layout details, or every combination of helper flags. Remove duplicate scenarios and obsolete fixtures when their protected behavior is removed.
- Use stable Compose test tags for interaction assertions. Preserve the tested separation between navigating or entering a password and explicitly starting recorder operations.
- Keep test media synthetic or redistributable. The bundled video lives in `app/src/androidTest/assets/`; consult `THIRD_PARTY_NOTICES.md` before replacing or regenerating it.
- Treat simulator and emulator results as software validation, not recorder-compatibility evidence. Record the model, firmware, and exact function for hardware checks; do not infer family-wide support from one unit.

## Project layout and module map

This is a single-module Gradle Android project, not a multiplatform or workspace monorepo. Plugin and formatting configuration lives in `build.gradle.kts`; Android configuration and app/test dependencies live in `app/build.gradle.kts`. Repository resolution and module inclusion live in `settings.gradle.kts`.

Use these responsibility boundaries to narrow searches:

- `app/src/main/java/app/mazlens/`: Android activity entry point and production package root.
- `app/src/main/java/app/mazlens/app/`: application state, ViewModel orchestration, result classification, safe problem explanations, credentials, and export coordination.
- `app/src/main/java/app/mazlens/feature/`: feature screens and the saved-player presentation route.
- `app/src/main/java/app/mazlens/ui/`: app navigation, action callbacks, shared components, and theme.
- `app/src/main/java/app/mazlens/core/protocol/`: recorder commands, session ownership, JSON framing, and protocol validation.
- `app/src/main/java/app/mazlens/core/archive/`: binary archive parsing, recording identity, and remote-path validation.
- `app/src/main/java/app/mazlens/core/operation/`: operation and local-playback request gates.
- `app/src/main/java/app/mazlens/core/connection/` and `app/src/main/java/app/mazlens/core/network/`: Android Wi-Fi Direct discovery, group verification, and route-bound sockets.
- `app/src/main/java/app/mazlens/data/`: durable diagnostic history, private export snapshots, and recording publication/recovery.
- `app/src/main/java/app/mazlens/video/`: RTSP diagnostics and live/local Media3 player controllers.
- `app/src/main/AndroidManifest.xml` and `app/src/main/res/xml/`: permissions, cleartext transport, backup exclusions, and FileProvider exposure.
- `app/src/main/assets/licenses/`: license texts and runtime notices shipped in the APK.

## Dev environment tips

- Use JDK 21 or newer to run Gradle; Java and Kotlin compilation target JVM 17. Do not confuse the build runtime with the bytecode target.
- Take Android SDK requirements from `app/build.gradle.kts` and exact SDK-manager package IDs from `.github/workflows/android.yml`; do not derive package names by copying the numeric `compileSdk` value.
- Keep local SDK paths in the existing machine environment or ignored `local.properties`, never in tracked build configuration.
- Keep dependency versions in the existing Gradle files and preserve the Wrapper distribution checksum. Dependency repositories are centralized in `settings.gradle.kts`, which rejects project-level repositories.
- Keep protocol and archive logic free of Android APIs, but recognize that it uses JVM networking, I/O, and JSON facilities. Do not treat it as Kotlin common code or add speculative KMP/CMP scaffolding.

## Architecture notes

### Presentation and platform boundaries

- Keep screens state-driven: immutable UI data classes flow from `MazLensViewModel.state`, and `MazLensActions` callbacks carry user intent back to the application layer.
- Keep sockets, file I/O, and Android service access out of composables. Coordinate recorder operations in the ViewModel and perform blocking work on `Dispatchers.IO`.
- Keep permission/document launchers, clipboard access, and external open/share intents in `MainActivity`; use `UiEvent` for one-shot ViewModel effects.
- Keep player lifetime with the ViewModel/controller. Compose may attach a `Player` to its surface and manage presentation state, but must not release the player.
- Keep window orientation and fullscreen system-bar changes in the player route. Preserve playback across configuration recreation, while stopping it when the app genuinely backgrounds.
- Put user-facing failure explanations through `ProblemExplainer` and the existing result classifiers. Generate public text from fixed phrases and validated protocol numbers; never interpolate raw recorder replies, credentials, or private exception details into problem cards.

### Recorder operation ownership

- Preserve one foreground recorder operation at a time. Keep its `OperationGate` ticket until sockets/player cleanup and durable result persistence finish, including after cancellation.
- Capture the verified connection and `P2pSocketProvider` route for the entire operation. Reject stale work using both its ticket and connection epoch before updating recorder-specific state or publishing a file.
- Invalidate delayed local-file playback/share requests through `PlaybackRequestGate` when stopped, superseded, or backgrounded; returning to the foreground must not revive an old request.
- Cancel by closing owned sockets so blocking reads unblock. Preserve the distinction between immediate `close()` cancellation and graceful `RecorderSession.finish()` session shutdown.
- Keep one continuous command reader in `RecorderSession` responsible for replies, heartbeats, and asynchronous recorder callbacks. Serialize commands and accept only the session's validated positive token.
- Preserve bounded frames, response counts, traces, transfer sizes, and deadlines. Keep strict numeric, path, archive-length, and date validation at network and storage boundaries.
- Keep the authenticated recorder session and heartbeat reader alive when switching cameras; replace only the RTSP probe/player. Close a session after a command timeout rather than retrying a command whose late reply cannot be correlated safely.

### Storage and privacy

- Download into an owned private partial file. Validate transfer size, completion, and any supplied checksum before committing through `RecordingStore` under the operation's publication gate.
- Treat the metadata rename as the recording publication point. Do not expose partials or orphaned originals through saved-recording lists; preserve startup recovery and same-directory staged writes.
- Keep recording identities scoped to the recorder, not just the remote filename/path. Preserve legacy metadata reads without associating unscoped originals with the connected recorder.
- Preserve a valid saved original when a repeat download fails or returns different content.
- Keep history bounded and durable, recover interrupted attempts, and retain the private export snapshot while Android's document picker owns it.
- Keep entered passwords in memory, bound to the verified recorder ID across temporary disconnections. Clear them when another recorder connects. Recover credentials only from successful history with the same recorder ID, never display names or unscoped legacy records; an invalid explicitly entered password must not silently fall back to a stored one.
- Keep cloud and device-transfer backup disabled. Share recordings only through the restricted `${applicationId}.recordings` FileProvider with temporary read permission; do not broaden it to other private directories.
- Treat diagnostic responses, traces, clipboard copies, and ZIP reports as sensitive and not anonymized. Keep raw data private and exclude it from public logs, issues, and fixtures.

## Code style

- Write production code in Kotlin and UI in Compose; preserve the Russian-language interface.
- Let Spotless apply `ktfmt`'s Kotlin-language style to Kotlin/Gradle scripts and `google-java-format` to Java tests. Do not introduce competing formatter rules.
- Preserve `.editorconfig` encoding and line endings, including CRLF for Windows batch scripts.
- Prefer direct dependencies and concrete owners. Add an interface or module only for a real second implementation or platform need; do not introduce a DI framework, generic repository hierarchy, or one-use-case-per-method layering.
- Reserve comments for protocol constraints, ownership rules, and other non-obvious decisions.

## Common workflows

### Add or change a feature

1. Extend application UI state and action callbacks, then add presentation under the matching feature package or shared UI boundary.
2. Implement protocol, archive, storage, or playback behavior in its existing owner; coordinate it in `MazLensViewModel` without bypassing lifecycle and operation gates.
3. Wire navigation in `MazLensApp` and platform-only effects through `MainActivity`/`UiEvent` when needed. Keep recorder I/O behind an explicit user action.
4. Extend the risk-relevant JVM or emulator scenarios, run affected tests, and complete the checks above before submission.

### Change dependencies or distribute a release

- Update both `THIRD_PARTY_NOTICES.md` and packaged notices in `app/src/main/assets/licenses/` when runtime dependencies change.
- Use semantic public versions and increase Android `versionCode` for every distributed update. Keep signing keys and passwords outside Git; preserve the application ID and release signing key for installable updates.
- Do not commit APKs, build outputs, personal recordings, device reports, third-party app binaries, or signing material.

## Known pitfalls

- Wi-Fi Direct pairing is not recorder authentication. Require the recorder to be the verified group owner and the phone to be its client before issuing recorder commands.
- Use the captured P2P-bound socket provider for command, data, and RTSP traffic; never fall back to the default network when the recorder route is unavailable.
- Preserve the permission split: nearby-device permission on API 33+, location permission on older supported Android versions. The location permission supports discovery, not phone GPS collection.
- Preserve cleartext-traffic support: the recorder protocol and local video transport are unencrypted. Do not assume HTTPS/TLS endpoints exist.
- Do not equate a successful RTSP response or advertised video track with a working preview; success requires an actual rendered frame.
- Treat backgrounding, disconnect, and recorder changes as cancellation boundaries. Do not publish a recording or start a player from an old connection or request after those boundaries.

## PR and Git guidance

- Use short English commit subjects in the imperative mood, starting with a capitalized verb such as `Implement`, `Fix`, or `Add`, matching the repository history. Omit the final period.
- Keep one logical change per commit, including its regression tests and directly related documentation. Commit independent fixes separately.
- Explain the problem, changed behavior, and verification in contributions. Distinguish automated results from hardware results and state which checks were not run.
- Keep changes tied to a user-visible improvement, demonstrated defect, or concrete maintenance need, as required by `CONTRIBUTING.md`.

## Maintain this file

Keep `AGENTS.md` up to date when project changes affect these instructions. Correct stale paths, commands, and ownership rules, and add durable project-specific knowledge as it becomes available. Keep it concise and under roughly 400 lines; omit temporary status, dates, changelogs, feature inventories, secrets, machine-specific paths, one-time setup steps, and generic technology advice. Do not invent conventions or fill gaps with placeholders.
