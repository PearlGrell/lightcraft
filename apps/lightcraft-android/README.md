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

The phone UI is the desktop app's design on a phone: the desktop's Midnight colour tokens, its
Inter font (bundled from `assets/fonts` at build time), its icons (`ui/DeskIcons.kt`, ported
stroke for stroke from `crates/ui-egui/src/icons.rs`) and its widgets (`ui/Widgets.kt`: the
gradient-track slider with a hollow thumb, section headers, flyout rows, bordered buttons).

- **Launch**: the LightCraft lynx on the canvas (AndroidX SplashScreen) until the library is open.
- **Library = the phone's gallery**, laid out as the desktop's library: a **sidebar** (drawer) with
  My Photos (All Photos, Recently Added, Picks, Edited), Albums (the phone's folders), By Date and
  **Recently Deleted**; **Search Photos** and a rating / flag **filter** on the top bar; the
  **justified grid** (photos at their own shape) with stars, pick / reject flags and an edited mark;
  the bottom bar's view modes (justified, square, one per row), sort, and a thumbnail-size slider
  (or pinch). Press and hold to select: rate 0–5 stars, flag pick / reject, **Copy Edit
  Settings** / Paste, share, and **delete** (to Android's trash, Recently Deleted, on Android 11+:
  restore or delete for good there; photos deleted for good leave the catalog with their edits,
  `Mobile::forget_paths`). Opening a photo imports it in place (`Mobile::photo_for_path`).
- **Editor**, as the desktop's develop view: a top bar (library, file name, undo, redo, before /
  after, export, more: info and rating, copy / paste edit settings, reset, delete), the photo
  (pinch or double-tap to zoom, press and hold for the original, a draggable before / after
  split), the **filmstrip** (toggle on the rail), the panel (drag its handle to resize, tap to fold
  it) and the **tool rail**: Edit, Crop, Remove, Masking, Presets.
- **Edit panel** (the desktop's right panel): the **histogram** (R, G, B over luminance, clipping
  corners; drag a zone to move Blacks, Shadows, Exposure, Highlights or Whites; tap the camera line
  to fold it) over **ISO, focal length, aperture and shutter**; Edit with Auto, B&W, Reset;
  **Profile** (`profiles.list`, `develop.profile`) and its amount; then collapsible **Light**
  (with **Curve**: point curve in RGB / R / G / B, and the parametric curve; **HDR**), **Color**
  (white-balance presets and the **selector**: tap a neutral point; Temp / Tint / Vibrance /
  Saturation on the desktop's gradient tracks; **Color Mixer** and **Color Grading** flyouts),
  **Effects** (with Vignette and Grain), **Detail**, **Optics**, **Geometry** (Upright) and
  **Calibration**. Every slider comes from the engine's control list (`develop.controls`), with its
  track. Dials are used where three values of one colour read best (Color Mixer); Color Grading has
  the desktop's **colour wheels**.
- **Crop**: aspect ratios, **Auto** straighten (`crop.autoStraighten`), rotate, flip, angle.
- **Masking**, as the desktop's panel: Create New Mask tiles (Subject, Sky, Background, Brush,
  Linear, Radial, Luminance; **Object** and **Describe** with SAM 3; Dodge and Burn), Pop Subject
  and Blue Sky, the masks list, each selection with **Add / Subtract / Invert / delete**
  (`mask.addComponent`, `mask.component`), brush settings, and the desktop's 17 local sliders.
  Subject and Background come from ML Kit on the phone; brush strokes and Object taps map to the
  photo through `Mobile::view_to_image`. SAM 3 is an optional ~3.4 GB download (Meta's SAM
  License) offered on phones with 6 GB of memory or more.
- **Remove**: background removal (ML Kit matte), Magic Eraser (tolerance, feather, opacity,
  contiguous, anti-alias, sample), undo, restore, transparent PNG.
- **Presets**: groups as sections, live thumbnails (`Mobile::preset_thumbnail`).
- **Export**: JPEG, PNG, WebP or AVIF, size and quality, then Save to Gallery (Pictures/LightCraft)
  or Share. **Info**: file, size, capture time, camera, lens, exposure, place; stars and flags.

**Brand mark**: the launcher icon (adaptive: the lynx on the amber field, an ink-only themed
icon), the splash icon and the library header are the LightCraft app icon
(`assets/app-icon/lightcraft.svg`), rendered for Android by `android/icons.sh` (needs `resvg`).
Re-run it after the icon changes. Adobe's logos and other Adobe assets may not be used (see
`CLAUDE.md`).

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
`./gradlew :app:recordPaparazziRelease -x cargoNdk` renders the library, sidebar, Edit panel,
Masking and Color Grading on the JVM (with the system font: layoutlib here can't load Inter), no device or NDK needed, into `app/src/test/snapshots/` (gitignored) for a
quick look at layout changes; `./gradlew :app:testReleaseUnitTest -x cargoNdk` runs the JVM tests.

Licences: the Compose, AndroidX (incl. core-splashscreen) and Material icon libraries are
Apache-2.0 dependencies fetched by Gradle, as is ML Kit subject segmentation (Google Play
services); the optional SAM 3 model is Meta's, under its SAM License, downloaded only when the
person agrees; the launcher icon is original work (`assets/ATTRIBUTION.md`).
