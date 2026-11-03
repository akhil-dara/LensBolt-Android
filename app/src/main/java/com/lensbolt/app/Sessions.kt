package com.lensbolt.app

import java.io.File

// The native receiving session, one at a time, each in its own directory under the rx root (rx/s<n>; the
// FileProvider's rx/ path covers them). Tearing a session down joins its threads, and its collector may be in the
// middle of finishing a large file (hash, unpack): seconds. So a new session starts at once, and the old one goes
// to `background`, which destroys it and only then removes its directory: nothing it writes late can land in the
// new session, and neither the UI thread nor the camera thread ever waits on a teardown. The camera and the status
// poll read the live handle under a read lock (withHandle; readers never wait on each other); the write lock is held
// only to swap the handle, never while a session is created or destroyed. No call made under the read lock may
// block (submit drops a capture rather than wait, status copies counters, batchPage answers busy instead of waiting
// for the receiver): a swap on the UI thread then never waits behind one.
class Sessions(
    private val root: File,
    private val create: (File) -> Long,
    private val destroy: (Long) -> Unit,
    private val background: (Runnable) -> Unit,
) {
    // readers (the camera's submit, the status poll, a page of the batch's list) never wait on each other; only
    // swapping the handle waits for the readers in progress
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private var handle = 0L
    private var seq = 0
    /** The live session's directory (null after stop); UI thread only. */
    var dir: File? = null
        private set

    /** `f` with the live handle (0: none) under the lock: a teardown never frees it under a running call. */
    fun <T> withHandle(f: (Long) -> T): T { val l = lock.readLock(); l.lock(); try { return f(handle) } finally { l.unlock() } }

    /** A fresh session in a new directory; the previous one, and its directory, go to the background. */
    fun start() {
        val d = nextDir(root, seq).apply { mkdirs() }
        seq = d.name.substring(1).toInt()
        val h = create(d)
        retire(swap(h), dir)
        dir = d
    }

    /** No session (not enough space, a receiver failure): it and its directory go to the background. */
    fun stop() { retire(swap(0L), dir); dir = null }

    /** The activity goes away: the session is torn down in the background; its directory (a received file not
     *  saved yet) stays until the next app start wipes the root. */
    fun release() { retire(swap(0L), null) }

    /** A restored Done screen: its file's directory belongs to this (handle-less) session and goes with the next
     *  start. */
    fun adopt(d: File) { dir = d }

    private fun swap(h: Long): Long { val l = lock.writeLock(); l.lock(); try { val old = handle; handle = h; return old } finally { l.unlock() } }

    private fun retire(h: Long, d: File?) {
        if (h == 0L && d == null) return
        background(Runnable {
            if (h != 0L) destroy(h)
            d?.deleteRecursively()
        })
    }

    companion object {
        private val NAME = Regex("s(\\d+)")

        /** rx/s<n>: past every session directory still there and past `after` (a directory whose teardown has
         *  not finished is never reused). */
        fun nextDir(root: File, after: Int): File {
            val there = root.list()?.mapNotNull { NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
            return File(root, "s${maxOf(there, after) + 1}")
        }

        /** App start: nothing from an earlier run is kept. */
        fun wipe(root: File) { root.deleteRecursively(); root.mkdirs() }
    }
}
