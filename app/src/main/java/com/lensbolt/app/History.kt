package com.lensbolt.app

// The file history: what was saved, when and where. Metadata only (a small JSON file in the app's private
// storage); the files themselves are never copied. Opening an item again uses the document it was saved as,
// while Android still lets LensBolt read it.

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved file: its name and size, when (ms since 1970), where in words ("Download/Transfers/trip"), the
 *  saved document (a content URI, null when unknown), the batch it came in (null: a single file) and its SHA-256
 *  (64 hex digits; null when not known). A batch saved with Save all is one item: `name` the batch's, `files` how
 *  many of its files were saved (0: a single file), `size` their total, `savedTo` its folder, `id` the batch and
 *  folder it stands for (later saves of the same batch into the same folder update it). */
data class HistoryItem(val name: String, val size: Long, val date: Long, val savedTo: String, val uri: String?, val batch: String?,
                       val sha: String? = null, val files: Int = 0, val id: String? = null)

/** Newest first, at most [MAX] items. Read and written on one thread (the app's saving thread). */
class History(private val file: File) {
    companion object {
        const val MAX = 500

        /** The URIs whose kept permission goes with the items `gone` (cleared, or past [MAX]): each one no item `kept`
         *  still uses, and never the Save all folder `tree` (its permission is Save all's). */
        fun releasable(gone: List<HistoryItem>, kept: List<HistoryItem>, tree: String?): List<String> {
            val still = kept.mapNotNullTo(HashSet()) { it.uri }
            return gone.mapNotNull { it.uri }.filter { it != tree && it !in still }.distinct()
        }
    }

    private var list: MutableList<HistoryItem>? = null
    /** times the file was written (one per [add] or [upsert]) */
    var writes = 0
        private set

    fun items(): List<HistoryItem> = load().toList()

    /** `item` first; the items past [MAX] are dropped and returned (their permissions can go). */
    fun add(item: HistoryItem): List<HistoryItem> = upsert(listOf(item))

    /** Each item first, replacing the item with the same `id` (a batch's entry updated); one write for all. The
     *  items past [MAX] are dropped and returned. */
    fun upsert(items: Collection<HistoryItem>): List<HistoryItem> {
        if (items.isEmpty()) return emptyList()
        val l = load()
        for (item in items) {
            if (item.id != null) l.removeAll { it.id == item.id }
            l.add(0, item)
        }
        val gone = ArrayList<HistoryItem>()
        while (l.size > MAX) gone += l.removeAt(l.size - 1)
        save(l)
        return gone
    }

    fun clear() { list = mutableListOf(); file.delete() }

    private fun load(): MutableList<HistoryItem> {
        list?.let { return it }
        val out = mutableListOf<HistoryItem>()
        try {
            if (file.isFile) {
                val a = JSONArray(file.readText())
                for (k in 0 until a.length()) {
                    val o = a.optJSONObject(k) ?: continue
                    val name = o.optString("name"); val savedTo = o.optString("savedTo")
                    if (name.isEmpty() || !o.has("size") || o.opt("size") !is Number) continue
                    out += HistoryItem(name, o.optLong("size"), o.optLong("date"), savedTo,
                        o.optString("uri").ifEmpty { null }, o.optString("batch").ifEmpty { null }, o.optString("sha").ifEmpty { null },
                        o.optInt("files", 0).coerceAtLeast(0), o.optString("id").ifEmpty { null })
                }
            }
        } catch (_: Throwable) { /* a damaged file reads as what it holds up to the damage: here, nothing */ }
        list = out
        return out
    }

    private fun save(l: List<HistoryItem>) {
        val a = JSONArray()
        for (h in l) a.put(JSONObject().apply {
            put("name", h.name); put("size", h.size); put("date", h.date); put("savedTo", h.savedTo)
            h.uri?.let { put("uri", it) }; h.batch?.let { put("batch", it) }; h.sha?.let { put("sha", it) }
            if (h.files > 0) put("files", h.files); h.id?.let { put("id", it) }
        })
        writes++
        val tmp = File(file.parentFile, file.name + ".tmp")
        try { tmp.writeText(a.toString()); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) } } catch (_: Throwable) { tmp.delete() }
    }
}

/** Save all's history: one entry per batch and folder, added up as files are saved and written once a run ends
 *  ([flush]), never once per file. On the saving thread only. */
class HistoryBatcher(private val history: History) {
    companion object {
        const val FLUSH_FILES = 500
        const val FLUSH_MS = 5_000L

        /** A batch's own token (set when the batch starts): sessions are numbered from s1 again on every app start. */
        fun token(): String = java.util.UUID.randomUUID().toString()

        /** The history entry a batch's saves into one folder add to: the session, its batch number, the batch's
         *  token and the folder. */
        fun batchId(session: String?, gen: Int, token: String, tree: String) = "$session/$gen/$token|$tree"
    }

    private val entries = HashMap<String, HistoryItem>()
    private val changed = LinkedHashSet<String>()
    private var filesSince = 0
    private var firstSince = 0L

    /** One file of `batch` saved into `where` (in words); `id` names the batch session and the folder. */
    fun saved(id: String, batch: String, where: String, size: Long, now: Long) {
        val e = entries[id]
        entries[id] = e?.copy(files = e.files + 1, size = e.size + size, date = now, savedTo = where)
            ?: HistoryItem(batch, size, now, where, null, null, files = 1, id = id)
        if (filesSince == 0) firstSince = now
        filesSince++
        changed += id
    }

    /** A long run lands as it goes: [FLUSH_FILES] files or [FLUSH_MS] since the first file not yet written. */
    fun due(now: Long) = changed.isNotEmpty() && (filesSince >= FLUSH_FILES || now - firstSince >= FLUSH_MS)

    /** The run ended: its entries written (one write); the items that fell off the history returned. */
    fun flush(): List<HistoryItem> {
        if (changed.isEmpty()) return emptyList()
        val items = changed.map { entries.getValue(it) }
        changed.clear(); filesSince = 0
        return history.upsert(items)
    }
}

/** A history row's words. */
object HistoryWords {
    private fun files(n: Int) = if (n == 1) "1 file" else "%,d files".format(n)

    /** "2.1 MB · <date>"; a batch "37 files · 64.0 MB · <date>". */
    fun meta(h: HistoryItem, date: String) = (if (h.files > 0) "${files(h.files)} · " else "") + "${Fmt.bytes(h.size)} · $date"

    /** "Saved to Download/Transfers/trip", with " · from <batch>" for a file of a batch saved on its own. */
    fun where(h: HistoryItem) = "Saved to ${h.savedTo}" + (h.batch?.let { " · from $it" } ?: "")

    /** What TalkBack reads for a row (its description replaces the row's text): the hash too, spelt in fours. */
    fun spoken(h: HistoryItem, date: String) =
        "${h.name}, " + (if (h.files > 0) "${files(h.files)}, " else "") + "${Fmt.bytes(h.size)}, saved $date to ${h.savedTo}" +
            (h.sha?.let { ". " + ShaText.spoken(it) } ?: "")

    /** A row's menu: Open and Share while its saved document is known; its SHA-256 to show (or hide, `shown`) and
     *  copy when its hash is. The hash is not on the row itself until asked for. */
    fun actions(h: HistoryItem, shown: Boolean = false): List<RowAction> =
        (if (h.uri != null) listOf(RowAction.OPEN, RowAction.SHARE) else emptyList()) +
            (if (h.sha != null) listOf(if (shown) RowAction.HIDE_SHA else RowAction.SHOW_SHA, RowAction.COPY_SHA) else emptyList())
}
