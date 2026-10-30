//! Generative fill painted by the front end's inpainting model (the phone runs LaMa with ONNX
//! Runtime), as the pipeline's [`GenerativeFill`] for Generative spots.
//!
//! Everything a model needs that is not the model is done here, in one tested place: the box is
//! padded to a square (its edge colours repeated), brought to the model's [`SIDE`] × [`SIDE`],
//! converted from scene-linear light to 8-bit sRGB, and the hole is **blanked** so the network
//! never sees what it removes (shown it, LaMa paints a ghost of it back). The model's answer is
//! brought back to the box and only the hole takes it. Results are kept, and a render of the same
//! spot at another size (draft, loupe, export) reuses the closest one instead of running the model
//! again: it works at [`SIDE`] whatever the render size.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, PoisonError};

use lightcraft_engine::pipeline::spots::GenerativeFill;

/// The side of the square the model paints.
pub const SIDE: usize = 512;

/// Results kept (a few spots, each at preview, loupe and export size).
const KEPT: usize = 8;

/// Cells along each side of a result's signature.
const SIG: usize = 32;

/// An inpainting model: `rgb` is [`SIDE`]² 8-bit sRGB (interleaved, the hole black), `hole` is
/// [`SIDE`]² bytes (1 = paint in); the answer is the painted [`SIDE`]² 8-bit sRGB.
pub trait Painter: Send + Sync {
    fn paint(&self, rgb: &[u8], hole: &[u8]) -> Result<Vec<u8>, String>;
}

/// A result kept: what it was asked (a small signature of the picture and the hole) and the answer.
struct Kept {
    colour: Vec<u8>,
    hole: Vec<bool>,
    painted: Arc<Vec<u8>>,
}

pub(crate) struct PainterFill {
    painter: Box<dyn Painter>,
    kept: Mutex<VecDeque<Kept>>,
}

impl PainterFill {
    pub(crate) fn new(painter: Box<dyn Painter>) -> PainterFill {
        PainterFill { painter, kept: Mutex::new(VecDeque::new()) }
    }
}

pub(crate) fn to_srgb8(v: f32) -> u8 {
    let v = if v.is_finite() { v.clamp(0.0, 1.0) } else { 0.0 };
    let e = if v <= 0.003_130_8 { v * 12.92 } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 };
    (e * 255.0).round().clamp(0.0, 255.0) as u8
}

pub(crate) fn from_srgb8(v: u8) -> f32 {
    let e = f32::from(v) / 255.0;
    if e <= 0.040_45 { e / 12.92 } else { ((e + 0.055) / 1.055).powf(2.4) }
}

/// Bilinear resize of interleaved 8-bit RGB.
pub(crate) fn resize_rgb(src: &[u8], sw: usize, sh: usize, dw: usize, dh: usize) -> Vec<u8> {
    let mut out = vec![0u8; dw.saturating_mul(dh).saturating_mul(3)];
    if sw == 0 || sh == 0 || src.len() < sw * sh * 3 {
        return out;
    }
    let at = |x: usize, y: usize, c: usize| f32::from(src.get((y.min(sh - 1) * sw + x.min(sw - 1)) * 3 + c).copied().unwrap_or(0));
    for y in 0..dh {
        let fy = ((y as f32 + 0.5) * sh as f32 / dh as f32 - 0.5).max(0.0);
        let (y0, ty) = (fy.floor() as usize, fy.fract());
        for x in 0..dw {
            let fx = ((x as f32 + 0.5) * sw as f32 / dw as f32 - 0.5).max(0.0);
            let (x0, tx) = (fx.floor() as usize, fx.fract());
            for c in 0..3 {
                let top = at(x0, y0, c) * (1.0 - tx) + at(x0 + 1, y0, c) * tx;
                let bottom = at(x0, y0 + 1, c) * (1.0 - tx) + at(x0 + 1, y0 + 1, c) * tx;
                if let Some(d) = out.get_mut((y * dw + x) * 3 + c) {
                    *d = (top * (1.0 - ty) + bottom * ty).round().clamp(0.0, 255.0) as u8;
                }
            }
        }
    }
    out
}

/// The hole at another size: a cell is hole when any source pixel it covers is (it never shrinks).
pub(crate) fn resize_hole(src: &[bool], sw: usize, sh: usize, dw: usize, dh: usize) -> Vec<bool> {
    let mut out = vec![false; dw.saturating_mul(dh)];
    if sw == 0 || sh == 0 {
        return out;
    }
    for (i, h) in src.iter().enumerate() {
        if !*h {
            continue;
        }
        let (x, y) = (i % sw, i / sw);
        let (x0, x1) = (x * dw / sw, ((x + 1) * dw).div_ceil(sw).min(dw));
        let (y0, y1) = (y * dh / sh, ((y + 1) * dh).div_ceil(sh).min(dh));
        for yy in y0..y1.max(y0 + 1).min(dh) {
            for xx in x0..x1.max(x0 + 1).min(dw) {
                if let Some(d) = out.get_mut(yy * dw + xx) {
                    *d = true;
                }
            }
        }
    }
    out
}

/// A small summary of what the model is asked: mean colour and hole of each of [`SIG`]² cells.
fn signature(rgb: &[u8], hole: &[bool]) -> (Vec<u8>, Vec<bool>) {
    let cell = SIDE / SIG;
    let mut colour = vec![0u8; SIG * SIG * 3];
    let mut holes = vec![false; SIG * SIG];
    for cy in 0..SIG {
        for cx in 0..SIG {
            let (mut sum, mut n, mut any) = ([0u32; 3], 0u32, false);
            for y in cy * cell..(cy + 1) * cell {
                for x in cx * cell..(cx + 1) * cell {
                    let i = y * SIDE + x;
                    any |= hole.get(i).copied().unwrap_or(false);
                    for (c, s) in sum.iter_mut().enumerate() {
                        *s += u32::from(rgb.get(i * 3 + c).copied().unwrap_or(0));
                    }
                    n += 1;
                }
            }
            for (c, s) in sum.iter().enumerate() {
                if let Some(d) = colour.get_mut((cy * SIG + cx) * 3 + c) {
                    *d = (s / n.max(1)).min(255) as u8;
                }
            }
            if let Some(d) = holes.get_mut(cy * SIG + cx) {
                *d = any;
            }
        }
    }
    (colour, holes)
}

/// Whether a kept result answers the same question: nearly the same hole cells and colours (a
/// draft render and an export of one spot differ a little, a different spot or photo a lot).
fn same(k: &Kept, colour: &[u8], hole: &[bool]) -> bool {
    let differ = k.hole.iter().zip(hole).filter(|(a, b)| a != b).count();
    if differ * 50 > hole.len() {
        return false;
    }
    let diff: u64 = k.colour.iter().zip(colour).map(|(a, b)| u64::from(a.abs_diff(*b))).sum();
    diff <= colour.len() as u64 * 6
}

impl GenerativeFill for PainterFill {
    fn fill(&self, rgb: &[[f32; 3]], hole: &[bool], w: usize, h: usize) -> Option<Vec<[f32; 3]>> {
        let n = w.checked_mul(h)?;
        if n == 0 || rgb.len() != n || hole.len() != n || !hole.iter().any(|x| *x) {
            return None;
        }
        // the box on a square canvas, its edges repeated, so the model sees it unsquashed
        let side = w.max(h);
        let mut square = vec![0u8; side * side * 3];
        let mut square_hole = vec![false; side * side];
        for y in 0..side {
            for x in 0..side {
                let i = y.min(h - 1) * w + x.min(w - 1);
                let p = rgb.get(i).copied().unwrap_or_default();
                for (c, v) in p.iter().enumerate() {
                    if let Some(d) = square.get_mut((y * side + x) * 3 + c) {
                        *d = to_srgb8(*v);
                    }
                }
                if x < w && y < h {
                    if let Some(d) = square_hole.get_mut(y * side + x) {
                        *d = hole.get(i).copied().unwrap_or(false);
                    }
                }
            }
        }
        let mut model_rgb = resize_rgb(&square, side, side, SIDE, SIDE);
        let model_hole = resize_hole(&square_hole, side, side, SIDE, SIDE);
        // the hole blanked out: the model must not see what it removes
        for (px, h) in model_rgb.chunks_exact_mut(3).zip(&model_hole) {
            if *h {
                px.fill(0);
            }
        }
        let (colour, holes) = signature(&model_rgb, &model_hole);
        let found = self.kept.lock().unwrap_or_else(PoisonError::into_inner).iter().find(|k| same(k, &colour, &holes)).map(|k| k.painted.clone());
        let painted = match found {
            Some(p) => p,
            None => {
                let mask: Vec<u8> = model_hole.iter().map(|h| u8::from(*h)).collect();
                let out = match self.painter.paint(&model_rgb, &mask) {
                    Ok(out) if out.len() == SIDE * SIDE * 3 => Arc::new(out),
                    Ok(out) => {
                        log::warn!("generative fill: the model gave {} values, not {}", out.len(), SIDE * SIDE * 3);
                        return None;
                    }
                    Err(e) => {
                        log::warn!("generative fill: {e}");
                        return None;
                    }
                };
                let mut kept = self.kept.lock().unwrap_or_else(PoisonError::into_inner);
                if kept.len() >= KEPT {
                    kept.pop_front();
                }
                kept.push_back(Kept { colour, hole: holes, painted: out.clone() });
                out
            }
        };
        let back = resize_rgb(&painted, SIDE, SIDE, side, side);
        Some(
            (0..n)
                .map(|i| {
                    let (x, y) = (i % w, i / w);
                    let orig = rgb.get(i).copied().unwrap_or_default();
                    if !hole.get(i).copied().unwrap_or(false) {
                        return orig;
                    }
                    let j = (y * side + x) * 3;
                    match back.get(j..j + 3) {
                        Some(p) => [from_srgb8(p[0]), from_srgb8(p[1]), from_srgb8(p[2])],
                        None => orig,
                    }
                })
                .collect(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Paints the hole one colour, counts its calls, and checks the hole reaches it blanked.
    struct Flat {
        calls: Arc<AtomicUsize>,
        colour: [u8; 3],
    }

    impl Painter for Flat {
        fn paint(&self, rgb: &[u8], hole: &[u8]) -> Result<Vec<u8>, String> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            assert_eq!((rgb.len(), hole.len()), (SIDE * SIDE * 3, SIDE * SIDE));
            assert!(rgb.chunks_exact(3).zip(hole).all(|(p, h)| *h == 0 || p == [0, 0, 0]), "the hole reaches the model blanked");
            Ok(rgb.chunks_exact(3).zip(hole).flat_map(|(p, h)| if *h == 1 { self.colour } else { [p[0], p[1], p[2]] }).collect())
        }
    }

    fn scene(w: usize, h: usize) -> (Vec<[f32; 3]>, Vec<bool>) {
        let mut rgb = vec![[0.2f32, 0.3, 0.6]; w * h];
        let mut hole = vec![false; w * h];
        for y in h / 3..h * 2 / 3 {
            for x in w / 3..w * 2 / 3 {
                rgb[y * w + x] = [0.9, 0.1, 0.1];
                hole[y * w + x] = true;
            }
        }
        (rgb, hole)
    }

    #[test]
    fn the_hole_is_painted_and_the_rest_kept() {
        let calls = Arc::new(AtomicUsize::new(0));
        let fill = PainterFill::new(Box::new(Flat { calls: calls.clone(), colour: [0, 255, 0] }));
        let (rgb, hole) = scene(300, 200);
        let out = fill.fill(&rgb, &hole, 300, 200).unwrap();
        let mid = out[100 * 300 + 150];
        assert!(mid[1] > 0.9 && mid[0] < 0.05, "the hole takes the model's colour: {mid:?}");
        assert_eq!(out[5 * 300 + 5], rgb[5 * 300 + 5], "outside the hole nothing changes");
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn the_same_spot_at_another_size_reuses_the_result() {
        let calls = Arc::new(AtomicUsize::new(0));
        let fill = PainterFill::new(Box::new(Flat { calls: calls.clone(), colour: [0, 255, 0] }));
        for (w, h) in [(300, 200), (150, 100), (600, 400)] {
            let (rgb, hole) = scene(w, h);
            assert!(fill.fill(&rgb, &hole, w, h).is_some());
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1, "draft, loupe and export of one spot run the model once");
        // a different spot runs it again
        let (rgb, mut hole) = scene(300, 200);
        hole.iter_mut().take(300 * 40).for_each(|h| *h = true);
        assert!(fill.fill(&rgb, &hole, 300, 200).is_some());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn a_failing_or_misbehaving_model_falls_back() {
        struct Fails;
        impl Painter for Fails {
            fn paint(&self, _: &[u8], _: &[u8]) -> Result<Vec<u8>, String> {
                Err("no model".into())
            }
        }
        struct Short;
        impl Painter for Short {
            fn paint(&self, _: &[u8], _: &[u8]) -> Result<Vec<u8>, String> {
                Ok(vec![1, 2, 3])
            }
        }
        let (rgb, hole) = scene(90, 60);
        assert!(PainterFill::new(Box::new(Fails)).fill(&rgb, &hole, 90, 60).is_none());
        assert!(PainterFill::new(Box::new(Short)).fill(&rgb, &hole, 90, 60).is_none());
        // hostile sizes
        let f = PainterFill::new(Box::new(Fails));
        assert!(f.fill(&rgb, &hole, 0, 60).is_none());
        assert!(f.fill(&rgb, &hole, 91, 60).is_none());
        assert!(f.fill(&rgb, &[false; 5400], 90, 60).is_none());
    }
}
