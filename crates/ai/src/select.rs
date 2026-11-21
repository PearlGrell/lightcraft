//! Tap to select (EfficientSAM-Ti): [`Selector::encode`] analyses a photo once (the slow part);
//! [`Selector::select`] turns taps into a selection in a fraction of a second. The result is a
//! `side × side` grid of logits stretched over the whole photo — the engine's stored
//! segmentation format — so the selection renders at any size and needs no model afterwards.

use lightcraft_nn::Tensor;

use crate::models::SELECT;
use crate::store::Store;
use crate::{Error, Result, Rgb8};

/// The encoder's input edge (the model stretches every photo to this square).
const EDGE: usize = 1024;
/// At most this many taps per selection.
const MAX_POINTS: usize = 16;

/// A photo analysed for selection.
pub struct Selector {
    embeddings: Tensor,
}

/// A selection: logits over the whole photo (`side × side`, row-major) and the model's own
/// estimate of how good it is (0..1).
#[derive(Clone, Debug)]
pub struct Selection {
    pub side: usize,
    pub logits: Vec<f32>,
    pub score: f32,
}

/// A tap: where (0..1 across the photo) and whether it adds (`true`) or leaves out.
#[derive(Clone, Copy, Debug)]
pub struct Tap {
    pub x: f32,
    pub y: f32,
    pub include: bool,
}

impl Selector {
    /// Analyse `img` (any size; the model works on it stretched to 1024 × 1024).
    pub fn encode(store: &Store, img: &Rgb8, cancel: Option<&std::sync::atomic::AtomicBool>) -> Result<Selector> {
        let enc = store.session(SELECT, Store::file(SELECT, 0)?)?;
        // the model normalizes itself; it takes RGB in 0..1
        let input = Tensor::f32(vec![1, 3, EDGE, EDGE], img.planes(EDGE, EDGE, None))?;
        let mut out = enc.run(vec![("batched_images".into(), input)], cancel)?;
        let embeddings = out.remove("image_embeddings").ok_or_else(|| Error::Input("the encoder gave no embeddings".into()))?;
        Ok(Selector { embeddings })
    }

    /// The selection the taps describe (the best of the model's three answers).
    pub fn select(&self, store: &Store, taps: &[Tap]) -> Result<Selection> {
        if taps.is_empty() || taps.len() > MAX_POINTS {
            return Err(Error::Input(format!("{} taps (1 to {MAX_POINTS})", taps.len())));
        }
        if taps.iter().any(|t| !(t.x.is_finite() && t.y.is_finite())) {
            return Err(Error::Input("a tap is not a number".into()));
        }
        let dec = store.session(SELECT, Store::file(SELECT, 1)?)?;
        let n = taps.len();
        // taps in the encoder's pixels (the image size we tell the decoder is the square it saw)
        let coords: Vec<f32> = taps.iter().flat_map(|t| [t.x.clamp(0.0, 1.0) * EDGE as f32, t.y.clamp(0.0, 1.0) * EDGE as f32]).collect();
        let labels: Vec<f32> = taps.iter().map(|t| if t.include { 1.0 } else { 0.0 }).collect();
        let feeds = vec![
            ("image_embeddings".to_string(), self.embeddings.clone()),
            ("batched_point_coords".to_string(), Tensor::f32(vec![1, 1, n, 2], coords)?),
            ("batched_point_labels".to_string(), Tensor::f32(vec![1, 1, n], labels)?),
            ("orig_im_size".to_string(), Tensor::i64(vec![2], vec![EDGE as i64, EDGE as i64])?),
        ];
        let out = dec.run(feeds, None)?;
        let iou = out.get("iou_predictions").ok_or_else(|| Error::Input("the decoder gave no scores".into()))?.fs()?.to_vec();
        // the low-resolution logits: [1, 3, 256, 256] over the whole (stretched) photo
        let low = dec
            .outputs()
            .iter()
            .filter_map(|o| out.get(o))
            .find(|t| t.rank() == 4 && t.shape.get(1) == Some(&3))
            .ok_or_else(|| Error::Input("the decoder gave no masks".into()))?;
        let side = *low.shape.get(3).ok_or_else(|| Error::Input("mask shape".into()))?;
        let best = iou.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).map_or(0, |(i, _)| i);
        let logits = low.fs()?.get(best * side * side..(best + 1) * side * side).ok_or_else(|| Error::Input("mask size".into()))?.to_vec();
        Ok(Selection { side, logits, score: iou.get(best).copied().unwrap_or(0.0) })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn without_the_model_it_says_so() {
        let s = Store::new(std::env::temp_dir().join("lightcraft-ai-no-models"));
        let img = Rgb8::new(4, 4, vec![128; 48]).unwrap();
        assert!(matches!(Selector::encode(&s, &img, None), Err(Error::Missing(_))));
    }
}
