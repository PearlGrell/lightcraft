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

## What works

- **Library = the phone's gallery**: after the media permission (`READ_MEDIA_IMAGES`, or storage
  before Android 13; a clear prompt, and Settings when it was denied for good) the grid shows every
  image MediaStore has, newest first, in day sections (Today, Yesterday, dates). Opening one imports
  it in place (`Mobile::photo_for_path`): the original stays where it is and the edits live in the
  app's catalog. No demo or generated pictures. Pinch or the grid button changes density.
- **Editor** (phone-editor layout): the photo edge to edge (pinch or double-tap to zoom, press and
  hold for the original, Compare for a draggable before/after split); back, undo, redo, compare and
  share float over it. At the bottom, the open tool's panel, a tool row, and a six-tab bar:
  **Quick** (Auto, Pop, Sky, B&W, Remove BG, Reset), **Presets** (live thumbnail strip rendered with
  `Mobile::preset_thumbnail`, which never applies the preset), **Crop** (aspect ratios, rotate,
  flip, straighten), **Edit** (Auto | Light, Color, Curve, Effects, Detail, Optics, Geometry),
  **Masking** and **Remove**. Every slider comes from the engine's control list
  (`develop.controls`), grouped by id prefix in `Tools.kt`. Sliders scrub relative to the finger,
  snap to their default with a haptic tick, reset on double-tap; one drag is one undo step.
- **Point tone curve** (`ui/CurveEditor.kt`): RGB, Red, Green and Blue; tap to add a point, drag to
  move it, drag an inner point off the grid to remove it (`develop.curve`).
- **AI masks** (Masking tab): Subject and Background come from ML Kit Subject Segmentation
  (`Segmenter.kt`, on-device; Play services downloads the model on first use) and enter the engine
  as segmentation masks (`Mobile::add_seg_mask` → `mask.add`); Sky is the engine's own sky mask;
  Linear and Radial gradients too. Each mask has local sliders, invert and delete.
- **Remove**: one-tap background removal (ML Kit matte) and a Magic Eraser (tap to erase similar
  colour; tolerance in CIELAB ΔE, contiguous, sample size, anti-alias, feather, opacity), with undo,
  reset and transparent PNG export with edge decontamination. The pixel work is the pure-Rust
  `lightcraft-bgremove` crate.
- **Export / share**: JPEG through the engine's exporter, handed to the Android share sheet.

**Brand mark**: `res/drawable/ic_brand_mark.xml` (library header) and `ic_launcher_*.xml` (launcher)
are original artwork (an amber "Lc" monogram on an espresso tile). Replace those files, keeping their names, to rebrand. Adobe's logos and other
Adobe assets may not be used (see `CLAUDE.md`).

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
`./gradlew :app:recordPaparazziRelease` renders the editor chrome on the JVM (no device) into
`app/src/test/snapshots/` (gitignored) for a quick look at layout changes.

Licences: the Compose, AndroidX and Material icon libraries are Apache-2.0 dependencies fetched by
Gradle, as is ML Kit subject segmentation (Google Play services); the launcher icon is original work (`assets/ATTRIBUTION.md`).
