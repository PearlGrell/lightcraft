//! Relative depth (Depth Anything V2 Small): how near each part of the photo is, for Lens Blur.
//! The result is a square grid stretched over the whole photo, 0 (farthest) .. 1 (nearest).

use lightcraft_nn::Tensor;

use crate::image::resample;
use crate::models::DEPTH;
use crate::store::Store;
use crate::{Error, Result, Rgb8};

/// The model works on sides that are multiples of its 14-pixel patches; the long side is this.
const LONG: usize = 518;
const PATCH: usize = 14;
const MEAN: [f32; 3] = [0.485, 0.456, 0.406];
const STD: [f32; 3] = [0.229, 0.224, 0.225];

/// The depth of `img` as a `side × side` grid over the whole photo (0 far .. 1 near).
pub fn estimate(store: &Store, img: &Rgb8, side: usize, cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<Vec<f32>> {
    if side == 0 || side > 1024 {
        return Err(Error::Input(format!("a depth grid of side {side}")));
    }
    let s = store.session(DEPTH, Store::file(DEPTH, 0)?)?;
    // the photo's aspect, long side 518, both sides whole patches
    let (w, h) = (img.width as f32, img.height as f32);
    let scale = LONG as f32 / w.max(h);
    let snap = |v: f32| (((v * scale) / PATCH as f32).round() as usize).max(1) * PATCH;
    let (iw, ih) = (snap(w), snap(h));
    let input = Tensor::f32(vec![1, 3, ih, iw], img.planes(iw, ih, Some((MEAN, STD))))?;
    let out = s.run(vec![("pixel_values".into(), input)], cancel)?;
    let d = out.values().next().ok_or_else(|| Error::Input("the model gave no depth".into()))?;
    let (oh, ow) = match d.shape[..] {
        [_, oh, ow] => (oh, ow),
        [_, _, oh, ow] => (oh, ow),
        _ => return Err(Error::Input(format!("depth of shape {:?}", d.shape))),
    };
    let v = d.fs()?;
    // relative inverse depth (bigger = nearer): spread the 2nd..98th percentile over 0..1
    let mut sorted: Vec<f32> = v.iter().copied().filter(|x| x.is_finite()).collect();
    if sorted.is_empty() {
        return Err(Error::Input("the depth is empty".into()));
    }
    sorted.sort_by(f32::total_cmp);
    let pick = |q: f32| sorted.get(((sorted.len() - 1) as f32 * q) as usize).copied().unwrap_or(0.0);
    let (lo, hi) = (pick(0.02), pick(0.98));
    let span = (hi - lo).max(1e-6);
    let norm: Vec<f32> = v.iter().map(|x| if x.is_finite() { ((x - lo) / span).clamp(0.0, 1.0) } else { 0.0 }).collect();
    Ok(resample(&norm, ow, oh, side, side))
}
