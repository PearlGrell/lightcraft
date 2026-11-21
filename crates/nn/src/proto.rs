//! A bounded reader for ONNX model files (protobuf), covering what the runner needs: the graph's
//! nodes with every attribute kind (tensors and sub-graphs included), its weights in any numeric
//! type, and its inputs and outputs.
//!
//! The bytes are hostile: every length is checked against what is left, nesting is bounded, counts
//! and element totals are capped, and a weight whose bytes disagree with its shape is an error.
//! Weights kept outside the file (`external_data`) are refused.

use std::collections::HashMap;

use crate::{Error, Result};

/// Sub-graph nesting (an `If` inside an `If`…) beyond this is refused.
const MAX_DEPTH: usize = 8;
/// At most this many nodes in one graph.
const MAX_NODES: usize = 200_000;
/// At most this many weight elements in the whole file (≈ 1 GB of f32).
const MAX_ELEMENTS: usize = 256 * 1024 * 1024;
/// At most this many values in one repeated field.
const MAX_LIST: usize = 64 * 1024 * 1024;

/// The element type of a tensor, as ONNX numbers them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ElemType {
    F32,
    U8,
    I8,
    I16,
    I32,
    I64,
    Bool,
    F16,
    F64,
    U32,
    U64,
    BF16,
}

impl ElemType {
    pub fn from_onnx(code: i64) -> Result<ElemType> {
        Ok(match code {
            1 => ElemType::F32,
            2 => ElemType::U8,
            3 => ElemType::I8,
            5 => ElemType::I16,
            6 => ElemType::I32,
            7 => ElemType::I64,
            9 => ElemType::Bool,
            10 => ElemType::F16,
            11 => ElemType::F64,
            12 => ElemType::U32,
            13 => ElemType::U64,
            16 => ElemType::BF16,
            _ => return Err(Error::Unsupported(format!("tensor element type {code}"))),
        })
    }

    fn size(self) -> usize {
        match self {
            ElemType::U8 | ElemType::I8 | ElemType::Bool => 1,
            ElemType::I16 | ElemType::F16 | ElemType::BF16 => 2,
            ElemType::F32 | ElemType::I32 | ElemType::U32 => 4,
            ElemType::I64 | ElemType::F64 | ElemType::U64 => 8,
        }
    }
}

/// A tensor's values, widened to the three kinds the runner computes in.
#[derive(Clone, Debug, PartialEq)]
pub enum Data {
    /// Every float type.
    F32(Vec<f32>),
    /// Every integer type.
    I64(Vec<i64>),
    /// Booleans.
    Bool(Vec<bool>),
}

impl Data {
    pub fn len(&self) -> usize {
        match self {
            Data::F32(v) => v.len(),
            Data::I64(v) => v.len(),
            Data::Bool(v) => v.len(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// A constant tensor: a weight, or an attribute's value.
#[derive(Clone, Debug, PartialEq)]
pub struct TensorData {
    pub dims: Vec<usize>,
    pub data: Data,
}

#[derive(Clone, Debug)]
pub enum Attr {
    Int(i64),
    Float(f32),
    Str(String),
    Ints(Vec<i64>),
    Floats(Vec<f32>),
    Tensor(TensorData),
    Graph(Box<Graph>),
    /// A kind the runner never reads (lists of strings, tensors or graphs…).
    Other,
}

#[derive(Clone, Debug)]
pub struct Node {
    pub op: String,
    pub domain: String,
    pub name: String,
    pub inputs: Vec<String>,
    pub outputs: Vec<String>,
    pub attrs: HashMap<String, Attr>,
}

impl Node {
    pub fn int(&self, name: &str) -> Option<i64> {
        match self.attrs.get(name) {
            Some(Attr::Int(v)) => Some(*v),
            _ => None,
        }
    }

    pub fn float(&self, name: &str) -> Option<f32> {
        match self.attrs.get(name) {
            Some(Attr::Float(v)) => Some(*v),
            _ => None,
        }
    }

    pub fn ints(&self, name: &str) -> Option<&[i64]> {
        match self.attrs.get(name) {
            Some(Attr::Ints(v)) => Some(v),
            _ => None,
        }
    }

    pub fn floats(&self, name: &str) -> Option<&[f32]> {
        match self.attrs.get(name) {
            Some(Attr::Floats(v)) => Some(v),
            _ => None,
        }
    }

    pub fn string(&self, name: &str) -> Option<&str> {
        match self.attrs.get(name) {
            Some(Attr::Str(v)) => Some(v),
            _ => None,
        }
    }

    pub fn tensor(&self, name: &str) -> Option<&TensorData> {
        match self.attrs.get(name) {
            Some(Attr::Tensor(t)) => Some(t),
            _ => None,
        }
    }

    pub fn graph(&self, name: &str) -> Option<&Graph> {
        match self.attrs.get(name) {
            Some(Attr::Graph(g)) => Some(g),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub struct Graph {
    pub nodes: Vec<Node>,
    pub initializers: HashMap<String, TensorData>,
    /// Inputs the caller feeds (weights some exporters also list as inputs are left out).
    pub inputs: Vec<String>,
    pub outputs: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct Model {
    pub graph: Graph,
    /// The default-domain operator set version.
    pub opset: i64,
}

/// Read a whole model file.
pub fn parse(bytes: &[u8]) -> Result<Model> {
    let mut r = Reader { buf: bytes, pos: 0 };
    let mut graph = None;
    let mut opset = 0i64;
    let mut elements = 0usize;
    while let Some((field, wire)) = r.key()? {
        match (field, wire) {
            (7, 2) => graph = Some(read_graph(r.sub()?, 0, &mut elements)?),
            (8, 2) => {
                let mut o = r.sub()?;
                let (mut domain, mut version) = (String::new(), 0i64);
                while let Some((f, w)) = o.key()? {
                    match (f, w) {
                        (1, 2) => domain = o.string()?,
                        (2, 0) => version = o.varint()? as i64,
                        _ => o.skip(w)?,
                    }
                }
                if domain.is_empty() || domain == "ai.onnx" {
                    opset = version;
                }
            }
            _ => r.skip(wire)?,
        }
    }
    let graph = graph.ok_or_else(|| Error::Malformed("the file has no graph".into()))?;
    Ok(Model { graph, opset })
}

fn read_graph(mut r: Reader<'_>, depth: usize, elements: &mut usize) -> Result<Graph> {
    if depth > MAX_DEPTH {
        return Err(Error::Malformed("sub-graphs nested too deeply".into()));
    }
    let mut g = Graph::default();
    let mut listed_inputs = Vec::new();
    while let Some((field, wire)) = r.key()? {
        match (field, wire) {
            (1, 2) => {
                if g.nodes.len() >= MAX_NODES {
                    return Err(Error::Malformed("too many nodes".into()));
                }
                g.nodes.push(read_node(r.sub()?, depth, elements)?);
            }
            (5, 2) => {
                let (name, t) = read_tensor(r.sub()?, elements)?;
                g.initializers.insert(name, t);
            }
            (11, 2) => listed_inputs.push(value_info_name(r.sub()?)?),
            (12, 2) => g.outputs.push(value_info_name(r.sub()?)?),
            _ => r.skip(wire)?,
        }
    }
    g.inputs = listed_inputs.into_iter().filter(|n| !g.initializers.contains_key(n)).collect();
    Ok(g)
}

fn value_info_name(mut r: Reader<'_>) -> Result<String> {
    let mut name = String::new();
    while let Some((f, w)) = r.key()? {
        match (f, w) {
            (1, 2) => name = r.string()?,
            _ => r.skip(w)?,
        }
    }
    Ok(name)
}

fn read_node(mut r: Reader<'_>, depth: usize, elements: &mut usize) -> Result<Node> {
    let mut n = Node { op: String::new(), domain: String::new(), name: String::new(), inputs: vec![], outputs: vec![], attrs: HashMap::new() };
    while let Some((field, wire)) = r.key()? {
        match (field, wire) {
            (1, 2) => n.inputs.push(r.string()?),
            (2, 2) => n.outputs.push(r.string()?),
            (3, 2) => n.name = r.string()?,
            (4, 2) => n.op = r.string()?,
            (7, 2) => n.domain = r.string()?,
            (5, 2) => {
                let (name, a) = read_attr(r.sub()?, depth, elements)?;
                n.attrs.insert(name, a);
            }
            _ => r.skip(wire)?,
        }
    }
    if n.op.is_empty() {
        return Err(Error::Malformed("a node without an operator".into()));
    }
    Ok(n)
}

fn read_attr(mut r: Reader<'_>, depth: usize, elements: &mut usize) -> Result<(String, Attr)> {
    let mut name = String::new();
    let mut kind = 0u64;
    let (mut f, mut i, mut s, mut t, mut g) = (None, None, None, None, None);
    let (mut floats, mut ints) = (Vec::new(), Vec::new());
    while let Some((field, wire)) = r.key()? {
        match (field, wire) {
            (1, 2) => name = r.string()?,
            (20, 0) => kind = r.varint()?,
            (2, 5) => f = Some(r.f32()?),
            (3, 0) => i = Some(r.varint()? as i64),
            (4, 2) => s = Some(String::from_utf8_lossy(r.bytes()?).into_owned()),
            (5, 2) => t = Some(read_tensor(r.sub()?, elements)?.1),
            (6, 2) => g = Some(read_graph(r.sub()?, depth + 1, elements)?),
            (7, 5) => floats.push(r.f32()?),
            (7, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(floats.len())?;
                    floats.push(p.f32()?);
                }
            }
            (8, 0) => ints.push(r.varint()? as i64),
            (8, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(ints.len())?;
                    ints.push(p.varint()? as i64);
                }
            }
            _ => r.skip(wire)?,
        }
    }
    // the type field says which value it is; older files without it are read by what is present
    let a = match kind {
        1 => f.map(Attr::Float),
        2 => i.map(Attr::Int),
        3 => s.map(Attr::Str),
        4 => t.map(Attr::Tensor),
        5 => g.map(|g| Attr::Graph(Box::new(g))),
        6 => Some(Attr::Floats(floats)),
        7 => Some(Attr::Ints(ints)),
        0 => f
            .map(Attr::Float)
            .or(i.map(Attr::Int))
            .or(s.map(Attr::Str))
            .or(t.map(Attr::Tensor))
            .or(g.map(|g| Attr::Graph(Box::new(g))))
            .or((!floats.is_empty()).then_some(Attr::Floats(floats)))
            .or((!ints.is_empty()).then_some(Attr::Ints(ints))),
        _ => Some(Attr::Other),
    };
    Ok((name, a.unwrap_or(Attr::Other)))
}

fn cap(len: usize) -> Result<()> {
    if len >= MAX_LIST { Err(Error::Malformed("a list is too long".into())) } else { Ok(()) }
}

fn read_tensor(mut r: Reader<'_>, elements: &mut usize) -> Result<(String, TensorData)> {
    let mut dims = Vec::new();
    let mut code = 1i64;
    let mut name = String::new();
    let mut raw: Option<&[u8]> = None;
    let (mut floats, mut int32s, mut int64s, mut doubles) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
    while let Some((field, wire)) = r.key()? {
        match (field, wire) {
            (1, 0) => dims.push(r.varint()? as i64),
            (1, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(dims.len())?;
                    dims.push(p.varint()? as i64);
                }
            }
            (2, 0) => code = r.varint()? as i64,
            (8, 2) => name = r.string()?,
            (9, 2) => raw = Some(r.bytes()?),
            (4, 5) => floats.push(r.f32()?),
            (4, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(floats.len())?;
                    floats.push(p.f32()?);
                }
            }
            (5, 0) => int32s.push(r.varint()? as i64),
            (5, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(int32s.len())?;
                    int32s.push(p.varint()? as i64);
                }
            }
            (7, 0) => int64s.push(r.varint()? as i64),
            (7, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(int64s.len())?;
                    int64s.push(p.varint()? as i64);
                }
            }
            (10, 1) => doubles.push(r.f64()?),
            (10, 2) => {
                let mut p = r.sub()?;
                while !p.done() {
                    cap(doubles.len())?;
                    doubles.push(p.f64()?);
                }
            }
            (13, 2) | (14, 0) => return Err(Error::Unsupported(format!("tensor {name:?} keeps its weights outside the file"))),
            _ => r.skip(wire)?,
        }
    }
    let ty = ElemType::from_onnx(code)?;
    let dims: Vec<usize> = dims
        .iter()
        .map(|&d| usize::try_from(d).map_err(|_| Error::Malformed(format!("tensor {name:?} has a negative size"))))
        .collect::<Result<_>>()?;
    let count = dims.iter().try_fold(1usize, |a, &d| a.checked_mul(d)).ok_or_else(|| Error::Malformed("a tensor's size overflows".into()))?;
    *elements = elements.saturating_add(count);
    if *elements > MAX_ELEMENTS {
        return Err(Error::Malformed("the weights are too large".into()));
    }
    let data = match raw {
        Some(raw) => {
            if raw.len() != count.saturating_mul(ty.size()) {
                return Err(Error::Malformed(format!("tensor {name:?}: {} bytes for {count} values", raw.len())));
            }
            decode_raw(ty, raw)
        }
        None => {
            let data = match ty {
                ElemType::F32 => Data::F32(floats),
                ElemType::F64 => Data::F32(doubles.iter().map(|v| *v as f32).collect()),
                // half floats travel as their 16 bits in int32_data
                ElemType::F16 => Data::F32(int32s.iter().map(|v| half::f16::from_bits(*v as u16).to_f32()).collect()),
                ElemType::BF16 => Data::F32(int32s.iter().map(|v| half::bf16::from_bits(*v as u16).to_f32()).collect()),
                ElemType::I64 => Data::I64(int64s),
                ElemType::U64 => Data::I64(int64s),
                ElemType::Bool => Data::Bool(int32s.iter().map(|v| *v != 0).collect()),
                ElemType::U8 | ElemType::I8 | ElemType::I16 | ElemType::I32 | ElemType::U32 => Data::I64(int32s),
            };
            if data.len() != count {
                return Err(Error::Malformed(format!("tensor {name:?}: {} values for {count}", data.len())));
            }
            data
        }
    };
    Ok((name, TensorData { dims, data }))
}

fn decode_raw(ty: ElemType, raw: &[u8]) -> Data {
    match ty {
        ElemType::F32 => Data::F32(raw.as_chunks::<4>().0.iter().map(|b| f32::from_le_bytes(*b)).collect()),
        ElemType::F64 => Data::F32(raw.as_chunks::<8>().0.iter().map(|b| f64::from_le_bytes(*b) as f32).collect()),
        ElemType::F16 => Data::F32(raw.as_chunks::<2>().0.iter().map(|b| half::f16::from_le_bytes(*b).to_f32()).collect()),
        ElemType::BF16 => Data::F32(raw.as_chunks::<2>().0.iter().map(|b| half::bf16::from_le_bytes(*b).to_f32()).collect()),
        ElemType::I64 => Data::I64(raw.as_chunks::<8>().0.iter().map(|b| i64::from_le_bytes(*b)).collect()),
        ElemType::U64 => Data::I64(raw.as_chunks::<8>().0.iter().map(|b| u64::from_le_bytes(*b) as i64).collect()),
        ElemType::I32 => Data::I64(raw.as_chunks::<4>().0.iter().map(|b| i64::from(i32::from_le_bytes(*b))).collect()),
        ElemType::U32 => Data::I64(raw.as_chunks::<4>().0.iter().map(|b| i64::from(u32::from_le_bytes(*b))).collect()),
        ElemType::I16 => Data::I64(raw.as_chunks::<2>().0.iter().map(|b| i64::from(i16::from_le_bytes(*b))).collect()),
        ElemType::I8 => Data::I64(raw.iter().map(|b| i64::from(*b as i8)).collect()),
        ElemType::U8 => Data::I64(raw.iter().map(|b| i64::from(*b)).collect()),
        ElemType::Bool => Data::Bool(raw.iter().map(|b| *b != 0).collect()),
    }
}

/// A cursor over one protobuf message's bytes.
struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn done(&self) -> bool {
        self.pos >= self.buf.len()
    }

    /// The next field's number and wire type, or `None` at the end.
    fn key(&mut self) -> Result<Option<(u64, u8)>> {
        if self.done() {
            return Ok(None);
        }
        let k = self.varint()?;
        Ok(Some((k >> 3, (k & 7) as u8)))
    }

    fn varint(&mut self) -> Result<u64> {
        let mut v = 0u64;
        for shift in (0..64).step_by(7) {
            let b = *self.buf.get(self.pos).ok_or_else(|| Error::Malformed("the file ends inside a number".into()))?;
            self.pos += 1;
            v |= u64::from(b & 0x7f) << shift;
            if b & 0x80 == 0 {
                return Ok(v);
            }
        }
        Err(Error::Malformed("a number is too long".into()))
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(n)
            .filter(|e| *e <= self.buf.len())
            .ok_or_else(|| Error::Malformed("a field runs past the end of the file".into()))?;
        let s = self.buf.get(self.pos..end).ok_or_else(|| Error::Malformed("a field runs past the end of the file".into()))?;
        self.pos = end;
        Ok(s)
    }

    fn bytes(&mut self) -> Result<&'a [u8]> {
        let n = usize::try_from(self.varint()?).map_err(|_| Error::Malformed("a field is too long".into()))?;
        self.take(n)
    }

    fn sub(&mut self) -> Result<Reader<'a>> {
        Ok(Reader { buf: self.bytes()?, pos: 0 })
    }

    fn string(&mut self) -> Result<String> {
        Ok(String::from_utf8_lossy(self.bytes()?).into_owned())
    }

    fn f32(&mut self) -> Result<f32> {
        let b = self.take(4)?;
        let a: [u8; 4] = b.try_into().map_err(|_| Error::Malformed("a short float".into()))?;
        Ok(f32::from_le_bytes(a))
    }

    fn f64(&mut self) -> Result<f64> {
        let b = self.take(8)?;
        let a: [u8; 8] = b.try_into().map_err(|_| Error::Malformed("a short double".into()))?;
        Ok(f64::from_le_bytes(a))
    }

    fn skip(&mut self, wire: u8) -> Result<()> {
        match wire {
            0 => {
                self.varint()?;
            }
            1 => {
                self.take(8)?;
            }
            2 => {
                self.bytes()?;
            }
            5 => {
                self.take(4)?;
            }
            _ => return Err(Error::Malformed(format!("unknown protobuf wire type {wire}"))),
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hostile_bytes_are_errors_not_panics() {
        for bytes in [&[][..], &[0x3a, 0xff, 0xff, 0xff, 0xff, 0x0f][..], &[0x3a, 0x05, 0x0a][..], &[0xff; 16][..], &[0x0f][..]] {
            assert!(parse(bytes).is_err(), "{bytes:?}");
        }
    }

    #[test]
    fn a_small_graph_is_read() {
        // graph { node { input "x" output "y" op_type "Relu" } input { name "x" } output { name "y" } }
        let node = [&[0x0a, 0x01, b'x'][..], &[0x12, 0x01, b'y'], &[0x22, 0x04], b"Relu"].concat();
        let graph = [&[0x0a, node.len() as u8][..], &node, &[0x5a, 0x03, 0x0a, 0x01, b'x'], &[0x62, 0x03, 0x0a, 0x01, b'y']].concat();
        let model = [&[0x3a, graph.len() as u8][..], &graph].concat();
        let m = parse(&model).unwrap();
        assert_eq!(m.graph.nodes.len(), 1);
        assert_eq!(m.graph.nodes[0].op, "Relu");
        assert_eq!(m.graph.inputs, vec!["x".to_string()]);
        assert_eq!(m.graph.outputs, vec!["y".to_string()]);
    }
}
