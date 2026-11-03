package com.lensbolt.app

// Where LensBolt keeps things. A received file lives only in the app's private cache (cacheDir/rx) until the user
// saves or shares it through the system; nothing is copied anywhere on its own. Lab mode (our tests only) is on
// while files/lab.on exists in the app's external files dir; only then are session logs and frame dumps written.

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Storage {
    const val AUTHORITY = "com.lensbolt.app.files"
    // headroom on top of the receiver's peak: the file system's own overhead, a log or two, other apps writing
    const val MARGIN_BYTES = 64L shl 20

    fun rxDir(ctx: Context): File = File(ctx.cacheDir, "rx")

    fun wipeRx(ctx: Context) { rxDir(ctx).apply { deleteRecursively(); mkdirs() } }

    private fun ext(ctx: Context): File? = ctx.getExternalFilesDir(null)

    // lab.on counts only where no other app can create it: on API 29 apps with legacy storage can write into
    // Android/data, so there lab mode needs a debuggable build
    fun labAllowed(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 30 || (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun labOn(ctx: Context): Boolean = labAllowed(ctx) && (ext(ctx)?.let { File(it, "lab.on").exists() } ?: false)

    // frame dumps and session logs from lab runs; a normal start removes them
    fun purgeLabFiles(ctx: Context) {
        val dir = ext(ctx) ?: return
        File(dir, "dump").deleteRecursively(); File(dir, "logs").deleteRecursively()
        File(dir, "dump.request").delete()
    }

    fun freeBytes(ctx: Context): Long = ctx.cacheDir.usableSpace

    // Space a transfer needs in the cache: the receiver's peak (the core's `disk_peak`: the stream, plus the
    // unpacked file while a compressed transfer finishes; measured exact for 64 MB, core/tests/disk_peak.rs),
    // never less than 1.0 x the stream, plus the margin. `diskPeak` 0 (unknown) counts as the stream.
    fun needBytes(streamSize: Long, diskPeak: Long = 0): Long = maxOf(streamSize, diskPeak) + MARGIN_BYTES

    fun contentUri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, AUTHORITY, f)
}

// What Back does on the Done screen: leave, or ask once ("Not saved yet") when the file was neither saved nor
// shared and we have not asked yet.
fun backAsks(done: Boolean, savedOrShared: Boolean, asked: Boolean): Boolean = done && !savedOrShared && !asked

// Whether Back is ours to handle: a pane open (Back closes it); a batch's Done screen while a received file has no
// save (`kept` false) and we have not asked yet, saves running or not (the question says Save all or Keep saving);
// a single file's Done screen as [backAsks], except while its copy runs (Back leaves; the copy finishes on its own).
fun backEnabled(paneOpen: Boolean, batch: Boolean, done: Boolean, kept: Boolean, asked: Boolean, saving: Boolean): Boolean = when {
    paneOpen -> true
    batch -> done && !kept && !asked
    else -> !saving && backAsks(done, kept, asked)
}
