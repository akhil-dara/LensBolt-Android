package com.lensbolt.app

import java.nio.ByteBuffer

/** The Rust receiver (android/native). One handle = one transfer session; every call is thread-safe. */
object Native {
    init { System.loadLibrary("lensbolt") }
    /** `workers` <= 0: half the cores. Returns 0 if the output directory cannot be used. */
    external fun create(outDir: String, workers: Int): Long
    /** Never blocks: false when the capture was dropped (every worker busy, or the transfer is complete). */
    external fun submit(handle: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int, yRowStride: Int, uvRowStride: Int, uvPixelStride: Int): Boolean
    /** LiveStatus as JSON (see core/src/flat/live.rs). */
    external fun status(handle: Long): String
    /** Files offset until offset + count (at most 1,000) of the batch being received, as JSON: {"offset","total","files":[...]}.
     *  The status carries only the batch's counts. Never waits: null while the receiver is busy with a feed (it may be
     *  writing a large file for seconds); ask again later. */
    external fun batchPage(handle: Long, offset: Int, count: Int): String?
    external fun destroy(handle: Long)
}
