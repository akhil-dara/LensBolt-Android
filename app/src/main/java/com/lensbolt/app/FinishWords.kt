package com.lensbolt.app

/** Finish words: the receiver's few words naming the parts still missing (core `Receiver::finish_words`, in the
 *  status as `finish_words`), typed on the sender so it sends only those. Optional: the file arrives without them.
 *  The same rule and sentence as the browser receiver (web/receive-logic.js `finishWords`, `FINISH_TEXT`). */
object FinishWords {
    const val TEXT = "Type these on the sender to finish faster"

    /** The words to show for one status poll: the status's `finish_words` while the file is not complete; null when
     *  there are none (no manifest yet, the first pass still bringing new parts, nothing complete, nothing short). */
    fun of(words: String?, complete: Boolean): String? = words?.trim()?.takeIf { !complete && it.isNotEmpty() }

    /** The parts the shown words name, for a small line under them: the status's `finish_parts` (the core's
     *  "Parts 7, 12–15, 30", from 1 as the sender shows them); null whenever no words are shown. The browser
     *  receiver's rule (web/receive-logic.js `finishParts`). */
    fun parts(parts: String?, words: String?): String? = if (words == null) null else parts?.trim()?.takeIf { it.isNotEmpty() }

    /** What TalkBack says when the words appear or change. */
    fun spoken(words: String): String = "$TEXT: $words"
}

/** Says the finish words once when they appear, and again only when they change (a part finished), never on every
 *  poll; words that go away and come back the same are not said again. New transfer resets it. */
class WordsAnnouncer {
    private var said: String? = null

    fun reset() { said = null }

    /** The words on screen at this render (null: none); the sentence to announce, or null. */
    fun next(words: String?): String? {
        if (words == null || words == said) return null
        said = words
        return FinishWords.spoken(words)
    }
}
