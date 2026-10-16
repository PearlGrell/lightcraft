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
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jlongArray, jstring};
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

#[cfg(test)]
mod tests {
    use super::*;

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
