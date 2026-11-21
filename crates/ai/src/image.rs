//! The pictures the models read and write: interleaved 8-bit RGB, and the resampling between the
//! photo's size and each model's input size.

use crate::{Error, Result};

/// An 8-bit RGB picture, row-major, 3 bytes a pixel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rgb8 {
    pub width: usize,
    pub height: usize,
    pub data: Vec<u8>,
}

/// The largest picture a model is handed (64 MP).
const MAX_PIXELS: usize = 64 * 1024 * 1024;

impl Rgb8 {
    pub fn new(width: usize, height: usize, data: Vec<u8>) -> Result<Rgb8> {
        let n =
            width.checked_mul(height).filter(|n| *n > 0 && *n <= MAX_PIXELS).ok_or_else(|| Error::Input(format!("a {width}×{height} picture")))?;
        if n.checked_mul(3) != Some(data.len()) {
            return Err(Error::Input(format!("{} bytes for a {width}×{height} RGB picture", data.len())));
        }
        Ok(Rgb8 { width, height, data })
    }

    /// Bilinear sample (pixel centres at +0.5), channel `c`.
    fn sample(&self, x: f32, y: f32, c: usize) -> f32 {
        let fx = (x - 0.5).clamp(0.0, (self.width - 1) as f32);
        let fy = (y - 0.5).clamp(0.0, (self.height - 1) as f32);
        let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.width - 1), (y0 + 1).min(self.height - 1));
        let (dx, dy) = (fx - x0 as f32, fy - y0 as f32);
        let at = |x: usize, y: usize| f32::from(self.data.get((y * self.width + x) * 3 + c).copied().unwrap_or(0));
        (at(x0, y0) * (1.0 - dx) + at(x1, y0) * dx) * (1.0 - dy) + (at(x0, y1) * (1.0 - dx) + at(x1, y1) * dx) * dy
    }

    /// Planar `[3, h, w]` floats of the picture stretched to `w × h`, each value `v / 255 · scale`
    /// after `(v/255 − mean) / std` when given (ImageNet-style normalization).
    pub fn planes(&self, w: usize, h: usize, norm: Option<([f32; 3], [f32; 3])>) -> Vec<f32> {
        let mut out = vec![0f32; 3 * w * h];
        let (sx, sy) = (self.width as f32 / w as f32, self.height as f32 / h as f32);
        for c in 0..3 {
            for y in 0..h {
                for x in 0..w {
                    // area-ish: average a 2×2 of bilinear taps when shrinking a lot
                    let (cx, cy) = ((x as f32 + 0.5) * sx, (y as f32 + 0.5) * sy);
                    let v = if sx > 2.0 || sy > 2.0 {
                        let (ox, oy) = (sx * 0.25, sy * 0.25);
                        (self.sample(cx - ox, cy - oy, c)
                            + self.sample(cx + ox, cy - oy, c)
                            + self.sample(cx - ox, cy + oy, c)
                            + self.sample(cx + ox, cy + oy, c))
                            * 0.25
                    } else {
                        self.sample(cx, cy, c)
                    } / 255.0;
                    out[(c * h + y) * w + x] = match norm {
                        Some((m, s)) => (v - m[c]) / s[c],
                        None => v,
                    };
                }
            }
        }
        out
    }
}

/// Resample a `sw × sh` grid of values to `dw × dh` (bilinear, pixel centres aligned).
pub fn resample(src: &[f32], sw: usize, sh: usize, dw: usize, dh: usize) -> Vec<f32> {
    let mut out = vec![0f32; dw * dh];
    if sw == 0 || sh == 0 || src.len() < sw * sh {
        return out;
    }
    let at = |x: usize, y: usize| src.get(y * sw + x).copied().unwrap_or(0.0);
    for y in 0..dh {
        let fy = ((y as f32 + 0.5) * sh as f32 / dh as f32 - 0.5).clamp(0.0, (sh - 1) as f32);
        let (y0, dy) = (fy.floor() as usize, fy - fy.floor());
        let y1 = (y0 + 1).min(sh - 1);
        for x in 0..dw {
            let fx = ((x as f32 + 0.5) * sw as f32 / dw as f32 - 0.5).clamp(0.0, (sw - 1) as f32);
            let (x0, dx) = (fx.floor() as usize, fx - fx.floor());
            let x1 = (x0 + 1).min(sw - 1);
            out[y * dw + x] = (at(x0, y0) * (1.0 - dx) + at(x1, y0) * dx) * (1.0 - dy) + (at(x0, y1) * (1.0 - dx) + at(x1, y1) * dx) * dy;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pictures_are_checked_and_resampled() {
        assert!(Rgb8::new(2, 2, vec![0; 11]).is_err());
        assert!(Rgb8::new(0, 2, vec![]).is_err());
        let img = Rgb8::new(2, 1, vec![0, 0, 0, 255, 255, 255]).unwrap();
        let p = img.planes(4, 1, None);
        // left dark, right bright, in between rising
        assert!(p[0] < p[1] && p[1] < p[2] && p[2] < p[3], "{p:?}");
        let r = resample(&[0.0, 1.0], 2, 1, 4, 1);
        assert!(r[0] <= r[1] && r[1] <= r[2] && r[2] <= r[3]);
    }
}
