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

- **Launch**: the LightCraft lynx on the app's near-black canvas (AndroidX SplashScreen), held
  until the engine has opened the library (at most ~1.5 s).
- **Library = the phone's gallery**: after the media permission (`READ_MEDIA_IMAGES`, or storage
  before Android 13; a clear prompt, and Settings when it was denied for good) the grid shows every
  image MediaStore has, newest first, in day sections (Today, Yesterday, dates). Opening one imports
  it in place (`Mobile::photo_for_path`): the original stays where it is and the edits live in the
  app's catalog. Pinch or the grid button changes density. **All / Edited / Favourites** filters;
  small badges mark edited and favourite photos (`Mobile::gallery_marks`).
- **Select, favourite, paste, share, delete**: press and hold a photo to select (tap more, or a
  day's Select). The bar then favourites (`photo.flag`), pastes copied edits onto the selection
  (`develop.paste`), shares the originals, or **deletes them from the phone**: Android's own
  confirmation (`MediaStore.createDeleteRequest`; on Android 10 the app confirms, then the system
  asks per file it doesn't own). Photos really gone are dropped from the catalog with their edits
  (`Mobile::forget_paths`). The editor's ⋮ menu deletes the open photo the same way.
- **Editor** (phone-editor layout): the photo edge to edge (pinch or double-tap to zoom, press and
  hold for the original, Compare for a draggable before/after split); back, histogram, undo, redo,
  compare, ⋮ (info and rating, copy / paste edits, reset, delete) and **Export** over it. At the
  bottom, the open tool's panel, a tool row, and six tabs: **Quick** (Auto, Subject pop, Blue
  sky, B&W, Remove BG, Reset), **Presets** (live thumbnails, `Mobile::preset_thumbnail`), **Crop**
  (aspect ratios, **Auto straighten** `crop.autoStraighten`, **Upright** Auto / Level / Vertical
  `geometry.upright`, rotate, flip, straighten), **Edit** (Auto | Light + Profile + HDR, Color,
  Curve, Effects, Detail, Optics, Geometry), **Masking** and **Remove**.
- **Dials, not line sliders** (`ui/Dial.kt`): every value is a rotary dial (a 270° arc, the
  change from the default in amber, the value in the middle) in a scrolling grid. Drag sideways to
  turn it (relative to the finger), it clicks into the default with a haptic tick, double-tap
  resets, a tap opens a **fine ruler** above the grid for exact values; one drag is one undo step.
  The dials come from the engine's control list (`develop.controls`), grouped by id prefix in
  `Tools.kt`, so new engine controls appear without app changes.
- **Histogram** (`Mobile::histogram`, `ui/HistogramView.kt`): luminance and R/G/B, the share of
  clipped shadows and highlights, and **tonal zones**: drag sideways on Blacks, Shadows, Exposure,
  Highlights or Whites to move that control.
- **Tone curve**: Point (`ui/CurveEditor.kt`: RGB, Red, Green, Blue; tap to add, drag, drag off
  to remove; `develop.curve`) and Parametric (the engine's `curve.*` controls as dials).
- **Masks** (Masking tab): Subject and Background from ML Kit Subject Segmentation (`Segmenter.kt`,
  on-device) as engine segmentation masks (`Mobile::add_seg_mask`); Sky (the engine's); **Brush,
  Dodge and Burn** painted on the photo (`mask.brushStroke`; size, feather, flow, paint / erase;
  Dodge and Burn start at ±⅓ EV); Linear and Radial gradients. Each mask has local dials, invert
  and delete. Screen points map to the uncropped photo through `Mobile::view_to_image`.
- **AI Object and Describe masks (SAM 3)**: tap an object (Add / Remove parts; `mask.add` kind
  `object`, `mask.objectPoint`) or type what to select (`sky`, `the red car`, `car, road`; kind
  `prompt`). The engine's SAM 3 runs on the phone's CPU (`lightcraft-mobile` feature `sam`). The
  model is **not** shipped: it is an optional ~3.4 GB download (Meta's SAM License, not
  LightCraft's) the app offers, with size and licence, only on phones with 6 GB of memory or more;
  progress and cancel in the panel (`segment.model.download` / `status` / `cancel`). It lives in
  the app's private storage. Without it every other feature works.
- **Remove**: one-tap background removal (ML Kit matte) and a Magic Eraser (tap to erase similar
  colour; tolerance in CIELAB ΔE, contiguous, sample size, anti-alias, feather, opacity), with undo,
  reset and transparent PNG export with edge decontamination (`lightcraft-bgremove`).
- **Export**: a sheet with format (JPEG, PNG, WebP, AVIF), quality and size (full, 4096, 2048,
  1080 px long edge) through the engine's one exporter; **Save to gallery** writes
  `Pictures/LightCraft` through MediaStore, **Share** hands the file to the share sheet.
- **Info**: file, dimensions, format and size, capture time, camera, lens, exposure and place
  (`photo.inspect`), a 0–5 star rating (`photo.rate`) and favourite.

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
`./gradlew :app:recordPaparazziRelease -x cargoNdk` renders the editor chrome (dials, histogram,
tool rows) on the JVM, no device or NDK needed, into `app/src/test/snapshots/` (gitignored) for a
quick look at layout changes; `./gradlew :app:testReleaseUnitTest -x cargoNdk` runs the JVM tests.

Licences: the Compose, AndroidX (incl. core-splashscreen) and Material icon libraries are
Apache-2.0 dependencies fetched by Gradle, as is ML Kit subject segmentation (Google Play
services); the optional SAM 3 model is Meta's, under its SAM License, downloaded only when the
person agrees; the launcher icon is original work (`assets/ATTRIBUTION.md`).
