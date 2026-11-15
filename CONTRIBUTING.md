# Contributing

Keep changes focused on a user-visible improvement, a demonstrated defect or a concrete maintenance need. Explain the problem, changed behavior and how it was checked.

## Code

- Production code is Kotlin; the UI uses Jetpack Compose and immutable state.
- Keep sockets, files and Android services out of composables. Preserve explicit ownership and cancellation of sessions and players.
- Use direct dependencies where possible. Add an interface or module when a real second implementation or platform needs it.
- Do not introduce a DI framework, generic repository hierarchy or one-use-case-per-method structure just to add layers.
- Format with `./gradlew spotlessApply`. Keep comments for constraints and non-obvious decisions, rather than a narration of each statement.
- Preserve protocol validation, bounded reads, route binding, atomic downloads and same-recorder credential matching.

## Validation

Run the affected tests while developing. Before submitting a change:

```sh
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Run `./gradlew :app:connectedDebugAndroidTest` on an emulator for changes to UI, lifecycle, permissions, FileProvider or playback. These tests use loopback sockets and synthetic media; they do not connect to a car.

Add tests for observable behavior, protocol handling, data integrity or a reproduced regression. Do not add tests solely for getters, text changes or formatting. Remove a test when the implementation it protects is removed.

Hardware results should identify the recorder model, firmware and tested function. Never infer support for a family from one unit. A simulated response or successful build does not replace a hardware check.

## Issues and private data

Include the MazLens version, Android version, recorder model/firmware, steps and visible error. Raw diagnostic reports may contain passwords, tokens, device identifiers and recording names. Do not attach them to public issues. Remove private values before sharing a minimal example.

Keep signing keys, APKs, build outputs, personal recordings, device reports and third-party app binaries out of Git. Test fixtures must be synthetic or redistributable with a documented origin. The checked-in video fixture is synthetic; its regeneration command is documented in `THIRD_PARTY_NOTICES.md`.

## Releases

Use semantic versions for public MazLens releases, starting at `0.1.1`. Increase Android's `versionCode` for every distributed update, even if the visible version numbering changes. Never commit signing credentials. GitHub CI builds and checks source; it does not publish or sign a release.
