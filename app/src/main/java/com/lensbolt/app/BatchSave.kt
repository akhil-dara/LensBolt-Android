package com.lensbolt.app

// Save all's rules, pure (tested on the JVM): which files still need saving, what leaving a batch asks first, when a
// folder's permission can go back, and how often the screen follows the saves. MainActivity calls these on its UI
// thread.

/** Each file of a batch as Save all and the rows' Save… see it (by index): queued, saved, or failed with a reason.
 *  The saved and queued files are counted as they change, never recounted. UI thread only. */
class SaveLedger {
    // present = tried; null = saved, else the reason it was not
    private val result = HashMap<Int, String?>()
    private val queued = HashSet<Int>()
    // queued files with no earlier save (a saved file saved again is not "unsaved")
    private val queuedUnsaved = HashSet<Int>()
    var savedCount = 0
        private set
    val queuedCount get() = queued.size
    /** Queued files with no earlier save: unsaved files on their way. */
    val queuedUnsavedCount get() = queuedUnsaved.size

    fun isQueued(i: Int) = i in queued
    fun isSaved(i: Int) = result.containsKey(i) && result[i] == null

    /** Not saved and not on its way (never tried, or failed). */
    fun needsSave(i: Int) = i !in queued && !isSaved(i)

    fun queue(i: Int) { queued += i; if (!isSaved(i)) queuedUnsaved += i }

    /** A save of `i` ended: saved (`error` null) or not; a failed repeat of a saved file keeps the earlier save. */
    fun done(i: Int, error: String?) {
        queued -= i; queuedUnsaved -= i
        if (error == null) { if (!isSaved(i)) savedCount++; result[i] = null }
        else if (!isSaved(i)) result[i] = error
    }

    /** A save that never happened (the folder was lost): the file is as if never tried. */
    fun forget(i: Int) { queued -= i; queuedUnsaved -= i; if (isSaved(i)) savedCount--; result.remove(i) }

    /** What a received row adds after its state: "saving…", "saved", "not saved: reason", or nothing. */
    fun note(i: Int): String? = when {
        i in queued -> "saving…"
        !result.containsKey(i) -> null
        else -> result[i]?.let { "not saved: $it" } ?: "saved"
    }

    /** Received files neither saved nor on their way. */
    fun unsaved(received: Int) = (received - savedCount - queuedUnsaved.size).coerceAtLeast(0)

    /** Every received file has a save (Back and New transfer stop asking). */
    fun allSaved(received: Int) = savedCount >= received

    fun clear() { result.clear(); queued.clear(); queuedUnsaved.clear(); savedCount = 0 }
}

/** A question before something is lost: `risky` drops it, `safe` keeps it. The safe choice goes last (the dialog's
 *  positive button, the one that takes the focus); the risky one before it. */
data class Confirm(val title: String, val message: String, val risky: String, val safe: String)

/** The app's questions in one place (wording and which choice is the safe one). */
object Asks {
    fun startOver() = Confirm("Start over?", "What has been received so far is discarded.", risky = "Start over", safe = "Keep receiving")
    fun clearHistory() = Confirm("Clear history?", "The list of saved files is removed. The files stay where they were saved.", risky = "Clear", safe = "Keep")
    fun notSaved(name: String) = Confirm("Not saved yet",
        "$name is only inside LensBolt. Save it to keep it; otherwise it is removed the next time LensBolt starts.", risky = "Discard", safe = "Save")
    fun batch(a: BatchAsk.Ask) = Confirm(a.title, a.message, risky = "Discard", safe = a.keep)
}

/** What leaving a batch with files not saved asks first (Back, New transfer). */
object BatchAsk {
    /** `keep`: the button that stays (Save all, or Keep saving while a run is under way); the other is Discard. */
    data class Ask(val title: String, val message: String, val keep: String, val saving: Boolean)

    /** Null when every received file is saved. `queued`: unsaved files being saved now
     *  ([SaveLedger.queuedUnsavedCount]); `newTransfer`: New transfer (else Back). Keep saving only when every
     *  unsaved file is on its way; while some are not (a row's Save… under way), Save all saves the rest. */
    fun leaving(received: Int, saved: Int, queued: Int, newTransfer: Boolean): Ask? {
        val unsaved = received - saved
        if (unsaved <= 0) return null
        val title = if (unsaved == 1) "1 file not saved yet" else "%,d files not saved yet".format(unsaved)
        if (queued >= unsaved) return Ask(title,
            "LensBolt is still saving them. " + if (newTransfer) "A new transfer stops that, and removes the files not saved."
                else "Leaving now may stop that; the files not saved are removed the next time LensBolt starts.",
            "Keep saving", saving = true)
        val them = if (unsaved == 1) "It is" else "They are"
        val under = if (queued <= 0) "" else if (queued == 1) " (1 is being saved)" else " (%,d are being saved)".format(queued)
        return Ask(title,
            "$them only inside LensBolt$under. Save ${if (unsaved == 1) "it" else "them"} to keep ${if (unsaved == 1) "it" else "them"}; " +
                if (newTransfer) "a new transfer removes ${if (unsaved == 1) "it" else "them"}." else "otherwise ${if (unsaved == 1) "it is" else "they are"} removed the next time LensBolt starts.",
            "Save all", saving = false)
    }
}

/** Save all's folder permissions while saves are queued. The folder the user changed away from goes back only once
 *  no save queued for it is left (its saves keep writing there); a folder picked back before then stays. Folders by
 *  their tree URI as text. UI thread only. */
class TreeKeeper {
    private val inUse = HashMap<String, Int>()
    private val later = LinkedHashSet<String>()

    fun inUse(tree: String) = (inUse[tree] ?: 0) > 0

    /** A save through `tree` was queued. */
    fun queued(tree: String) { inUse[tree] = (inUse[tree] ?: 0) + 1 }

    /** A queued save through `tree` ended (saved, failed, or dropped with an older batch): the folders whose
     *  permission can go back now (`current`: the folder Save all uses now, never given back). */
    fun ended(tree: String, current: String?): List<String> {
        val n = (inUse[tree] ?: 0) - 1
        if (n > 0) { inUse[tree] = n; return emptyList() }
        inUse.remove(tree)
        if (tree != current && later.remove(tree)) return listOf(tree)
        return emptyList()
    }

    /** The user picked `picked` instead of `old`: the folders whose permission goes back now. */
    fun picked(old: String?, picked: String): List<String> {
        lastLost = null
        later.remove(picked)
        if (old == null || old == picked) return emptyList()
        if (inUse(old)) { later += old; return emptyList() }
        return listOf(old)
    }

    /** What a save that found its folder unwritable means: FORGET the remembered folder (it is the one that
     *  failed: say so, the user picks again), the SAME loss again (the rest of that run: the file is as if never
     *  tried), or an OTHER folder's failure (one changed away from: that file failed, the remembered one stays). */
    enum class Lost { FORGET, SAME, OTHER }

    private var lastLost: String? = null

    /** LensBolt can no longer write to `failed`; `current`: the remembered folder (null once forgotten). */
    fun lost(failed: String, current: String?): Lost = when {
        failed == current -> { lastLost = failed; Lost.FORGET }
        failed == lastLost -> Lost.SAME
        else -> Lost.OTHER
    }
}

/** At most one screen update per `ms`, however many saves report. [request]: the delay before the update to
 *  schedule, or null when one is already scheduled; [ran] when it ran. UI thread only. */
class Coalesce(private val ms: Long) {
    private var pending = false
    private var last = Long.MIN_VALUE / 2

    fun request(now: Long): Long? {
        if (pending) return null
        pending = true
        return (last + ms - now).coerceAtLeast(0)
    }

    fun ran(now: Long) { pending = false; last = now }
}
