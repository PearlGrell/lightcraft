//! Network operators: convolutions and matrix products (on candle's multi-threaded kernels), and
//! normalizations, pooling, softmax, reductions, resize and grid sampling (here, in checked Rust).

use rayon::prelude::*;

use crate::proto::Node;
use crate::tensor::{Tensor, axis, broadcast_shape, numel, strides};
use crate::{Error, Result};

fn pair(v: Option<&[i64]>, default: i64) -> Result<(usize, usize)> {
    match v {
        None => Ok((default as usize, default as usize)),
        Some([a, b]) if *a >= 0 && *b >= 0 => Ok((*a as usize, *b as usize)),
        Some(other) => Err(Error::Unsupported(format!("2-D convolution parameter {other:?}"))),
    }
}

/// The four pads (top, left, bottom, right) of a 2-D window, honouring `auto_pad`.
fn pads2(n: &Node, ih: usize, iw: usize, k: (usize, usize), s: (usize, usize), d: (usize, usize)) -> Result<[usize; 4]> {
    let auto = n.string("auto_pad").unwrap_or("NOTSET");
    match auto {
        "NOTSET" => match n.ints("pads") {
            None => Ok([0; 4]),
            Some([t, l, b, r]) if [t, l, b, r].iter().all(|v| **v >= 0) => Ok([*t as usize, *l as usize, *b as usize, *r as usize]),
            Some(p) => Err(Error::Unsupported(format!("pads {p:?}"))),
        },
        "VALID" => Ok([0; 4]),
        "SAME_UPPER" | "SAME_LOWER" => {
            let need = |i: usize, k: usize, s: usize, d: usize| {
                let o = i.div_ceil(s.max(1));
                ((o.saturating_sub(1)) * s + (k.saturating_sub(1)) * d + 1).saturating_sub(i)
            };
            let (ph, pw) = (need(ih, k.0, s.0, d.0), need(iw, k.1, s.1, d.1));
            let upper = auto == "SAME_UPPER";
            let split = |p: usize| if upper { (p / 2, p - p / 2) } else { (p - p / 2, p / 2) };
            let ((t, b), (l, r)) = (split(ph), split(pw));
            Ok([t, l, b, r])
        }
        other => Err(Error::Unsupported(format!("auto_pad {other}"))),
    }
}

/// Elements one im2col tile may hold (64 MB of f32): big layers run in bands of output rows.
const COL_BUDGET: usize = 16 * 1024 * 1024;

/// `batch` products `a [m, k] × b [k, n]` (row-major, batches back to back): candle's
/// multi-threaded gemm natively, plain loops on the web (which runs no models).
fn mm(a: &[f32], b: Vec<f32>, batch: usize, m: usize, k: usize, n: usize) -> Result<Vec<f32>> {
    if a.len() != batch * m * k || b.len() != batch * k * n {
        return Err(Error::Run("matrix product sizes".into()));
    }
    #[cfg(not(target_arch = "wasm32"))]
    {
        let dev = candle_core::Device::Cpu;
        let ta = candle_core::Tensor::from_slice(a, (batch, m, k), &dev)?;
        let tb = candle_core::Tensor::from_vec(b, (batch, k, n), &dev)?;
        Ok(ta.matmul(&tb)?.flatten_all()?.to_vec1::<f32>()?)
    }
    #[cfg(target_arch = "wasm32")]
    {
        let mut out = vec![0f32; batch * m * n];
        for bi in 0..batch {
            for i in 0..m {
                for p in 0..k {
                    let av = a[(bi * m + i) * k + p];
                    let row = &b[(bi * k + p) * n..(bi * k + p + 1) * n];
                    let dst = &mut out[(bi * m + i) * n..(bi * m + i + 1) * n];
                    for (d, bv) in dst.iter_mut().zip(row) {
                        *d += av * bv;
                    }
                }
            }
        }
        Ok(out)
    }
}

/// `w [m, k] × col [k, n]`.
fn gemm_mm(w: &[f32], m: usize, k: usize, col: Vec<f32>, n: usize) -> Result<Vec<f32>> {
    mm(w, col, 1, m, k, n)
}

pub fn conv(n: &Node, x: &Tensor, w: &Tensor, b: Option<&Tensor>) -> Result<Tensor> {
    if x.rank() != 4 || w.rank() != 4 {
        return Err(Error::Unsupported(format!("Conv of rank {} (only 2-D)", x.rank())));
    }
    let [bn, c, ih, iw] = x.shape[..] else { return Err(Error::Run("Conv input".into())) };
    let [o, cg, kh, kw] = w.shape[..] else { return Err(Error::Run("Conv weight".into())) };
    let (s, d) = (pair(n.ints("strides"), 1)?, pair(n.ints("dilations"), 1)?);
    if s.0 == 0 || s.1 == 0 || d.0 == 0 || d.1 == 0 {
        return Err(Error::Run("Conv strides or dilations".into()));
    }
    let groups = usize::try_from(n.int("group").unwrap_or(1)).map_err(|_| Error::Run("Conv group".into()))?.max(1);
    if cg.checked_mul(groups) != Some(c) || o % groups != 0 {
        return Err(Error::Run(format!("Conv of {c} channels by {o}×{cg} in {groups} groups")));
    }
    let p = pads2(n, ih, iw, (kh, kw), s, d)?;
    let (ph, pw) = (ih + p[0] + p[2], iw + p[1] + p[3]);
    let (span_h, span_w) = ((kh - 1) * d.0 + 1, (kw - 1) * d.1 + 1);
    if ph < span_h || pw < span_w {
        return Err(Error::Run("Conv window larger than its input".into()));
    }
    let (oh, ow) = ((ph - span_h) / s.0 + 1, (pw - span_w) / s.1 + 1);
    numel(&[bn, o, oh, ow])?;
    let xs = x.fs()?;
    let ws = w.fs()?;
    let bias = b.map(|t| t.fs()).transpose()?;
    // the input value at (channel ch, padded row py, padded column px)
    let at = |plane: &[f32], py: usize, px: usize| -> f32 {
        if py < p[0] || px < p[1] || py >= p[0] + ih || px >= p[1] + iw {
            0.0
        } else {
            plane.get((py - p[0]) * iw + (px - p[1])).copied().unwrap_or(0.0)
        }
    };
    let mut out = vec![0f32; bn * o * oh * ow];
    let og = o / groups;
    for bi in 0..bn {
        let img = xs.get(bi * c * ih * iw..(bi + 1) * c * ih * iw).ok_or_else(|| Error::Run("Conv input size".into()))?;
        let dst = out.get_mut(bi * o * oh * ow..(bi + 1) * o * oh * ow).ok_or_else(|| Error::Run("Conv output size".into()))?;
        if groups == c && cg == 1 && og == 1 {
            // depthwise: each output channel reads one input channel
            dst.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(ch, plane_out)| {
                let plane = img.get(ch * ih * iw..(ch + 1) * ih * iw).unwrap_or(&[]);
                let k = ws.get(ch * kh * kw..(ch + 1) * kh * kw).unwrap_or(&[]);
                for y in 0..oh {
                    for xx in 0..ow {
                        let mut acc = 0f32;
                        for ky in 0..kh {
                            for kx in 0..kw {
                                acc += k.get(ky * kw + kx).copied().unwrap_or(0.0) * at(plane, y * s.0 + ky * d.0, xx * s.1 + kx * d.1);
                            }
                        }
                        plane_out[y * ow + xx] = acc;
                    }
                }
            });
        } else {
            let kdim = cg * kh * kw;
            for g in 0..groups {
                let wg = ws.get(g * og * kdim..(g + 1) * og * kdim).ok_or_else(|| Error::Run("Conv weight size".into()))?;
                let img_g = img.get(g * cg * ih * iw..(g + 1) * cg * ih * iw).ok_or_else(|| Error::Run("Conv input size".into()))?;
                let simple = kh == 1 && kw == 1 && s == (1, 1) && p == [0; 4];
                let band = (COL_BUDGET / (kdim * ow).max(1)).clamp(1, oh);
                let mut y0 = 0;
                while y0 < oh {
                    let rows = band.min(oh - y0);
                    let ncol = rows * ow;
                    let col: Vec<f32> = if simple {
                        // a 1×1 convolution is a plain product with the input planes
                        let mut col = Vec::with_capacity(kdim * ncol);
                        for ch in 0..cg {
                            col.extend_from_slice(
                                img_g
                                    .get(ch * ih * iw + y0 * iw..ch * ih * iw + (y0 + rows) * iw)
                                    .ok_or_else(|| Error::Run("Conv input size".into()))?,
                            );
                        }
                        col
                    } else {
                        let mut col = vec![0f32; kdim * ncol];
                        col.par_chunks_mut(ncol.max(1)).enumerate().for_each(|(r, row)| {
                            let (ch, ky, kx) = (r / (kh * kw), (r / kw) % kh, r % kw);
                            let plane = img_g.get(ch * ih * iw..(ch + 1) * ih * iw).unwrap_or(&[]);
                            for y in 0..rows {
                                let py = (y0 + y) * s.0 + ky * d.0;
                                for xx in 0..ow {
                                    row[y * ow + xx] = at(plane, py, xx * s.1 + kx * d.1);
                                }
                            }
                        });
                        col
                    };
                    let prod = gemm_mm(wg, og, kdim, col, ncol)?;
                    for oc in 0..og {
                        let src = prod.get(oc * ncol..(oc + 1) * ncol).ok_or_else(|| Error::Run("Conv product size".into()))?;
                        let at_out = (g * og + oc) * oh * ow + y0 * ow;
                        dst.get_mut(at_out..at_out + ncol).ok_or_else(|| Error::Run("Conv output size".into()))?.copy_from_slice(src);
                    }
                    y0 += rows;
                }
            }
        }
        if let Some(bias) = bias {
            dst.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(ch, plane)| {
                let v = bias.get(ch).copied().unwrap_or(0.0);
                plane.iter_mut().for_each(|q| *q += v);
            });
        }
    }
    Tensor::f32(vec![bn, o, oh, ow], out)
}

pub fn conv_transpose(n: &Node, x: &Tensor, w: &Tensor, b: Option<&Tensor>) -> Result<Tensor> {
    if x.rank() != 4 || w.rank() != 4 {
        return Err(Error::Unsupported("ConvTranspose of rank other than 4".into()));
    }
    let (s, d) = (pair(n.ints("strides"), 1)?, pair(n.ints("dilations"), 1)?);
    if s.0 != s.1 || d.0 != d.1 || n.int("group").unwrap_or(1) != 1 {
        return Err(Error::Unsupported("ConvTranspose strides, dilations or groups".into()));
    }
    let op = pair(n.ints("output_padding"), 0)?;
    let p = match n.ints("pads") {
        None => [0usize; 4],
        Some([t, l, b2, r]) if [t, l, b2, r].iter().all(|v| **v >= 0) => [*t as usize, *l as usize, *b2 as usize, *r as usize],
        Some(other) => return Err(Error::Unsupported(format!("ConvTranspose pads {other:?}"))),
    };
    if !(p[0] == p[2] && p[1] == p[3] && p[0] == p[1] && op.0 == op.1) {
        return Err(Error::Unsupported("ConvTranspose with uneven pads".into()));
    }
    #[cfg(not(target_arch = "wasm32"))]
    {
        let mut y = x.to_candle()?.conv_transpose2d(&w.to_candle()?, p[0], op.0, s.0, d.0)?;
        if let Some(b) = b {
            y = y.broadcast_add(&b.to_candle()?.reshape((1, b.len(), 1, 1))?)?;
        }
        Tensor::from_candle(&y)
    }
    #[cfg(target_arch = "wasm32")]
    {
        let _ = (b, p, op, s, d);
        Err(Error::Unsupported("ConvTranspose in the web build".into()))
    }
}

/// MatMul with NumPy semantics (1-D operands, broadcast batch dimensions).
pub fn matmul(a: &Tensor, b: &Tensor) -> Result<Tensor> {
    let (mut a2, mut b2) = (a.clone(), b.clone());
    let (va, vb) = (a.rank() == 1, b.rank() == 1);
    if va {
        a2 = a2.reshaped(vec![1, a.len()])?;
    }
    if vb {
        b2 = b2.reshaped(vec![b.len(), 1])?;
    }
    let (ra, rb) = (a2.rank(), b2.rank());
    let (m, k) = (a2.shape[ra - 2], a2.shape[ra - 1]);
    let (k2, nn) = (b2.shape[rb - 2], b2.shape[rb - 1]);
    if k != k2 {
        return Err(Error::Run(format!("MatMul {:?} × {:?}", a.shape, b.shape)));
    }
    let batch = broadcast_shape(&a2.shape[..ra - 2], &b2.shape[..rb - 2])?;
    let mut out_shape = batch.clone();
    out_shape.extend([m, nn]);
    numel(&out_shape)?;
    let y = if rb == 2 {
        // the common case: [..., m, k] × [k, n] as one 2-D product
        let rows: usize = a2.shape[..ra - 1].iter().product();
        let p = mm(a2.fs()?, b2.fs()?.to_vec(), 1, rows, k, nn)?;
        let mut s = a2.shape[..ra - 1].to_vec();
        s.push(nn);
        Tensor::f32(s, p)?
    } else {
        let mut sa = batch.clone();
        sa.extend([m, k]);
        let mut sb = batch.clone();
        sb.extend([k, nn]);
        let ea = if a2.shape == sa { a2.clone() } else { super::shape::expand(&a2, &sa.iter().map(|d| *d as i64).collect::<Vec<_>>())? };
        let eb = if b2.shape == sb { b2.clone() } else { super::shape::expand(&b2, &sb.iter().map(|d| *d as i64).collect::<Vec<_>>())? };
        let bn: usize = batch.iter().product();
        let p = mm(ea.fs()?, eb.fs()?.to_vec(), bn, m, k, nn)?;
        Tensor::f32(out_shape.clone(), p)?
    };
    let mut s = y.shape.clone();
    if vb {
        s.pop();
    }
    if va {
        let l = s.len();
        s.remove(l - if vb { 1 } else { 2 });
    }
    y.reshaped(s)
}

pub fn gemm(n: &Node, a: &Tensor, b: &Tensor, c: Option<&Tensor>) -> Result<Tensor> {
    let ta = n.int("transA").unwrap_or(0) != 0;
    let tb = n.int("transB").unwrap_or(0) != 0;
    let (alpha, beta) = (n.float("alpha").unwrap_or(1.0), n.float("beta").unwrap_or(1.0));
    let a = if ta { super::shape::transpose(a, None)? } else { a.clone() };
    let b = if tb { super::shape::transpose(b, None)? } else { b.clone() };
    let mut y = matmul(&a, &b)?;
    if alpha != 1.0 {
        y = Tensor::f32(y.shape.clone(), y.fs()?.iter().map(|v| v * alpha).collect())?;
    }
    if let Some(c) = c {
        let cs = if beta != 1.0 { Tensor::f32(c.shape.clone(), c.fs()?.iter().map(|v| v * beta).collect())? } else { c.clone() };
        y = super::elementwise::arith("Add", &y, &cs)?;
    }
    Ok(y)
}

pub fn max_pool(n: &Node, x: &Tensor) -> Result<Tensor> {
    let [b, c, ih, iw] = x.shape[..] else { return Err(Error::Unsupported("MaxPool of rank other than 4".into())) };
    let k = pair(n.ints("kernel_shape"), 1)?;
    let s = pair(n.ints("strides"), 1)?;
    let d = pair(n.ints("dilations"), 1)?;
    if s.0 == 0 || s.1 == 0 || d.0 == 0 || d.1 == 0 || k.0 == 0 || k.1 == 0 {
        return Err(Error::Run("MaxPool window".into()));
    }
    let p = pads2(n, ih, iw, k, s, d)?;
    let ceil = n.int("ceil_mode").unwrap_or(0) != 0;
    let size = |i: usize, lo: usize, hi: usize, k: usize, s: usize, d: usize| -> usize {
        let span = (i + lo + hi).saturating_sub((k - 1) * d + 1);
        let o = if ceil { span.div_ceil(s) + 1 } else { span / s + 1 };
        // a last window that would start in the end padding is dropped (as PyTorch / ONNX Runtime do)
        if ceil && (o - 1) * s >= i + lo { o - 1 } else { o }
    };
    let (oh, ow) = (size(ih, p[0], p[2], k.0, s.0, d.0), size(iw, p[1], p[3], k.1, s.1, d.1));
    numel(&[b, c, oh, ow])?;
    let v = x.fs()?;
    let mut out = vec![0f32; b * c * oh * ow];
    out.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(plane, o)| {
        let Some(src) = v.get(plane * ih * iw..(plane + 1) * ih * iw) else { return };
        for y in 0..oh {
            for xx in 0..ow {
                let mut m = f32::NEG_INFINITY;
                for ky in 0..k.0 {
                    let sy = (y * s.0 + ky * d.0) as i64 - p[0] as i64;
                    if sy < 0 || sy >= ih as i64 {
                        continue;
                    }
                    for kx in 0..k.1 {
                        let sx = (xx * s.1 + kx * d.1) as i64 - p[1] as i64;
                        if sx >= 0 && sx < iw as i64 {
                            m = m.max(src.get(sy as usize * iw + sx as usize).copied().unwrap_or(f32::NEG_INFINITY));
                        }
                    }
                }
                o[y * ow + xx] = m;
            }
        }
    });
    Tensor::f32(vec![b, c, oh, ow], out)
}

pub fn global_avg_pool(x: &Tensor) -> Result<Tensor> {
    let [b, c, ..] = x.shape[..] else { return Err(Error::Run("GlobalAveragePool rank".into())) };
    let hw: usize = x.shape[2..].iter().product();
    let v = x.fs()?;
    let out: Vec<f32> = (0..b * c).map(|i| v.get(i * hw..(i + 1) * hw).map_or(0.0, |s| s.iter().sum::<f32>() / hw.max(1) as f32)).collect();
    let mut shape = vec![b, c];
    shape.extend(std::iter::repeat_n(1, x.rank() - 2));
    Tensor::f32(shape, out)
}

/// Per-channel `x · scale[c] + bias[c]` over NC… tensors.
fn per_channel(x: &Tensor, f: impl Fn(usize, &[f32], &mut [f32]) + Sync) -> Result<Tensor> {
    let [b, c, ..] = x.shape[..] else { return Err(Error::Run("needs N, C axes".into())) };
    let hw: usize = x.shape[2..].iter().product();
    let v = x.fs()?;
    let mut out = vec![0f32; v.len()];
    out.par_chunks_mut(hw.max(1)).enumerate().for_each(|(i, o)| {
        if let Some(src) = v.get(i * hw..(i + 1) * hw) {
            f(i % c.max(1), src, o);
        }
    });
    let _ = b;
    Tensor::f32(x.shape.clone(), out)
}

pub fn batch_norm(n: &Node, x: &Tensor, scale: &Tensor, bias: &Tensor, mean: &Tensor, var: &Tensor) -> Result<Tensor> {
    let eps = n.float("epsilon").unwrap_or(1e-5);
    let (s, b, m, v) = (scale.fs()?, bias.fs()?, mean.fs()?, var.fs()?);
    per_channel(x, |c, src, o| {
        let (s, b, m, v) =
            (s.get(c).copied().unwrap_or(1.0), b.get(c).copied().unwrap_or(0.0), m.get(c).copied().unwrap_or(0.0), v.get(c).copied().unwrap_or(1.0));
        let k = s / (v + eps).sqrt();
        for (d, x) in o.iter_mut().zip(src) {
            *d = (x - m) * k + b;
        }
    })
}

pub fn instance_norm(n: &Node, x: &Tensor, scale: &Tensor, bias: &Tensor) -> Result<Tensor> {
    let eps = n.float("epsilon").unwrap_or(1e-5);
    let (s, b) = (scale.fs()?, bias.fs()?);
    per_channel(x, |c, src, o| {
        let len = src.len().max(1) as f32;
        let mean = src.iter().sum::<f32>() / len;
        let var = src.iter().map(|v| (v - mean) * (v - mean)).sum::<f32>() / len;
        let k = s.get(c).copied().unwrap_or(1.0) / (var + eps).sqrt();
        let bb = b.get(c).copied().unwrap_or(0.0);
        for (d, x) in o.iter_mut().zip(src) {
            *d = (x - mean) * k + bb;
        }
    })
}

pub fn layer_norm(n: &Node, x: &Tensor, scale: &Tensor, bias: Option<&Tensor>) -> Result<Tensor> {
    let eps = n.float("epsilon").unwrap_or(1e-5);
    let a = axis(n.int("axis").unwrap_or(-1), x.rank())?;
    let inner: usize = x.shape[a..].iter().product();
    let v = x.fs()?;
    let sc = scale.fs()?;
    let bi = bias.map(|b| b.fs()).transpose()?;
    if sc.len() != inner && sc.len() != 1 {
        return Err(Error::Run("LayerNormalization scale".into()));
    }
    let mut out = vec![0f32; v.len()];
    out.par_chunks_mut(inner.max(1)).enumerate().for_each(|(i, o)| {
        if let Some(src) = v.get(i * inner..(i + 1) * inner) {
            let len = inner.max(1) as f32;
            let mean = src.iter().sum::<f32>() / len;
            let var = src.iter().map(|q| (q - mean) * (q - mean)).sum::<f32>() / len;
            let r = 1.0 / (var + eps).sqrt();
            for (j, (d, q)) in o.iter_mut().zip(src).enumerate() {
                let g = if sc.len() == 1 { sc[0] } else { sc.get(j).copied().unwrap_or(1.0) };
                let bb = bi.and_then(|b| if b.len() == 1 { b.first() } else { b.get(j) }).copied().unwrap_or(0.0);
                *d = (q - mean) * r * g + bb;
            }
        }
    });
    Tensor::f32(x.shape.clone(), out)
}

/// Softmax (or LogSoftmax) over one axis.
pub fn softmax(x: &Tensor, ax: i64, log: bool) -> Result<Tensor> {
    let a = axis(ax, x.rank())?;
    let dim = x.shape[a];
    let inner: usize = x.shape[a + 1..].iter().product();
    let outer: usize = x.shape[..a].iter().product();
    let v = x.fs()?;
    let mut out = vec![0f32; v.len()];
    if inner == 1 {
        out.par_chunks_mut(dim.max(1)).zip(v.par_chunks(dim.max(1))).for_each(|(o, src)| {
            let m = src.iter().copied().fold(f32::NEG_INFINITY, f32::max);
            let mut sum = 0f32;
            for (d, q) in o.iter_mut().zip(src) {
                *d = (q - m).exp();
                sum += *d;
            }
            let ls = sum.ln();
            for (d, q) in o.iter_mut().zip(src) {
                *d = if log { q - m - ls } else { *d / sum };
            }
        });
        return Tensor::f32(x.shape.clone(), out);
    }
    out.par_chunks_mut((dim * inner).max(1)).enumerate().for_each(|(o, chunk)| {
        for i in 0..inner {
            let at = |j: usize| v.get((o * dim + j) * inner + i).copied().unwrap_or(0.0);
            let m = (0..dim).map(at).fold(f32::NEG_INFINITY, f32::max);
            let sum: f32 = (0..dim).map(|j| (at(j) - m).exp()).sum();
            for j in 0..dim {
                if let Some(d) = chunk.get_mut(j * inner + i) {
                    *d = if log { at(j) - m - sum.ln() } else { (at(j) - m).exp() / sum };
                }
            }
        }
    });
    let _ = outer;
    Tensor::f32(x.shape.clone(), out)
}

/// ReduceMean / Sum / Max / Min / Prod / L2 over `axes` (all when empty and not a no-op).
pub fn reduce(op: &str, x: &Tensor, axes: Option<Vec<i64>>, keep: bool, noop_empty: bool) -> Result<Tensor> {
    let r = x.rank();
    let ax: Vec<usize> = match axes {
        Some(a) if !a.is_empty() => a.iter().map(|v| axis(*v, r)).collect::<Result<_>>()?,
        _ if noop_empty => return Ok(x.clone()),
        _ => (0..r).collect(),
    };
    let out_keep: Vec<usize> = (0..r).map(|d| if ax.contains(&d) { 1 } else { x.shape[d] }).collect();
    let total = numel(&out_keep)?;
    // the common case: reducing the last axes (LayerNorm's mean, a pooled vector)
    let trailing = !ax.is_empty() && ax.iter().all(|a| *a >= r - ax.len());
    if trailing && matches!(op, "ReduceMean" | "ReduceSum" | "ReduceMax") && matches!(x.buf, crate::tensor::Buf::F(_)) {
        let inner: usize = ax.iter().map(|d| x.shape[*d]).product();
        let v = x.fs()?;
        let acc: Vec<f32> = v
            .par_chunks(inner.max(1))
            .map(|row| match op {
                "ReduceMax" => row.iter().copied().fold(f32::NEG_INFINITY, f32::max),
                "ReduceMean" => row.iter().sum::<f32>() / inner.max(1) as f32,
                _ => row.iter().sum::<f32>(),
            })
            .collect();
        let shape = if keep { out_keep } else { (0..r).filter(|d| !ax.contains(d)).map(|d| x.shape[d]).collect() };
        return Tensor::f32(shape, acc);
    }
    let ost = strides(&out_keep);
    let st = strides(&x.shape);
    let v = x.to_f32s();
    let init = match op {
        "ReduceMax" => f32::NEG_INFINITY,
        "ReduceMin" => f32::INFINITY,
        "ReduceProd" => 1.0,
        _ => 0.0,
    };
    let mut acc = vec![init; total];
    for (f, val) in v.iter().enumerate() {
        let mut rem = f;
        let mut o = 0usize;
        for d in 0..r {
            let c = rem / st[d];
            rem %= st[d];
            if !ax.contains(&d) {
                o += c * ost[d];
            }
        }
        if let Some(a) = acc.get_mut(o) {
            *a = match op {
                "ReduceMax" => a.max(*val),
                "ReduceMin" => a.min(*val),
                "ReduceProd" => *a * val,
                "ReduceL2" | "ReduceSumSquare" => *a + val * val,
                _ => *a + val,
            };
        }
    }
    let count: usize = ax.iter().map(|d| x.shape[*d]).product();
    match op {
        "ReduceMean" => acc.iter_mut().for_each(|a| *a /= count.max(1) as f32),
        "ReduceL2" => acc.iter_mut().for_each(|a| *a = a.sqrt()),
        "ReduceMax" | "ReduceMin" | "ReduceSum" | "ReduceProd" | "ReduceSumSquare" => {}
        _ => return Err(Error::Unsupported(format!("operator {op}"))),
    }
    let shape = if keep { out_keep } else { (0..r).filter(|d| !ax.contains(d)).map(|d| x.shape[d]).collect() };
    match &x.buf {
        crate::tensor::Buf::I(_) => Tensor::i64(shape, acc.iter().map(|a| *a as i64).collect()),
        _ => Tensor::f32(shape, acc),
    }
}

/// Resize (Upsample) of an NCHW tensor: nearest or (bi)linear, from `scales` or `sizes`.
pub fn resize(n: &Node, x: &Tensor, scales: Option<&[f32]>, sizes: Option<&[i64]>) -> Result<Tensor> {
    if x.rank() != 4 {
        return Err(Error::Unsupported(format!("Resize of rank {}", x.rank())));
    }
    let [b, c, ih, iw] = x.shape[..] else { return Err(Error::Run("Resize".into())) };
    let (oh, ow, sh, sw) = match (sizes, scales) {
        (Some(s), _) if s.len() == 4 => {
            let (oh, ow) = (usize::try_from(s[2]).unwrap_or(0), usize::try_from(s[3]).unwrap_or(0));
            (oh, ow, oh as f32 / ih.max(1) as f32, ow as f32 / iw.max(1) as f32)
        }
        (_, Some(s)) if s.len() == 4 => {
            if !(s[2].is_finite() && s[3].is_finite() && s[2] > 0.0 && s[3] > 0.0) {
                return Err(Error::Run("Resize scales".into()));
            }
            ((ih as f32 * s[2]).floor() as usize, (iw as f32 * s[3]).floor() as usize, s[2], s[3])
        }
        _ => return Err(Error::Run("Resize needs scales or sizes for 4 axes".into())),
    };
    numel(&[b, c, oh, ow])?;
    let mode = n.string("mode").unwrap_or("nearest");
    let ctm = n.string("coordinate_transformation_mode").unwrap_or("half_pixel");
    let nearest_mode = n.string("nearest_mode").unwrap_or("round_prefer_floor");
    let src = |o: usize, out: usize, inn: usize, scale: f32| -> f32 {
        let o = o as f32;
        match ctm {
            "align_corners" => {
                if out > 1 {
                    o * (inn as f32 - 1.0) / (out as f32 - 1.0)
                } else {
                    0.0
                }
            }
            "asymmetric" => o / scale,
            "pytorch_half_pixel" => {
                if out > 1 {
                    (o + 0.5) / scale - 0.5
                } else {
                    0.0
                }
            }
            "tf_half_pixel_for_nn" => (o + 0.5) / scale,
            _ => (o + 0.5) / scale - 0.5,
        }
    };
    let v = x.fs()?;
    let mut out = vec![0f32; b * c * oh * ow];
    let (linear, cubic) = match mode {
        "linear" | "bilinear" => (true, false),
        "nearest" => (false, false),
        "cubic" => (false, true),
        other => return Err(Error::Unsupported(format!("Resize mode {other}"))),
    };
    let ca = n.float("cubic_coeff_a").unwrap_or(-0.75);
    let exclude = n.int("exclude_outside").unwrap_or(0) != 0;
    // the four cubic weights for a fractional offset t (Keys, with a = cubic_coeff_a)
    let cubic_w = |t: f32| -> [f32; 4] {
        let w = |x: f32| {
            let x = x.abs();
            if x <= 1.0 {
                ((ca + 2.0) * x - (ca + 3.0)) * x * x + 1.0
            } else if x < 2.0 {
                ((ca * x - 5.0 * ca) * x + 8.0 * ca) * x - 4.0 * ca
            } else {
                0.0
            }
        };
        [w(1.0 + t), w(t), w(1.0 - t), w(2.0 - t)]
    };
    out.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(plane, o)| {
        let Some(p) = v.get(plane * ih * iw..(plane + 1) * ih * iw) else { return };
        let at = |y: usize, x: usize| p.get(y * iw + x).copied().unwrap_or(0.0);
        for y in 0..oh {
            let fy = src(y, oh, ih, sh);
            for xx in 0..ow {
                let fx = src(xx, ow, iw, sw);
                o[y * ow + xx] = if cubic {
                    let (y0, x0) = (fy.floor(), fx.floor());
                    let (wy, wx) = (cubic_w(fy - y0), cubic_w(fx - x0));
                    let (y0, x0) = (y0 as i64, x0 as i64);
                    let mut acc = 0f32;
                    let mut norm = 0f32;
                    for (i, wyi) in wy.iter().enumerate() {
                        let sy = y0 - 1 + i as i64;
                        for (j, wxj) in wx.iter().enumerate() {
                            let sx = x0 - 1 + j as i64;
                            let inside = sy >= 0 && sx >= 0 && sy < ih as i64 && sx < iw as i64;
                            if exclude && !inside {
                                continue;
                            }
                            let (cy, cx) = (sy.clamp(0, ih as i64 - 1) as usize, sx.clamp(0, iw as i64 - 1) as usize);
                            acc += wyi * wxj * at(cy, cx);
                            norm += wyi * wxj;
                        }
                    }
                    if exclude && norm != 0.0 { acc / norm } else { acc }
                } else if linear {
                    let fy = fy.clamp(0.0, (ih - 1) as f32);
                    let fx = fx.clamp(0.0, (iw - 1) as f32);
                    let (y0, x0) = (fy.floor() as usize, fx.floor() as usize);
                    let (y1, x1) = ((y0 + 1).min(ih - 1), (x0 + 1).min(iw - 1));
                    let (dy, dx) = (fy - y0 as f32, fx - x0 as f32);
                    let top = at(y0, x0) * (1.0 - dx) + at(y0, x1) * dx;
                    let bot = at(y1, x0) * (1.0 - dx) + at(y1, x1) * dx;
                    top * (1.0 - dy) + bot * dy
                } else {
                    let pick = |f: f32, n: usize| -> usize {
                        let r = match nearest_mode {
                            "floor" => f.floor(),
                            "ceil" => f.ceil(),
                            "round_prefer_ceil" => (f + 0.5).floor(),
                            _ => (f - 0.5).ceil(),
                        };
                        (r.max(0.0) as usize).min(n - 1)
                    };
                    at(pick(fy, ih), pick(fx, iw))
                };
            }
        }
    });
    Tensor::f32(vec![b, c, oh, ow], out)
}

/// GridSample (bilinear or nearest; zeros, border or reflection padding) of NCHW by an
/// N×Ho×Wo×2 grid of normalized (x, y).
pub fn grid_sample(n: &Node, x: &Tensor, grid: &Tensor) -> Result<Tensor> {
    let [b, c, ih, iw] = x.shape[..] else { return Err(Error::Unsupported("GridSample of rank other than 4".into())) };
    let [gb, oh, ow, two] = grid.shape[..] else { return Err(Error::Run("GridSample grid".into())) };
    if gb != b || two != 2 {
        return Err(Error::Run("GridSample grid shape".into()));
    }
    numel(&[b, c, oh, ow])?;
    let mode = n.string("mode").unwrap_or("bilinear");
    let bilinear = matches!(mode, "bilinear" | "linear");
    if !bilinear && mode != "nearest" {
        return Err(Error::Unsupported(format!("GridSample mode {mode}")));
    }
    let padding = n.string("padding_mode").unwrap_or("zeros");
    let align = n.int("align_corners").unwrap_or(0) != 0;
    let (v, g) = (x.fs()?, grid.fs()?);
    let unnorm = |t: f32, size: usize| if align { (t + 1.0) / 2.0 * (size as f32 - 1.0) } else { ((t + 1.0) * size as f32 - 1.0) / 2.0 };
    let reflect = |t: f32, size: usize| -> f32 {
        let (lo, hi) = if align { (0.0, size as f32 - 1.0) } else { (-0.5, size as f32 - 0.5) };
        let span = hi - lo;
        if span <= 0.0 {
            return 0.0;
        }
        let mut t = (t - lo).abs() % (2.0 * span);
        if t > span {
            t = 2.0 * span - t;
        }
        t + lo
    };
    let mut out = vec![0f32; b * c * oh * ow];
    out.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(plane, o)| {
        let (bi, ci) = (plane / c.max(1), plane % c.max(1));
        let Some(p) = v.get((bi * c + ci) * ih * iw..(bi * c + ci + 1) * ih * iw) else { return };
        let at = |y: i64, x: i64| -> f32 {
            if padding == "border" || padding == "reflection" {
                let (y, x) = (y.clamp(0, ih as i64 - 1), x.clamp(0, iw as i64 - 1));
                return p.get(y as usize * iw + x as usize).copied().unwrap_or(0.0);
            }
            if y < 0 || x < 0 || y >= ih as i64 || x >= iw as i64 { 0.0 } else { p.get(y as usize * iw + x as usize).copied().unwrap_or(0.0) }
        };
        for i in 0..oh * ow {
            let (gx, gy) = (g.get((bi * oh * ow + i) * 2).copied().unwrap_or(0.0), g.get((bi * oh * ow + i) * 2 + 1).copied().unwrap_or(0.0));
            let (mut fx, mut fy) = (unnorm(gx, iw), unnorm(gy, ih));
            if padding == "reflection" {
                fx = reflect(fx, iw);
                fy = reflect(fy, ih);
            }
            if !(fx.is_finite() && fy.is_finite()) {
                o[i] = 0.0;
                continue;
            }
            o[i] = if bilinear {
                let (x0, y0) = (fx.floor() as i64, fy.floor() as i64);
                let (dx, dy) = (fx - x0 as f32, fy - y0 as f32);
                (at(y0, x0) * (1.0 - dx) + at(y0, x0 + 1) * dx) * (1.0 - dy) + (at(y0 + 1, x0) * (1.0 - dx) + at(y0 + 1, x0 + 1) * dx) * dy
            } else {
                at(fy.round_ties_even() as i64, fx.round_ties_even() as i64)
            };
        }
    });
    Tensor::f32(vec![b, c, oh, ow], out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(attrs: &[(&str, crate::proto::Attr)]) -> Node {
        Node {
            op: String::new(),
            domain: String::new(),
            name: String::new(),
            inputs: vec![],
            outputs: vec![],
            attrs: attrs.iter().cloned().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }

    #[test]
    fn conv_with_uneven_pads_and_bias() {
        let x = Tensor::f32(vec![1, 1, 2, 2], vec![1.0, 2.0, 3.0, 4.0]).unwrap();
        let w = Tensor::f32(vec![1, 1, 1, 1], vec![2.0]).unwrap();
        let b = Tensor::f32(vec![1], vec![1.0]).unwrap();
        let n = node(&[("pads", crate::proto::Attr::Ints(vec![1, 0, 0, 0]))]);
        let y = conv(&n, &x, &w, Some(&b)).unwrap();
        assert_eq!(y.shape, vec![1, 1, 3, 2]);
        assert_eq!(y.fs().unwrap(), &[1.0, 1.0, 3.0, 5.0, 7.0, 9.0]);
    }

    #[test]
    fn matmul_broadcasts_and_softmax_sums_to_one() {
        let a = Tensor::f32(vec![2, 1, 2], vec![1.0, 2.0, 3.0, 4.0]).unwrap();
        let b = Tensor::f32(vec![2, 1], vec![10.0, 1.0]).unwrap();
        assert_eq!(matmul(&a, &b).unwrap().fs().unwrap(), &[12.0, 34.0]);
        let s = softmax(&Tensor::f32(vec![3], vec![1.0, 2.0, 3.0]).unwrap(), -1, false).unwrap();
        assert!((s.fs().unwrap().iter().sum::<f32>() - 1.0).abs() < 1e-6);
    }

    #[test]
    fn resize_bilinear_half_pixel() {
        let x = Tensor::f32(vec![1, 1, 1, 2], vec![0.0, 1.0]).unwrap();
        let n = node(&[("mode", crate::proto::Attr::Str("linear".into()))]);
        let y = resize(&n, &x, Some(&[1.0, 1.0, 1.0, 2.0]), None).unwrap();
        assert_eq!(y.fs().unwrap(), &[0.0, 0.25, 0.75, 1.0]);
    }
}
