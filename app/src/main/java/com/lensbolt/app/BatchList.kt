package com.lensbolt.app

// A batch's file list as the app holds it, and its words. Pure apart from org.json (tested on the JVM with the
// org.json jar): the pages come from Native.batchPage (core/src/flat/live.rs batch_page_json), read off the main
// thread; the screen only reads the arrays.

import org.json.JSONObject
import kotlin.math.roundToInt

enum class FileState { WAITING, RECEIVING, RECEIVED, FAILED }

/** The batch screen's state, as the browser's batch view has it: the list not in yet, receiving, done with every
 *  file verified, done with some missing or failed (amber, never the verified mark). */
enum class BatchState { WAITING_LIST, RECEIVING, DONE_ALL, DONE_PARTIAL }

/** The list's filters. Missing: neither received nor failed (each file's own state). */
enum class Filter(val label: String) { ALL("All"), RECEIVED("Received"), MISSING("Missing"), FAILED("Failed") }

/** Every file of a batch: name, size, state (with progress and a failure's reason). Up to the 1,000,000 files an
 *  index may list; 10,000 cost a few hundred KB. The files and bytes per state are counted as pages change, so
 *  [count] and [bytes] cost nothing per screen update. Not thread-safe: one thread writes (pages), the screen reads
 *  a [snapshot]. */
class BatchFiles {
    var total = 0
        private set
    /** the file list has arrived (the batch's total is known) */
    val known get() = total > 0
    /** changes on every page that changed something (the screen redraws on a new version) */
    var version = 0L
        private set
    private var names = arrayOfNulls<String>(0)
    private var sizes = LongArray(0)
    private var states = ByteArray(0)
    private var progress = FloatArray(0)
    private val reasons = HashMap<Int, String>()
    // a received file's SHA-256 (64 hex digits), from its page
    private val shas = HashMap<Int, String>()
    // files and bytes per FileState ordinal, kept as entries change
    private var counts = IntArray(4)
    private var bytesBy = LongArray(4)

    private fun uncount(i: Int) { val st = states[i].toInt(); counts[st]--; bytesBy[st] -= sizes[i] }
    private fun recount(i: Int) { val st = states[i].toInt(); counts[st]++; bytesBy[st] += sizes[i] }

    fun name(i: Int): String = names[i] ?: ""
    fun size(i: Int): Long = sizes[i]
    fun state(i: Int): FileState = FileState.entries[states[i].toInt()]
    fun progress(i: Int): Float = progress[i]
    fun reason(i: Int): String? = reasons[i]
    /** The SHA-256 a received file was verified against (its page's "sha256"); null when not received or unknown. */
    fun sha256(i: Int): String? = shas[i]

    private fun resize(n: Int) {
        if (n == total) return
        for (i in n until total) uncount(i)
        names = names.copyOf(n); sizes = sizes.copyOf(n); states = states.copyOf(n); progress = progress.copyOf(n)
        reasons.keys.removeAll { it >= n }
        shas.keys.removeAll { it >= n }
        // new entries are waiting files of size 0 until their page comes
        if (n > total) counts[FileState.WAITING.ordinal] += n - total
        total = n
    }

    /** One page: {"offset","total","files":[{name,size,state,progress?,reason?,sha256?}]}. */
    fun apply(page: JSONObject) {
        val n = page.optInt("total", 0)
        var changed = n != total
        resize(n)
        val off = page.optInt("offset", 0)
        val files = page.optJSONArray("files") ?: return
        for (k in 0 until files.length()) {
            val i = off + k
            if (i !in 0 until total) break
            val f = files.optJSONObject(k) ?: continue
            val st = when (f.optString("state")) {
                "receiving" -> FileState.RECEIVING; "received" -> FileState.RECEIVED; "failed" -> FileState.FAILED; else -> FileState.WAITING
            }
            val name = f.optString("name"); val size = f.optLong("size")
            val p = if (st == FileState.RECEIVING) f.optDouble("progress", 0.0).toFloat() else if (st == FileState.RECEIVED) 1f else 0f
            if (names[i] != name || sizes[i] != size || states[i].toInt() != st.ordinal || progress[i] != p) {
                uncount(i)
                names[i] = name; sizes[i] = size; states[i] = st.ordinal.toByte(); progress[i] = p; changed = true
                recount(i)
            }
            if (st == FileState.FAILED) { val r = f.optString("reason", "").ifEmpty { null }; if (r != reasons[i]) { changed = true; if (r == null) reasons.remove(i) else reasons[i] = r } }
            else if (reasons.remove(i) != null) changed = true
            val sha = if (st == FileState.RECEIVED) ShaText.valid(f.optString("sha256", "")) else null
            if (sha != shas[i]) { changed = true; if (sha == null) shas.remove(i) else shas[i] = sha }
        }
        if (changed) version++
    }

    private fun matches(i: Int, f: Filter): Boolean = when (f) {
        Filter.ALL -> true
        Filter.RECEIVED -> states[i].toInt() == FileState.RECEIVED.ordinal
        Filter.FAILED -> states[i].toInt() == FileState.FAILED.ordinal
        Filter.MISSING -> states[i].toInt() == FileState.WAITING.ordinal || states[i].toInt() == FileState.RECEIVING.ordinal
    }

    fun count(f: Filter): Int = when (f) {
        Filter.ALL -> total
        Filter.RECEIVED -> counts[FileState.RECEIVED.ordinal]
        Filter.FAILED -> counts[FileState.FAILED.ordinal]
        Filter.MISSING -> counts[FileState.WAITING.ordinal] + counts[FileState.RECEIVING.ordinal]
    }

    fun indices(f: Filter): IntArray {
        if (f == Filter.ALL) return IntArray(total) { it }
        val out = IntArray(count(f)); var k = 0
        for (i in 0 until total) if (matches(i, f)) out[k++] = i
        return out
    }

    /** The files' sizes added up (a batch's size is its files, never its folder). */
    fun bytes(f: Filter = Filter.ALL): Long = when (f) {
        Filter.ALL -> bytesBy.sum()
        Filter.RECEIVED -> bytesBy[FileState.RECEIVED.ordinal]
        Filter.FAILED -> bytesBy[FileState.FAILED.ordinal]
        Filter.MISSING -> bytesBy[FileState.WAITING.ordinal] + bytesBy[FileState.RECEIVING.ordinal]
    }

    /** A copy the screen can read while pages keep coming. */
    fun snapshot(): BatchFiles = BatchFiles().also { c ->
        c.total = total; c.version = version
        c.names = names.copyOf(); c.sizes = sizes.copyOf(); c.states = states.copyOf(); c.progress = progress.copyOf()
        c.reasons.putAll(reasons); c.shas.putAll(shas)
        c.counts = counts.copyOf(); c.bytesBy = bytesBy.copyOf()
    }
}

/** Reads a batch's whole list, page by page, without ever waiting for the receiver. */
object PageReader {
    enum class Outcome { DONE, BUSY, STOPPED }

    /** `fetch`'s answer when there is no session to read. */
    const val NO_SESSION = ""

    /** Every page into `into`, `page` files at a time. `fetch(offset, count)`: a page's JSON, null while the receiver
     *  is busy with a feed (Native.batchPage: the pages read so far stay, BUSY, and the caller asks again later), or
     *  [NO_SESSION]; `goOn` false stops (an older batch's read). */
    fun readAll(into: BatchFiles, page: Int, fetch: (Int, Int) -> String?, goOn: () -> Boolean): Outcome {
        var off = 0
        do {
            if (!goOn()) return Outcome.STOPPED
            val json = fetch(off, page) ?: return Outcome.BUSY
            if (json == NO_SESSION) return Outcome.STOPPED
            into.apply(JSONObject(json))
            off += page
        } while (off < into.total)
        return Outcome.DONE
    }
}

/** The batch's words on screen. */
object BatchWords {
    private fun files(n: Int) = if (n == 1) "1 file" else "%,d files".format(n)

    /** Done: "3 files · 2.2 MB · all verified"; with failures "2 of 3 files · 1.9 MB · 1 failed" (the bytes of
     *  the files received). */
    fun done(files: Int, received: Int, failed: Int, bytes: Long): String {
        if (received == files && failed == 0) return "${files(files)} · ${Fmt.bytes(bytes)} · ${if (files == 1) "verified" else "all verified"}"
        val missing = files - received - failed
        return "%,d of ${files(files)} · ${Fmt.bytes(bytes)}".format(received) +
            (if (failed > 0) " · %,d failed".format(failed) else "") + (if (missing > 0) " · %,d missing".format(missing) else "")
    }

    /** Receiving: "12 of 40 files · 18.0 MB of 64.0 MB", the bytes of the stream; before the list arrives
     *  "Waiting for the file list · 64.0 MB". */
    fun receiving(received: Int, total: Int, failed: Int, done: Long, stream: Long): String {
        if (total <= 0) return "Waiting for the file list · ${Fmt.bytes(stream)}"
        return "%,d of ${files(total)} · ${Fmt.bytes(minOf(done, stream))} of ${Fmt.bytes(stream)}".format(received) +
            if (failed > 0) " · %,d failed".format(failed) else ""
    }

    fun state(s: FileState, progress: Float, reason: String?): String = when (s) {
        FileState.WAITING -> "Waiting"
        FileState.RECEIVING -> "Receiving ${(progress * 100).roundToInt().coerceIn(0, 99)}%"
        FileState.RECEIVED -> "Received"
        FileState.FAILED -> if (reason.isNullOrBlank()) "Failed" else "Failed: $reason"
    }

    /** A row as TalkBack reads it: "photo.jpg, 2.1 MB, received". */
    fun spoken(name: String, size: Long, label: String): String = "$name, ${Fmt.bytes(size)}, ${label.replaceFirstChar { it.lowercase() }}"

    fun filter(f: Filter, n: Int): String = "${f.label} %,d".format(n)

    fun stateOf(done: Boolean, total: Int, received: Int, failed: Int): BatchState = when {
        !done && total <= 0 -> BatchState.WAITING_LIST
        !done -> BatchState.RECEIVING
        total > 0 && received == total && failed == 0 -> BatchState.DONE_ALL
        else -> BatchState.DONE_PARTIAL
    }

    /** The Done screen's line under the summary. */
    fun verifiedLine(s: BatchState): String =
        if (s == BatchState.DONE_PARTIAL) "Each file received is SHA-256 verified; the rest did not arrive" else "SHA-256 verified, each file"

    /** Save all under way: "Saving 12 of 40 · photo.jpg" (`done` files finished of the run's `run`); a bar shows how far. */
    fun saving(done: Int, run: Int, name: String): String = "Saving %,d of %,d · %s".format(done + 1, run, name)

    /** The bar under it: the run's files done plus the current file's percent, over the run (0..1). */
    fun saveFraction(done: Int, run: Int, pct: Int): Float =
        if (run <= 0) 0f else ((done + pct.coerceIn(0, 100) / 100f) / run).coerceIn(0f, 1f)

    /** A received row's menu: Save…, Open, Share, and its SHA-256 to show (or hide, and copy, once shown) when the
     *  page gave one. */
    fun rowActions(sha: Boolean, shown: Boolean): List<RowAction> = listOf(RowAction.SAVE, RowAction.OPEN, RowAction.SHARE) +
        if (!sha) emptyList() else if (shown) listOf(RowAction.HIDE_SHA, RowAction.COPY_SHA) else listOf(RowAction.SHOW_SHA)
}

/** File names that fit their space: never trimmed when they fit (two lines in a row, on the Done screen); beyond
 *  that the middle goes, and the extension always stays. */
object NameFit {
    private const val ELL = "…"
    private const val MAX_EXT = 16

    /** `fits`: whether a text fits the space (a row's two lines). The longest shortening that fits, or the
     *  extension with as little as fits before it. */
    fun shorten(name: String, fits: (String) -> Boolean): String {
        if (fits(name)) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= MAX_EXT) name.substring(dot) else ""
        val stem = name.substring(0, name.length - ext.length)
        fun cut(keep: Int): String {
            val head = (keep + 1) * 2 / 3
            return stem.take(head) + ELL + stem.takeLast(keep - head) + ext
        }
        // the largest number of stem characters kept that still fits
        var lo = 0; var hi = stem.length - 1
        if (!fits(cut(0))) return ELL + ext
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(cut(mid))) lo = mid else hi = mid - 1
        }
        return cut(lo)
    }
}

/** Names and places for Save all. */
object SaveNames {
    /** `name`, or "name (2).ext", "name (3).ext" … for the first one not in `taken` (compared ignoring case:
     *  phone storage is). A leading dot is not an extension. */
    fun unique(name: String, taken: Set<String>): String = free(name, taken.mapTo(HashSet()) { it.lowercase() })

    private fun free(name: String, low: Set<String>): String {
        if (name.lowercase() !in low) return name
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 2
        while (true) { val c = "$stem ($n)$ext"; if (c.lowercase() !in low) return c; n++ }
    }

    /** The names in one folder as Save all fills it: each [take] costs one lookup (10,000 files into one folder
     *  never compare every name with every other). */
    class Taken(names: Collection<String> = emptyList()) {
        private val low = names.mapTo(HashSet()) { it.lowercase() }
        /** `name` or its next free " (n)", now taken. */
        fun take(name: String): String = free(name, low).also { low += it.lowercase() }
        fun clear() = low.clear()
    }

    /** A picked folder as words, from its tree document id and the provider's authority: the volume, then the
     *  path's folders joined with " › ". External storage ids are "<volume>:<path>": "primary" is "Internal
     *  storage", any other volume an "SD card" ("primary:Download/Transfers" → "Internal storage › Download ›
     *  Transfers", "1A2B-3C4D:" → "SD card"). The Downloads provider's ids are "downloads", "raw:<file path>" or
     *  "msf:<number>" (a folder under Downloads, named only by `name`, the folder's display name, when known). */
    fun treeLabel(docId: String?, authority: String? = null, name: String? = null): String {
        if (docId.isNullOrBlank()) return "Folder"
        val downloads = authority == DOWNLOADS || docId == "downloads" || docId.startsWith("msf:")
        if (docId.startsWith("raw:")) return rawLabel(docId.removePrefix("raw:"))
        if (downloads) return listOf("Downloads").plus(listOfNotNull(name?.trim()?.takeIf { it.isNotEmpty() && it != "Downloads" && it != "Download" && docId != "downloads" })).joinToString(SEP)
        val colon = docId.indexOf(':')
        if (colon < 0) return docId.replaceFirstChar { it.uppercase() }
        val vol = docId.substring(0, colon)
        val root = when (vol) { "primary" -> listOf("Internal storage"); "home" -> listOf("Internal storage", "Documents"); else -> listOf("SD card") }
        return (root + segments(docId.substring(colon + 1))).joinToString(SEP)
    }

    /** A folder inside a labelled one: "SD card › LensBolt" and "trip" → "SD card › LensBolt › trip". */
    fun inside(label: String, folder: String?): String = if (folder.isNullOrBlank()) label else label + SEP + folder

    /** The folder row's words: before any save "Saving to <folder>" (or how the folder is chosen); after a save
     *  its result takes the row ("Saved 4 files to SD card › LensBolt › trip"), so one line says both. */
    fun folderLine(folder: String?, saved: String?): String = saved ?: folder?.let { "Saving to $it" } ?: "You choose the folder the first time"

    const val SEP = " › "
    private const val DOWNLOADS = "com.android.providers.downloads.documents"

    // a file path on the device: /storage/emulated/<user>/… is the internal storage, /storage/<id>/… a card
    private fun rawLabel(path: String): String {
        val parts = segments(path)
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> (listOf("Internal storage") + parts.drop(3)).joinToString(SEP)
            parts.size >= 2 && parts[0] == "storage" -> (listOf("SD card") + parts.drop(2)).joinToString(SEP)
            parts.isEmpty() -> "Internal storage"
            else -> parts.joinToString(SEP)
        }
    }

    // a path's folders, each %-decoded (a '+' stays a '+': document ids are not form data)
    private fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }.map { seg ->
        try { java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { seg }
    }
}
