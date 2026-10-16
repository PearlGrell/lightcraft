//! Background removal on an alpha matte: the Photoshop-style **magic eraser** (pixels within a
//! tolerance of a sampled colour, measured as CIELAB ΔE, flood-filled from the tap or across the
//! whole image), **mattes** from a segmenter's probabilities (the phone's subject segmentation), and
//! **edge refinement** (colour decontamination of half-transparent edge pixels).
//!
//! Everything works on straight sRGB RGBA bytes plus a separate alpha plane (`0` = removed,
//! `255` = kept), so the photo is never changed and every step can be undone by keeping the old plane.
//! Inputs are untrusted: sizes are checked, points outside the image are errors, never panics.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::collections::VecDeque;
use std::fmt;

/// A request the image can't satisfy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// The pixel or alpha buffer doesn't match `width × height`.
    Size { expected: usize, got: usize },
    /// The point is outside the image.
    Outside,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Size { expected, got } => write!(f, "image buffer has {got} values, expected {expected}"),
            Error::Outside => write!(f, "the point is outside the image"),
        }
    }
}

impl std::error::Error for Error {}

/// Magic-eraser options (the tool's settings).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EraseOptions {
    /// 0–100: how different a colour may be from the sample and still be erased (ΔE up to half of it).
    pub tolerance: f32,
    /// Only pixels connected to the tapped one (flood fill); off erases matching colour everywhere.
    pub contiguous: bool,
    /// Sample radius: 0 = the tapped pixel, 1 = 3×3 average, 2 = 5×5.
    pub sample: u32,
    /// Smooth alpha across a band around the tolerance edge instead of a hard cut.
    pub anti_alias: bool,
    /// Softens the erased region's edge by this many pixels.
    pub feather: f32,
    /// 0–100: how much of a matching pixel is erased.
    pub opacity: f32,
}

impl Default for EraseOptions {
    fn default() -> Self {
        EraseOptions { tolerance: 32.0, contiguous: true, sample: 1, anti_alias: true, feather: 0.0, opacity: 100.0 }
    }
}

/// An image to erase from: `width × height` straight sRGB RGBA bytes.
#[derive(Debug, Clone, Copy)]
pub struct Image<'a> {
    pub width: usize,
    pub height: usize,
    pub rgba: &'a [u8],
}

impl Image<'_> {
    fn check(&self, alpha: &[u8]) -> Result<usize, Error> {
        let n = self.width.checked_mul(self.height).ok_or(Error::Size { expected: usize::MAX, got: alpha.len() })?;
        let bytes = n.checked_mul(4).ok_or(Error::Size { expected: usize::MAX, got: self.rgba.len() })?;
        if self.rgba.len() != bytes {
            return Err(Error::Size { expected: bytes, got: self.rgba.len() });
        }
        if alpha.len() != n {
            return Err(Error::Size { expected: n, got: alpha.len() });
        }
        Ok(n)
    }

    fn lab(&self, i: usize) -> [f32; 3] {
        let px = |k: usize| self.rgba.get(i * 4 + k).copied().unwrap_or(0);
        srgb_to_lab([px(0), px(1), px(2)])
    }
}

/// Erase around the pixel `(x, y)` into `alpha`. Returns how many pixels changed.
pub fn magic_erase(img: Image, alpha: &mut [u8], x: usize, y: usize, o: &EraseOptions) -> Result<usize, Error> {
    let n = img.check(alpha)?;
    if x >= img.width || y >= img.height {
        return Err(Error::Outside);
    }
    let sample = sample_lab(img, x, y, o.sample.min(2) as usize);
    let tol = (o.tolerance.clamp(0.0, 100.0) * 0.5).max(0.5);
    // anti-aliasing: full erase up to 75 % of the tolerance, none beyond 125 %, linear between
    let (inner, outer) = if o.anti_alias { (tol * 0.75, tol * 1.25) } else { (tol, tol) };
    let weight = |i: usize| {
        let d = delta_e(img.lab(i), sample);
        if d <= inner {
            1.0
        } else if d >= outer || outer <= inner {
            0.0
        } else {
            (outer - d) / (outer - inner)
        }
    };
    let mut erase = vec![0f32; n];
    if o.contiguous {
        let w = img.width;
        let mut queue = VecDeque::from([y * w + x]);
        let mut seen = vec![false; n];
        if let Some(s) = seen.get_mut(y * w + x) {
            *s = true;
        }
        while let Some(i) = queue.pop_front() {
            let v = weight(i);
            if v <= 0.0 {
                continue;
            }
            if let Some(e) = erase.get_mut(i) {
                *e = v;
            }
            let (px, py) = (i % w, i / w);
            let neighbours = [(px > 0).then(|| i - 1), (px + 1 < w).then(|| i + 1), (py > 0).then(|| i - w), (py + 1 < img.height).then(|| i + w)];
            for j in neighbours.into_iter().flatten() {
                if let Some(s) = seen.get_mut(j)
                    && !*s
                {
                    *s = true;
                    queue.push_back(j);
                }
            }
        }
    } else {
        for (i, e) in erase.iter_mut().enumerate() {
            *e = weight(i);
        }
    }
    if o.feather > 0.0 {
        blur(&mut erase, img.width, img.height, o.feather.min(256.0).round() as usize);
    }
    let opacity = o.opacity.clamp(0.0, 100.0) / 100.0;
    let mut changed = 0;
    for (a, e) in alpha.iter_mut().zip(&erase) {
        let next = (f32::from(*a) * (1.0 - e * opacity)).round().clamp(0.0, 255.0) as u8;
        if next != *a {
            *a = next;
            changed += 1;
        }
    }
    Ok(changed)
}

/// A matte from a segmenter: `probs` is `side × side` foreground probabilities (0..1) covering the
/// whole image; `alpha` gets them resampled (bilinear) to `width × height`, inverted when `invert`
/// (keep the background instead of the subject).
pub fn matte(width: usize, height: usize, side: usize, probs: &[f32], invert: bool, alpha: &mut [u8]) -> Result<(), Error> {
    let n = width.checked_mul(height).ok_or(Error::Size { expected: usize::MAX, got: alpha.len() })?;
    if alpha.len() != n {
        return Err(Error::Size { expected: n, got: alpha.len() });
    }
    let cells = side.checked_mul(side).ok_or(Error::Size { expected: usize::MAX, got: probs.len() })?;
    if side == 0 || probs.len() != cells {
        return Err(Error::Size { expected: cells, got: probs.len() });
    }
    let at = |x: usize, y: usize| probs.get(y.min(side - 1) * side + x.min(side - 1)).copied().unwrap_or(0.0);
    for (i, a) in alpha.iter_mut().enumerate() {
        let fx = ((i % width) as f32 + 0.5) / width as f32 * side as f32 - 0.5;
        let fy = ((i / width) as f32 + 0.5) / height as f32 * side as f32 - 0.5;
        let (x0, y0) = (fx.floor().max(0.0) as usize, fy.floor().max(0.0) as usize);
        let (tx, ty) = ((fx - x0 as f32).clamp(0.0, 1.0), (fy - y0 as f32).clamp(0.0, 1.0));
        let top = at(x0, y0) * (1.0 - tx) + at(x0 + 1, y0) * tx;
        let bottom = at(x0, y0 + 1) * (1.0 - tx) + at(x0 + 1, y0 + 1) * tx;
        let mut p = (top * (1.0 - ty) + bottom * ty).clamp(0.0, 1.0);
        if !p.is_finite() {
            p = 0.0;
        }
        if invert {
            p = 1.0 - p;
        }
        *a = (p * 255.0).round() as u8;
    }
    Ok(())
}

/// Edge refinement: half-transparent pixels take on the colour of the nearest fully kept pixels
/// around them (within `radius`), so background colour no longer bleeds through the edge.
pub fn decontaminate(width: usize, height: usize, rgba: &mut [u8], alpha: &[u8], radius: usize) -> Result<usize, Error> {
    Image { width, height, rgba }.check(alpha)?;
    let r = radius.clamp(1, 16) as isize;
    let source = rgba.to_vec();
    let mut changed = 0;
    for (i, &a) in alpha.iter().enumerate() {
        if a == 0 || a == 255 {
            continue;
        }
        let (x, y) = ((i % width) as isize, (i / width) as isize);
        let mut sum = [0u32; 3];
        let mut count = 0u32;
        for dy in -r..=r {
            for dx in -r..=r {
                let (nx, ny) = (x + dx, y + dy);
                if nx < 0 || ny < 0 || nx >= width as isize || ny >= height as isize {
                    continue;
                }
                let j = ny as usize * width + nx as usize;
                if alpha.get(j).copied().unwrap_or(0) == 255 {
                    for (k, s) in sum.iter_mut().enumerate() {
                        *s += u32::from(source.get(j * 4 + k).copied().unwrap_or(0));
                    }
                    count += 1;
                }
            }
        }
        if count > 0 {
            for (k, s) in sum.iter().enumerate() {
                if let (Some(p), Some(v)) = (rgba.get_mut(i * 4 + k), s.checked_div(count)) {
                    *p = v as u8;
                }
            }
            changed += 1;
        }
    }
    Ok(changed)
}

fn sample_lab(img: Image, x: usize, y: usize, r: usize) -> [f32; 3] {
    let mut sum = [0f32; 3];
    let mut count = 0f32;
    for sy in y.saturating_sub(r)..=(y + r).min(img.height - 1) {
        for sx in x.saturating_sub(r)..=(x + r).min(img.width - 1) {
            let lab = img.lab(sy * img.width + sx);
            for k in 0..3 {
                sum[k] += lab[k];
            }
            count += 1.0;
        }
    }
    sum.map(|v| v / count.max(1.0))
}

fn delta_e(a: [f32; 3], b: [f32; 3]) -> f32 {
    ((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt()
}

/// sRGB bytes → CIELAB (D65).
pub fn srgb_to_lab(c: [u8; 3]) -> [f32; 3] {
    let lin = |v: u8| {
        let v = f32::from(v) / 255.0;
        if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
    };
    let (r, g, b) = (lin(c[0]), lin(c[1]), lin(c[2]));
    let x = (0.412_456_4 * r + 0.357_576_1 * g + 0.180_437_5 * b) / 0.950_47;
    let y = 0.212_672_9 * r + 0.715_152_2 * g + 0.072_175 * b;
    let z = (0.019_333_9 * r + 0.119_192 * g + 0.950_304_1 * b) / 1.088_83;
    let f = |t: f32| if t > 0.008_856 { t.cbrt() } else { 7.787 * t + 16.0 / 116.0 };
    let (fx, fy, fz) = (f(x), f(y), f(z));
    [116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)]
}

/// Separable box blur (two passes ≈ a soft triangle) of a plane in place.
fn blur(plane: &mut [f32], w: usize, h: usize, r: usize) {
    if r == 0 || w == 0 || h == 0 {
        return;
    }
    for _ in 0..2 {
        box_pass(plane, w, h, r, true);
        box_pass(plane, w, h, r, false);
    }
}

fn box_pass(plane: &mut [f32], w: usize, h: usize, r: usize, horizontal: bool) {
    let (lines, len) = if horizontal { (h, w) } else { (w, h) };
    let idx = |line: usize, k: usize| if horizontal { line * w + k } else { k * w + line };
    let mut row = vec![0f32; len];
    for line in 0..lines {
        for (k, v) in row.iter_mut().enumerate() {
            *v = plane.get(idx(line, k)).copied().unwrap_or(0.0);
        }
        let mut prefix = vec![0f32; len + 1];
        for k in 0..len {
            prefix[k + 1] = prefix[k] + row[k];
        }
        for k in 0..len {
            let (a, b) = (k.saturating_sub(r), (k + r + 1).min(len));
            if let Some(p) = plane.get_mut(idx(line, k)) {
                *p = (prefix[b] - prefix[a]) / (b - a) as f32;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `w × h` image: left half red, right half blue, with a red island in the blue.
    fn two_tone(w: usize, h: usize) -> Vec<u8> {
        let mut px = Vec::with_capacity(w * h * 4);
        for y in 0..h {
            for x in 0..w {
                let island = x >= w * 3 / 4 && x < w * 3 / 4 + 2 && y < 2;
                let red = x < w / 2 || island;
                px.extend_from_slice(if red { &[220, 30, 30, 255] } else { &[30, 40, 220, 255] });
            }
        }
        px
    }

    #[test]
    fn contiguous_erases_only_the_connected_region() {
        let (w, h) = (16, 8);
        let px = two_tone(w, h);
        let mut a = vec![255u8; w * h];
        let o = EraseOptions { anti_alias: false, ..Default::default() };
        let changed = magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 0, 0, &o).unwrap();
        assert_eq!(changed, w / 2 * h);
        assert_eq!(a[0], 0);
        assert_eq!(a[w - 1], 255, "blue stays");
        assert_eq!(a[w * 3 / 4], 255, "the red island is not connected");
    }

    #[test]
    fn global_erases_matching_colour_everywhere() {
        let (w, h) = (16, 8);
        let px = two_tone(w, h);
        let mut a = vec![255u8; w * h];
        let o = EraseOptions { contiguous: false, anti_alias: false, ..Default::default() };
        magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 0, 0, &o).unwrap();
        assert_eq!(a[w * 3 / 4], 0, "the island goes too");
        assert_eq!(a[w - 1], 255);
    }

    #[test]
    fn tolerance_decides_what_matches() {
        // a horizontal grey ramp: a low tolerance erases a narrow band, a high one more of it
        let (w, h) = (64, 1);
        let px: Vec<u8> = (0..w)
            .flat_map(|x| {
                let v = (x * 4) as u8;
                [v, v, v, 255]
            })
            .collect();
        let erased = |t: f32| {
            let mut a = vec![255u8; w];
            let o = EraseOptions { tolerance: t, anti_alias: false, sample: 0, ..Default::default() };
            magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 0, 0, &o).unwrap()
        };
        assert!(erased(10.0) < erased(60.0));
        assert!(erased(60.0) < w);
    }

    #[test]
    fn anti_alias_and_feather_leave_partial_alpha_at_the_edge() {
        let (w, h) = (64, 1);
        let px: Vec<u8> = (0..w)
            .flat_map(|x| {
                let v = (x * 4) as u8;
                [v, v, v, 255]
            })
            .collect();
        let mut a = vec![255u8; w];
        let o = EraseOptions { tolerance: 40.0, anti_alias: true, sample: 0, ..Default::default() };
        magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 0, 0, &o).unwrap();
        assert!(a.iter().any(|&v| v > 0 && v < 255), "{a:?}");
        let (w, h) = (16, 8);
        let px = two_tone(w, h);
        let mut a = vec![255u8; w * h];
        let o = EraseOptions { anti_alias: false, feather: 2.0, ..Default::default() };
        magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 0, 0, &o).unwrap();
        assert!((0..w).any(|x| a[x] > 0 && a[x] < 255), "feathered edge");
    }

    #[test]
    fn opacity_erases_partly() {
        let (w, h) = (4, 4);
        let px = vec![10u8; w * h * 4];
        let mut a = vec![255u8; w * h];
        let o = EraseOptions { opacity: 50.0, anti_alias: false, ..Default::default() };
        magic_erase(Image { width: w, height: h, rgba: &px }, &mut a, 1, 1, &o).unwrap();
        assert!(a.iter().all(|&v| v == 128), "{a:?}");
    }

    #[test]
    fn bad_input_is_an_error_not_a_panic() {
        let px = vec![0u8; 16];
        let mut a = vec![255u8; 4];
        let img = Image { width: 2, height: 2, rgba: &px };
        assert_eq!(magic_erase(img, &mut a, 2, 0, &EraseOptions::default()), Err(Error::Outside));
        assert!(magic_erase(Image { width: 3, height: 2, rgba: &px }, &mut a, 0, 0, &EraseOptions::default()).is_err());
        assert!(magic_erase(Image { width: usize::MAX, height: 2, rgba: &px }, &mut a, 0, 0, &EraseOptions::default()).is_err());
        let o = EraseOptions { tolerance: f32::NAN, feather: f32::INFINITY, opacity: f32::NAN, ..Default::default() };
        assert!(magic_erase(img, &mut a, 0, 0, &o).is_ok());
        assert!(matte(2, 2, 0, &[], false, &mut a).is_err());
        assert!(matte(2, 2, 2, &[0.0; 3], false, &mut a).is_err());
        assert!(decontaminate(2, 2, &mut [0u8; 15], &a, 1).is_err());
    }

    #[test]
    fn matte_resamples_and_inverts() {
        let probs = [1.0, 0.0, 1.0, 0.0];
        let mut a = vec![0u8; 8 * 2];
        matte(8, 2, 2, &probs, false, &mut a).unwrap();
        assert_eq!(a[0], 255);
        assert_eq!(a[7], 0);
        matte(8, 2, 2, &probs, true, &mut a).unwrap();
        assert_eq!(a[0], 0);
        assert_eq!(a[7], 255);
        matte(1, 1, 1, &[f32::NAN], false, &mut a[..1]).unwrap();
    }

    #[test]
    fn decontaminate_pulls_edge_colour_from_the_subject() {
        let (w, h) = (3, 1);
        let mut px = vec![200, 0, 0, 255, 100, 100, 200, 255, 0, 0, 255, 255];
        let a = [255, 128, 0];
        assert_eq!(decontaminate(w, h, &mut px, &a, 1).unwrap(), 1);
        assert_eq!(&px[4..7], &[200, 0, 0]);
    }
}
