//! LightCraft's ONNX runner: the optional AI models (tap-to-select, inpainting, object detection,
//! depth, portrait matting, restoration) run through it, on the CPU, in pure Rust.
//!
//! A model file is read by [`proto`], a bounded reader that treats the bytes as hostile. [`Session`]
//! then runs the graph eagerly: each node in file order on [`candle_core`] tensors (multi-threaded
//! matrix products and convolutions), with the operators candle lacks (bilinear resize, grid
//! sampling, top-k…) written here. Unknown operators or attributes are errors that name them, never
//! a panic, and every size that comes from the file or the caller is checked.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod exec;
pub mod ops;
pub mod proto;
pub mod tensor;

pub use exec::Session;
pub use tensor::Tensor;

/// What can go wrong reading or running a model.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("malformed model: {0}")]
    Malformed(String),
    #[error("unsupported: {0}")]
    Unsupported(String),
    #[error("{0}")]
    Run(String),
}

#[cfg(not(target_arch = "wasm32"))]
impl From<candle_core::Error> for Error {
    fn from(e: candle_core::Error) -> Self {
        Error::Run(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
