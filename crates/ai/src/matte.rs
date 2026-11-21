//! Portrait matting (MODNet): how much of each pixel is the person, hair strands included, for
//! cutting people out of their background.

use lightcraft_nn::Tensor;

use crate::image::resample;
use crate::models::MATTE;
use crate::store::Store;
use crate::{Error, Result, Rgb8};

/// The model's reference size: the picture's short side is brought to it (both sides whole
/// multiples of 32, as the network halves them five times).
const REF: f32 = 512.0;
const STEP: usize = 32;

/// The matte of `img` at its own size, one value per pixel (0 background .. 1 person).
pub fn matte(store: &Store, img: &Rgb8, cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<Vec<f32>> {
    let s = store.session(MATTE, Store::file(MATTE, 0)?)?;
    let (w, h) = (img.width as f32, img.height as f32);
    let scale = REF / w.min(h);
    let snap = |v: f32| (((v * scale) / STEP as f32).round() as usize).max(1) * STEP;
    let (iw, ih) = (snap(w), snap(h));
    // normalized to −1..1
    let input = Tensor::f32(vec![1, 3, ih, iw], img.planes(iw, ih, Some(([0.5; 3], [0.5; 3]))))?;
    let out = s.run(vec![("input".into(), input)], cancel)?;
    let m = out.values().next().ok_or_else(|| Error::Input("the model gave no matte".into()))?;
    let [1, 1, oh, ow] = m.shape[..] else { return Err(Error::Input(format!("a matte of shape {:?}", m.shape))) };
    let v: Vec<f32> = m.fs()?.iter().map(|x| x.clamp(0.0, 1.0)).collect();
    Ok(resample(&v, ow, oh, img.width, img.height))
}
