//! Run a model on reference inputs and compare with reference outputs (made by onnxruntime):
//! `cargo run --release -p lightcraft-nn --example compare -- model.onnx dir/` where `dir` holds
//! `in_<name>.json/.bin` and `out_<name>.json/.bin` (shape + dtype, raw little-endian values).
#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]

use lightcraft_nn::{Session, Tensor};

fn read(dir: &std::path::Path, stem: &str) -> Tensor {
    let meta = std::fs::read_to_string(dir.join(format!("{stem}.json"))).unwrap();
    let shape: Vec<usize> = meta
        .split("\"shape\": [")
        .nth(1)
        .unwrap()
        .split(']')
        .next()
        .unwrap()
        .split(',')
        .filter(|s| !s.trim().is_empty())
        .map(|s| s.trim().parse().unwrap())
        .collect();
    let dtype = meta.split("\"dtype\": \"").nth(1).unwrap().split('"').next().unwrap();
    let raw = std::fs::read(dir.join(format!("{stem}.bin"))).unwrap();
    match dtype {
        "f32" => Tensor::f32(shape, raw.chunks_exact(4).map(|b| f32::from_le_bytes(b.try_into().unwrap())).collect()).unwrap(),
        "i64" => Tensor::i64(shape, raw.chunks_exact(8).map(|b| i64::from_le_bytes(b.try_into().unwrap())).collect()).unwrap(),
        "u8" => Tensor::i64(shape, raw.iter().map(|b| i64::from(*b)).collect()).unwrap(),
        "bool" => Tensor::bool(shape, raw.iter().map(|b| *b != 0).collect()).unwrap(),
        other => panic!("dtype {other}"),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let (model, dir) = (&args[1], std::path::Path::new(&args[2]));
    let t0 = std::time::Instant::now();
    let s = Session::load(&std::fs::read(model).unwrap()).unwrap();
    println!("loaded in {:?}", t0.elapsed());
    let inputs: Vec<(String, Tensor)> = s.inputs().iter().map(|n| (n.clone(), read(dir, &format!("in_{}", n.replace(['/', ':'], "_"))))).collect();
    let t1 = std::time::Instant::now();
    let out = s.run(inputs, None).unwrap();
    println!("ran in {:?}", t1.elapsed());
    for name in s.outputs() {
        let stem = format!("out_{}", name.replace(['/', ':'], "_"));
        if !dir.join(format!("{stem}.bin")).exists() {
            continue;
        }
        let want = read(dir, &stem);
        let got = &out[name];
        assert_eq!(got.shape, want.shape, "{name} shape");
        let (g, w) = (got.to_f32s(), want.to_f32s());
        let (mut max, mut sum) = (0f32, 0f64);
        for (a, b) in g.iter().zip(&w) {
            let d = (a - b).abs();
            max = max.max(d);
            sum += f64::from(d);
        }
        let range = w.iter().fold(0f32, |m, v| m.max(v.abs()));
        println!("{name}: shape {:?} max |Δ| {max:.5} mean |Δ| {:.6} (values up to {range:.3})", got.shape, sum / g.len().max(1) as f64);
    }
}
