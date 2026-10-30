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
  before Android 13; a "No photo access" page with **Change permissions**, which opens Settings when
  it was denied for good) the grid shows every image MediaStore has under **Photos on device ▾**
  (pick a device folder), grouped by day with counts (tap a day to fold it), with file-kind filter
  and newest/oldest sort. A bottom bar switches between **Gallery**, **Albums** (device folders) and
  **Edited** (photos changed in LightCraft, kept across launches). The ☰ drawer has What's New,
  About, Local storage (sizes, clear cache) and Help. Opening a photo imports it in place
  (`Mobile::photo_for_path`): the original stays where it is and the edits live in the app's
  catalog. Pinch or the ⋮ menu changes density.
- **Editor** (phone-editor layout): a black top bar (back; undo, redo, before/after, share and a ⋮
  menu with Save copy to device, Create Preset, Copy / Paste Settings, Apply from previous, Reset
  all edits), the photo (pinch or double-tap to zoom, press and hold for the original), and at the
  bottom the open tool's panel, a tool row and a six-tab bar (icon over label, the current one on a
  blue square): **Actions** (Auto, Pop, Sky, B&W, Remove BG, Reset), **Presets** (Recommended /
  Favorites / Yours tabs, one row per group that opens into live thumbnails rendered with
  `Mobile::preset_thumbnail`; long-press to favourite or delete), **Crop** (drag the frame's
  corners, edges or middle, rotate, flip, a straighten dial, then Aspect ratio tiles and Geometry),
  **Edit** (Auto | Light, Color, Curve,
  Effects, Detail, Optics), **Masking** and **Remove**. Every slider comes from the engine's control
  list (`develop.controls`), grouped by id prefix in `Tools.kt`. Sliders scrub relative to the
  finger, snap to their default with a haptic tick, reset on double-tap; one drag is one undo step.
- **Point tone curve** (`ui/CurveEditor.kt`): RGB, Red, Green and Blue; tap to add a point, drag to
  move it, drag an inner point off the grid to remove it (`develop.curve`).
- **Crop by hand**: in the Crop tab the preview shows the *whole* photo
  (`Mobile::render_uncropped`), dimmed outside the crop, so a handle can be dragged back outwards.
  A drag on a corner, an edge or the middle goes to `crop.drag`, which owns the anchoring, the
  aspect lock and the image bounds; the frame is drawn from `develop.get` → `crop.geometry`, so it
  always shows the crop the engine actually has. Pinch-zoom, double-tap-zoom and compare-hold are
  off while the tool is open, because each used to swallow the drag.
- **AI masks** (Masking tab): Subject and Background come from ML Kit Subject Segmentation
  (`Segmenter.kt`, on-device; Play services downloads the model on first use) and enter the engine
  as segmentation masks (`Mobile::add_seg_mask` → `mask.add`); Sky is the engine's own sky mask;
  Linear and Radial gradients too. Each mask has local sliders, invert and delete.
- **Refining any mask**: **Add** and **Erase** are selection operations, as the desktop's ＋/− are —
  each paints positively into a brush component whose op adds to or subtracts from the mask
  (`mask.addComponent`), so an AI subject, the sky or a gradient can all be brushed away. (The
  brush's own `erase` flag only rubs paint out of paint, which is why it could never touch an AI
  selection.) **Sample color** appears only on a mask that has a colour range to put a sample in.
- **Remove**: one-tap background removal (ML Kit matte) and a Magic Eraser (tap to erase similar
  colour; tolerance in CIELAB ΔE, contiguous, sample size, anti-alias, feather, opacity), with undo,
  reset and transparent PNG export with edge decontamination. The pixel work is the pure-Rust
  `lightcraft-bgremove` crate.
- **Remove a selection**: paint over something, or tap it (Smart select); the selection shows in
  red and grows a little before it is filled, so outlines and soft shadows go too. Tap tools aim
  with a loupe: press, slide, lift.
- **AI models (optional downloads, run on the phone with ONNX Runtime)**, chosen by testing the
  candidates on a real photo (removing a person, against a hand-made mask) on the desktop first:
  - *Smart select*: SAM 2.1 tiny (Meta, Apache-2.0; the onnx-community Transformers.js export,
    fp16 encoder + decoder, 88 MB). One tap gives three candidate masks; the most confident (the
    largest when close) is the whole object: IoU 0.97 with the reference, where MobileSAM's
    single-mask decoder gave 0.41. Without it, Tap object uses ML Kit (people and pets).
  - *Generative fill*: big-LaMa (Samsung AI Center, Apache-2.0; Carve's fp32 ONNX, 208 MB). It
    rebuilt wall, ledge and sky where LaMa-Dilated left a ghost and MI-GAN smeared. The edit is a
    `generative` spot, as non-destructive as any other: the Rust pipeline paints it at render time
    through `Mobile::set_painter`, which calls Kotlin's `LamaPainter.paint` over JNI. The box is
    squared, brought to 512 px and the hole **blanked** (shown it, LaMa paints a ghost of it back)
    in `crates/mobile/src/paint.rs`; one spot at preview, loupe and export size runs the model
    once. Without the model, Generative spots render as content-aware fill.

  Every file is checked against its SHA-256 as it downloads; the INTERNET permission is for these
  downloads only.
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
