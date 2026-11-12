# Working on Seion

This is a single-module Gradle project (`app/`, package `org.seion`). Debug builds install next to release builds as
`io.github.de1987.seion.debug` (the Kotlin package is `org.seion`). minSdk is 31, compile and target SDK 37, and the toolchain versions are pinned in
`gradle/libs.versions.toml`.

## Layout

```
app/src/main/java/org/seion/
  library/    Room index, incremental folder scanner, tag reader, WorkManager scan job
  art/        cover lookup order, embedded pictures, folder images, chosen covers, online sources and cache
  meta/       online identification of untagged files
  playback/   PlaybackService (ExoPlayer in a MediaLibraryService), queue, browse tree for cars and Bluetooth
    dsp/      the audio engine: float sink, engine, CrystalX, CutoffDetector and RenovaPure, limiter, filters
    viz/      spectrum analyser fed from the sink
  playlists/  playlists database, M3U import and export
  settings/   DataStore settings and the app language
  ui/         Compose screens, Now playing, mini-player, ambient background
app/src/debug/  a broadcast-driven media browser for testing the browse tree without a car
app/src/test/   JVM and Robolectric unit tests; test/resources/dsp holds the frozen DSP vectors
tools/          release checks, DSP reference vectors and a few device helpers
```

Playback runs in a Media3 `MediaLibraryService`; the UI talks to it through a `MediaController`. The library index in
Room is a cache that can always be rebuilt from the files, while playlists live in their own database. Navigation is
a small hand-written back stack rather than navigation-compose, and `SeionApp` holds a hand-written dependency
container.

The audio engine wraps Media3's `DefaultAudioSink` in a forwarding sink. Each mono or stereo PCM stream up to 192 kHz
is converted to float, trimmed, run through `DspEngine` at a constant latency and handed on as float. Anything else
passes through untouched. The audio thread never allocates or takes a lock (`EngineTest` checks the allocation
part), and with both effects off the output is the input, delayed, bit for bit.

## Build and check

```sh
make check    # assembleDebug testDebugUnitTest ktlintCheck lintDebug
make format   # ktlintFormat
make install  # install the debug APK (SERIAL=<adb serial> if more than one device is connected)
```

Run `make check` before sending a change; it is the whole gate. Lint aborts on errors, and hard-coded UI text and
missing translations count as errors (`config/lint/lint.xml`). Every user-visible string goes into both
`res/values/strings.xml` and `res/values-pt-rBR/strings.xml`. For faster iterations run a single test class, for
example `./gradlew testDebugUnitTest --tests '*CrystalXTest'`.

`-Pseion.ffmpeg=false` builds without the GPL-3.0 FFmpeg decoder (see the README).

`seion.projectUrl` is where Seion's source lives, `https://github.com/de1987/seion` by default (the
`defaultProjectUrl` line in `app/build.gradle.kts`). It becomes `BuildConfig.PROJECT_URL` and is used in three places:
the "Source code" row in Settings, the line at the top of the licences screen, and the User-Agent of the MusicBrainz
and Cover Art Archive lookups (`Seion/<version> ( <url> )`, the contact MusicBrainz asks clients to send). If the
repository moves, change that one line, or build with `-Pseion.projectUrl=https://...`. It must be an `https://` URL.

## DSP tests and tools

The frozen vectors in `app/src/test/resources/dsp/frozen-*.json` pin the engine's constants. They are produced by
`tools/dsp/frozen_vectors.py`, which uses only numpy and scipy and does not read the Kotlin code. If a change
legitimately moves them, regenerate with `PYTHONDONTWRITEBYTECODE=1 python3 tools/dsp/frozen_vectors.py` and commit
the JSON together with the change.

Three opt-in tools are skipped by the normal test run:

```sh
./gradlew testDebugUnitTest -Pseion.bench=true --tests '*DspBenchmarkTest'
./gradlew testDebugUnitTest -Pseion.render.in=<dir> -Pseion.render.out=<dir> --tests '*DspRenderTool'
SEION_META_EVAL=<dir> ./gradlew testDebugUnitTest --tests '*MetadataEvalTool'
```

The first prints real-time factors on the JVM. The second runs every `<name>.wav` in the input folder through each
effect setting (a `<name>.ref.wav` next to it is used as the lossless reference) and writes loudness-matched renders,
`metrics.json` and a blind A/B page. The third replays a labelled set of tracks (`gt.json` plus cached lookup
candidates) through the file-name parser and the metadata matcher and prints how many it got right.

On a device, the debug build prints engine load with `adb shell dumpsys activity service io.github.de1987.seion.debug/org.seion.playback.PlaybackService`.

## Device helpers

`tools/dev/adb_ui.py` drives the app over adb by Compose test tag (`MainActivity` exposes test tags as resource ids).
`tools/dev/demo_library.py` generates a fictional library with procedural covers for screenshots, and
`tools/dev/make-test-beat.sh` makes a short royalty-free beat so the spectrum analyser has something to show.
`tools/dev/record-clip.sh` records a screen clip and rejects takes with dropped frames. Each script explains its
options at the top.

## Release builds

Release builds are signed with your own key, which never goes into the repository. Copy
`keystore.properties.example` to `keystore.properties` in the project root (it is git-ignored) and fill it in, or set
the environment variables `SEION_RELEASE_STORE_FILE`, `SEION_RELEASE_STORE_PASSWORD`, `SEION_RELEASE_KEY_ALIAS` and
`SEION_RELEASE_KEY_PASSWORD`, which take precedence over the file. `SEION_KEYSTORE_PROPERTIES` can point at a
properties file somewhere else. Without a complete signing config `assembleRelease` fails instead of falling back to
the debug key.

A new key can be made with:

```sh
keytool -genkeypair -v -keystore release.jks -alias seion -keyalg RSA -keysize 4096 -validity 10000
```

Keep a backup of it. Android only installs an update that is signed with the same key as the installed app.

```sh
make release        # assembleRelease, then tools/release/verify_apk.sh
make release-test   # checks that the verifier rejects the debug APK and stale builds
```

The verifier fails if the APK is debuggable or testOnly, if the package is not `io.github.de1987.seion`, or if its version does
not match `app/build.gradle.kts`. To pin the signing certificate as well, pass its SHA-256 as a second argument or
set `SEION_RELEASE_CERT_SHA256`. You can read the digest from a signed APK with
`apksigner verify --print-certs app-release.apk`.

Bump `versionCode` and `versionName` in `app/build.gradle.kts` for every release.

## Distribution

Seion asks for `MANAGE_EXTERNAL_STORAGE` (All files access) so it can read a music folder anywhere on the device
without the MediaStore. Google Play only allows that permission for a few kinds of apps, so Seion is meant to be
sideloaded or shipped through F-Droid-style repositories, not Google Play.
