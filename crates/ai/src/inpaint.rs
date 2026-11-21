//! Generative fill (MI-GAN 512): the hole in a picture is painted with new content that continues
//! its surroundings. The model file is MI-GAN's own pipeline: it crops around the hole, runs at
//! 512 × 512 and pastes back, so the picture can be any size.

use lightcraft_nn::Tensor;

use crate::models::INPAINT;
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
