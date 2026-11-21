//! Generative fill (MI-GAN 512): the hole in a picture is painted with new content that continues
//! its surroundings. The model file is MI-GAN's own pipeline: it crops around the hole, runs at
//! 512 × 512 and pastes back, so the picture can be any size.

use lightcraft_nn::Tensor;

use crate::image::resample;
use crate::models::{INPAINT, INPAINT_BEST};
use crate::store::Store;
use crate::{Error, Result, Rgb8};

/// `img` with every pixel where `hole` is true filled in; the rest is left as it was.
pub fn fill(store: &Store, img: &Rgb8, hole: &[bool], cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<Rgb8> {
    let (w, h) = (img.width, img.height);
    if hole.len() != w * h {
        return Err(Error::Input(format!("a {}-pixel hole for a {w}×{h} picture", hole.len())));
    }
    if !hole.iter().any(|b| *b) {
        return Ok(img.clone());
    }
    let s = store.session(INPAINT, Store::file(INPAINT, 0)?)?;
    let mut planes = vec![0i64; 3 * w * h];
    for (i, px) in img.data.chunks_exact(3).enumerate() {
        for c in 0..3 {
            planes[c * w * h + i] = i64::from(px[c]);
        }
    }
    // the pipeline's mask: 255 known, 0 to fill
    let mask: Vec<i64> = hole.iter().map(|b| if *b { 0 } else { 255 }).collect();
    let feeds = vec![("image".to_string(), Tensor::i64(vec![1, 3, h, w], planes)?), ("mask".to_string(), Tensor::i64(vec![1, 1, h, w], mask)?)];
    let out = s.run(feeds, cancel)?;
    let r = out.get("result").ok_or_else(|| Error::Input("the model gave no picture".into()))?;
    if r.shape != [1, 3, h, w] {
        return Err(Error::Input(format!("the model gave {:?} for {w}×{h}", r.shape)));
    }
    let v = r.to_f32s();
    let mut data = img.data.clone();
    for (i, filled) in hole.iter().enumerate() {
        if *filled {
            for c in 0..3 {
                if let (Some(d), Some(s)) = (data.get_mut(i * 3 + c), v.get(c * w * h + i)) {
                    *d = s.round().clamp(0.0, 255.0) as u8;
                }
            }
        }
    }
    Rgb8::new(w, h, data)
}

/// LaMa's input and output side (the model is fixed at 512 × 512).
const LAMA: usize = 512;

/// [`fill`] with LaMa: better on large areas, slower. The picture is stretched to 512 × 512 for
/// the model and its answer stretched back; only the hole takes it.
pub fn fill_best(store: &Store, img: &Rgb8, hole: &[bool], cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<Rgb8> {
    let (w, h) = (img.width, img.height);
    if hole.len() != w * h {
        return Err(Error::Input(format!("a {}-pixel hole for a {w}×{h} picture", hole.len())));
    }
    if !hole.iter().any(|b| *b) {
        return Ok(img.clone());
    }
    let s = store.session(INPAINT_BEST, Store::file(INPAINT_BEST, 0)?)?;
    // the OpenCV export is fed and answers in BGR order, 0..1 in and 0..255 out
    let rgb = img.planes(LAMA, LAMA, None);
    let mut bgr = vec![0f32; 3 * LAMA * LAMA];
    for c in 0..3 {
        bgr[(2 - c) * LAMA * LAMA..(3 - c) * LAMA * LAMA].copy_from_slice(&rgb[c * LAMA * LAMA..(c + 1) * LAMA * LAMA]);
    }
    let hole_f: Vec<f32> = hole.iter().map(|b| if *b { 1.0 } else { 0.0 }).collect();
    let mask: Vec<f32> = resample(&hole_f, w, h, LAMA, LAMA).iter().map(|v| if *v > 0.0 { 1.0 } else { 0.0 }).collect();
    let feeds =
        vec![("image".to_string(), Tensor::f32(vec![1, 3, LAMA, LAMA], bgr)?), ("mask".to_string(), Tensor::f32(vec![1, 1, LAMA, LAMA], mask)?)];
    let out = s.run(feeds, cancel)?;
    let r = out.values().next().ok_or_else(|| Error::Input("the model gave no picture".into()))?;
    if r.shape != [1, 3, LAMA, LAMA] {
        return Err(Error::Input(format!("the model gave {:?}", r.shape)));
    }
    let v = r.to_f32s();
    // back to the picture's size, channel by channel (B, G, R → R, G, B)
    let planes: Vec<Vec<f32>> =
        (0..3).map(|c| resample(v.get((2 - c) * LAMA * LAMA..(3 - c) * LAMA * LAMA).unwrap_or(&[]), LAMA, LAMA, w, h)).collect();
    let mut data = img.data.clone();
    for (i, filled) in hole.iter().enumerate() {
        if *filled {
            for (c, plane) in planes.iter().enumerate() {
                if let (Some(d), Some(s)) = (data.get_mut(i * 3 + c), plane.get(i)) {
                    *d = s.round().clamp(0.0, 255.0) as u8;
                }
            }
        }
    }
    Rgb8::new(w, h, data)
}
