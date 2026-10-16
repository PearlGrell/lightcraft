//! LightCraft for phones: a thread-safe handle over the engine for the native mobile UIs.
//!
//! The Android app (`apps/lightcraft-android`) is a Kotlin / Jetpack Compose UI. It talks to the
//! engine only through [`Mobile`]: every edit is an engine command by id with JSON parameters
//! ([`Mobile::execute`], the same commands the desktop UI, CLI and MCP use), and pixels come back as
//! straight sRGB RGBA ([`Mobile::render`], [`Mobile::thumbnail`]). The JNI glue lives in the app's
//! own crate; this crate is safe Rust and runs (and is tested) on any host.
//!
//! Renders are built under the session lock and run outside it, so a slider drag on the UI thread
//! never waits for the render of the previous position.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::sync::{Mutex, MutexGuard, PoisonError};

use lightcraft_engine::catalog::{PhotoId, Source};
use lightcraft_engine::export::{ExportOptions, export_photo};
use lightcraft_engine::{EngineError, RenderJob, Session};
use serde_json::{Value, json};

pub use lightcraft_bgremove::EraseOptions;

/// The largest edge a render may ask for (a phone screen is ≤ 4K; exports go through `export.*`).
pub const MAX_EDGE: usize = 8192;

/// A rendered image: `width × height` straight-alpha sRGB pixels, 4 bytes each (RGBA).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

/// One engine session shared by every screen of the app.
pub struct Mobile {
    session: Mutex<Session>,
    cutout: Mutex<Option<Cutout>>,
}

/// The background-removal workspace: the edited photo rendered once, and its alpha matte with the
/// previous mattes for undo. The photo itself is never changed.
struct Cutout {
    frame: Frame,
    alpha: Vec<u8>,
    history: Vec<Vec<u8>>,
}

/// How many matte steps undo can go back.
const CUTOUT_UNDO: usize = 20;

impl Cutout {
    fn push(&mut self) {
        if self.history.len() >= CUTOUT_UNDO {
            self.history.remove(0);
        }
        self.history.push(self.alpha.clone());
    }

    fn image(&self) -> lightcraft_bgremove::Image<'_> {
        lightcraft_bgremove::Image { width: self.frame.width as usize, height: self.frame.height as usize, rgba: &self.frame.rgba }
    }

    /// The photo with the matte as its alpha.
    fn composed(&self, refine: bool) -> Result<Frame, String> {
        let mut rgba = self.frame.rgba.clone();
        if refine {
            let (w, h) = (self.frame.width as usize, self.frame.height as usize);
            lightcraft_bgremove::decontaminate(w, h, &mut rgba, &self.alpha, 2).map_err(|e| e.to_string())?;
        }
        for (px, a) in rgba.chunks_exact_mut(4).zip(&self.alpha) {
            if let Some(p) = px.get_mut(3) {
                *p = ((u16::from(*p) * u16::from(*a)) / 255) as u8;
            }
        }
        Ok(Frame { width: self.frame.width, height: self.frame.height, rgba })
    }
}

impl Default for Mobile {
    fn default() -> Self {
        Self::new()
    }
}

impl Mobile {
    /// An empty in-memory session (open a library with [`Mobile::open_library`]).
    pub fn new() -> Mobile {
        // `with_fs` installs the file decoders (JPEG, PNG, HEIC, raws…): without them every
        // imported photo fails to render with "no decoder available"
        Mobile { session: Mutex::new(Session::new().with_fs()), cutout: Mutex::new(None) }
    }

    /// A session with the procedural demo library (first run, screenshots, tests).
    pub fn demo() -> Mobile {
        Mobile { session: Mutex::new(Session::with_demo().with_fs()), cutout: Mutex::new(None) }
    }

    fn lock(&self) -> MutexGuard<'_, Session> {
        // a command that panicked was already turned into an error by the engine's guard
        self.session.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Open (or create) the library in `dir`, the app's private files directory. A new library
    /// starts with the demo photos when `seed_demo`. Returns the load report as JSON.
    pub fn open_library(&self, dir: &str, seed_demo: bool) -> Result<String, String> {
        let mut s = self.lock();
        let report = s.open_library(dir, seed_demo).map_err(|e| e.to_string())?;
        Ok(format!("{report:?}"))
    }

    /// Run engine command `id` with `params` (a JSON object, or empty for none). Never fails: the
    /// answer is `{"ok":true,"result":…}` or `{"ok":false,"kind":…,"error":…}`.
    pub fn execute(&self, id: &str, params: &str) -> String {
        let params = match params.trim() {
            "" => Value::Null,
            p => match serde_json::from_str(p) {
                Ok(v) => v,
                Err(e) => return failure("BadParams", &format!("parameters are not JSON: {e}")),
            },
        };
        match self.lock().execute(id, &params) {
            Ok(result) => json!({"ok": true, "result": result}).to_string(),
            Err(e) => engine_failure(&e),
        }
    }

    /// The library photo for the file at `path` (a gallery image), importing it in place the first
    /// time: the phone's gallery is the library, the engine keeps the edits.
    pub fn photo_for_path(&self, path: &str) -> Result<u64, String> {
        let find = |s: &Session| s.catalog.photos().find(|p| matches!(&p.source, Source::File { path: q } if q == path)).map(|p| p.id.0);
        if let Some(id) = find(&self.lock()) {
            return Ok(id);
        }
        let mut s = self.lock();
        s.execute("library.import", &json!({"paths": [path], "mode": "add"})).map_err(|e| e.to_string())?;
        find(&s).ok_or_else(|| format!("{path} could not be imported"))
    }

    /// The ids of the photos the library view shows, in its order.
    pub fn photos(&self) -> Vec<u64> {
        self.lock().visible_cloned().into_iter().map(|p| p.0).collect()
    }

    /// Render `photo` with its edits to fit `max_w × max_h` (the unedited look when `before`).
    /// `draft` renders faster at slightly lower quality, for frames during a slider drag.
    pub fn render(&self, photo: u64, max_w: u32, max_h: u32, before: bool, draft: bool) -> Result<Frame, String> {
        let (w, h) = (clamp_edge(max_w)?, clamp_edge(max_h)?);
        let job = {
            let mut s = self.lock();
            let job = if before { s.render_job(PhotoId(photo), w, h, true, true) } else { s.loupe_job(PhotoId(photo), w, h, true) };
            job.ok_or_else(|| format!("no photo {photo}"))?
        };
        run(if draft { job.draft() } else { job })
    }

    /// A grid thumbnail of `photo` whose long edge is at least `long_edge`.
    pub fn thumbnail(&self, photo: u64, long_edge: u32) -> Result<Frame, String> {
        let edge = clamp_edge(long_edge)?;
        let job = self.lock().thumb_job(PhotoId(photo), edge).ok_or_else(|| format!("no photo {photo}"))?;
        run(job)
    }

    /// Export `photo` with its edits through the engine's one encoder (`options` are the export
    /// dialog's JSON: `format`, `quality`, `width`…) and write it into the directory `dir`. Returns the
    /// path written, for the share sheet.
    pub fn export(&self, photo: u64, options: &str, dir: &str) -> Result<String, String> {
        let options: Value = match options.trim() {
            "" => json!({}),
            o => serde_json::from_str(o).map_err(|e| format!("export options are not JSON: {e}"))?,
        };
        let o = ExportOptions::from_json(&options);
        let out = export_photo(&mut self.lock(), PhotoId(photo), &o, 1)?;
        // the engine names the file; keep only its last component so it stays inside `dir`
        let name = std::path::Path::new(&out.file_name).file_name().ok_or_else(|| format!("bad export name {:?}", out.file_name))?;
        let path = std::path::Path::new(dir).join(name);
        std::fs::create_dir_all(dir).map_err(|e| format!("{dir}: {e}"))?;
        std::fs::write(&path, &out.bytes).map_err(|e| format!("{}: {e}", path.display()))?;
        Ok(path.to_string_lossy().into_owned())
    }
}

impl Mobile {
    /// A mask on the active photo from a segmenter's output (the phone's on-device subject
    /// segmentation): `probs` are `side × side` foreground probabilities over the uncropped photo.
    /// `invert` keeps the rest instead (Background). Returns the engine's answer, as [`Mobile::execute`].
    pub fn add_seg_mask(&self, name: &str, side: u32, probs: &[f32], invert: bool) -> String {
        let side = side as usize;
        if side == 0 || side > 1024 || side.checked_mul(side) != Some(probs.len()) {
            return failure("BadParams", &format!("a {side}×{side} mask needs {} values, got {}", side.saturating_mul(side), probs.len()));
        }
        let logits: Vec<f32> = probs
            .iter()
            .map(|p| {
                let p = if p.is_finite() { p.clamp(1e-4, 1.0 - 1e-4) } else { 1e-4 };
                let l = (p / (1.0 - p)).ln();
                if invert { -l } else { l }
            })
            .collect();
        let seg = lightcraft_engine::develop::SegMask::from_logits(side, &logits);
        let seg = match serde_json::to_value(seg) {
            Ok(v) => v,
            Err(e) => return failure("Other", &e.to_string()),
        };
        let params = json!({"kind": "prompt", "text": name, "name": name, "seg": seg});
        match self.lock().execute("mask.add", &params) {
            Ok(result) => json!({"ok": true, "result": result}).to_string(),
            Err(e) => engine_failure(&e),
        }
    }

    fn cutout(&self) -> MutexGuard<'_, Option<Cutout>> {
        self.cutout.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Start removing the background of `photo`: renders it with its edits at `long_edge` and starts
    /// with everything kept. Returns the size.
    pub fn cutout_begin(&self, photo: u64, long_edge: u32) -> Result<(u32, u32), String> {
        let frame = self.render(photo, long_edge, long_edge, false, false)?;
        let n = (frame.width as usize).saturating_mul(frame.height as usize);
        let size = (frame.width, frame.height);
        *self.cutout() = Some(Cutout { frame, alpha: vec![255; n], history: Vec::new() });
        Ok(size)
    }

    fn with_cutout<T>(&self, f: impl FnOnce(&mut Cutout) -> Result<T, String>) -> Result<T, String> {
        let mut c = self.cutout();
        f(c.as_mut().ok_or("no background removal in progress")?)
    }

    /// Replace the matte with a segmenter's `side × side` foreground probabilities (`invert`: keep the
    /// background).
    pub fn cutout_matte(&self, side: u32, probs: &[f32], invert: bool) -> Result<(), String> {
        self.with_cutout(|c| {
            let mut next = vec![0u8; c.alpha.len()];
            lightcraft_bgremove::matte(c.frame.width as usize, c.frame.height as usize, side as usize, probs, invert, &mut next)
                .map_err(|e| e.to_string())?;
            c.push();
            c.alpha = next;
            Ok(())
        })
    }

    /// Magic eraser at the normalized point `(x, y)` of the photo. Returns how many pixels changed.
    pub fn cutout_erase(&self, x: f32, y: f32, o: &lightcraft_bgremove::EraseOptions) -> Result<usize, String> {
        self.with_cutout(|c| {
            if !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
                return Err("the point is outside the photo".into());
            }
            let px = ((x * c.frame.width as f32) as usize).min(c.frame.width.saturating_sub(1) as usize);
            let py = ((y * c.frame.height as f32) as usize).min(c.frame.height.saturating_sub(1) as usize);
            let mut next = c.alpha.clone();
            let n = lightcraft_bgremove::magic_erase(c.image(), &mut next, px, py, o).map_err(|e| e.to_string())?;
            if n > 0 {
                c.push();
                c.alpha = next;
            }
            Ok(n)
        })
    }

    /// Undo the last matte step; `false` when there is none.
    pub fn cutout_undo(&self) -> Result<bool, String> {
        self.with_cutout(|c| {
            Ok(match c.history.pop() {
                Some(a) => {
                    c.alpha = a;
                    true
                }
                None => false,
            })
        })
    }

    /// Back to everything kept (undoable).
    pub fn cutout_reset(&self) -> Result<(), String> {
        self.with_cutout(|c| {
            c.push();
            c.alpha.iter_mut().for_each(|a| *a = 255);
            Ok(())
        })
    }

    /// The photo with the current matte as alpha, for the on-screen preview.
    pub fn cutout_frame(&self) -> Result<Frame, String> {
        self.with_cutout(|c| c.composed(false))
    }

    /// Write the cut-out photo as a transparent PNG into `dir` (edges decontaminated when `refine`).
    pub fn cutout_export(&self, dir: &str, name: &str, refine: bool) -> Result<String, String> {
        let frame = self.with_cutout(|c| c.composed(refine))?;
        let img = lightcraft_codecs::encode::EncodeImage::new(frame.width, frame.height, 4, lightcraft_codecs::encode::Samples::U8(&frame.rgba));
        let bytes = lightcraft_codecs::encode::encode_png(&img, &Default::default()).map_err(|e| e.to_string())?;
        let stem: String = name.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_').take(64).collect();
        let stem = if stem.is_empty() { "cutout".to_string() } else { stem };
        std::fs::create_dir_all(dir).map_err(|e| format!("{dir}: {e}"))?;
        let path = std::path::Path::new(dir).join(format!("{stem}.png"));
        std::fs::write(&path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
        Ok(path.to_string_lossy().into_owned())
    }

    /// End background removal (frees the rendered photo).
    pub fn cutout_end(&self) {
        *self.cutout() = None;
    }
}

fn clamp_edge(edge: u32) -> Result<usize, String> {
    match usize::try_from(edge) {
        Ok(0) | Err(_) => Err(format!("render size {edge} must be between 1 and {MAX_EDGE}")),
        Ok(e) => Ok(e.min(MAX_EDGE)),
    }
}

fn run(job: RenderJob) -> Result<Frame, String> {
    let image = job.run().rendered?.image;
    let width = u32::try_from(image.width).map_err(|e| e.to_string())?;
    let height = u32::try_from(image.height).map_err(|e| e.to_string())?;
    Ok(Frame { width, height, rgba: image.data.into_iter().flatten().collect() })
}

fn engine_failure(e: &EngineError) -> String {
    failure(&format!("{:?}", e.kind()), &e.to_string())
}

fn failure(kind: &str, msg: &str) -> String {
    json!({"ok": false, "kind": kind, "error": msg}).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(s: &str) -> Value {
        serde_json::from_str(s).unwrap()
    }

    #[test]
    fn demo_library_lists_photos_and_renders_one() {
        let m = Mobile::demo();
        let ids = m.photos();
        assert!(!ids.is_empty());
        let f = m.render(ids[0], 320, 240, false, false).unwrap();
        assert!(f.width <= 320 && f.height <= 240 && (f.width == 320 || f.height == 240));
        assert_eq!(f.rgba.len(), (f.width * f.height * 4) as usize);
    }

    #[test]
    fn an_edit_through_execute_changes_the_render() {
        let m = Mobile::demo();
        let id = m.photos()[0];
        let before = m.render(id, 160, 120, false, false).unwrap();
        let r = parse(&m.execute("develop.set", &json!({"ids": [id], "control": "light.exposure", "value": 1.5}).to_string()));
        assert_eq!(r["ok"], true, "{r}");
        let after = m.render(id, 160, 120, false, false).unwrap();
        let mean = |f: &Frame| f.rgba.iter().map(|v| u64::from(*v)).sum::<u64>() / f.rgba.len() as u64;
        assert!(mean(&after) > mean(&before), "exposure +1.5 should brighten");
        // the before view still shows the unedited look
        let unedited = m.render(id, 160, 120, true, false).unwrap();
        assert_eq!(mean(&unedited), mean(&before));
    }

    #[test]
    fn bad_input_is_an_error_answer_not_a_panic() {
        let m = Mobile::demo();
        assert_eq!(parse(&m.execute("no.such.command", ""))["kind"], "UnknownCommand");
        assert_eq!(parse(&m.execute("develop.set", "{not json"))["kind"], "BadParams");
        assert!(m.render(u64::MAX, 100, 100, false, false).is_err());
        assert!(m.render(m.photos()[0], 0, 100, false, false).is_err());
        assert!(m.thumbnail(m.photos()[0], 0).is_err());
    }

    #[test]
    fn export_writes_a_jpeg_into_the_folder() {
        let m = Mobile::demo();
        let dir = std::env::temp_dir().join(format!("lightcraft-mobile-export-{}", std::process::id()));
        let path = m.export(m.photos()[0], r#"{"format":"jpeg","width":96}"#, dir.to_str().unwrap()).unwrap();
        let bytes = std::fs::read(&path).unwrap();
        assert!(path.starts_with(dir.to_str().unwrap()) && bytes.starts_with(&[0xff, 0xd8]), "{path}");
        assert!(m.export(u64::MAX, "", dir.to_str().unwrap()).is_err());
        assert!(m.export(m.photos()[0], "{bad", dir.to_str().unwrap()).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_imported_jpeg_renders() {
        let dir = std::env::temp_dir().join(format!("lightcraft-mobile-jpeg-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        // a real JPEG on disk: the demo photo exported through the engine's encoder
        let demo = Mobile::demo();
        let jpeg = demo.export(demo.photos()[0], r#"{"format":"jpeg","width":200}"#, dir.join("in").to_str().unwrap()).unwrap();
        let m = Mobile::new();
        m.open_library(dir.join("lib").to_str().unwrap(), false).unwrap();
        let r = parse(&m.execute("library.import", &json!({"paths": [jpeg]}).to_string()));
        assert_eq!(r["ok"], true, "{r}");
        let id = *m.photos().last().unwrap();
        let f = m.render(id, 160, 160, false, false).unwrap();
        assert!(f.width.max(f.height) == 160, "{}x{}", f.width, f.height);
        assert!(m.thumbnail(id, 64).is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_gallery_path_maps_to_one_photo() {
        let dir = std::env::temp_dir().join(format!("lightcraft-mobile-gallery-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let demo = Mobile::demo();
        let jpeg = demo.export(demo.photos()[0], r#"{"format":"jpeg","width":120}"#, dir.join("dcim").to_str().unwrap()).unwrap();
        let m = Mobile::new();
        m.open_library(dir.join("lib").to_str().unwrap(), false).unwrap();
        let id = m.photo_for_path(&jpeg).unwrap();
        assert_eq!(m.photo_for_path(&jpeg).unwrap(), id, "the second call finds the imported photo");
        assert_eq!(m.photos(), vec![id]);
        assert!(m.render(id, 64, 64, false, false).is_ok());
        assert!(m.photo_for_path(dir.join("missing.jpg").to_str().unwrap()).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn segmentation_masks_are_added_and_inverted() {
        let m = Mobile::demo();
        let id = m.photos()[0];
        parse(&m.execute("library.select", &json!({"ids": [id], "active": id}).to_string()));
        let probs: Vec<f32> = (0..64 * 64).map(|i| if (i % 64) < 32 { 0.95 } else { 0.05 }).collect();
        assert_eq!(parse(&m.add_seg_mask("Subject", 64, &probs, false))["ok"], true);
        assert_eq!(parse(&m.add_seg_mask("Background", 64, &probs, true))["ok"], true);
        let d = parse(&m.execute("develop.get", ""));
        assert_eq!(d["result"]["masks"].as_array().unwrap().len(), 2, "{d}");
        assert!(m.render(id, 64, 64, false, false).is_ok());
        assert_eq!(parse(&m.add_seg_mask("x", 64, &probs[..10], false))["kind"], "BadParams");
        assert_eq!(parse(&m.add_seg_mask("x", 0, &[], false))["kind"], "BadParams");
    }

    #[test]
    fn background_removal_round_trip() {
        let m = Mobile::demo();
        let id = m.photos()[0];
        assert!(m.cutout_frame().is_err(), "nothing started");
        let (w, h) = m.cutout_begin(id, 96).unwrap();
        assert_eq!(w.max(h), 96);
        let o = lightcraft_bgremove::EraseOptions { tolerance: 100.0, contiguous: false, ..Default::default() };
        assert!(m.cutout_erase(0.5, 0.5, &o).unwrap() > 0);
        let f = m.cutout_frame().unwrap();
        assert!(f.rgba.chunks_exact(4).any(|p| p[3] < 255));
        assert!(m.cutout_undo().unwrap());
        assert!(m.cutout_frame().unwrap().rgba.chunks_exact(4).all(|p| p[3] == 255));
        assert!(m.cutout_erase(1.5, 0.5, &o).is_err());
        m.cutout_matte(2, &[1.0, 0.0, 1.0, 0.0], false).unwrap();
        assert!(m.cutout_matte(2, &[1.0], false).is_err());
        let dir = std::env::temp_dir().join(format!("lightcraft-mobile-cutout-{}", std::process::id()));
        let path = m.cutout_export(dir.to_str().unwrap(), "../evil name", true).unwrap();
        assert!(path.ends_with("evilname.png"), "{path}");
        let bytes = std::fs::read(&path).unwrap();
        assert!(bytes.starts_with(&[0x89, b'P', b'N', b'G']));
        m.cutout_end();
        assert!(m.cutout_undo().is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn thumbnails_render() {
        let m = Mobile::demo();
        let f = m.thumbnail(m.photos()[0], 200).unwrap();
        assert!(f.width.max(f.height) >= 200);
    }

    #[test]
    fn a_library_on_disk_reopens_with_its_edits() {
        let dir = std::env::temp_dir().join(format!("lightcraft-mobile-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let id = {
            let m = Mobile::new();
            m.open_library(dir.to_str().unwrap(), true).unwrap();
            let id = m.photos()[0];
            let r = parse(&m.execute("develop.set", &json!({"ids": [id], "control": "light.exposure", "value": 0.7}).to_string()));
            assert_eq!(r["ok"], true, "{r}");
            id
        };
        let m = Mobile::new();
        m.open_library(dir.to_str().unwrap(), false).unwrap();
        let got = parse(&m.execute("develop.get", &json!({"id": id}).to_string()));
        assert_eq!(got["ok"], true, "{got}");
        assert!(got["result"].to_string().contains("0.7"), "{got}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
