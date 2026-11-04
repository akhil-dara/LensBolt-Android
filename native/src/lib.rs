//! JNI surface of the phone receiver (class `com.lensbolt.app.Native`). The camera analyzer calls `submit`
//! with the three YUV_420_888 planes; the planes are copied only when a decode worker is free (the copy is
//! a few ms; the analyzer closes the image right after), Y always and U/V only when the receiver asks for
//! them (`want_chroma`: a b/w tile reads from Y alone), and the RGB conversion runs on the worker.

use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use lensbolt_core::flat::live::{Capture, LiveConfig, LiveReceiver};
use lensbolt_core::image::{GrayImage, RgbImage};
use std::path::PathBuf;

/// The camera plane behind a direct ByteBuffer, borrowed: valid until the analyzer closes the image (after
/// `submit` returns), so anything kept is copied out before then.
fn plane_ref<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let len = env.get_direct_buffer_capacity(buf).ok()?;
    if ptr.is_null() || len == 0 { return None; }
    // SAFETY: a direct ByteBuffer of the camera image, valid until the analyzer closes the image (after we return)
    Some(unsafe { std::slice::from_raw_parts(ptr, len) })
}
fn plane(env: &JNIEnv, buf: &JByteBuffer) -> Option<Vec<u8>> { plane_ref(env, buf).map(|p| p.to_vec()) }

#[no_mangle]
pub extern "system" fn Java_com_lensbolt_app_Native_create(mut env: JNIEnv, _: JClass, out_dir: JString, workers: jint) -> jlong {
    // one thread per capture: the workers decode captures side by side; a decode that also fanned out over
    // every core oversubscribed the phone (measured: 600 ms per capture with 6 workers x 8 threads)
    if std::env::var_os("LB_THREADS").is_none() { std::env::set_var("LB_THREADS", "1"); }
    let Ok(dir) = env.get_string(&out_dir) else { return 0 };
    let dir = PathBuf::from(String::from(dir));
    let _ = std::fs::create_dir_all(&dir);
    let cfg = if workers > 0 { LiveConfig { workers: workers as usize } } else { LiveConfig::default() };
    match LiveReceiver::new(&dir, cfg) { Ok(r) => Box::into_raw(Box::new(r)) as jlong, Err(_) => 0 }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_lensbolt_app_Native_submit(
    env: JNIEnv, _: JClass, handle: jlong, y: JByteBuffer, u: JByteBuffer, v: JByteBuffer,
    width: jint, height: jint, y_row: jint, uv_row: jint, uv_pixel: jint,
) -> jboolean {
    if handle == 0 || width <= 0 || height <= 0 { return JNI_FALSE; }
    // SAFETY: handle came from create and is only freed by destroy, which the app calls after unbinding the camera
    let live = unsafe { &*(handle as *const LiveReceiver) };
    if !live.has_room() { live.skip(); return JNI_FALSE; }
    let (w, h, yr, ur, px) = (width as u32, height as u32, y_row as usize, uv_row as usize, uv_pixel as usize);
    // while the tile is tracked and no colour is wanted, only its region of Y is copied (a whole full-sensor
    // plane costs several ms here on the camera's thread; the region well under one)
    let chroma = live.want_chroma();
    if !chroma {
        if let Some([x0, y0, x1, y1]) = live.crop_rect(w, h) {
            let Some(src) = plane_ref(&env, &y) else { live.skip(); return JNI_FALSE };
            if src.len() < yr * (h as usize - 1) + w as usize { live.skip(); return JNI_FALSE; }
            let (cw, ch) = ((x1 - x0) as usize, (y1 - y0) as usize);
            let mut data = Vec::with_capacity(cw * ch);
            for row in y0 as usize..y1 as usize { let o = row * yr + x0 as usize; data.extend_from_slice(&src[o..o + cw]); }
            let crop = GrayImage { width: cw as u32, height: ch as u32, data };
            let ok = live.submit_capture(move || Capture { y: crop, rgb: None, origin: (x0, y0) });
            return if ok { JNI_TRUE } else { JNI_FALSE };
        }
    }
    // every offered capture is counted: one that cannot be copied is a drop
    let Some(yp) = plane(&env, &y) else { live.skip(); return JNI_FALSE };
    if yp.len() < yr * (h as usize - 1) + w as usize { live.skip(); return JNI_FALSE; }
    // decided before the copy: the analyzer closes the image once we return
    let uv = if chroma {
        let (Some(up), Some(vp)) = (plane(&env, &u), plane(&env, &v)) else { live.skip(); return JNI_FALSE };
        Some((up, vp))
    } else { None };
    // the b/w fast path reads only the Y plane; RGB is built only for colour frames (the manifest)
    let ok = live.submit_capture(move || {
        let y = GrayImage::from_plane(w, h, &yp, yr);
        let rgb = uv.map(|(up, vp)| Box::new(move || RgbImage::from_yuv420(w, h, &yp, yr, &up, &vp, ur, px)) as Box<dyn FnOnce() -> RgbImage + Send>);
        Capture { y, rgb, origin: (0, 0) }
    });
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_lensbolt_app_Native_status(env: JNIEnv, _: JClass, handle: jlong) -> jstring {
    let json = if handle == 0 { "{}".to_string() } else { unsafe { &*(handle as *const LiveReceiver) }.status().to_json() };
    env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Files `offset .. offset + count` (at most 1,000) of the batch being received, as JSON (`LiveReceiver::try_batch_page`):
/// `{"offset","total","files":[...]}`; the status carries only the batch's counts. Never waits: null while the
/// collector is inside a feed (it may be writing a large file for seconds); the app asks again on its next tick, so
/// its lock around the handle is never held across a feed.
#[no_mangle]
pub extern "system" fn Java_com_lensbolt_app_Native_batchPage(env: JNIEnv, _: JClass, handle: jlong, offset: jint, count: jint) -> jstring {
    let json = if handle == 0 { Some("{\"offset\":0,\"total\":0,\"files\":[]}".to_string()) } else {
        // SAFETY: as for status: the handle lives until destroy
        unsafe { &*(handle as *const LiveReceiver) }.try_batch_page(offset.max(0) as u32, count.max(0) as u32)
    };
    match json {
        Some(json) => env.new_string(json).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lensbolt_app_Native_destroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 { drop(unsafe { Box::from_raw(handle as *mut LiveReceiver) }); }
}
