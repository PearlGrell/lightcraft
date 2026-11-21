//! Running a model: [`Session::load`] reads it and turns its weights into tensors once;
//! [`Session::run`] feeds inputs, runs the nodes in file order (ONNX keeps them topologically
//! sorted) and frees each intermediate as soon as nothing later reads it.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::ops::{elementwise as ew, nn, shape as sh};
use crate::proto::{self, Graph, Node};
use crate::tensor::Tensor;
use crate::{Error, Result};

/// A model ready to run, any number of times, from any thread.
pub struct Session {
    model: proto::Model,
    weights: HashMap<String, Tensor>,
}

/// Values visible to a graph: its own, then its parent's (an `If` branch reads the outer graph's).
struct Scope<'a> {
    vals: HashMap<String, Tensor>,
    parent: Option<&'a Scope<'a>>,
    weights: &'a HashMap<String, Tensor>,
}

impl Scope<'_> {
    fn get(&self, name: &str) -> Option<&Tensor> {
        self.vals.get(name).or_else(|| self.weights.get(name)).or_else(|| self.parent.and_then(|p| p.get(name)))
    }
}

/// How many times each value is read in `g`, counting reads from inside its sub-graphs.
fn reads(g: &Graph, counts: &mut HashMap<String, usize>) {
    for n in &g.nodes {
        for i in n.inputs.iter().filter(|i| !i.is_empty()) {
            *counts.entry(i.clone()).or_default() += 1;
        }
        for a in n.attrs.values() {
            if let proto::Attr::Graph(sub) = a {
                reads(sub, counts);
            }
        }
    }
    for o in &g.outputs {
        *counts.entry(o.clone()).or_default() += 1;
    }
}

impl Session {
    pub fn load(bytes: &[u8]) -> Result<Session> {
        let model = proto::parse(bytes)?;
        let mut weights = HashMap::new();
        collect_weights(&model.graph, &mut weights)?;
        Ok(Session { model, weights })
    }

    /// The names of the inputs the caller feeds.
    pub fn inputs(&self) -> &[String] {
        &self.model.graph.inputs
    }

    pub fn outputs(&self) -> &[String] {
        &self.model.graph.outputs
    }

    /// Run with `inputs` (by name); `cancel` is checked between nodes. Returns every graph output.
    pub fn run(&self, inputs: Vec<(String, Tensor)>, cancel: Option<&AtomicBool>) -> Result<HashMap<String, Tensor>> {
        let g = &self.model.graph;
        for name in &g.inputs {
            if !inputs.iter().any(|(n, _)| n == name) {
                return Err(Error::Run(format!("input {name:?} is missing")));
            }
        }
        let mut scope = Scope { vals: inputs.into_iter().collect(), parent: None, weights: &self.weights };
        run_graph(g, &mut scope, self.model.opset, cancel)?;
        g.outputs
            .iter()
            .map(|o| {
                scope
                    .vals
                    .remove(o)
                    .or_else(|| self.weights.get(o).cloned())
                    .map(|t| (o.clone(), t))
                    .ok_or_else(|| Error::Run(format!("output {o:?} was not computed")))
            })
            .collect()
    }
}

fn collect_weights(g: &Graph, out: &mut HashMap<String, Tensor>) -> Result<()> {
    for (k, v) in &g.initializers {
        out.insert(k.clone(), Tensor::from_data(v)?);
    }
    for n in &g.nodes {
        for a in n.attrs.values() {
            if let proto::Attr::Graph(sub) = a {
                collect_weights(sub, out)?;
            }
        }
    }
    Ok(())
}

/// `LIGHTCRAFT_NN_PROFILE=1`: print the time each operator type took after a run (stderr).
fn profiling() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("LIGHTCRAFT_NN_PROFILE").is_some())
}

fn run_graph(g: &Graph, scope: &mut Scope<'_>, opset: i64, cancel: Option<&AtomicBool>) -> Result<()> {
    let mut left = HashMap::new();
    reads(g, &mut left);
    let mut times: HashMap<&str, (std::time::Duration, usize)> = HashMap::new();
    for n in &g.nodes {
        let started = profiling().then(std::time::Instant::now);
        if cancel.is_some_and(|c| c.load(Ordering::Relaxed)) {
            return Err(Error::Run("cancelled".into()));
        }
        let outs = if n.op == "If" {
            run_if(n, scope, opset, cancel)?
        } else {
            let args: Vec<Option<&Tensor>> = n.inputs.iter().map(|i| if i.is_empty() { None } else { scope.get(i) }).collect();
            for (i, a) in n.inputs.iter().zip(&args) {
                if !i.is_empty() && a.is_none() {
                    return Err(Error::Run(format!("{} ({}) reads {i:?}, which nothing made", n.op, n.name)));
                }
            }
            op(n, &args, opset).map_err(|e| match e {
                Error::Run(m) => Error::Run(format!("{} ({}): {m}", n.op, n.name)),
                other => other,
            })?
        };
        // free what this node was the last reader of (graph outputs keep a read)
        for i in &n.inputs {
            if let Some(c) = left.get_mut(i) {
                *c = c.saturating_sub(1);
                if *c == 0 {
                    scope.vals.remove(i);
                }
            }
        }
        for (name, t) in n.outputs.iter().zip(outs) {
            if !name.is_empty() {
                scope.vals.insert(name.clone(), t);
            }
        }
        if let Some(t0) = started {
            let e = times.entry(n.op.as_str()).or_default();
            e.0 += t0.elapsed();
            e.1 += 1;
        }
    }
    if profiling() && !times.is_empty() {
        let mut v: Vec<_> = times.into_iter().collect();
        v.sort_by_key(|e| std::cmp::Reverse(e.1.0));
        for (op, (d, count)) in v.iter().take(12) {
            eprintln!("  {op:<22} {:>9.1} ms  ×{count}", d.as_secs_f64() * 1000.0);
        }
    }
    Ok(())
}

fn run_if(n: &Node, scope: &Scope<'_>, opset: i64, cancel: Option<&AtomicBool>) -> Result<Vec<Tensor>> {
    let cond = scope.get(n.inputs.first().map(String::as_str).unwrap_or("")).ok_or_else(|| Error::Run("If without a condition".into()))?;
    let branch = if cond.scalar()? != 0.0 { n.graph("then_branch") } else { n.graph("else_branch") };
    let g = branch.ok_or_else(|| Error::Run("If without its branch".into()))?;
    let mut inner = Scope { vals: HashMap::new(), parent: Some(scope), weights: scope.weights };
    run_graph(g, &mut inner, opset, cancel)?;
    g.outputs.iter().map(|o| inner.get(o).cloned().ok_or_else(|| Error::Run(format!("If branch output {o:?}")))).collect()
}

/// An optional input's integer values.
fn ints(t: Option<&Tensor>) -> Option<Vec<i64>> {
    t.map(Tensor::to_i64s)
}

fn need<'a>(args: &[Option<&'a Tensor>], i: usize) -> Result<&'a Tensor> {
    args.get(i).copied().flatten().ok_or_else(|| Error::Run(format!("input {i} is missing")))
}

fn opt<'a>(args: &[Option<&'a Tensor>], i: usize) -> Option<&'a Tensor> {
    args.get(i).copied().flatten()
}

/// Run one operator.
fn op(n: &Node, a: &[Option<&Tensor>], opset: i64) -> Result<Vec<Tensor>> {
    if !(n.domain.is_empty() || n.domain == "ai.onnx") {
        return Err(Error::Unsupported(format!("operator {}::{}", n.domain, n.op)));
    }
    let one = |t: Tensor| Ok(vec![t]);
    match n.op.as_str() {
        "Identity" | "Dropout" => one(need(a, 0)?.clone()),
        "Constant" => {
            let t = if let Some(t) = n.tensor("value") {
                Tensor::from_data(t)?
            } else if let Some(v) = n.float("value_float") {
                Tensor::f32(vec![], vec![v])?
            } else if let Some(v) = n.int("value_int") {
                Tensor::i64(vec![], vec![v])?
            } else if let Some(v) = n.floats("value_floats") {
                Tensor::f32(vec![v.len()], v.to_vec())?
            } else if let Some(v) = n.ints("value_ints") {
                Tensor::i64(vec![v.len()], v.to_vec())?
            } else {
                return Err(Error::Unsupported("Constant without a value".into()));
            };
            one(t)
        }
        "Add" | "Sub" | "Mul" | "Div" | "Pow" | "Mod" => one(ew::arith(&n.op, need(a, 0)?, need(a, 1)?)?),
        "Max" | "Min" | "Sum" | "Mean" => {
            let ts: Vec<&Tensor> = a.iter().flatten().copied().collect();
            let first = ts.first().ok_or_else(|| Error::Run("no inputs".into()))?;
            let op = match n.op.as_str() {
                "Sum" | "Mean" => "Add",
                other => other,
            };
            let mut acc = (*first).clone();
            for t in ts.iter().skip(1) {
                acc = ew::arith(op, &acc, t)?;
            }
            if n.op == "Mean" {
                let k = Tensor::f32(vec![], vec![ts.len() as f32])?;
                acc = ew::arith("Div", &acc, &k)?;
            }
            one(acc)
        }
        "Equal" | "Greater" | "Less" | "GreaterOrEqual" | "LessOrEqual" => one(ew::compare(&n.op, need(a, 0)?, need(a, 1)?)?),
        "And" | "Or" | "Xor" => one(ew::logic(&n.op, need(a, 0)?, need(a, 1)?)?),
        "Not" => one(ew::not(need(a, 0)?)?),
        "Neg" => one(ew::neg(need(a, 0)?)?),
        "Relu" | "LeakyRelu" | "Sigmoid" | "HardSigmoid" | "HardSwish" | "Tanh" | "Exp" | "Log" | "Sqrt" | "Reciprocal" | "Abs" | "Floor"
        | "Ceil" | "Round" | "Sin" | "Cos" | "Erf" | "Softplus" | "Elu" | "Gelu" => one(ew::unary(&n.op, n, need(a, 0)?)?),
        "PRelu" => one(ew::prelu(need(a, 0)?, need(a, 1)?)?),
        "Clip" => one(ew::clip(n, need(a, 0)?, opt(a, 1), opt(a, 2))?),
        "Where" => one(ew::where_(need(a, 0)?, need(a, 1)?, need(a, 2)?)?),
        "Cast" => one(ew::cast(need(a, 0)?, n.int("to").ok_or_else(|| Error::Run("Cast without `to`".into()))?)?),
        "Shape" => one(sh::shape_of(need(a, 0)?, n.int("start"), n.int("end"))?),
        "Size" => one(Tensor::i64(vec![], vec![need(a, 0)?.len() as i64])?),
        "Reshape" => one(sh::reshape(need(a, 0)?, &need(a, 1)?.to_i64s(), n.int("allowzero").unwrap_or(0) != 0)?),
        "Flatten" => one(sh::flatten(need(a, 0)?, n.int("axis").unwrap_or(1))?),
        "Unsqueeze" => {
            let axes = if opset >= 13 { ints(opt(a, 1)) } else { n.ints("axes").map(<[i64]>::to_vec) };
            one(sh::unsqueeze(need(a, 0)?, &axes.ok_or_else(|| Error::Run("Unsqueeze without axes".into()))?)?)
        }
        "Squeeze" => {
            let axes = if opset >= 13 { ints(opt(a, 1)) } else { n.ints("axes").map(<[i64]>::to_vec) };
            one(sh::squeeze(need(a, 0)?, axes.as_deref())?)
        }
        "Transpose" => one(sh::transpose(need(a, 0)?, n.ints("perm"))?),
        "Concat" => {
            let ts: Vec<&Tensor> = a.iter().flatten().copied().collect();
            one(sh::concat(&ts, n.int("axis").ok_or_else(|| Error::Run("Concat without axis".into()))?)?)
        }
        "Slice" => {
            let x = need(a, 0)?;
            if opset < 10 {
                let starts = n.ints("starts").ok_or_else(|| Error::Run("Slice starts".into()))?;
                let ends = n.ints("ends").ok_or_else(|| Error::Run("Slice ends".into()))?;
                one(sh::slice(x, starts, ends, n.ints("axes"), None)?)
            } else {
                let (s, e) = (need(a, 1)?.to_i64s(), need(a, 2)?.to_i64s());
                let (ax, st) = (ints(opt(a, 3)), ints(opt(a, 4)));
                one(sh::slice(x, &s, &e, ax.as_deref(), st.as_deref())?)
            }
        }
        "Split" => {
            let sizes = if opset >= 13 { ints(opt(a, 1)) } else { n.ints("split").map(<[i64]>::to_vec) };
            let count = n.int("num_outputs").map_or(n.outputs.len(), |v| v.max(1) as usize);
            sh::split(need(a, 0)?, n.int("axis").unwrap_or(0), sizes, count)
        }
        "Gather" => one(sh::gather(need(a, 0)?, need(a, 1)?, n.int("axis").unwrap_or(0))?),
        "GatherElements" => one(sh::gather_elements(need(a, 0)?, need(a, 1)?, n.int("axis").unwrap_or(0))?),
        "Expand" => one(sh::expand(need(a, 0)?, &need(a, 1)?.to_i64s())?),
        "Tile" => one(sh::tile(need(a, 0)?, &need(a, 1)?.to_i64s())?),
        "Range" => one(sh::range(need(a, 0)?, need(a, 1)?, need(a, 2)?)?),
        "ConstantOfShape" => {
            let v = n.tensor("value").map(Tensor::from_data).transpose()?;
            one(sh::constant_of_shape(need(a, 0)?, v.as_ref())?)
        }
        "Pad" => {
            let x = need(a, 0)?;
            let mode = n.string("mode").unwrap_or("constant");
            if opset < 11 {
                let pads = n.ints("pads").ok_or_else(|| Error::Run("Pad without pads".into()))?;
                one(sh::pad(x, pads, mode, f64::from(n.float("value").unwrap_or(0.0)), None)?)
            } else {
                let value = opt(a, 2).map(Tensor::scalar).transpose()?.unwrap_or(0.0);
                let axes = ints(opt(a, 3));
                one(sh::pad(x, &need(a, 1)?.to_i64s(), mode, value, axes.as_deref())?)
            }
        }
        "DepthToSpace" => {
            let bs = usize::try_from(n.int("blocksize").unwrap_or(0)).map_err(|_| Error::Run("DepthToSpace blocksize".into()))?;
            one(sh::depth_to_space(need(a, 0)?, bs, n.string("mode") == Some("CRD"))?)
        }
        "ScatterND" => {
            if n.string("reduction").is_some_and(|r| r != "none") {
                return Err(Error::Unsupported("ScatterND reduction".into()));
            }
            one(sh::scatter_nd(need(a, 0)?, need(a, 1)?, need(a, 2)?)?)
        }
        "OneHot" => one(sh::one_hot(need(a, 0)?, need(a, 1)?, need(a, 2)?, n.int("axis").unwrap_or(-1))?),
        "TopK" => {
            let k = usize::try_from(need(a, 1)?.scalar()? as i64).map_err(|_| Error::Run("TopK k".into()))?;
            let (v, i) = sh::topk(need(a, 0)?, k, n.int("axis").unwrap_or(-1), n.int("largest").unwrap_or(1) != 0)?;
            Ok(vec![v, i])
        }
        "Conv" => one(nn::conv(n, need(a, 0)?, need(a, 1)?, opt(a, 2))?),
        "ConvTranspose" => one(nn::conv_transpose(n, need(a, 0)?, need(a, 1)?, opt(a, 2))?),
        "MatMul" => one(nn::matmul(need(a, 0)?, need(a, 1)?)?),
        "Gemm" => one(nn::gemm(n, need(a, 0)?, need(a, 1)?, opt(a, 2))?),
        "MaxPool" => one(nn::max_pool(n, need(a, 0)?)?),
        "GlobalAveragePool" => one(nn::global_avg_pool(need(a, 0)?)?),
        "BatchNormalization" => one(nn::batch_norm(n, need(a, 0)?, need(a, 1)?, need(a, 2)?, need(a, 3)?, need(a, 4)?)?),
        "InstanceNormalization" => one(nn::instance_norm(n, need(a, 0)?, need(a, 1)?, need(a, 2)?)?),
        "LayerNormalization" => one(nn::layer_norm(n, need(a, 0)?, need(a, 1)?, opt(a, 2))?),
        "Softmax" | "LogSoftmax" => {
            let default = if opset >= 13 { -1 } else { 1 };
            let x = need(a, 0)?;
            if opset < 13 && x.rank() > 2 && n.int("axis").unwrap_or(1) != x.rank() as i64 - 1 && n.int("axis").unwrap_or(1) != -1 {
                return Err(Error::Unsupported("Softmax over flattened axes (opset < 13)".into()));
            }
            one(nn::softmax(x, n.int("axis").unwrap_or(default), n.op == "LogSoftmax")?)
        }
        "ReduceMean" | "ReduceSum" | "ReduceMax" | "ReduceMin" | "ReduceProd" | "ReduceL2" | "ReduceSumSquare" => {
            // axes moved from attribute to input: ReduceSum at opset 13, the rest at 18
            let from_input = (n.op == "ReduceSum" && opset >= 13) || opset >= 18;
            let axes = if from_input { ints(opt(a, 1)) } else { n.ints("axes").map(<[i64]>::to_vec) };
            one(nn::reduce(&n.op, need(a, 0)?, axes, n.int("keepdims").unwrap_or(1) != 0, n.int("noop_with_empty_axes").unwrap_or(0) != 0)?)
        }
        "Resize" | "Upsample" => {
            let x = need(a, 0)?;
            // opset 10 Upsample/Resize: (X, scales); 11+: (X, roi, scales, sizes)
            let (scales, sizes) = if n.op == "Upsample" || opset < 11 { (opt(a, 1), None) } else { (opt(a, 2), opt(a, 3)) };
            let scales = scales.filter(|t| !t.is_empty()).map(Tensor::to_f32s);
            let sizes = sizes.filter(|t| !t.is_empty()).map(Tensor::to_i64s);
            one(nn::resize(n, x, scales.as_deref(), sizes.as_deref())?)
        }
        "GridSample" => one(nn::grid_sample(n, need(a, 0)?, need(a, 1)?)?),
        other => Err(Error::Unsupported(format!("operator {other}"))),
    }
}
