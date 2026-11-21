//! The runner's values: an N-dimensional array of f32, i64 or bool, row-major, with its buffer
//! shared (cloning a tensor never copies its data).

use std::sync::Arc;

use crate::proto::{Data, TensorData};
use crate::{Error, Result};

/// The largest tensor the runner makes (≈ 2 GB of f32): a model whose shapes ask for more is
/// stopped with an error instead of running the phone out of memory.
pub const MAX_ELEMS: usize = 512 * 1024 * 1024;

#[derive(Clone, Debug)]
pub enum Buf {
    F(Arc<Vec<f32>>),
    I(Arc<Vec<i64>>),
    B(Arc<Vec<bool>>),
}

#[derive(Clone, Debug)]
pub struct Tensor {
    pub shape: Vec<usize>,
    pub buf: Buf,
}

/// Elements in a tensor of `shape`, or an error past [`MAX_ELEMS`].
pub fn numel(shape: &[usize]) -> Result<usize> {
    let n = shape.iter().try_fold(1usize, |a, &d| a.checked_mul(d)).ok_or_else(|| Error::Run(format!("shape {shape:?} is too large")))?;
    if n > MAX_ELEMS {
        return Err(Error::Run(format!("shape {shape:?} is too large")));
    }
    Ok(n)
}

/// Row-major strides of `shape`.
pub fn strides(shape: &[usize]) -> Vec<usize> {
    let mut s = vec![1usize; shape.len()];
    for i in (0..shape.len().saturating_sub(1)).rev() {
        s[i] = s[i + 1].saturating_mul(shape[i + 1]);
    }
    s
}

/// The shape two shapes broadcast to (NumPy rules).
pub fn broadcast_shape(a: &[usize], b: &[usize]) -> Result<Vec<usize>> {
    let n = a.len().max(b.len());
    let mut out = vec![0usize; n];
    for (i, o) in out.iter_mut().enumerate() {
        let da = if i + a.len() >= n { a[i + a.len() - n] } else { 1 };
        let db = if i + b.len() >= n { b[i + b.len() - n] } else { 1 };
        *o = match (da, db) {
            (x, y) if x == y => x,
            (1, y) => y,
            (x, 1) => x,
            _ => return Err(Error::Run(format!("shapes {a:?} and {b:?} don't broadcast"))),
        };
    }
    Ok(out)
}

/// For each element of `out` (row-major), the index into a tensor of `shape` broadcast to it.
pub fn broadcast_index(shape: &[usize], out: &[usize]) -> Vec<usize> {
    let n = out.len();
    let total: usize = out.iter().product();
    if shape == out {
        return (0..total).collect();
    }
    let src = strides(shape);
    // the source stride for each output axis (0 where the source has size 1 or no axis)
    let mut st = vec![0usize; n];
    for i in 0..n {
        if i + shape.len() >= n {
            let j = i + shape.len() - n;
            st[i] = if shape[j] == 1 { 0 } else { src[j] };
        }
    }
    let mut idx = vec![0usize; total];
    let mut coord = vec![0usize; n];
    let mut at = 0usize;
    for slot in idx.iter_mut() {
        *slot = at;
        // advance the multi-index, keeping `at` in step
        for d in (0..n).rev() {
            coord[d] += 1;
            at += st[d];
            if coord[d] < out[d] {
                break;
            }
            at -= st[d] * coord[d];
            coord[d] = 0;
        }
    }
    idx
}

impl Tensor {
    pub fn f32(shape: Vec<usize>, data: Vec<f32>) -> Result<Tensor> {
        Self::check(&shape, data.len())?;
        Ok(Tensor { shape, buf: Buf::F(Arc::new(data)) })
    }

    pub fn i64(shape: Vec<usize>, data: Vec<i64>) -> Result<Tensor> {
        Self::check(&shape, data.len())?;
        Ok(Tensor { shape, buf: Buf::I(Arc::new(data)) })
    }

    pub fn bool(shape: Vec<usize>, data: Vec<bool>) -> Result<Tensor> {
        Self::check(&shape, data.len())?;
        Ok(Tensor { shape, buf: Buf::B(Arc::new(data)) })
    }

    fn check(shape: &[usize], len: usize) -> Result<()> {
        let n = numel(shape)?;
        if n != len {
            return Err(Error::Run(format!("{len} values for shape {shape:?}")));
        }
        Ok(())
    }

    pub fn from_data(t: &TensorData) -> Result<Tensor> {
        match &t.data {
            Data::F32(v) => Tensor::f32(t.dims.clone(), v.clone()),
            Data::I64(v) => Tensor::i64(t.dims.clone(), v.clone()),
            Data::Bool(v) => Tensor::bool(t.dims.clone(), v.clone()),
        }
    }

    pub fn len(&self) -> usize {
        match &self.buf {
            Buf::F(v) => v.len(),
            Buf::I(v) => v.len(),
            Buf::B(v) => v.len(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn rank(&self) -> usize {
        self.shape.len()
    }

    /// The f32 values, or an error naming what the operator expected.
    pub fn fs(&self) -> Result<&[f32]> {
        match &self.buf {
            Buf::F(v) => Ok(v),
            _ => Err(Error::Run("expected a float tensor".into())),
        }
    }

    pub fn is(&self) -> Result<&[i64]> {
        match &self.buf {
            Buf::I(v) => Ok(v),
            _ => Err(Error::Run("expected an integer tensor".into())),
        }
    }

    pub fn bs(&self) -> Result<&[bool]> {
        match &self.buf {
            Buf::B(v) => Ok(v),
            _ => Err(Error::Run("expected a boolean tensor".into())),
        }
    }

    /// The values as i64, whatever the type (shape arithmetic reads float or bool tensors too).
    pub fn to_i64s(&self) -> Vec<i64> {
        match &self.buf {
            Buf::F(v) => v.iter().map(|x| *x as i64).collect(),
            Buf::I(v) => v.to_vec(),
            Buf::B(v) => v.iter().map(|x| i64::from(*x)).collect(),
        }
    }

    pub fn to_f32s(&self) -> Vec<f32> {
        match &self.buf {
            Buf::F(v) => v.to_vec(),
            Buf::I(v) => v.iter().map(|x| *x as f32).collect(),
            Buf::B(v) => v.iter().map(|x| f32::from(u8::from(*x))).collect(),
        }
    }

    /// The single value of a one-element tensor, as f64.
    pub fn scalar(&self) -> Result<f64> {
        if self.len() != 1 {
            return Err(Error::Run(format!("expected one value, got shape {:?}", self.shape)));
        }
        Ok(match &self.buf {
            Buf::F(v) => f64::from(v[0]),
            Buf::I(v) => v[0] as f64,
            Buf::B(v) => f64::from(u8::from(v[0])),
        })
    }

    /// The same data under another shape of the same size.
    pub fn reshaped(&self, shape: Vec<usize>) -> Result<Tensor> {
        Self::check(&shape, self.len())?;
        Ok(Tensor { shape, buf: self.buf.clone() })
    }

    /// A new tensor of the same type, taking element `idx[i]` of this one for each output element.
    pub fn take(&self, shape: Vec<usize>, idx: &[usize]) -> Result<Tensor> {
        let get = |len: usize, i: usize| if i < len { Ok(i) } else { Err(Error::Run("index out of range".into())) };
        match &self.buf {
            Buf::F(v) => Tensor::f32(shape, idx.iter().map(|&i| get(v.len(), i).map(|i| v[i])).collect::<Result<_>>()?),
            Buf::I(v) => Tensor::i64(shape, idx.iter().map(|&i| get(v.len(), i).map(|i| v[i])).collect::<Result<_>>()?),
            Buf::B(v) => Tensor::bool(shape, idx.iter().map(|&i| get(v.len(), i).map(|i| v[i])).collect::<Result<_>>()?),
        }
    }

    #[cfg(not(target_arch = "wasm32"))]
    pub fn to_candle(&self) -> Result<candle_core::Tensor> {
        let dev = candle_core::Device::Cpu;
        Ok(match &self.buf {
            Buf::F(v) => candle_core::Tensor::from_slice(v.as_slice(), self.shape.as_slice(), &dev)?,
            Buf::I(v) => candle_core::Tensor::from_slice(v.as_slice(), self.shape.as_slice(), &dev)?,
            Buf::B(v) => {
                let u: Vec<u8> = v.iter().map(|b| u8::from(*b)).collect();
                candle_core::Tensor::from_slice(u.as_slice(), self.shape.as_slice(), &dev)?
            }
        })
    }

    #[cfg(not(target_arch = "wasm32"))]
    pub fn from_candle(t: &candle_core::Tensor) -> Result<Tensor> {
        let shape = t.dims().to_vec();
        let flat = t.flatten_all()?;
        match t.dtype() {
            candle_core::DType::F32 => Tensor::f32(shape, flat.to_vec1::<f32>()?),
            candle_core::DType::I64 => Tensor::i64(shape, flat.to_vec1::<i64>()?),
            candle_core::DType::U8 => Tensor::bool(shape, flat.to_vec1::<u8>()?.into_iter().map(|b| b != 0).collect()),
            other => Tensor::f32(shape, flat.to_dtype(candle_core::DType::F32)?.to_vec1::<f32>().map_err(|e| Error::Run(format!("{other:?}: {e}")))?),
        }
    }
}

/// Normalize a possibly negative axis against `rank`.
pub fn axis(a: i64, rank: usize) -> Result<usize> {
    let r = rank as i64;
    let a2 = if a < 0 { a + r } else { a };
    if a2 < 0 || a2 >= r.max(1) {
        return Err(Error::Run(format!("axis {a} out of range for rank {rank}")));
    }
    Ok(a2 as usize)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn broadcasting_follows_numpy() {
        assert_eq!(broadcast_shape(&[2, 1, 3], &[4, 1]).unwrap(), vec![2, 4, 3]);
        assert!(broadcast_shape(&[2, 3], &[4, 3]).is_err());
        // [1,3] over [2,3]: rows repeat
        assert_eq!(broadcast_index(&[1, 3], &[2, 3]), vec![0, 1, 2, 0, 1, 2]);
        // [2,1] over [2,3]: columns repeat
        assert_eq!(broadcast_index(&[2, 1], &[2, 3]), vec![0, 0, 0, 1, 1, 1]);
        assert_eq!(broadcast_index(&[], &[2, 2]), vec![0, 0, 0, 0]);
    }

    #[test]
    fn hostile_shapes_are_errors() {
        assert!(numel(&[usize::MAX, 2]).is_err());
        assert!(numel(&[1 << 20, 1 << 20]).is_err());
        assert!(Tensor::f32(vec![2, 2], vec![0.0; 3]).is_err());
        assert!(axis(5, 3).is_err() && axis(-4, 3).is_err());
        assert_eq!(axis(-1, 3).unwrap(), 2);
    }
}
