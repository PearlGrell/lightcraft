//! Elementwise operators with NumPy broadcasting: arithmetic, comparisons, logic, activations,
//! `Where` and `Cast`.

use rayon::prelude::*;

use crate::proto::Node;
use crate::tensor::{Buf, Tensor, broadcast_index, broadcast_shape, numel};
use crate::{Error, Result};

/// Runs of at least this many elements are split across threads.
const PAR: usize = 1 << 15;

fn map_f(x: &[f32], f: impl Fn(f32) -> f32 + Sync) -> Vec<f32> {
    if x.len() >= PAR { x.par_iter().map(|v| f(*v)).collect() } else { x.iter().map(|v| f(*v)).collect() }
}

/// A float unary operator.
pub fn unary(op: &str, n: &Node, x: &Tensor) -> Result<Tensor> {
    let alpha = n.float("alpha");
    let f: Box<dyn Fn(f32) -> f32 + Sync> = match op {
        "Relu" => Box::new(|v| v.max(0.0)),
        "LeakyRelu" => {
            let a = alpha.unwrap_or(0.01);
            Box::new(move |v| if v >= 0.0 { v } else { v * a })
        }
        "Sigmoid" => Box::new(|v| 1.0 / (1.0 + (-v).exp())),
        "HardSigmoid" => {
            let (a, b) = (alpha.unwrap_or(0.2), n.float("beta").unwrap_or(0.5));
            Box::new(move |v| (a * v + b).clamp(0.0, 1.0))
        }
        "HardSwish" => Box::new(|v| v * (v / 6.0 + 0.5).clamp(0.0, 1.0)),
        "Tanh" => Box::new(|v| v.tanh()),
        "Exp" => Box::new(|v| v.exp()),
        "Log" => Box::new(|v| v.ln()),
        "Sqrt" => Box::new(|v| v.sqrt()),
        "Reciprocal" => Box::new(|v| 1.0 / v),
        "Abs" => Box::new(|v| v.abs()),
        "Floor" => Box::new(|v| v.floor()),
        "Ceil" => Box::new(|v| v.ceil()),
        "Round" => Box::new(|v| v.round_ties_even()),
        "Sin" => Box::new(|v| v.sin()),
        "Cos" => Box::new(|v| v.cos()),
        "Erf" => Box::new(erf),
        "Softplus" => Box::new(|v| if v > 20.0 { v } else { v.exp().ln_1p() }),
        "Elu" => {
            let a = alpha.unwrap_or(1.0);
            Box::new(move |v| if v >= 0.0 { v } else { a * (v.exp() - 1.0) })
        }
        "Gelu" => {
            let tanh = n.string("approximate") == Some("tanh");
            Box::new(move |v| {
                if tanh {
                    0.5 * v * (1.0 + (0.797_884_6 * (v + 0.044_715 * v * v * v)).tanh())
                } else {
                    0.5 * v * (1.0 + erf(v / std::f32::consts::SQRT_2))
                }
            })
        }
        _ => return Err(Error::Unsupported(format!("operator {op}"))),
    };
    match &x.buf {
        Buf::F(v) => Tensor::f32(x.shape.clone(), map_f(v, f)),
        Buf::I(v) if op == "Abs" => Tensor::i64(x.shape.clone(), v.iter().map(|a| a.wrapping_abs()).collect()),
        Buf::I(_) if matches!(op, "Floor" | "Ceil" | "Round") => Ok(x.clone()),
        _ => Err(Error::Run(format!("{op} needs a float tensor"))),
    }
}

/// Neg works on floats and integers.
pub fn neg(x: &Tensor) -> Result<Tensor> {
    match &x.buf {
        Buf::F(v) => Tensor::f32(x.shape.clone(), map_f(v, |a| -a)),
        Buf::I(v) => Tensor::i64(x.shape.clone(), v.iter().map(|a| a.wrapping_neg()).collect()),
        Buf::B(_) => Err(Error::Run("Neg of a boolean tensor".into())),
    }
}

/// The error function (Abramowitz–Stegun 7.1.26 refined; |error| < 1.2e-7).
pub fn erf(x: f32) -> f32 {
    let x = f64::from(x);
    let t = 1.0 / (1.0 + 0.5 * x.abs());
    let y = 1.0
        - t * (-x * x - 1.265_512_23
            + t * (1.000_023_68
                + t * (0.374_091_96
                    + t * (0.096_784_18
                        + t * (-0.186_288_06
                            + t * (0.278_868_07 + t * (-1.135_203_98 + t * (1.488_515_87 + t * (-0.822_152_23 + t * 0.170_872_77)))))))))
            .exp();
    (if x >= 0.0 { y } else { -y }) as f32
}

/// Broadcast `a` and `b` to one shape and combine them element by element.
fn zip<A: Copy + Sync, B: Copy + Sync, C: Send>(
    a: &[A],
    ash: &[usize],
    b: &[B],
    bsh: &[usize],
    f: impl Fn(A, B) -> C + Sync,
) -> Result<(Vec<usize>, Vec<C>)> {
    let out = broadcast_shape(ash, bsh)?;
    let total = numel(&out)?;
    if ash == bsh {
        let v = if total >= PAR {
            a.par_iter().zip(b.par_iter()).map(|(x, y)| f(*x, *y)).collect()
        } else {
            a.iter().zip(b).map(|(x, y)| f(*x, *y)).collect()
        };
        return Ok((out, v));
    }
    if b.len() == 1 {
        let y = b[0];
        let v = if total >= PAR { a.par_iter().map(|x| f(*x, y)).collect() } else { a.iter().map(|x| f(*x, y)).collect() };
        if a.len() == total {
            return Ok((out, v));
        }
    }
    if a.len() == 1 {
        let x = a[0];
        let v = if total >= PAR { b.par_iter().map(|y| f(x, *y)).collect() } else { b.iter().map(|y| f(x, *y)).collect() };
        if b.len() == total {
            return Ok((out, v));
        }
    }
    let ia = broadcast_index(ash, &out);
    let ib = broadcast_index(bsh, &out);
    let get = |i: usize| -> Option<C> { Some(f(*a.get(*ia.get(i)?)?, *b.get(*ib.get(i)?)?)) };
    let v: Option<Vec<C>> = if total >= PAR { (0..total).into_par_iter().map(get).collect() } else { (0..total).map(get).collect() };
    Ok((out, v.ok_or_else(|| Error::Run("broadcast index out of range".into()))?))
}

/// Arithmetic: Add, Sub, Mul, Div, Pow, Max, Min, Mod.
pub fn arith(op: &str, a: &Tensor, b: &Tensor) -> Result<Tensor> {
    match (&a.buf, &b.buf) {
        (Buf::F(x), Buf::F(y)) => {
            let f: fn(f32, f32) -> f32 = match op {
                "Add" => |p, q| p + q,
                "Sub" => |p, q| p - q,
                "Mul" => |p, q| p * q,
                "Div" => |p, q| p / q,
                "Pow" => |p, q| if q == 2.0 { p * p } else { p.powf(q) },
                "Max" => f32::max,
                "Min" => f32::min,
                "Mod" => |p, q| p % q,
                _ => return Err(Error::Unsupported(format!("operator {op}"))),
            };
            let (s, v) = zip(x, &a.shape, y, &b.shape, f)?;
            Tensor::f32(s, v)
        }
        (Buf::I(x), Buf::I(y)) => {
            let f: fn(i64, i64) -> i64 = match op {
                "Add" => i64::wrapping_add,
                "Sub" => i64::wrapping_sub,
                "Mul" => i64::wrapping_mul,
                // integer division by zero is a model error, not a crash: it reads as 0
                "Div" => |p, q| p.checked_div(q).unwrap_or(0),
                "Pow" => |p, q| u32::try_from(q).map_or(0, |e| p.wrapping_pow(e)),
                "Max" => i64::max,
                "Min" => i64::min,
                "Mod" => |p, q| p.checked_rem_euclid(q).map_or(0, |r| if q < 0 && r != 0 { r + q } else { r }),
                _ => return Err(Error::Unsupported(format!("operator {op}"))),
            };
            let (s, v) = zip(x, &a.shape, y, &b.shape, f)?;
            Tensor::i64(s, v)
        }
        // Pow with a float base and an integer exponent (or the other way) happens in exports
        (Buf::F(_), Buf::I(_)) if op == "Pow" => arith(op, a, &Tensor::f32(b.shape.clone(), b.to_f32s())?),
        _ => Err(Error::Run(format!("{op} of mismatched types"))),
    }
}

/// Equal, Greater, Less, GreaterOrEqual, LessOrEqual → bool.
pub fn compare(op: &str, a: &Tensor, b: &Tensor) -> Result<Tensor> {
    macro_rules! cmp {
        ($x:expr, $y:expr) => {{
            let (s, v) = match op {
                "Equal" => zip($x, &a.shape, $y, &b.shape, |p, q| p == q)?,
                "Greater" => zip($x, &a.shape, $y, &b.shape, |p, q| p > q)?,
                "Less" => zip($x, &a.shape, $y, &b.shape, |p, q| p < q)?,
                "GreaterOrEqual" => zip($x, &a.shape, $y, &b.shape, |p, q| p >= q)?,
                "LessOrEqual" => zip($x, &a.shape, $y, &b.shape, |p, q| p <= q)?,
                _ => return Err(Error::Unsupported(format!("operator {op}"))),
            };
            Tensor::bool(s, v)
        }};
    }
    match (&a.buf, &b.buf) {
        (Buf::F(x), Buf::F(y)) => cmp!(x, y),
        (Buf::I(x), Buf::I(y)) => cmp!(x, y),
        (Buf::B(x), Buf::B(y)) if op == "Equal" => {
            let (s, v) = zip(x, &a.shape, y, &b.shape, |p, q| p == q)?;
            Tensor::bool(s, v)
        }
        _ => Err(Error::Run(format!("{op} of mismatched types"))),
    }
}

/// And, Or, Xor on booleans.
pub fn logic(op: &str, a: &Tensor, b: &Tensor) -> Result<Tensor> {
    let f: fn(bool, bool) -> bool = match op {
        "And" => |p, q| p && q,
        "Or" => |p, q| p || q,
        "Xor" => |p, q| p ^ q,
        _ => return Err(Error::Unsupported(format!("operator {op}"))),
    };
    let (s, v) = zip(a.bs()?, &a.shape, b.bs()?, &b.shape, f)?;
    Tensor::bool(s, v)
}

pub fn not(x: &Tensor) -> Result<Tensor> {
    Tensor::bool(x.shape.clone(), x.bs()?.iter().map(|b| !b).collect())
}

/// `cond ? x : y`, all three broadcast together.
pub fn where_(cond: &Tensor, x: &Tensor, y: &Tensor) -> Result<Tensor> {
    let out = broadcast_shape(&broadcast_shape(&cond.shape, &x.shape)?, &y.shape)?;
    let total = numel(&out)?;
    let c = cond.bs()?;
    let (ic, ix, iy) = (broadcast_index(&cond.shape, &out), broadcast_index(&x.shape, &out), broadcast_index(&y.shape, &out));
    let pick = |i: usize| -> Option<(bool, usize, usize)> { Some((*c.get(*ic.get(i)?)?, *ix.get(i)?, *iy.get(i)?)) };
    let sel: Option<Vec<(bool, usize, usize)>> = (0..total).map(pick).collect();
    let sel = sel.ok_or_else(|| Error::Run("Where index out of range".into()))?;
    macro_rules! choose {
        ($a:expr, $b:expr, $mk:path) => {{
            let v: Option<Vec<_>> = sel.iter().map(|(t, i, j)| if *t { $a.get(*i).copied() } else { $b.get(*j).copied() }).collect();
            $mk(out, v.ok_or_else(|| Error::Run("Where index out of range".into()))?)
        }};
    }
    match (&x.buf, &y.buf) {
        (Buf::F(a), Buf::F(b)) => choose!(a, b, Tensor::f32),
        (Buf::I(a), Buf::I(b)) => choose!(a, b, Tensor::i64),
        (Buf::B(a), Buf::B(b)) => choose!(a, b, Tensor::bool),
        _ => Err(Error::Run("Where of mismatched types".into())),
    }
}

/// Cast to ONNX element type `to` (floats compute as f32, integers as i64).
pub fn cast(x: &Tensor, to: i64) -> Result<Tensor> {
    match to {
        1 | 10 | 11 | 16 => Tensor::f32(x.shape.clone(), x.to_f32s()),
        9 => Tensor::bool(
            x.shape.clone(),
            match &x.buf {
                Buf::F(v) => v.iter().map(|a| *a != 0.0).collect(),
                Buf::I(v) => v.iter().map(|a| *a != 0).collect(),
                Buf::B(v) => v.to_vec(),
            },
        ),
        2 | 3 | 5 | 6 | 7 | 12 | 13 => Tensor::i64(
            x.shape.clone(),
            match &x.buf {
                // float → integer truncates toward zero (saturating, NaN → 0)
                Buf::F(v) => v.iter().map(|a| *a as i64).collect(),
                _ => x.to_i64s(),
            },
        ),
        _ => Err(Error::Unsupported(format!("Cast to type {to}"))),
    }
}

/// Clip to `[min, max]` (inputs from opset 11, attributes before).
pub fn clip(n: &Node, x: &Tensor, lo: Option<&Tensor>, hi: Option<&Tensor>) -> Result<Tensor> {
    let lo = match lo {
        Some(t) => Some(t.scalar()? as f32),
        None => n.float("min"),
    };
    let hi = match hi {
        Some(t) => Some(t.scalar()? as f32),
        None => n.float("max"),
    };
    match &x.buf {
        Buf::F(v) => {
            let (l, h) = (lo.unwrap_or(f32::NEG_INFINITY), hi.unwrap_or(f32::INFINITY));
            Tensor::f32(x.shape.clone(), map_f(v, |a| a.max(l).min(h)))
        }
        Buf::I(v) => {
            let (l, h) = (lo.map_or(i64::MIN, |a| a as i64), hi.map_or(i64::MAX, |a| a as i64));
            Tensor::i64(x.shape.clone(), v.iter().map(|a| (*a).max(l).min(h)).collect())
        }
        Buf::B(_) => Err(Error::Run("Clip of a boolean tensor".into())),
    }
}

/// PRelu: `x < 0 ? slope · x : x`, the slope broadcast over `x`.
pub fn prelu(x: &Tensor, slope: &Tensor) -> Result<Tensor> {
    let (s, v) = zip(x.fs()?, &x.shape, slope.fs()?, &slope.shape, |a, k| if a < 0.0 { a * k } else { a })?;
    Tensor::f32(s, v)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(op: &str) -> Node {
        Node { op: op.into(), domain: String::new(), name: String::new(), inputs: vec![], outputs: vec![], attrs: Default::default() }
    }

    #[test]
    fn arithmetic_broadcasts_and_integers_never_panic() {
        let a = Tensor::f32(vec![2, 2], vec![1.0, 2.0, 3.0, 4.0]).unwrap();
        let b = Tensor::f32(vec![2], vec![10.0, 20.0]).unwrap();
        assert_eq!(arith("Add", &a, &b).unwrap().fs().unwrap(), &[11.0, 22.0, 13.0, 24.0]);
        let i = Tensor::i64(vec![2], vec![7, i64::MIN]).unwrap();
        let z = Tensor::i64(vec![2], vec![0, -1]).unwrap();
        assert_eq!(arith("Div", &i, &z).unwrap().is().unwrap(), &[0, 0]);
        assert!(arith("Add", &a, &Tensor::f32(vec![3], vec![0.0; 3]).unwrap()).is_err());
        assert!(arith("Add", &a, &i).is_err());
    }

    #[test]
    fn pow_of_a_negative_base_squares() {
        let a = Tensor::f32(vec![2], vec![-3.0, 2.0]).unwrap();
        let two = Tensor::f32(vec![], vec![2.0]).unwrap();
        assert_eq!(arith("Pow", &a, &two).unwrap().fs().unwrap(), &[9.0, 4.0]);
    }

    #[test]
    fn activations_and_where() {
        let x = Tensor::f32(vec![3], vec![-1.0, 0.0, 2.0]).unwrap();
        assert_eq!(unary("Relu", &node("Relu"), &x).unwrap().fs().unwrap(), &[0.0, 0.0, 2.0]);
        assert!((erf(0.5) - 0.520_499_9).abs() < 1e-6 && (erf(-2.0) + 0.995_322_3).abs() < 1e-6);
        let c = Tensor::bool(vec![3], vec![true, false, true]).unwrap();
        let y = Tensor::f32(vec![], vec![9.0]).unwrap();
        assert_eq!(where_(&c, &x, &y).unwrap().fs().unwrap(), &[-1.0, 9.0, 2.0]);
        assert!(unary("Nope", &node("Nope"), &x).is_err());
    }
}
