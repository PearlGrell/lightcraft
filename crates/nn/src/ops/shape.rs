//! Shape and indexing operators. Most build, for each output element, the index of the input
//! element it copies, and hand that to [`Tensor::take`]: one checked path for every element type.

use rayon::prelude::*;

use crate::tensor::{Buf, Tensor, axis, broadcast_shape, numel, strides};
use crate::{Error, Result};

/// The flat input index for each output element of `x` read with `in_strides` along `out` (row-major).
fn walk(out: &[usize], in_strides: &[usize], base: usize) -> Result<Vec<usize>> {
    let total = numel(out)?;
    let n = out.len();
    let mut idx = Vec::with_capacity(total);
    let mut coord = vec![0usize; n];
    let mut at = base;
    for _ in 0..total {
        idx.push(at);
        for d in (0..n).rev() {
            coord[d] += 1;
            at = at.wrapping_add(in_strides[d]);
            if coord[d] < out[d] {
                break;
            }
            at = at.wrapping_sub(in_strides[d].wrapping_mul(coord[d]));
            coord[d] = 0;
        }
    }
    Ok(idx)
}

pub fn shape_of(x: &Tensor, start: Option<i64>, end: Option<i64>) -> Result<Tensor> {
    let r = x.rank() as i64;
    let clampi = |v: i64| (if v < 0 { v + r } else { v }).clamp(0, r) as usize;
    let s = start.map_or(0, clampi);
    let e = end.map_or(x.rank(), clampi).max(s);
    let dims: Vec<i64> = x.shape.get(s..e).unwrap_or(&[]).iter().map(|d| *d as i64).collect();
    Tensor::i64(vec![dims.len()], dims)
}

pub fn reshape(x: &Tensor, spec: &[i64], allow_zero: bool) -> Result<Tensor> {
    let mut out = Vec::with_capacity(spec.len());
    let mut infer = None;
    for (i, &d) in spec.iter().enumerate() {
        match d {
            -1 if infer.is_none() => {
                infer = Some(i);
                out.push(1);
            }
            0 if !allow_zero => out.push(*x.shape.get(i).ok_or_else(|| Error::Run("Reshape copies an axis the input lacks".into()))?),
            d if d >= 0 => out.push(d as usize),
            _ => return Err(Error::Run(format!("Reshape to {spec:?}"))),
        }
    }
    if let Some(i) = infer {
        let known = out
            .iter()
            .enumerate()
            .filter(|(j, _)| *j != i)
            .try_fold(1usize, |a, (_, d)| a.checked_mul(*d))
            .ok_or_else(|| Error::Run("Reshape overflows".into()))?;
        if known == 0 || !x.len().is_multiple_of(known) {
            return Err(Error::Run(format!("Reshape of {:?} to {spec:?}", x.shape)));
        }
        out[i] = x.len() / known;
    }
    x.reshaped(out)
}

pub fn unsqueeze(x: &Tensor, axes: &[i64]) -> Result<Tensor> {
    let r = x.rank() + axes.len();
    let mut ax: Vec<usize> = axes.iter().map(|a| axis(*a, r)).collect::<Result<_>>()?;
    ax.sort_unstable();
    let mut out = x.shape.clone();
    for a in ax {
        if a > out.len() {
            return Err(Error::Run("Unsqueeze axis out of range".into()));
        }
        out.insert(a, 1);
    }
    x.reshaped(out)
}

pub fn squeeze(x: &Tensor, axes: Option<&[i64]>) -> Result<Tensor> {
    let out: Vec<usize> = match axes {
        None => x.shape.iter().copied().filter(|d| *d != 1).collect(),
        Some(axes) => {
            let ax: Vec<usize> = axes.iter().map(|a| axis(*a, x.rank())).collect::<Result<_>>()?;
            x.shape.iter().enumerate().filter(|(i, _)| !ax.contains(i)).map(|(_, d)| *d).collect()
        }
    };
    x.reshaped(out)
}

pub fn flatten(x: &Tensor, ax: i64) -> Result<Tensor> {
    let a = if ax < 0 { (ax + x.rank() as i64).max(0) as usize } else { (ax as usize).min(x.rank()) };
    let outer: usize = x.shape.iter().take(a).product();
    let inner: usize = x.shape.iter().skip(a).product();
    x.reshaped(vec![outer, inner])
}

pub fn transpose(x: &Tensor, perm: Option<&[i64]>) -> Result<Tensor> {
    let r = x.rank();
    let perm: Vec<usize> = match perm {
        Some(p) => p.iter().map(|a| axis(*a, r)).collect::<Result<_>>()?,
        None => (0..r).rev().collect(),
    };
    let mut seen = vec![false; r];
    for &p in &perm {
        if p >= r || std::mem::replace(&mut seen[p], true) {
            return Err(Error::Run(format!("Transpose perm {perm:?}")));
        }
    }
    if perm.len() != r {
        return Err(Error::Run(format!("Transpose perm {perm:?} for rank {r}")));
    }
    let st = strides(&x.shape);
    let out: Vec<usize> = perm.iter().map(|&p| x.shape[p]).collect();
    let ins: Vec<usize> = perm.iter().map(|&p| st[p]).collect();
    x.take(out.clone(), &walk(&out, &ins, 0)?)
}

pub fn concat(xs: &[&Tensor], ax: i64) -> Result<Tensor> {
    let first = xs.iter().find(|t| !t.is_empty()).or(xs.first()).ok_or_else(|| Error::Run("Concat of nothing".into()))?;
    let r = first.rank();
    let a = axis(ax, r)?;
    let parts: Vec<&&Tensor> = xs.iter().filter(|t| !(t.is_empty() && t.rank() == r && t.shape.get(a) == Some(&0))).collect();
    let mut out = first.shape.clone();
    out[a] = 0;
    for t in &parts {
        if t.rank() != r || t.shape.iter().enumerate().any(|(i, d)| i != a && *d != first.shape[i]) {
            return Err(Error::Run(format!("Concat of {:?} and {:?}", first.shape, t.shape)));
        }
        out[a] += t.shape[a];
    }
    let outer: usize = out.iter().take(a).product();
    let inner: usize = out.iter().skip(a + 1).product();
    numel(&out)?;
    macro_rules! join {
        ($get:ident, $mk:path) => {{
            let mut v = Vec::with_capacity(out.iter().product());
            for o in 0..outer {
                for t in &parts {
                    let src = t.$get()?;
                    let chunk = t.shape[a] * inner;
                    v.extend_from_slice(src.get(o * chunk..(o + 1) * chunk).ok_or_else(|| Error::Run("Concat out of range".into()))?);
                }
            }
            $mk(out, v)
        }};
    }
    match &first.buf {
        Buf::F(_) => join!(fs, Tensor::f32),
        Buf::I(_) => join!(is, Tensor::i64),
        Buf::B(_) => join!(bs, Tensor::bool),
    }
}

/// Slice with ONNX's clamping rules (negative indices count from the end; steps may be negative).
pub fn slice(x: &Tensor, starts: &[i64], ends: &[i64], axes: Option<&[i64]>, steps: Option<&[i64]>) -> Result<Tensor> {
    let r = x.rank();
    let mut lo = vec![0i64; r];
    let mut step = vec![1i64; r];
    let mut count: Vec<usize> = x.shape.clone();
    for i in 0..starts.len() {
        let a = match axes {
            Some(ax) => axis(*ax.get(i).ok_or_else(|| Error::Run("Slice axes".into()))?, r)?,
            None => i,
        };
        let dim = *x.shape.get(a).ok_or_else(|| Error::Run("Slice axis".into()))? as i64;
        let s = steps.and_then(|s| s.get(i)).copied().unwrap_or(1);
        if s == 0 {
            return Err(Error::Run("Slice step 0".into()));
        }
        let fix = |v: i64| if v < 0 { v.saturating_add(dim) } else { v };
        let mut b = fix(starts[i]);
        let mut e = fix(*ends.get(i).ok_or_else(|| Error::Run("Slice ends".into()))?);
        if s > 0 {
            b = b.clamp(0, dim);
            e = e.clamp(0, dim);
        } else {
            b = b.clamp(0, dim - 1);
            e = e.clamp(-1, dim - 1);
        }
        let n = if s > 0 { (e - b + s - 1).div_euclid(s) } else { (b - e - s - 1).div_euclid(-s) };
        lo[a] = b;
        step[a] = s;
        count[a] = n.max(0) as usize;
    }
    let st = strides(&x.shape);
    let base: i64 = lo.iter().zip(&st).map(|(l, s)| l * *s as i64).sum();
    let ins: Vec<usize> = step.iter().zip(&st).map(|(k, s)| (k * *s as i64) as usize).collect();
    if count.contains(&0) {
        return x.take(count, &[]);
    }
    x.take(count.clone(), &walk(&count, &ins, base as usize)?)
}

pub fn split(x: &Tensor, ax: i64, sizes: Option<Vec<i64>>, outputs: usize) -> Result<Vec<Tensor>> {
    let a = axis(ax, x.rank())?;
    let dim = x.shape[a];
    let sizes: Vec<usize> = match sizes {
        Some(s) => s.iter().map(|v| usize::try_from(*v).map_err(|_| Error::Run("Split size".into()))).collect::<Result<_>>()?,
        None => {
            let n = outputs.max(1);
            let each = dim.div_ceil(n);
            (0..n).map(|i| each.min(dim.saturating_sub(i * each))).collect()
        }
    };
    if sizes.iter().sum::<usize>() != dim {
        return Err(Error::Run(format!("Split {sizes:?} of {dim}")));
    }
    let mut start = 0i64;
    let mut out = Vec::new();
    for s in sizes {
        out.push(slice(x, &[start], &[start + s as i64], Some(&[a as i64]), None)?);
        start += s as i64;
    }
    Ok(out)
}

pub fn gather(x: &Tensor, idx: &Tensor, ax: i64) -> Result<Tensor> {
    let a = axis(ax, x.rank())?;
    let dim = x.shape[a] as i64;
    let ids: Vec<usize> = idx
        .to_i64s()
        .iter()
        .map(|&i| {
            let j = if i < 0 { i + dim } else { i };
            if (0..dim).contains(&j) { Ok(j as usize) } else { Err(Error::Run(format!("Gather index {i} out of range {dim}"))) }
        })
        .collect::<Result<_>>()?;
    let outer: usize = x.shape.iter().take(a).product();
    let inner: usize = x.shape.iter().skip(a + 1).product();
    let mut out: Vec<usize> = x.shape[..a].to_vec();
    out.extend_from_slice(&idx.shape);
    out.extend_from_slice(&x.shape[a + 1..]);
    numel(&out)?;
    let mut map = Vec::with_capacity(outer * ids.len() * inner);
    for o in 0..outer {
        for &i in &ids {
            let base = (o * x.shape[a] + i) * inner;
            map.extend(base..base + inner);
        }
    }
    x.take(out, &map)
}

pub fn gather_elements(x: &Tensor, idx: &Tensor, ax: i64) -> Result<Tensor> {
    let a = axis(ax, x.rank())?;
    if idx.rank() != x.rank() {
        return Err(Error::Run("GatherElements ranks".into()));
    }
    let dim = x.shape[a] as i64;
    let st = strides(&x.shape);
    let ids = idx.to_i64s();
    let ist = strides(&idx.shape);
    let mut map = Vec::with_capacity(ids.len());
    for (flat, &i) in ids.iter().enumerate() {
        let j = if i < 0 { i + dim } else { i };
        if !(0..dim).contains(&j) {
            return Err(Error::Run("GatherElements index out of range".into()));
        }
        let mut at = 0usize;
        let mut rem = flat;
        for d in 0..x.rank() {
            let c = rem / ist[d];
            rem %= ist[d];
            let c = if d == a { j as usize } else { c };
            if c >= x.shape[d] {
                return Err(Error::Run("GatherElements index out of range".into()));
            }
            at += c * st[d];
        }
        map.push(at);
    }
    x.take(idx.shape.clone(), &map)
}

pub fn expand(x: &Tensor, shape: &[i64]) -> Result<Tensor> {
    let target: Vec<usize> = shape.iter().map(|d| usize::try_from(*d).map_err(|_| Error::Run("Expand shape".into()))).collect::<Result<_>>()?;
    let out = broadcast_shape(&x.shape, &target)?;
    numel(&out)?;
    x.take(out.clone(), &crate::tensor::broadcast_index(&x.shape, &out))
}

pub fn tile(x: &Tensor, reps: &[i64]) -> Result<Tensor> {
    if reps.len() != x.rank() {
        return Err(Error::Run("Tile repeats".into()));
    }
    let out: Vec<usize> = x.shape.iter().zip(reps).map(|(d, r)| d.checked_mul(usize::try_from(*r).unwrap_or(0)).unwrap_or(usize::MAX)).collect();
    let total = numel(&out)?;
    let st = strides(&x.shape);
    let ost = strides(&out);
    let map: Vec<usize> = (0..total)
        .map(|f| {
            let mut rem = f;
            let mut at = 0;
            for d in 0..out.len() {
                let c = rem / ost[d];
                rem %= ost[d];
                at += (c % x.shape[d].max(1)) * st[d];
            }
            at
        })
        .collect();
    x.take(out, &map)
}

pub fn range(start: &Tensor, limit: &Tensor, delta: &Tensor) -> Result<Tensor> {
    let (s, l, d) = (start.scalar()?, limit.scalar()?, delta.scalar()?);
    if d == 0.0 || !(s.is_finite() && l.is_finite() && d.is_finite()) {
        return Err(Error::Run("Range step".into()));
    }
    let n = ((l - s) / d).ceil().max(0.0);
    if n > crate::tensor::MAX_ELEMS as f64 {
        return Err(Error::Run("Range too long".into()));
    }
    let n = n as usize;
    match &start.buf {
        Buf::F(_) => Tensor::f32(vec![n], (0..n).map(|i| (s + i as f64 * d) as f32).collect()),
        _ => Tensor::i64(vec![n], (0..n).map(|i| (s + i as f64 * d) as i64).collect()),
    }
}

pub fn constant_of_shape(shape: &Tensor, value: Option<&Tensor>) -> Result<Tensor> {
    let dims: Vec<usize> =
        shape.to_i64s().iter().map(|d| usize::try_from(*d).map_err(|_| Error::Run("ConstantOfShape".into()))).collect::<Result<_>>()?;
    let n = numel(&dims)?;
    match value.map(|v| &v.buf) {
        None => Tensor::f32(dims, vec![0.0; n]),
        Some(Buf::F(v)) => Tensor::f32(dims, vec![*v.first().unwrap_or(&0.0); n]),
        Some(Buf::I(v)) => Tensor::i64(dims, vec![*v.first().unwrap_or(&0); n]),
        Some(Buf::B(v)) => Tensor::bool(dims, vec![*v.first().unwrap_or(&false); n]),
    }
}

/// Pad: `pads` = [begin per axis…, end per axis…] (negative crops); constant, reflect or edge.
pub fn pad(x: &Tensor, pads: &[i64], mode: &str, value: f64, axes: Option<&[i64]>) -> Result<Tensor> {
    let r = x.rank();
    let ax: Vec<usize> = match axes {
        Some(a) => a.iter().map(|v| axis(*v, r)).collect::<Result<_>>()?,
        None => (0..r).collect(),
    };
    if pads.len() != 2 * ax.len() {
        return Err(Error::Run(format!("Pad {pads:?} for rank {r}")));
    }
    let (mut before, mut after) = (vec![0i64; r], vec![0i64; r]);
    for (i, &a) in ax.iter().enumerate() {
        before[a] = pads[i];
        after[a] = pads[i + ax.len()];
    }
    let out: Vec<usize> = (0..r).map(|d| (x.shape[d] as i64 + before[d] + after[d]).max(0) as usize).collect();
    let total = numel(&out)?;
    // the common case: an image's two spatial axes padded (no crop), the rest untouched
    if r == 4
        && before[..2] == [0, 0]
        && after[..2] == [0, 0]
        && before[2..].iter().chain(&after[2..]).all(|v| *v >= 0)
        && let Buf::F(v) = &x.buf
    {
        {
            let (ih, iw, oh, ow) = (x.shape[2] as i64, x.shape[3] as i64, out[2], out[3]);
            let src = |c: i64, n: i64| -> Option<i64> {
                if (0..n).contains(&c) {
                    return Some(c);
                }
                match mode {
                    "edge" => Some(c.clamp(0, n - 1)),
                    "reflect" if n > 1 => {
                        let p = 2 * (n - 1);
                        let m = c.rem_euclid(p);
                        Some(if m < n { m } else { p - m })
                    }
                    "reflect" => Some(0),
                    _ => None,
                }
            };
            let cols: Vec<Option<i64>> = (0..ow as i64).map(|x| src(x - before[3], iw)).collect();
            let mut o = vec![value as f32; total];
            o.par_chunks_mut((oh * ow).max(1)).enumerate().for_each(|(plane, dst)| {
                let Some(p) = v.get(plane * (ih * iw) as usize..(plane + 1) * (ih * iw) as usize) else { return };
                for y in 0..oh {
                    let Some(sy) = src(y as i64 - before[2], ih) else { continue };
                    for (xx, sx) in cols.iter().enumerate() {
                        if let Some(sx) = sx {
                            dst[y * ow + xx] = p.get((sy * iw + sx) as usize).copied().unwrap_or(0.0);
                        }
                    }
                }
            });
            return Tensor::f32(out, o);
        }
    }
    let st = strides(&x.shape);
    let ost = strides(&out);
    // per output element: the source index, or None for the constant
    let src = |f: usize| -> Option<usize> {
        let mut rem = f;
        let mut at = 0usize;
        for d in 0..r {
            let c = (rem / ost[d]) as i64 - before[d];
            rem %= ost[d];
            let n = x.shape[d] as i64;
            let c = if (0..n).contains(&c) {
                c
            } else {
                match mode {
                    "edge" => c.clamp(0, n - 1),
                    "reflect" if n > 1 => {
                        let p = 2 * (n - 1);
                        let m = c.rem_euclid(p);
                        if m < n { m } else { p - m }
                    }
                    "reflect" => 0,
                    _ => return None,
                }
            };
            at += c as usize * st[d];
        }
        Some(at)
    };
    match &x.buf {
        Buf::F(v) => Tensor::f32(out, (0..total).map(|f| src(f).and_then(|i| v.get(i).copied()).unwrap_or(value as f32)).collect()),
        Buf::I(v) => Tensor::i64(out, (0..total).map(|f| src(f).and_then(|i| v.get(i).copied()).unwrap_or(value as i64)).collect()),
        Buf::B(v) => Tensor::bool(out, (0..total).map(|f| src(f).and_then(|i| v.get(i).copied()).unwrap_or(value != 0.0)).collect()),
    }
}

/// DepthToSpace (PixelShuffle): mode `DCR` (default) or `CRD`.
pub fn depth_to_space(x: &Tensor, bs: usize, crd: bool) -> Result<Tensor> {
    let [n, c, h, w] = x.shape[..] else { return Err(Error::Run("DepthToSpace needs NCHW".into())) };
    if bs == 0 || c % (bs * bs) != 0 {
        return Err(Error::Run("DepthToSpace block size".into()));
    }
    let oc = c / (bs * bs);
    let t = if crd { x.reshaped(vec![n, oc, bs, bs, h, w])? } else { x.reshaped(vec![n, bs, bs, oc, h, w])? };
    let p = if crd { transpose(&t, Some(&[0, 1, 4, 2, 5, 3]))? } else { transpose(&t, Some(&[0, 3, 4, 1, 5, 2]))? };
    p.reshaped(vec![n, oc, h * bs, w * bs])
}

pub fn scatter_nd(data: &Tensor, idx: &Tensor, upd: &Tensor) -> Result<Tensor> {
    let k = *idx.shape.last().ok_or_else(|| Error::Run("ScatterND indices".into()))?;
    if k > data.rank() {
        return Err(Error::Run("ScatterND index depth".into()));
    }
    let st = strides(&data.shape);
    let slice: usize = data.shape[k..].iter().product();
    let ids = idx.to_i64s();
    let mut map: Vec<Option<usize>> = vec![None; data.len()];
    for (u, tuple) in ids.chunks(k.max(1)).enumerate() {
        let mut at = 0usize;
        for (d, &i) in tuple.iter().enumerate().take(k) {
            let n = data.shape[d] as i64;
            let j = if i < 0 { i + n } else { i };
            if !(0..n).contains(&j) {
                return Err(Error::Run("ScatterND index out of range".into()));
            }
            at += j as usize * st[d];
        }
        for e in 0..slice {
            *map.get_mut(at + e).ok_or_else(|| Error::Run("ScatterND out of range".into()))? = Some(u * slice + e);
        }
    }
    macro_rules! scatter {
        ($d:expr, $u:expr, $mk:path) => {{
            let v: Option<Vec<_>> = map
                .iter()
                .enumerate()
                .map(|(i, m)| match m {
                    Some(j) => $u.get(*j).copied(),
                    None => $d.get(i).copied(),
                })
                .collect();
            $mk(data.shape.clone(), v.ok_or_else(|| Error::Run("ScatterND updates".into()))?)
        }};
    }
    match (&data.buf, &upd.buf) {
        (Buf::F(d), Buf::F(u)) => scatter!(d, u, Tensor::f32),
        (Buf::I(d), Buf::I(u)) => scatter!(d, u, Tensor::i64),
        (Buf::B(d), Buf::B(u)) => scatter!(d, u, Tensor::bool),
        _ => Err(Error::Run("ScatterND types".into())),
    }
}

pub fn one_hot(idx: &Tensor, depth: &Tensor, values: &Tensor, ax: i64) -> Result<Tensor> {
    let d = depth.scalar()?;
    if !(1.0..=1e7).contains(&d) {
        return Err(Error::Run("OneHot depth".into()));
    }
    let d = d as usize;
    let vals = values.to_f32s();
    let (off, on) =
        (*vals.first().ok_or_else(|| Error::Run("OneHot values".into()))?, *vals.get(1).ok_or_else(|| Error::Run("OneHot values".into()))?);
    let r = idx.rank() + 1;
    let a = axis(ax, r)?;
    let mut out = idx.shape.clone();
    out.insert(a, d);
    let total = numel(&out)?;
    let ids = idx.to_i64s();
    let inner: usize = idx.shape[a..].iter().product();
    let mut v = vec![off; total];
    for (f, &i) in ids.iter().enumerate() {
        let j = if i < 0 { i + d as i64 } else { i };
        if (0..d as i64).contains(&j) {
            let (o, rest) = (f / inner.max(1), f % inner.max(1));
            if let Some(slot) = v.get_mut((o * d + j as usize) * inner + rest) {
                *slot = on;
            }
        }
    }
    match &values.buf {
        Buf::F(_) => Tensor::f32(out, v),
        _ => Tensor::i64(out, v.iter().map(|x| *x as i64).collect()),
    }
}

/// TopK along an axis → (values, indices).
pub fn topk(x: &Tensor, k: usize, ax: i64, largest: bool) -> Result<(Tensor, Tensor)> {
    let a = axis(ax, x.rank())?;
    let dim = x.shape[a];
    if k > dim {
        return Err(Error::Run(format!("TopK {k} of {dim}")));
    }
    let outer: usize = x.shape[..a].iter().product();
    let inner: usize = x.shape[a + 1..].iter().product();
    let v = x.to_f32s();
    let mut out = x.shape.clone();
    out[a] = k;
    let total = numel(&out)?;
    let (mut vals, mut ids) = (vec![0f32; total], vec![0i64; total]);
    let mut order: Vec<usize> = Vec::with_capacity(dim);
    for o in 0..outer {
        for i in 0..inner {
            order.clear();
            order.extend(0..dim);
            let at = |j: usize| v.get((o * dim + j) * inner + i).copied().unwrap_or(f32::NAN);
            order.sort_by(|p, q| {
                let c = at(*p).total_cmp(&at(*q));
                if largest { c.reverse().then(p.cmp(q)) } else { c.then(p.cmp(q)) }
            });
            for (r, &j) in order.iter().take(k).enumerate() {
                let slot = (o * k + r) * inner + i;
                vals[slot] = at(j);
                ids[slot] = j as i64;
            }
        }
    }
    let values = match &x.buf {
        Buf::I(_) => Tensor::i64(out.clone(), vals.iter().map(|x| *x as i64).collect())?,
        _ => Tensor::f32(out.clone(), vals)?,
    };
    Ok((values, Tensor::i64(out, ids)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(shape: &[usize]) -> Tensor {
        let n: usize = shape.iter().product();
        Tensor::f32(shape.to_vec(), (0..n).map(|i| i as f32).collect()).unwrap()
    }

    #[test]
    fn transpose_slice_concat_gather() {
        let x = t(&[2, 3]);
        assert_eq!(transpose(&x, None).unwrap().fs().unwrap(), &[0.0, 3.0, 1.0, 4.0, 2.0, 5.0]);
        assert_eq!(slice(&x, &[1], &[i64::MAX], Some(&[1]), None).unwrap().fs().unwrap(), &[1.0, 2.0, 4.0, 5.0]);
        assert_eq!(slice(&x, &[-1], &[i64::MIN], Some(&[1]), Some(&[-1])).unwrap().fs().unwrap(), &[2.0, 1.0, 0.0, 5.0, 4.0, 3.0]);
        assert_eq!(concat(&[&x, &x], 0).unwrap().shape, vec![4, 3]);
        assert_eq!(concat(&[&x, &x], 1).unwrap().fs().unwrap(), &[0.0, 1.0, 2.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 3.0, 4.0, 5.0]);
        let i = Tensor::i64(vec![2], vec![-1, 0]).unwrap();
        assert_eq!(gather(&x, &i, 1).unwrap().fs().unwrap(), &[2.0, 0.0, 5.0, 3.0]);
        assert!(gather(&x, &Tensor::i64(vec![1], vec![9]).unwrap(), 1).is_err());
    }

    #[test]
    fn reshape_pad_topk_depth_to_space() {
        let x = t(&[2, 3]);
        assert_eq!(reshape(&x, &[-1], false).unwrap().shape, vec![6]);
        assert!(reshape(&x, &[3, 0], false).is_err());
        let p = pad(&t(&[3]), &[2, 1], "reflect", 0.0, None).unwrap();
        assert_eq!(p.fs().unwrap(), &[2.0, 1.0, 0.0, 1.0, 2.0, 1.0]);
        let (v, i) = topk(&Tensor::f32(vec![4], vec![1.0, 9.0, 3.0, 9.0]).unwrap(), 2, 0, true).unwrap();
        assert_eq!((v.fs().unwrap(), i.is().unwrap()), (&[9.0, 9.0][..], &[1, 3][..]));
        let d = depth_to_space(&t(&[1, 4, 1, 1]), 2, true).unwrap();
        assert_eq!((d.shape.clone(), d.fs().unwrap().to_vec()), (vec![1, 1, 2, 2], vec![0.0, 1.0, 2.0, 3.0]));
    }
}
