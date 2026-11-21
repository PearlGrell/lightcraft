//! Where the models live on this device: [`Store`] knows which are downloaded, downloads and
//! checks them (exact size and SHA-256, through `lightcraft-fetch`), loads a model file the first
//! time a task needs it and keeps it until [`Store::unload`].

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};

use lightcraft_nn::Session;

use crate::models::{self, Model, ModelFile};
use crate::{Error, Result};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    /// Every file is there at its exact size.
    Ready,
    Missing,
}

pub struct Store {
    dir: PathBuf,
    loaded: Mutex<HashMap<&'static str, Arc<Session>>>,
}

impl Store {
    /// The models in `dir` (created when a download starts).
    pub fn new(dir: impl Into<PathBuf>) -> Store {
        Store { dir: dir.into(), loaded: Mutex::new(HashMap::new()) }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    fn model(id: &str) -> Result<&'static Model> {
        models::find(id).ok_or_else(|| Error::Input(format!("no model {id:?}")))
    }

    pub fn status(&self, id: &str) -> Result<Status> {
        let m = Self::model(id)?;
        let ok = m.files.iter().all(|f| std::fs::metadata(self.dir.join(f.name)).is_ok_and(|md| md.is_file() && md.len() == f.size));
        Ok(if ok { Status::Ready } else { Status::Missing })
    }

    /// Download model `id`'s files (resuming a partial one; each checked before it is kept).
    /// `progress(done, total)` in bytes over all its files; `cancel` stops it between reads.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn download(&self, id: &str, cancel: &std::sync::atomic::AtomicBool, progress: &mut dyn FnMut(u64, u64)) -> Result<()> {
        let m = Self::model(id)?;
        std::fs::create_dir_all(&self.dir).map_err(|e| Error::Io(format!("{}: {e}", self.dir.display())))?;
        let total = m.size();
        let mut before = 0u64;
        for f in m.files {
            if std::fs::metadata(self.dir.join(f.name)).is_ok_and(|md| md.len() == f.size) {
                before += f.size;
                continue;
            }
            let spec = lightcraft_fetch::FileSpec { name: f.name, size: Some(f.size), sha256: Some(f.sha256), max: f.size };
            let base = before;
            lightcraft_fetch::download_url(&spec, f.url, &self.dir, &lightcraft_fetch::Options::default(), cancel, &mut |p| {
                progress(base + p.done, total)
            })
            .map_err(|e| Error::Download(e.to_string()))?;
            before += f.size;
        }
        progress(total, total);
        Ok(())
    }

    /// Delete model `id`'s files (and forget it if loaded).
    pub fn remove(&self, id: &str) -> Result<()> {
        let m = Self::model(id)?;
        self.loaded.lock().unwrap_or_else(PoisonError::into_inner).retain(|k, _| !m.files.iter().any(|f| f.name == *k));
        for f in m.files {
            let p = self.dir.join(f.name);
            if p.exists() {
                std::fs::remove_file(&p).map_err(|e| Error::Io(format!("{}: {e}", p.display())))?;
            }
        }
        Ok(())
    }

    /// The loaded network for `file` of model `id`, loading it the first time.
    pub(crate) fn session(&self, id: &'static str, file: &ModelFile) -> Result<Arc<Session>> {
        if let Some(s) = self.loaded.lock().unwrap_or_else(PoisonError::into_inner).get(file.name) {
            return Ok(s.clone());
        }
        let path = self.dir.join(file.name);
        let ok = std::fs::metadata(&path).is_ok_and(|md| md.len() == file.size);
        if !ok {
            return Err(Error::Missing(Self::model(id).map(|m| m.name).unwrap_or(id)));
        }
        let bytes = std::fs::read(&path).map_err(|e| Error::Io(format!("{}: {e}", path.display())))?;
        let s = Arc::new(Session::load(&bytes)?);
        self.loaded.lock().unwrap_or_else(PoisonError::into_inner).insert(file.name, s.clone());
        Ok(s)
    }

    /// Model `id`'s file `index`, for the task modules.
    pub(crate) fn file(id: &'static str, index: usize) -> Result<&'static ModelFile> {
        Self::model(id)?.files.get(index).ok_or_else(|| Error::Input(format!("model {id} has no file {index}")))
    }

    /// Free every loaded network (the phone wants its memory back).
    pub fn unload(&self) {
        self.loaded.lock().unwrap_or_else(PoisonError::into_inner).clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_model_says_so() {
        let dir = std::env::temp_dir().join(format!("lightcraft-ai-store-{}", std::process::id()));
        let s = Store::new(&dir);
        assert_eq!(s.status(models::SELECT).unwrap(), Status::Missing);
        assert!(s.status("nope").is_err());
        let f = Store::file(models::INPAINT, 0).unwrap();
        assert!(matches!(s.session(models::INPAINT, f), Err(Error::Missing(_))));
        // a file of the wrong size is not the model
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(f.name), b"not a model").unwrap();
        assert_eq!(s.status(models::INPAINT).unwrap(), Status::Missing);
        s.remove(models::INPAINT).unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
