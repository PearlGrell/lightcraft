# LightCraft for Android

A native Android app (Kotlin + Jetpack Compose, Material 3) over the LightCraft engine. The engine is
the same Rust code as the desktop app, CLI and MCP server; the phone UI is new and touch-first.

```
apps/lightcraft-android/
  src/lib.rs        JNI entry points for ai.storyteller.lightcraft.android.Engine (Rust cdylib)
  android/          the Gradle project (app/ = the Compose app)
crates/mobile/      lightcraft-mobile: the thread-safe engine handle the JNI layer wraps
```

Every edit is an engine command by id with JSON parameters (`develop.set`, `develop.auto`,
`edit.undo`, `library.import`…), exactly what the desktop UI, CLI and MCP send, so the phone gets
every engine feature without a second implementation. Pixels come back as ARGB ints for a `Bitmap`;
exports go through the engine's one encoder (`lightcraft_engine::export`).

## What works (milestone 1)

- **Library grid**: the procedural demo library on first run, photos added with the system Photo
  Picker (copied into the app's private library), pinch or the grid button to change density.
- **Editor**: full-bleed photo, pinch and double-tap to zoom, press and hold for the original.
  Undo, redo and share always in the top bar.
- **Tool rail** (Lightroom-mobile style): Auto (`develop.auto`), Light, Color (white balance,
  vibrance, saturation), Effects, Detail. Each opens a slider panel above the rail; ranges, defaults
  and values come from the engine (`develop.controls`). One drag is one undo step
  (`develop.beginInteraction`/`endInteraction`), frames render in draft quality while dragging and
  in full quality on release; double-tap a slider's label to reset it; a haptic tick at its default.
- **Export / share**: JPEG through the engine's exporter, handed to the Android share sheet.
- Optics, Geometry, Crop, Masking, Remove and Presets are on the rail and come in later milestones.

## Build

Needs the Android SDK (platform 35, build-tools 35) and NDK 27.2.12479018, the Rust Android targets
and `cargo-ndk`:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cd apps/lightcraft-android/android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk
./gradlew assembleRelease -PrustAbis=arm64-v8a   # phones only, faster
```

Gradle runs `cargo ndk` before every build (the `cargoNdk` task), so Rust changes are picked up.
The release APK is minified and signed with the debug key so it installs directly; a store build
supplies its own signing config. `smoke-test.sh` installs an APK on a connected device or emulator,
opens the editor and saves screenshots; CI (`.github/workflows/android.yml`) runs it on an emulator.

Licences: the Compose, AndroidX and Material icon libraries are Apache-2.0 dependencies fetched by
Gradle; the launcher icon is original work (`assets/ATTRIBUTION.md`).
