//! JNI entry points of the LightCraft Android app: `ai.storyteller.lightcraft.android.Engine`.
//!
//! Each `Java_…` function is a thin, panic-proof wrapper around [`lightcraft_mobile::Mobile`]. Sessions
//! are kept in a handle table, so Kotlin holds a plain `long` id and no raw pointer crosses the
//! boundary: a stale or made-up handle is an error answer, never undefined behaviour.
//!
//! The only `unsafe` here is the `#[unsafe(no_mangle)]` attribute the JVM needs to find the
//! functions by name; there are no `unsafe` blocks (the `jni` crate wraps the raw interface).
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]
#![allow(unsafe_code)] // `#[unsafe(no_mangle)]` only: see the crate docs

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, PoisonError};

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloat, jfloatArray, jint, jintArray, jlong, jlongArray, jstring};
use lightcraft_mobile::{Frame, Mobile};

static SESSIONS: Mutex<Vec<Option<Arc<Mobile>>>> = Mutex::new(Vec::new());

fn create(demo: bool) -> i64 {
    let m = Arc::new(if demo { Mobile::demo() } else { Mobile::new() });
    let mut table = SESSIONS.lock().unwrap_or_else(PoisonError::into_inner);
    table.push(Some(m));
    i64::try_from(table.len()).unwrap_or(0)
}

fn session(handle: i64) -> Result<Arc<Mobile>, String> {
    let table = SESSIONS.lock().unwrap_or_else(PoisonError::into_inner);
    usize::try_from(handle)
        .ok()
        .and_then(|h| h.checked_sub(1))
        .and_then(|i| table.get(i))
        .and_then(Option::clone)
        .ok_or_else(|| format!("no LightCraft session {handle}"))
}

fn release(handle: i64) {
    let mut table = SESSIONS.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(slot) = usize::try_from(handle).ok().and_then(|h| h.checked_sub(1)).and_then(|i| table.get_mut(i)) {
        *slot = None;
    }
}

/// Run `f`, turning a panic into an error message.
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|p| {
        let msg = p.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| p.downcast_ref::<String>().cloned());
        Err(format!("internal error: {}", msg.unwrap_or_else(|| "panic".into())))
    })
}

fn error_json(msg: &str) -> String {
    serde_json_escape(msg)
}

/// `{"ok":false,"kind":"Other","error":msg}` without pulling serde into this crate.
fn serde_json_escape(msg: &str) -> String {
    let mut out = String::with_capacity(msg.len() + 40);
    out.push_str("{\"ok\":false,\"kind\":\"Other\",\"error\":\"");
    for c in msg.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if u32::from(c) < 0x20 => out.push_str(&format!("\\u{:04x}", u32::from(c))),
            c => out.push(c),
        }
    }
    out.push_str("\"}");
    out
}

/// Pixels for `android.graphics.Bitmap.createBitmap(colors, 2, width, width, height, ARGB_8888)`:
/// `[width, height, argb…]`.
fn frame_ints(f: &Frame) -> Vec<i32> {
    let mut out = Vec::with_capacity(f.rgba.len() / 4 + 2);
    out.push(i32::try_from(f.width).unwrap_or(0));
    out.push(i32::try_from(f.height).unwrap_or(0));
    out.extend(f.rgba.chunks_exact(4).map(|p| i32::from_be_bytes([p[3], p[0], p[1], p[2]])));
    out
}

fn rust_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(String::from).map_err(|e| e.to_string())
}

fn java_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn int_array(env: &mut JNIEnv, data: &[i32]) -> jintArray {
    let Ok(len) = i32::try_from(data.len()) else { return std::ptr::null_mut() };
    match env.new_int_array(len) {
        Ok(arr) => match env.set_int_array_region(&arr, 0, data) {
            Ok(()) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCreate(_env: JNIEnv, _class: JClass, demo: jboolean) -> jlong {
    guarded(|| Ok(create(demo != 0))).unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeRelease(_env: JNIEnv, _class: JClass, handle: jlong) {
    let _ = guarded(|| {
        release(handle);
        Ok(())
    });
}

/// Returns `null` on success, else the error message.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeOpenLibrary<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dir: JString<'l>,
    seed_demo: jboolean,
) -> jstring {
    let r = rust_string(&mut env, &dir).and_then(|dir| guarded(|| session(handle)?.open_library(&dir, seed_demo != 0)));
    match r {
        Ok(_) => std::ptr::null_mut(),
        Err(e) => java_string(&mut env, &e),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeExecute<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    id: JString<'l>,
    params: JString<'l>,
) -> jstring {
    let r = rust_string(&mut env, &id).and_then(|id| {
        let params = rust_string(&mut env, &params)?;
        guarded(|| Ok(session(handle)?.execute(&id, &params)))
    });
    let answer = r.unwrap_or_else(|e| error_json(&e));
    java_string(&mut env, &answer)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativePhotos<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jlongArray {
    let ids: Vec<i64> = match guarded(|| Ok(session(handle)?.photos())) {
        Ok(ids) => ids.into_iter().filter_map(|i| i64::try_from(i).ok()).collect(),
        Err(e) => {
            throw(&mut env, &e);
            return std::ptr::null_mut();
        }
    };
    let Ok(len) = i32::try_from(ids.len()) else { return std::ptr::null_mut() };
    match env.new_long_array(len) {
        Ok(arr) => match env.set_long_array_region(&arr, 0, &ids) {
            Ok(()) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// The library photo for a gallery file, imported in place the first time; throws
/// `IllegalStateException` when the file can't be read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativePhotoForPath<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    path: JString<'l>,
) -> jlong {
    let r = rust_string(&mut env, &path).and_then(|path| guarded(|| session(handle)?.photo_for_path(&path)));
    match r.and_then(|id| i64::try_from(id).map_err(|e| e.to_string())) {
        Ok(id) => id,
        Err(e) => {
            throw(&mut env, &e);
            0
        }
    }
}

fn photo_id(photo: jlong) -> Result<u64, String> {
    u64::try_from(photo).map_err(|_| format!("no photo {photo}"))
}

fn edge(v: jint) -> Result<u32, String> {
    u32::try_from(v).map_err(|_| format!("bad render size {v}"))
}

/// `[width, height, argb…]`, or throws `IllegalStateException` with the reason.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeRender<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    max_w: jint,
    max_h: jint,
    before: jboolean,
    draft: jboolean,
) -> jintArray {
    let r = guarded(|| session(handle)?.render(photo_id(photo)?, edge(max_w)?, edge(max_h)?, before != 0, draft != 0));
    match r {
        Ok(f) => int_array(&mut env, &frame_ints(&f)),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeThumbnail<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    long_edge: jint,
) -> jintArray {
    match guarded(|| session(handle)?.thumbnail(photo_id(photo)?, edge(long_edge)?)) {
        Ok(f) => int_array(&mut env, &frame_ints(&f)),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

/// Export `photo` into the folder `dir` with the export dialog's JSON `options`; returns the path
/// written, or throws `IllegalStateException` with the reason.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeExport<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    options: JString<'l>,
    dir: JString<'l>,
) -> jstring {
    let r = rust_string(&mut env, &options).and_then(|options| {
        let dir = rust_string(&mut env, &dir)?;
        guarded(|| session(handle)?.export(photo_id(photo)?, &options, &dir))
    });
    match r {
        Ok(path) => java_string(&mut env, &path),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativePresetThumbnail<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    preset: JString<'l>,
    long_edge: jint,
) -> jintArray {
    let r =
        rust_string(&mut env, &preset).and_then(|preset| guarded(|| session(handle)?.preset_thumbnail(photo_id(photo)?, &preset, edge(long_edge)?)));
    match r {
        Ok(f) => int_array(&mut env, &frame_ints(&f)),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

fn floats(env: &mut JNIEnv, a: &JFloatArray) -> Result<Vec<f32>, String> {
    let len = env.get_array_length(a).map_err(|e| e.to_string())?;
    let len = usize::try_from(len).map_err(|e| e.to_string())?;
    if len > 4 * 1024 * 1024 {
        return Err(format!("{len} values is too many"));
    }
    let mut out = vec![0f32; len];
    env.get_float_array_region(a, 0, &mut out).map_err(|e| e.to_string())?;
    Ok(out)
}

/// Throw `IllegalStateException` for an error; `T::default()` is what Kotlin then never sees.
fn or_throw<T: Default>(env: &mut JNIEnv, r: Result<T, String>) -> T {
    r.unwrap_or_else(|e| {
        throw(env, &e);
        T::default()
    })
}

/// Adds a mask from on-device segmentation (`side × side` probabilities); returns the engine's JSON answer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeAddSegMask<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    name: JString<'l>,
    side: jint,
    probs: JFloatArray<'l>,
    invert: jboolean,
) -> jstring {
    let r = rust_string(&mut env, &name).and_then(|name| {
        let probs = floats(&mut env, &probs)?;
        let side = u32::try_from(side).map_err(|_| format!("bad mask size {side}"))?;
        guarded(|| Ok(session(handle)?.add_seg_mask(&name, side, &probs, invert != 0)))
    });
    let answer = r.unwrap_or_else(|e| error_json(&e));
    java_string(&mut env, &answer)
}

/// `[width, height]` of the background-removal workspace.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutBegin<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    long_edge: jint,
) -> jintArray {
    let r = guarded(|| session(handle)?.cutout_begin(photo_id(photo)?, edge(long_edge)?));
    match r {
        Ok((w, h)) => int_array(&mut env, &[i32::try_from(w).unwrap_or(0), i32::try_from(h).unwrap_or(0)]),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutMatte<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    side: jint,
    probs: JFloatArray<'l>,
    invert: jboolean,
) {
    let r = floats(&mut env, &probs).and_then(|probs| {
        let side = u32::try_from(side).map_err(|_| format!("bad mask size {side}"))?;
        guarded(|| session(handle)?.cutout_matte(side, &probs, invert != 0))
    });
    or_throw(&mut env, r)
}

/// Magic eraser at a normalized point; returns how many pixels changed.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutErase<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x: jfloat,
    y: jfloat,
    tolerance: jfloat,
    contiguous: jboolean,
    sample: jint,
    anti_alias: jboolean,
    feather: jfloat,
    opacity: jfloat,
) -> jint {
    let o = lightcraft_mobile::EraseOptions {
        tolerance,
        contiguous: contiguous != 0,
        sample: u32::try_from(sample).unwrap_or(0),
        anti_alias: anti_alias != 0,
        feather,
        opacity,
    };
    let r = guarded(|| session(handle)?.cutout_erase(x, y, &o)).map(|n| i32::try_from(n).unwrap_or(i32::MAX));
    or_throw(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutUndo<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jboolean {
    let r = guarded(|| session(handle)?.cutout_undo());
    u8::from(or_throw(&mut env, r))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutReset<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>, handle: jlong) {
    let r = guarded(|| session(handle)?.cutout_reset());
    or_throw(&mut env, r)
}

/// The photo with the matte as alpha: `[width, height, argb…]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutFrame<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jintArray {
    match guarded(|| session(handle)?.cutout_frame()) {
        Ok(f) => int_array(&mut env, &frame_ints(&f)),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

/// Writes the transparent PNG into `dir`; returns its path.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutExport<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dir: JString<'l>,
    name: JString<'l>,
    refine: jboolean,
) -> jstring {
    let r = rust_string(&mut env, &dir).and_then(|dir| {
        let name = rust_string(&mut env, &name)?;
        guarded(|| session(handle)?.cutout_export(&dir, &name, refine != 0))
    });
    match r {
        Ok(path) => java_string(&mut env, &path),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeCutoutEnd(_env: JNIEnv, _class: JClass, handle: jlong) {
    let _ = guarded(|| {
        session(handle)?.cutout_end();
        Ok(())
    });
}

/// The histogram of `photo` with its edits: `[r×256, g×256, b×256, luma×256, shadows‰, highlights‰]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeHistogram<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    long_edge: jint,
) -> jintArray {
    match guarded(|| session(handle)?.histogram(photo_id(photo)?, edge(long_edge)?)) {
        Ok(h) => int_array(&mut env, &h.to_ints()),
        Err(e) => {
            throw(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

/// `Mobile::gallery_marks` as a JSON string (`[]` when the session is gone).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeGalleryMarks<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    let marks = guarded(|| Ok(session(handle)?.gallery_marks())).unwrap_or_else(|_| "[]".into());
    java_string(&mut env, &marks)
}

/// The gallery files at `paths` (one per line) were deleted: drop their photos. Returns how many
/// went, or throws with the reason.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeForgetPaths<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    paths: JString<'l>,
) -> jint {
    let r = rust_string(&mut env, &paths).and_then(|paths| {
        let list: Vec<String> = split_lines(&paths);
        guarded(|| session(handle)?.forget_paths(&list))
    });
    match r {
        Ok(n) => i32::try_from(n).unwrap_or(i32::MAX),
        Err(e) => {
            throw(&mut env, &e);
            0
        }
    }
}

fn split_lines(s: &str) -> Vec<String> {
    s.lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_string).collect()
}

/// A point of the shown (cropped, rotated) photo → the uncropped photo's normalized point, as
/// `[x, y]`; an empty array when it maps nowhere.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeViewToImage<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    photo: jlong,
    u: jfloat,
    v: jfloat,
) -> jfloatArray {
    let p = guarded(|| Ok(session(handle)?.view_to_image(photo_id(photo)?, u, v))).ok().flatten();
    let data: Vec<f32> = p.map(|(x, y)| vec![x, y]).unwrap_or_default();
    let Ok(len) = i32::try_from(data.len()) else { return std::ptr::null_mut() };
    match env.new_float_array(len) {
        Ok(arr) => match env.set_float_array_region(&arr, 0, &data) {
            Ok(()) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Keep AI models (SAM 3) under `dir`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeSetModelsDir<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dir: JString<'l>,
) {
    let r = rust_string(&mut env, &dir).and_then(|dir| {
        guarded(|| {
            session(handle)?.set_models_dir(&dir);
            Ok(())
        })
    });
    if let Err(e) = r {
        throw(&mut env, &e);
    }
}

/// Whether this build includes AI masks (SAM 3).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_storyteller_lightcraft_android_Engine_nativeAiMasksAvailable(_env: JNIEnv, _class: JClass) -> jboolean {
    jboolean::from(guarded(|| Ok(Mobile::ai_masks_available())).unwrap_or(false))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forgotten_paths_are_split_by_line() {
        assert_eq!(split_lines("/a.jpg\n\n  /b c.jpg \r\n"), vec!["/a.jpg".to_string(), "/b c.jpg".to_string()]);
        assert!(split_lines("").is_empty());
    }

    #[test]
    fn handles_are_checked() {
        let h = create(true);
        assert!(session(h).is_ok());
        release(h);
        assert!(session(h).is_err());
        assert!(session(0).is_err() && session(-1).is_err() && session(i64::MAX).is_err());
    }

    #[test]
    fn frames_become_argb_ints() {
        let f = Frame { width: 1, height: 1, rgba: vec![0x11, 0x22, 0x33, 0xff] };
        assert_eq!(frame_ints(&f), vec![1, 1, 0xff112233_u32 as i32]);
    }

    #[test]
    fn error_answers_are_json() {
        assert_eq!(error_json("a \"b\"\n"), r#"{"ok":false,"kind":"Other","error":"a \"b\"\u000a"}"#);
    }
}
