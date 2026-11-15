# Third-party notices

MazLens source is licensed under MIT. Dependency licenses remain with their respective authors. The source repository contains no proprietary application binaries, decompiled third-party source, firmware images or personal recorder data.

## Build and runtime dependencies

Versions are pinned in the Gradle build files. Runtime dependencies are downloaded during the build, not vendored as source.

| Component | License | Project |
| --- | --- | --- |
| Kotlin, kotlinx.coroutines, kotlinx.serialization and JetBrains annotations | Apache-2.0 | https://github.com/JetBrains/kotlin |
| AndroidX, Jetpack Compose, Media3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Gradle Wrapper (redistributed scripts and JAR) | Apache-2.0 | https://github.com/gradle/gradle |
| Spotless | Apache-2.0 | https://github.com/diffplug/spotless |
| ktfmt | Apache-2.0 | https://github.com/Kotlin/ktfmt |
| google-java-format | Apache-2.0 | https://github.com/google/google-java-format |
| Guava, failureaccess and JSpecify | Apache-2.0 | https://github.com/google/guava |
| JUnit 4 (tests only) | EPL-1.0 | https://github.com/junit-team/junit4 |
| JSON-java (JVM tests only) | Public domain | https://github.com/stleary/JSON-java |

The bundled Gradle Wrapper retains its original script headers and distribution notices in `licenses/Gradle-LICENSE.txt` and `licenses/Gradle-NOTICE.txt`. The files in `app/src/main/assets/licenses/` package the MazLens MIT license, Apache-2.0 text and runtime attribution notices with the APK. Update those notices when runtime dependencies change.

## Synthetic test video

`app/src/androidTest/assets/local-recording.mp4` is a generated color test pattern and sine tone. It contains no camera recording, location, faces or private metadata. It is used to verify actual decoding and seeking through the offline player.

A replacement fixture can be generated with FFmpeg:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100' \
  -t 30 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart \
  app/src/androidTest/assets/local-recording.mp4
```

FFmpeg is a local test-fixture authoring tool and is not included in the app. Regeneration does not promise byte-identical output across encoder versions.
