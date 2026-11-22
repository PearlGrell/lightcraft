//! The optional on-device AI models (`lightcraft-ai`): listing and downloading them, tap-to-select,
//! object detection and generative remove.
//!
//! **Nothing requires them.** The host says where they live ([`Session::use_ai_models`]); until a
//! model is downloaded its commands answer with a clear "not downloaded" error and everything else
//! works. What the models make is stored as plain data in the develop settings (a selection grid,
//! a spot's generated patch), so renders and exports never run a model.

use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex, PoisonError};

use lightcraft_ai::select::{Selector, Tap};
use lightcraft_ai::{Rgb8, Status, Store, depth, detect, inpaint, models};
use lightcraft_catalog::PhotoId;
use lightcraft_develop::{SegMask, Spot, SpotMode, SpotPatch};
use lightcraft_geom::Point;
use serde_json::{Value, json};

use super::{CommandSpec, always, bad, cmd, has_active};
use crate::{Result, Session};

/// The selection model's input edge, as it stretches every photo to.
const SELECT_EDGE: usize = 1024;
/// The depth grid's side (stored with the settings, sampled at any output size).
const DEPTH_SIDE: usize = 256;
/// The depth model works at a 518 px long side; this render is enough for it.
const DEPTH_EDGE: usize = 1024;
/// Detection works on a 640 × 640 stretch; this render is enough for it.
const DETECT_EDGE: usize = 1024;

/// A model download in progress or finished (`ai.models` reports it).
#[derive(Clone, Debug, Default)]
pub struct DownloadState {
    pub id: String,
    pub done: u64,
    pub total: u64,
    /// running, done, failed or cancelled.
    pub state: String,
    pub error: String,
    pub cancel: Option<Arc<AtomicBool>>,
}

/// The models, the download, and the photo the last selection analysed (kept for the next taps).
#[derive(Default)]
pub struct AiState {
    pub store: Option<Arc<Store>>,
    pub download: Arc<Mutex<DownloadState>>,
    pub(crate) selector: Option<(PhotoId, u64, Arc<Selector>)>,
}

impl Session {
    /// The models live in `dir` (the host's data folder; created when one downloads).
    pub fn use_ai_models(&mut self, dir: impl Into<std::path::PathBuf>) {
        self.ai.store = Some(Arc::new(Store::new(dir)));
        self.ai.selector = None;
    }

    fn ai_store(&self, c: &str) -> Result<Arc<Store>> {
        self.ai.store.clone().ok_or_else(|| bad(c, "this build has no place for AI models"))
    }

    /// Photo `id` rendered as the models see it: developed without masks, uncropped, before lens
    /// corrections and Upright (the frame selections are stored in), long edge `edge`.
    fn ai_picture(&mut self, id: PhotoId, edge: usize, keep_geometry: bool) -> std::result::Result<(u64, Rgb8), String> {
        let (key, mut d) = self.segment_key(id).ok_or("no such photo")?;
        if keep_geometry {
            // spots live in the corrected frame: the picture must be in it too
            if let Some(p) = self.catalog.photo(id) {
                d.geometry = p.develop.geometry.clone();
                d.optics = p.develop.optics;
            }
        }
        let job = self.preview_job(id, edge, edge, false, &d).ok_or("no such photo")?;
        let r = job.run().rendered?;
        let img = &r.image;
        let rgb: Vec<u8> = img.data.iter().flat_map(|p| [p[0], p[1], p[2]]).collect();
        Ok((key, Rgb8::new(img.width, img.height, rgb).map_err(|e| e.to_string())?))
    }
}

fn ai_err(c: &str, e: lightcraft_ai::Error) -> crate::EngineError {
    bad(c, e.to_string())
}

fn status_json(s: &Session) -> Value {
    let store = s.ai.store.clone();
    let dl = s.ai.download.lock().unwrap_or_else(PoisonError::into_inner).clone();
    let list: Vec<Value> = models::MODELS
        .iter()
        .map(|m| {
            let ready = store.as_ref().and_then(|st| st.status(m.id).ok()) == Some(Status::Ready);
            let mut v = json!({
                "id": m.id, "name": m.name, "purpose": m.purpose, "size": m.size(),
                "licence": m.licence, "source": m.source, "ready": ready,
            });
            if dl.id == m.id {
                v["download"] = json!({"state": dl.state, "done": dl.done, "total": dl.total, "error": dl.error});
            }
            v
        })
        .collect();
    json!({"models": list, "available": store.is_some()})
}

/// Taps from `{taps: [[x, y, include?], …]}` or `{points: [[x, y]…], exclude: [[x, y]…]}`.
fn taps_of(p: &Value) -> Vec<Tap> {
    let pt = |q: &Value| Some((q.get(0)?.as_f64()? as f32, q.get(1)?.as_f64()? as f32));
    let mut out = Vec::new();
    if let Some(a) = p.get("taps").and_then(Value::as_array) {
        for t in a {
            if let Some((x, y)) = pt(t) {
                out.push(Tap { x, y, include: t.get(2).and_then(Value::as_bool).unwrap_or(true) });
            }
        }
    }
    for (key, include) in [("points", true), ("exclude", false)] {
        if let Some(a) = p.get(key).and_then(Value::as_array) {
            out.extend(a.iter().filter_map(pt).map(|(x, y)| Tap { x, y, include }));
        }
    }
    out
}

pub fn specs() -> Vec<CommandSpec> {
    vec![
        cmd!(query "ai.models", "AI Models", [], None, "{} → {models: [{id, name, purpose, size, licence, source, ready, download?: {state, done, total, error}}], available}", always, |s, _| Ok(status_json(s))),
        cmd!(
            "ai.download",
            "Download AI Model",
            [],
            None,
            "{id, acknowledged: true} — download model `id` (see ai.models: its licence and size) into the host's model folder in the background, with an activity row; refused without `acknowledged` (pass it only after the user agreed) → {started}",
            always,
            |s, p| {
                let c = "ai.download";
                let id = p.get("id").and_then(Value::as_str).ok_or_else(|| bad(c, "missing id"))?;
                let model = models::find(id).ok_or_else(|| bad(c, format!("no model {id:?}")))?;
                if p.get("acknowledged").and_then(Value::as_bool) != Some(true) {
                    return Err(bad(
                        c,
                        format!("{} is under the {}: download it only after the user agreed (acknowledged: true)", model.name, model.licence),
                    ));
                }
                let store = s.ai_store(c)?;
                let state = s.ai.download.clone();
                {
                    let st = state.lock().unwrap_or_else(PoisonError::into_inner);
                    if st.state == "running" {
                        return Err(bad(c, format!("{} is already downloading", st.id)));
                    }
                }
                #[cfg(not(target_arch = "wasm32"))]
                {
                    let cancel = Arc::new(AtomicBool::new(false));
                    *state.lock().unwrap_or_else(PoisonError::into_inner) = DownloadState {
                        id: id.to_string(),
                        done: 0,
                        total: model.size(),
                        state: "running".into(),
                        error: String::new(),
                        cancel: Some(cancel.clone()),
                    };
                    let guard =
                        s.activity.start("ai.download", &format!("Downloading {}", model.name), crate::activity::Cancel::Flag(cancel.clone()));
                    let model_id = model.id;
                    std::thread::Builder::new()
                        .name("ai-download".into())
                        .spawn(move || {
                            let r = store.download(model_id, &cancel, &mut |done, total| {
                                guard.progress(done, total);
                                let mut st = state.lock().unwrap_or_else(PoisonError::into_inner);
                                st.done = done;
                                st.total = total;
                            });
                            let mut st = state.lock().unwrap_or_else(PoisonError::into_inner);
                            match r {
                                Ok(()) => st.state = "done".into(),
                                Err(e) if cancel.load(std::sync::atomic::Ordering::Relaxed) => {
                                    st.state = "cancelled".into();
                                    st.error = e.to_string();
                                }
                                Err(e) => {
                                    st.state = "failed".into();
                                    st.error = e.to_string();
                                }
                            }
                            st.cancel = None;
                        })
                        .map_err(|e| bad(c, e.to_string()))?;
                    Ok(json!({"started": true}))
                }
                #[cfg(target_arch = "wasm32")]
                {
                    let _ = (store, state);
                    Err(bad(c, "the web build downloads no models"))
                }
            }
        ),
        cmd!("ai.cancelDownload", "Cancel AI Model Download", [], None, "{}", always, |s, _| {
            if let Some(c) = &s.ai.download.lock().unwrap_or_else(PoisonError::into_inner).cancel {
                c.store(true, std::sync::atomic::Ordering::Relaxed);
            }
            Ok(json!({}))
        }),
        cmd!("ai.removeModel", "Delete AI Model", [], None, "{id} — delete a downloaded model's files", always, |s, p| {
            let c = "ai.removeModel";
            let id = p.get("id").and_then(Value::as_str).ok_or_else(|| bad(c, "missing id"))?;
            s.ai.selector = None;
            s.ai_store(c)?.remove(id).map_err(|e| ai_err(c, e))?;
            Ok(json!({}))
        }),
        cmd!(
            "ai.select",
            "Select Object",
            [],
            None,
            "{taps: [[x, y, include?]…] (or points / exclude: [[x, y]…]), normalized photo points; id?: mask to replace the first part of; op?: add|subtract with id — add the selection to that mask as a new part instead (subtract cuts it out); name?} — select the object the taps point at (EfficientSAM-Ti; the photo is analysed once, then each tap is quick) → {mask, score}",
            has_active,
            |s, p| {
                let c = "ai.select";
                let taps = taps_of(p);
                let photo = s.active().ok_or_else(|| bad(c, "no active photo"))?;
                let store = s.ai_store(c)?;
                let key = s.segment_key(photo).map(|k| k.0).ok_or_else(|| bad(c, "no such photo"))?;
                let selector = match &s.ai.selector {
                    Some((ph, k, sel)) if *ph == photo && *k == key => sel.clone(),
                    _ => {
                        let (_, img) = s.ai_picture(photo, SELECT_EDGE, false).map_err(|e| bad(c, e))?;
                        let sel = Arc::new(Selector::encode(&store, &img, None).map_err(|e| ai_err(c, e))?);
                        s.ai.selector = Some((photo, key, sel.clone()));
                        sel
                    }
                };
                let r = selector.select(&store, &taps).map_err(|e| ai_err(c, e))?;
                let seg = serde_json::to_value(SegMask::from_logits(r.side, &r.logits)).map_err(|e| bad(c, e.to_string()))?;
                let pts = |inc: bool| taps.iter().filter(|t| t.include == inc).map(|t| json!([t.x, t.y])).collect::<Vec<_>>();
                let op = p.get("op").and_then(Value::as_str);
                if let Some(op) = op
                    && op != "add"
                    && op != "subtract"
                {
                    return Err(bad(c, format!("op is add or subtract, not {op:?}")));
                }
                let mask = match p.get("id").and_then(Value::as_u64) {
                    // another part of the mask: the object the taps point at, added or cut out
                    Some(mid) if op.is_some() => {
                        s.execute(
                            "mask.addComponent",
                            &json!({"id": mid, "op": op, "kind": "object", "points": pts(true), "exclude": pts(false), "seg": seg}),
                        )?;
                        mid
                    }
                    Some(mid) => {
                        let shape = json!({"kind": "object", "hint": pts(true).iter().map(|v| json!({"x": v[0], "y": v[1]})).collect::<Vec<_>>(),
                                           "exclude": pts(false).iter().map(|v| json!({"x": v[0], "y": v[1]})).collect::<Vec<_>>(), "seg": seg});
                        s.execute("mask.update", &json!({"id": mid, "component": 0, "shape": shape}))?;
                        mid
                    }
                    None => {
                        let name = p.get("name").and_then(Value::as_str).unwrap_or("Object");
                        s.execute("mask.add", &json!({"kind": "object", "points": pts(true), "exclude": pts(false), "seg": seg, "name": name}))?;
                        s.develop_of(photo).and_then(|d| d.masks.last().map(|m| u64::from(m.id))).unwrap_or(0)
                    }
                };
                Ok(json!({"mask": mask, "score": r.score}))
            }
        ),
        cmd!(
            "ai.detect",
            "Find Objects",
            [],
            None,
            "{min?: 0..1 (0.5)} — people and everyday objects in the active photo (D-FINE-N, 80 kinds) → {objects: [{label, score, box: [x0, y0, x1, y1]}]} in normalized photo coordinates (as ai.select takes)",
            has_active,
            |s, p| {
                let c = "ai.detect";
                let photo = s.active().ok_or_else(|| bad(c, "no active photo"))?;
                let store = s.ai_store(c)?;
                let min = p.get("min").and_then(Value::as_f64).unwrap_or(0.5).clamp(0.0, 1.0) as f32;
                let (_, img) = s.ai_picture(photo, DETECT_EDGE, false).map_err(|e| bad(c, e))?;
                let found = detect::detect(&store, &img, min).map_err(|e| ai_err(c, e))?;
                Ok(
                    json!({"objects": found.iter().take(50).map(|d| json!({"label": d.label, "score": d.score, "box": [d.x0, d.y0, d.x1, d.y1]})).collect::<Vec<_>>()}),
                )
            }
        ),
        cmd!(
            "lensBlur.depth",
            "Lens Blur Depth",
            [],
            None,
            "{clear?: bool} — estimate how near each part of the active photo is (Depth Anything V2 Small) and store it with Lens Blur, so the blur grows with depth as through a real lens; clear removes it → {stored}",
            has_active,
            |s, p| {
                let c = "lensBlur.depth";
                let photo = s.active().ok_or_else(|| bad(c, "no active photo"))?;
                if p.get("clear").and_then(Value::as_bool) == Some(true) {
                    s.execute("develop.merge", &json!({"settings": {"lens_blur": {"depth": null}}, "label": "Lens Blur Depth"}))?;
                    return Ok(json!({"stored": false}));
                }
                let store = s.ai_store(c)?;
                let (_, img) = s.ai_picture(photo, DEPTH_EDGE, false).map_err(|e| bad(c, e))?;
                let grid = depth::estimate(&store, &img, DEPTH_SIDE, None).map_err(|e| ai_err(c, e))?;
                // stored as logits: the grid sampler's sigmoid gives the depth back
                let logits: Vec<f32> = grid
                    .iter()
                    .map(|d| {
                        let d = d.clamp(0.02, 0.98);
                        (d / (1.0 - d)).ln()
                    })
                    .collect();
                let seg = serde_json::to_value(SegMask::from_logits(DEPTH_SIDE, &logits)).map_err(|e| bad(c, e.to_string()))?;
                s.execute("develop.merge", &json!({"settings": {"lens_blur": {"depth": seg}}, "label": "Lens Blur Depth"}))?;
                Ok(json!({"stored": true}))
            }
        ),
        cmd!(
            "spot.generate",
            "Generative Remove",
            [],
            None,
            "{points: [[x,y],…] (normalized, as spot.add), size?: fraction of the long edge (0.03), feather?, quality?: fast|best} — remove what the stroke covers and fill it with generated content (fast: MI-GAN 512; best: LaMa, slower, better on large areas); the result is stored with the spot, so renders never run the model → {index}",
            has_active,
            |s, p| {
                let c = "spot.generate";
                let pts: Vec<Point> = p
                    .get("points")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(|q| Some(Point::new(q.get(0)?.as_f64()?, q.get(1)?.as_f64()?))).collect())
                    .unwrap_or_default();
                if pts.is_empty() || pts.len() > 4096 || pts.iter().any(|q| !(q.x.is_finite() && q.y.is_finite())) {
                    return Err(bad(c, "missing or bad points"));
                }
                let size = p.get("size").and_then(Value::as_f64).unwrap_or(0.03).clamp(0.002, 0.25);
                let feather = p.get("feather").and_then(Value::as_f64).unwrap_or(30.0).clamp(0.0, 100.0);
                let photo = s.active().ok_or_else(|| bad(c, "no active photo"))?;
                let store = s.ai_store(c)?;
                let best = p.get("quality").and_then(Value::as_str) == Some("best");
                let (model, name) = if best { (models::INPAINT_BEST, "LaMa") } else { (models::INPAINT, "MI-GAN 512") };
                if store.status(model).ok() != Some(Status::Ready) {
                    return Err(ai_err(c, lightcraft_ai::Error::Missing(name)));
                }
                // the picture in the spot's frame, sharp enough that the area is a few hundred pixels
                let (w0, h0) = s.catalog.photo(photo).map(|ph| (ph.width.max(1) as f64, ph.height.max(1) as f64)).unwrap_or((1.0, 1.0));
                let long = w0.max(h0);
                let (ax, ay) = (long / w0, long / h0);
                let (mut x0, mut y0, mut x1, mut y1) = (f64::MAX, f64::MAX, f64::MIN, f64::MIN);
                for q in &pts {
                    x0 = x0.min(q.x - size * ax);
                    x1 = x1.max(q.x + size * ax);
                    y0 = y0.min(q.y - size * ay);
                    y1 = y1.max(q.y + size * ay);
                }
                // context around the stroke for the model to continue from
                let pad = (x1 - x0).max(y1 - y0).max(2.0 * size) * 0.75;
                let rect = [(x0 - pad).max(0.0), (y0 - pad).max(0.0), (x1 + pad).min(1.0), (y1 + pad).min(1.0)];
                if rect[0] >= rect[2] || rect[1] >= rect[3] {
                    return Err(bad(c, "the stroke is off the photo"));
                }
                let span = (rect[2] - rect[0]).max(rect[3] - rect[1]);
                let edge = ((768.0 / span) as usize).clamp(1024, 4096);
                let (_, pic) = s.ai_picture(photo, edge, true).map_err(|e| bad(c, e))?;
                let (pw, ph) = (pic.width as f64, pic.height as f64);
                let (cx0, cy0) = ((rect[0] * pw).floor() as usize, (rect[1] * ph).floor() as usize);
                let (cx1, cy1) = (((rect[2] * pw).ceil() as usize).min(pic.width), ((rect[3] * ph).ceil() as usize).min(pic.height));
                let (cw, chh) = (cx1.saturating_sub(cx0), cy1.saturating_sub(cy0));
                if cw < 8 || chh < 8 {
                    return Err(bad(c, "the area is too small"));
                }
                let mut crop = Vec::with_capacity(cw * chh * 3);
                for y in cy0..cy1 {
                    crop.extend_from_slice(pic.data.get((y * pic.width + cx0) * 3..(y * pic.width + cx1) * 3).ok_or_else(|| bad(c, "crop"))?);
                }
                let crop = Rgb8::new(cw, chh, crop).map_err(|e| ai_err(c, e))?;
                // the hole: within the stroke's radius (a little beyond, the feather blends it)
                let r_px = size * pw.max(ph) * 1.15;
                let hole: Vec<bool> = (0..cw * chh)
                    .map(|i| {
                        let (x, y) = ((cx0 + i % cw) as f64 + 0.5, (cy0 + i / cw) as f64 + 0.5);
                        pts.iter().any(|q| ((x - q.x * pw).powi(2) + (y - q.y * ph).powi(2)).sqrt() <= r_px)
                    })
                    .collect();
                let filled = if best { inpaint::fill_best(&store, &crop, &hole, None) } else { inpaint::fill(&store, &crop, &hole, None) }
                    .map_err(|e| ai_err(c, e))?;
                // the patch covers exactly the cropped pixels
                let prect = [cx0 as f64 / pw, cy0 as f64 / ph, cx1 as f64 / pw, cy1 as f64 / ph];
                let patch = SpotPatch::encode(prect, cw as u32, chh as u32, &filled.data).ok_or_else(|| bad(c, "the generated area is too large"))?;
                let mut dd = (*s.develop_of(photo).unwrap_or_default()).clone();
                dd.spots.push(Spot { mode: SpotMode::Fill, points: pts, size, feather, opacity: 100.0, source_offset: None, patch: Some(patch) });
                let n = dd.spots.len();
                s.set_develop(photo, dd, "Generative Remove")?;
                s.active_spot = Some(n - 1);
                Ok(json!({"index": n - 1}))
            }
        ),
    ]
}
