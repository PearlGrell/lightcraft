//! The models LightCraft can download: each file pinned to one upstream commit, with its exact
//! size and SHA-256 (a file that doesn't match is never used), and the licence the user reads
//! before downloading.

/// One file of a model.
#[derive(Clone, Copy, Debug)]
pub struct ModelFile {
    /// The name it is stored under in the models folder.
    pub name: &'static str,
    pub url: &'static str,
    pub size: u64,
    pub sha256: &'static str,
}

#[derive(Clone, Copy, Debug)]
pub struct Model {
    pub id: &'static str,
    pub name: &'static str,
    /// What it does in LightCraft, in a few words.
    pub purpose: &'static str,
    pub files: &'static [ModelFile],
    pub licence: &'static str,
    /// Who made it and where the weights come from.
    pub source: &'static str,
}

impl Model {
    pub fn size(&self) -> u64 {
        self.files.iter().map(|f| f.size).sum()
    }
}

pub const SELECT: &str = "efficientsam-ti";
pub const INPAINT: &str = "migan-512";
pub const INPAINT_BEST: &str = "lama-512";
pub const DETECT: &str = "dfine-n";
pub const DEPTH: &str = "depth-anything-v2-small";
pub const MATTE: &str = "modnet";

pub const MODELS: &[Model] = &[
    Model {
        id: SELECT,
        name: "EfficientSAM-Ti",
        purpose: "Select an object by tapping it",
        files: &[
            ModelFile {
                name: "efficientsam_ti_encoder.onnx",
                url: "https://huggingface.co/yunyangx/EfficientSAM/resolve/1cf49585c39567bfc49e991ab8eb31f491ad4877/efficientsam_ti_encoder.onnx",
                size: 24_799_761,
                sha256: "84ed466ffcc5c1f8d08409bc34a23bb364ab2c15e402cb12d4335a42be0e0951",
            },
            ModelFile {
                name: "efficientsam_ti_decoder.onnx",
                url: "https://huggingface.co/yunyangx/EfficientSAM/resolve/1cf49585c39567bfc49e991ab8eb31f491ad4877/efficientsam_ti_decoder.onnx",
                size: 16_565_728,
                sha256: "a62f8fa5ea080447c0689418d69e58f1e83e0b7adf9c142e2bd9bcc8045c0b11",
            },
        ],
        licence: "Apache License 2.0",
        source: "EfficientSAM (Xiong et al., Meta AI, 2023), yunyangx/EfficientSAM",
    },
    Model {
        id: INPAINT,
        name: "MI-GAN 512",
        purpose: "Fill what you remove with new content",
        files: &[ModelFile {
            name: "migan.onnx",
            url: "https://huggingface.co/andraniksargsyan/migan/resolve/406830d0fa60666da0071c342ad2fbc8f30c5c64/migan.onnx",
            size: 29_546_882,
            sha256: "593eba0b7e04730f1b61c0a3cbca68d97d8d6a7ff5c6a44a7b9d7fcd880fc5ae",
        }],
        licence: "MIT License",
        source: "MI-GAN (Sargsyan et al., Picsart AI Research, ICCV 2023), uploaded by its first author",
    },
    Model {
        id: INPAINT_BEST,
        name: "LaMa",
        purpose: "Fill large removed areas (best quality, slower)",
        files: &[ModelFile {
            name: "inpainting_lama_2025jan.onnx",
            url: "https://huggingface.co/opencv/inpainting_lama/resolve/aee6d22f0a13e5e35af1c9a1c3afd62841fc6f3f/inpainting_lama_2025jan.onnx",
            size: 92_591_623,
            sha256: "7df918ac3921d3daf0aae1d219776cf0dc4e4935f035af81841b40adcf74fdf2",
        }],
        licence: "Apache License 2.0",
        source: "LaMa (Suvorov et al., WACV 2022), quantized ONNX from the OpenCV model zoo",
    },
    Model {
        id: DETECT,
        name: "D-FINE-N",
        purpose: "Find people and objects to suggest",
        files: &[ModelFile {
            name: "dfine_n_coco.onnx",
            url: "https://huggingface.co/onnx-community/dfine_n_coco-ONNX/resolve/380d2839c327efaf65dd0fe0c2c10ab7fadd5473/onnx/model.onnx",
            size: 15_258_358,
            sha256: "0f684f409618ee8a822410e754a29caa817d1aa16283ce89cad936d0a48e2f35",
        }],
        licence: "Apache License 2.0",
        source: "D-FINE (Peng et al., ICLR 2025), ustc-community/dfine-nano-coco, ONNX by onnx-community",
    },
    Model {
        id: DEPTH,
        name: "Depth Anything V2 Small",
        purpose: "Depth for Lens Blur",
        files: &[ModelFile {
            name: "depth_anything_v2_small.onnx",
            url: "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/4472b7362082ad9968fee890ca0f1e5aca36b93d/onnx/model.onnx",
            size: 99_060_839,
            sha256: "afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c",
        }],
        licence: "Apache License 2.0 (the Small model only)",
        source: "Depth Anything V2 (Yang et al., 2024), ONNX by onnx-community",
    },
    Model {
        id: MATTE,
        name: "MODNet",
        purpose: "Hair-fine edges for people",
        files: &[ModelFile {
            name: "modnet.onnx",
            url: "https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx",
            size: 25_888_640,
            sha256: "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9",
        }],
        licence: "Apache License 2.0",
        source: "MODNet (Ke et al., AAAI 2022), ONNX by Xenova",
    },
];

pub fn find(id: &str) -> Option<&'static Model> {
    MODELS.iter().find(|m| m.id == id)
}
