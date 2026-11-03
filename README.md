# LensBolt for Android (receiver)

Air-gapped receiver: point the back camera at a LensBolt sender screen; the file lands in `Downloads/LensBolt`,
SHA-256 verified. No network permission.

- `native/`: JNI crate over `core/` (`flat::live::LiveReceiver`). Captures are offered once and never block
  the camera: taken only while a decode worker is free, else dropped (the fountain code absorbs it). Decode
  and receiver panics cost one capture, not the session.
- `app/`: CameraX ImageAnalysis (YUV_420_888, largest sensor size or Full HD, 30 fps, continuous AF).

Build (needs NDK 27.2.12479018, `cargo install cargo-ndk`, `rustup target add aarch64-linux-android`):

    gradle assembleDebug        # runs cargo ndk first, APK in app/build/outputs/apk/debug/
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## Licence and support

LensBolt is free software under the [GNU AGPL-3.0](LICENSE). If it helps you, you can support the work:
[Buy me a coffee](https://buymeacoffee.com/akhildara1l) · [GitHub Sponsors](https://github.com/sponsors/akhil-dara).

The project lives in three repositories: [lensbolt-core](https://github.com/akhil-dara/lensbolt-core) (the codec and
decoder, Rust), [lensbolt-web](https://github.com/akhil-dara/lensbolt-web) (the sender and receiver pages and the
site, lensbolt.com) and [lensbolt-android](https://github.com/akhil-dara/lensbolt-android) (the Android receiver).
