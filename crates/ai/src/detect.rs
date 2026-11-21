//! Find people and everyday objects (D-FINE-N, the 80 COCO classes): labelled boxes, for
//! suggesting what to select or remove.

use lightcraft_nn::Tensor;

use crate::models::DETECT;
use crate::store::Store;
use crate::{Error, Result, Rgb8};

const EDGE: usize = 640;

/// A found object: its class and confidence (0..1), and its box (0..1 across the photo).
#[derive(Clone, Debug, PartialEq)]
pub struct Detection {
    pub label: &'static str,
    pub score: f32,
    pub x0: f32,
    pub y0: f32,
    pub x1: f32,
    pub y1: f32,
}

pub const LABELS: [&str; 80] = [
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "airplane",
    "bus",
    "train",
    "truck",
    "boat",
    "traffic light",
    "fire hydrant",
    "stop sign",
    "parking meter",
    "bench",
    "bird",
    "cat",
    "dog",
    "horse",
    "sheep",
    "cow",
    "elephant",
    "bear",
    "zebra",
    "giraffe",
    "backpack",
    "umbrella",
    "handbag",
    "tie",
    "suitcase",
    "frisbee",
    "skis",
    "snowboard",
    "sports ball",
    "kite",
    "baseball bat",
    "baseball glove",
    "skateboard",
    "surfboard",
    "tennis racket",
    "bottle",
    "wine glass",
    "cup",
    "fork",
    "knife",
    "spoon",
    "bowl",
    "banana",
    "apple",
    "sandwich",
    "orange",
    "broccoli",
    "carrot",
    "hot dog",
    "pizza",
    "donut",
    "cake",
    "chair",
    "couch",
    "potted plant",
    "bed",
    "dining table",
    "toilet",
    "tv",
    "laptop",
    "mouse",
    "remote",
    "keyboard",
    "cell phone",
    "microwave",
    "oven",
    "toaster",
    "sink",
    "refrigerator",
    "book",
    "clock",
    "vase",
    "scissors",
    "teddy bear",
    "hair drier",
    "toothbrush",
];

/// Objects in `img` scoring at least `min_score`, best first.
pub fn detect(store: &Store, img: &Rgb8, min_score: f32) -> Result<Vec<Detection>> {
    let s = store.session(DETECT, Store::file(DETECT, 0)?)?;
    // stretched to 640 × 640, scaled to 0..1, no mean / std
    let input = Tensor::f32(vec![1, 3, EDGE, EDGE], img.planes(EDGE, EDGE, None))?;
    let out = s.run(vec![("pixel_values".into(), input)], None)?;
    let logits = out.get("logits").ok_or_else(|| Error::Input("no logits".into()))?;
    let boxes = out.get("pred_boxes").ok_or_else(|| Error::Input("no boxes".into()))?;
    let [1, q, c] = logits.shape[..] else { return Err(Error::Input(format!("logits {:?}", logits.shape))) };
    let (l, b) = (logits.fs()?, boxes.fs()?);
    let mut found = Vec::new();
    for i in 0..q {
        let Some(row) = l.get(i * c..(i + 1) * c) else { break };
        let (k, z) = row.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).map_or((0, f32::NEG_INFINITY), |(k, z)| (k, *z));
        let score = 1.0 / (1.0 + (-z).exp());
        if score < min_score {
            continue;
        }
        let Some(&[cx, cy, bw, bh]) = b.get(i * 4..i * 4 + 4) else { continue };
        let label = LABELS.get(k).copied().unwrap_or("object");
        found.push(Detection {
            label,
            score,
            x0: (cx - bw / 2.0).clamp(0.0, 1.0),
            y0: (cy - bh / 2.0).clamp(0.0, 1.0),
            x1: (cx + bw / 2.0).clamp(0.0, 1.0),
            y1: (cy + bh / 2.0).clamp(0.0, 1.0),
        });
    }
    found.sort_by(|a, b| b.score.total_cmp(&a.score));
    Ok(found)
}
