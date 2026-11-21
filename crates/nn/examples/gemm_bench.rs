//! How fast candle's CPU matrix product is here (GFLOP/s), for sizing the runner's kernels.
#![allow(clippy::unwrap_used)]
fn main() {
    let dev = candle_core::Device::Cpu;
    for (m, k, n) in [(1024, 1024, 1024), (4096, 192, 4096), (64, 576, 65536)] {
        let a = candle_core::Tensor::ones((m, k), candle_core::DType::F32, &dev).unwrap();
        let b = candle_core::Tensor::ones((k, n), candle_core::DType::F32, &dev).unwrap();
        let _ = a.matmul(&b).unwrap();
        let t = std::time::Instant::now();
        let r = 3;
        for _ in 0..r {
            let _ = a.matmul(&b).unwrap();
        }
        let s = t.elapsed().as_secs_f64() / r as f64;
        println!("{m}x{k}x{n}: {:.1} ms, {:.1} GFLOP/s", s * 1e3, 2.0 * (m * k * n) as f64 / s / 1e9);
    }
    // the runner's own MatMul on the EfficientSAM encoder's shapes
    use lightcraft_nn::Tensor;
    for (a, b) in [(vec![1, 3, 4096, 64], vec![1, 3, 64, 4096]), (vec![1, 3, 4096, 4096], vec![1, 3, 4096, 64]), (vec![1, 4096, 192], vec![192, 576])]
    {
        let na: usize = a.iter().product();
        let nb: usize = b.iter().product();
        let ta = Tensor::f32(a.clone(), vec![0.5; na]).unwrap();
        let tb = Tensor::f32(b.clone(), vec![0.5; nb]).unwrap();
        let t = std::time::Instant::now();
        let _ = lightcraft_nn::ops::nn::matmul(&ta, &tb).unwrap();
        println!("runner MatMul {a:?} x {b:?}: {:.1} ms", t.elapsed().as_secs_f64() * 1e3);
        let t = std::time::Instant::now();
        let c = ta.to_candle().unwrap();
        println!("   to_candle {:.1} ms", t.elapsed().as_secs_f64() * 1e3);
        let t = std::time::Instant::now();
        let _ = Tensor::from_candle(&c).unwrap();
        println!("   from_candle {:.1} ms", t.elapsed().as_secs_f64() * 1e3);
    }
}
