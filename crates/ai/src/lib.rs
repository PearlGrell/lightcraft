//! LightCraft's optional on-device AI models: [`models`] lists them (where each comes from, its
//! size, checksum and licence), [`Store`] downloads, checks and loads them, and each task module
//! turns pixels into what the engine stores:
//!
//! - [`select`]: tap to select an object (EfficientSAM-Ti) → a segmentation grid over the photo.
//! - [`inpaint`]: fill a hole with generated content (MI-GAN) → the filled pixels.
//! - [`detect`]: find people and everyday objects (D-FINE-N) → labelled boxes.
//! - [`matte`]: how much of each pixel is the person (MODNet) → a hair-fine matte.
//! - [`depth`]: how near each part of the photo is (Depth Anything V2 Small) → a depth grid.
//!
//! No model is part of LightCraft: each downloads only when the user asks, and every feature that
//! uses one says so when it is missing. Everything runs on the CPU through `lightcraft-nn`.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod depth;
pub mod detect;
pub mod image;
pub mod inpaint;
pub mod matte;
pub mod models;
pub mod select;
pub mod store;

pub use image::Rgb8;
pub use store::{Status, Store};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The model isn't downloaded (or its file is damaged): the caller offers the download.
    #[error("the {0} model isn't downloaded")]
    Missing(&'static str),
    #[error("{0}")]
    Model(#[from] lightcraft_nn::Error),
    #[error("{0}")]
    Io(String),
    #[error("{0}")]
    Input(String),
    #[error("{0}")]
    Download(String),
}

pub type Result<T> = std::result::Result<T, Error>;
