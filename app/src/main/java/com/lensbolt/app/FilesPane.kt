package com.lensbolt.app

// The batch's file list (over a transfer in progress, and as the batch's Done screen) and the file history: one
// recycled list each (RecyclerView; 10,000 rows scroll smoothly), a header that scrolls with the rows. Views in
// code with ReceiverScreen's palette and type roles; MainActivity owns the data and the actions.

import android.content.Context
import android.text.StaticLayout
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

/** A file name on up to two lines (`fit`): never trimmed when it fits; beyond, the middle goes and the extension
 *  stays (NameFit). TalkBack reads the whole name. With `fit` off it is a plain text view. */
class NameView(ctx: Context) : AppCompatTextView(ctx) {
    var fit = false
    var full: String = ""
        set(v) {
            // the same name again is a no-op only while the text shown is still this view's own fit of it
            if (field == v && text.toString() == shown) return
            field = v; fitW = -1; shown = v; text = v; contentDescription = null; requestLayout()
        }
    private var fitW = -1
    private var shown: String? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fit && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
            if (avail > 0 && avail != fitW) {
                fitW = avail
                val lines = maxLines.coerceAtLeast(1)
                val s = NameFit.shorten(full) { t ->
                    StaticLayout.Builder.obtain(t, 0, t.length, paint, avail).setIncludePad(includeFontPadding)
                        .setBreakStrategy(breakStrategy).setHyphenationFrequency(hyphenationFrequency).build().lineCount <= lines
                }
                if (text.toString() != s) { shown = s; text = s; contentDescription = if (s != full) full else null }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** A SHA-256 as both Done screens lay it out (the browser's too): monospace groups of four, eight to a line where a
 *  line of eight fits, else four (ShaText.perLine); never a group split. TalkBack reads it group by group. */
class ShaView(ctx: Context) : AppCompatTextView(ctx) {
    var hex: String = ""
        set(v) { if (field != v) { field = v; perLine = 0; text = if (v.isEmpty()) "" else ShaText.lines(v, 8); contentDescription = if (v.isEmpty()) null else ShaText.spoken(v); requestLayout() } }
    private var perLine = 0

    init { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (hex.isNotEmpty() && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val avail = (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight).toFloat()
            val n = ShaText.perLine(avail) { paint.measureText(it) }
            if (n != perLine) { perLine = n; text = ShaText.lines(hex, n) }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** The files pane's header, as MainActivity sees the batch. */
data class FilesHeader(
    /** the batch's Done screen (else the list over a transfer still receiving) */
    val done: Boolean,
    val title: String,
    /** "3 files · 2.2 MB · all verified" / "12 of 40 files · 18.0 MB of 64.0 MB" */
    val summary: String,
    /** Done: "12.4 s · 3.48 Mbit/s" */
    val meta: String? = null,
    /** "Save all" / "Save 12 received"; null: nothing to save yet */
    val saveLabel: String? = null,
    val saveEnabled: Boolean = true,
    /** the folder Save all writes to, in words; null until the user picks one */
    val folder: String? = null,
    /** a save's result, shown in the folder row in place of "Saving to …" ("Saved 3 files to SD card › LensBolt › trip") */
    val saved: String? = null,
    /** a save under way ("Saving 2 of 3 · photo.jpg") */
    val saveLine: String? = null,
    /** Save all under way: how far (0..1), a determinate bar under the line; null when nothing is being saved */
    val saveProgress: Float? = null,
    /** waiting for the list, receiving, done with every file verified, or done with some missing or failed */
    val state: BatchState = if (done) BatchState.DONE_ALL else BatchState.RECEIVING,
    /** files that could not be saved, named (amber) */
    val saveProblem: String? = null,
    /** "Save as they arrive": its state while receiving; null on Done */
    val arrive: Boolean? = null,
    val note: String? = null,
    val donate: Boolean = false,
    /** changes whenever a row's save mark may have changed */
    val marks: Int = 0,
    /** a Save all run (or a row's Save…) is under way: its result is announced once, when it ends */
    val saving: Boolean = false,
)

enum class PaneAction { CLOSE, SAVE_ALL, CHANGE_FOLDER, ARRIVE_ON, ARRIVE_OFF, NEW_TRANSFER, CLEAR_HISTORY }
enum class RowAction(val label: String) { SAVE("Save…"), OPEN("Open"), SHARE("Share"), COPY_SHA("Copy SHA-256"), SHOW_SHA("Show SHA-256"), HIDE_SHA("Hide SHA-256") }

interface PaneHost {
    fun onPane(a: PaneAction)
    fun onFileRow(index: Int, a: RowAction)
    fun onHistoryRow(index: Int, a: RowAction)
}

/** What a row adds after its state: null, or "saved" / "not saved: reason" (Save all, per index). */
typealias SavedNote = (Int) -> String?

internal class FilesPane(private val s: ReceiverScreen, private val ctx: Context, private val host: PaneHost) {
    val view = FrameLayout(ctx)
    private val filesList = RecyclerView(ctx)
    private val historyList = RecyclerView(ctx)
    private val filesAdapter = FilesAdapter()
    private val historyAdapter = HistoryAdapter()
    var filter = Filter.ALL
        private set
    var showing: Pane = Pane.NONE
        private set

    init {
        for ((rv, a) in listOf(filesList to filesAdapter, historyList to historyAdapter)) {
            rv.layoutManager = LinearLayoutManager(ctx)
            rv.adapter = a
            rv.itemAnimator = null              // rows change state in place: no flashing
            rv.clipToPadding = false
            rv.setPadding(0, 0, 0, s.dp(24))
            view.addView(rv, FrameLayout.LayoutParams(-1, -1))
        }
    }

    fun show(p: Pane) {
        showing = p
        filesList.visibility = if (p == Pane.FILES) View.VISIBLE else View.GONE
        historyList.visibility = if (p == Pane.HISTORY) View.VISIBLE else View.GONE
    }

    fun setFilter(f: Filter) { if (filter != f) { filter = f; filesAdapter.refilter(); filesList.scrollToPosition(0) } }

    fun setFiles(files: BatchFiles?, header: FilesHeader, saved: SavedNote) = filesAdapter.update(files, header, saved)

    fun setHistory(items: List<HistoryItem>, note: String?) = historyAdapter.update(items, note)

    /** The scroll positions survive a rebuild (rotation, font size). */
    fun saveState() = filesList.layoutManager?.onSaveInstanceState() to historyList.layoutManager?.onSaveInstanceState()
    fun restoreState(st: Pair<android.os.Parcelable?, android.os.Parcelable?>) {
        st.first?.let { filesList.layoutManager?.onRestoreInstanceState(it) }; st.second?.let { historyList.layoutManager?.onRestoreInstanceState(it) }
    }

    // ---- rows ---------------------------------------------------------------------------------------------

    private class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        val name = NameView(ctx).apply { s.type(this, Type.BODY_LARGE); setTextColor(Ink.ON); fit = true; maxLines = 2 }
        val meta = s.text(Type.BODY_SMALL, Ink.ON_VAR).apply { fontFeatureSettings = "tnum" }
        val meta2 = s.text(Type.BODY_SMALL, Ink.ON_VAR).apply { visibility = View.GONE }
        // a batch file's SHA-256 under its row, shown from its menu (the browser's row hash), with Copy
        val shaHex = ShaView(ctx).apply { s.type(this, Type.BODY_SMALL); setTextColor(Ink.ON) }
        val shaCopy = s.button(2) {}.apply { text = "Copy" }
        val shaRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(shaHex, LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
            addView(shaCopy, LayoutParams(-2, -2))
        }
        init {
            orientation = VERTICAL; minimumHeight = s.dp(56); gravity = Gravity.CENTER_VERTICAL
            setPadding(s.dp(16), s.dp(10), s.dp(16), s.dp(10))
            addView(name, LayoutParams(-1, -2))
            addView(meta, LayoutParams(-1, -2).apply { topMargin = s.dp(2) })
            addView(meta2, LayoutParams(-1, -2).apply { topMargin = s.dp(2) })
            addView(shaRow, LayoutParams(-1, -2).apply { topMargin = s.dp(4) })
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ink.alpha(android.graphics.Color.WHITE, 0x22)), null,
                android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
        }
    }

    private fun menu(anchor: View, actions: List<RowAction>, pick: (RowAction) -> Unit) {
        if (actions.isEmpty()) return
        val m = PopupMenu(ctx, anchor)
        for (a in actions) m.menu.add(0, a.ordinal, a.ordinal, a.label)
        m.setOnMenuItemClickListener { pick(RowAction.entries[it.itemId]); true }
        m.show()
    }

    private fun rowA11y(row: View, spoken: String, actions: Boolean, what: String = "Save, open or share") {
        row.contentDescription = spoken
        row.isClickable = actions; row.isFocusable = true
        ViewCompat.setAccessibilityDelegate(row, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (actions) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfo.ACTION_CLICK, what))
            }
        })
    }

    // ---- the header (one view, built once per pane, updated in place) ------------------------------------

    private fun header(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(s.dp(16), s.dp(8), s.dp(16), s.dp(8))
        layoutParams = RecyclerView.LayoutParams(-1, -2)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = s.dp(top) }

    private inner class FilesAdapter : RecyclerView.Adapter<Holder>() {
        private var files: BatchFiles? = null
        private var head = FilesHeader(false, "", "")
        private var saved: SavedNote = { null }
        private var rows = IntArray(0)
        private var version = -1L
        // the file whose SHA-256 shows under its row (one at a time, as in the browser); -1 none
        private var hashShown = -1

        // header views
        private val box = header()
        private val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val mark = ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.VERIFIED, Ink.PRIMARY, s.density, 32)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val close = s.button(2) { host.onPane(PaneAction.CLOSE) }.apply { text = "Close"; contentDescription = "Close the file list" }
        private val title = NameView(ctx).apply { s.type(this, Type.TITLE_LARGE); setTextColor(Ink.ON); fit = true; maxLines = 2; ViewCompat.setAccessibilityHeading(this, true) }
        private val summary = s.text(Type.BODY_MEDIUM, Ink.ON).apply { fontFeatureSettings = "tnum" }
        private val meta = s.text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { fontFeatureSettings = "tnum" }
        private val verified = s.text(Type.LABEL_LARGE, Ink.PRIMARY_TEXT).apply { text = "SHA-256 verified, each file" }
        private val save = s.button(0) { host.onPane(PaneAction.SAVE_ALL) }
        private val folderRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val folder = s.text(Type.BODY_MEDIUM, Ink.ON_VAR)
        private val change = s.button(2) { host.onPane(PaneAction.CHANGE_FOLDER) }.apply { text = "Change"; contentDescription = "Change the folder" }
        private val saveLine = s.text(Type.BODY_MEDIUM, Ink.ON).apply { fontFeatureSettings = "tnum" }
        private val saveBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = false; contentDescription = "Saving"
            val track = android.graphics.drawable.GradientDrawable().apply { cornerRadius = s.dp(2).toFloat(); setColor(Ink.TRACK) }
            val bar = android.graphics.drawable.ClipDrawable(android.graphics.drawable.GradientDrawable().apply { cornerRadius = s.dp(2).toFloat(); setColor(Ink.PRIMARY) }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(track, bar)).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        private val saveProblem = s.text(Type.BODY_MEDIUM, Ink.AMBER_TEXT)
        private val arrive = SwitchCompat(ctx).apply {
            text = "Save as they arrive"; s.type(this, Type.BODY_MEDIUM); setTextColor(Ink.ON); minHeight = s.dp(48)
            // the switch shows the remembered choice only: a tap asks for the other state, and the header that comes
            // back sets it (a folder picker cancelled leaves it off)
            setOnClickListener { val want = isChecked; isChecked = head.arrive == true; host.onPane(if (want) PaneAction.ARRIVE_ON else PaneAction.ARRIVE_OFF) }
        }
        private val note = s.text(Type.BODY_MEDIUM, Ink.AMBER_TEXT)
        private val newTransfer = s.button(2) { host.onPane(PaneAction.NEW_TRANSFER) }.apply { text = "New transfer" }
        private val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        private val chipViews = Filter.entries.associateWith { f -> chip(f) }
        private val donate = s.donateCard()
        private val empty = s.text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { setPadding(s.dp(16), s.dp(16), s.dp(16), s.dp(16)) }

        init {
            setHasStableIds(false)
            topRow.addView(mark, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.dp(12) })
            topRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            topRow.addView(close, LinearLayout.LayoutParams(-2, -2))
            box.addView(topRow, lp())
            box.addView(title, lp(4))
            box.addView(summary, lp(4))
            box.addView(meta, lp(2))
            box.addView(verified, lp(8))
            box.addView(save, lp(16))
            folderRow.addView(folder, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
            folderRow.addView(change, LinearLayout.LayoutParams(-2, -2))
            box.addView(folderRow, lp(4))
            box.addView(saveLine, lp(4))
            box.addView(saveBar, LinearLayout.LayoutParams(-1, s.dp(4)).apply { topMargin = s.dp(6) })
            box.addView(saveProblem, lp(4))
            box.addView(arrive, lp(4))
            box.addView(note, lp(8))
            box.addView(newTransfer, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.dp(4); gravity = Gravity.END })
            box.addView(donate, lp(12))
            for (f in Filter.entries) chips.addView(chipViews[f], LinearLayout.LayoutParams(-2, -2).apply { marginEnd = s.dp(8) })
            val scroller = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
            box.addView(scroller, lp(16))
        }

        // a filter chip: its own shape, so the selected one can take the brand colour
        private fun chip(f: Filter): Button = s.button(1) { setFilter(f); paintChips() }.apply {
            minWidth = s.dp(48); minimumWidth = s.dp(48)
            setPadding(s.dp(16), s.dp(4), s.dp(16), s.dp(4))
            val shape = android.graphics.drawable.GradientDrawable().apply { cornerRadius = s.dp(8).toFloat(); setColor(Ink.TONAL) }
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Ink.alpha(android.graphics.Color.WHITE, 0x33)),
                android.graphics.drawable.InsetDrawable(shape, 0, s.dp(4), 0, s.dp(4)), null)
            tag = shape
        }

        private fun paintChips() {
            val b = files
            for ((f, c) in chipViews) {
                val n = b?.count(f) ?: 0
                val label = BatchWords.filter(f, n)
                if (c.text.toString() != label) c.text = label
                val on = f == filter
                c.isSelected = on
                (c.tag as? android.graphics.drawable.GradientDrawable)?.setColor(if (on) Ink.PRIMARY else Ink.TONAL)
                c.setTextColor(if (on) android.graphics.Color.WHITE else Ink.ON)
                ViewCompat.setStateDescription(c, if (on) "Selected" else "Not selected")
            }
        }

        private fun vis(v: View, on: Boolean) { val t = if (on) View.VISIBLE else View.GONE; if (v.visibility != t) v.visibility = t }
        private fun set(t: TextView, x: String?) { vis(t, x != null); if (x != null && t.text.toString() != x) t.text = x }

        private var markPartial: Boolean? = null

        private fun bindHeader() {
            val h = head
            vis(mark, h.done); vis(close, !h.done); vis(verified, h.done)
            // every file verified: the blue verified mark; some missing or failed: the amber warning, said in words
            val partial = h.state == BatchState.DONE_PARTIAL
            if (markPartial != partial) {
                markPartial = partial
                mark.setImageDrawable(if (partial) Glyph(Glyph.Kind.WARN, Ink.AMBER_TEXT, s.density, 32) else Glyph(Glyph.Kind.VERIFIED, Ink.PRIMARY, s.density, 32))
                verified.text = BatchWords.verifiedLine(h.state)
                verified.setTextColor(if (partial) Ink.AMBER_TEXT else Ink.PRIMARY_TEXT)
                summary.setTextColor(if (partial) Ink.AMBER_TEXT else Ink.ON)
            }
            title.full = h.title
            set(summary, h.summary); set(meta, h.meta)
            vis(save, h.saveLabel != null)
            h.saveLabel?.let { if (save.text.toString() != it) save.text = it }
            if (save.isEnabled != h.saveEnabled) save.isEnabled = h.saveEnabled
            vis(folderRow, h.saveLabel != null)
            set(folder, if (h.saveLabel == null) null else SaveNames.folderLine(h.folder, h.saved))
            vis(change, h.folder != null)
            set(saveLine, h.saveLine); set(saveProblem, h.saveProblem)
            vis(saveBar, h.saveProgress != null)
            h.saveProgress?.let { f ->
                val p = (f * 1000).toInt().coerceIn(0, 1000)
                if (saveBar.progress != p) { saveBar.setProgress(p, false); ViewCompat.setStateDescription(saveBar, "${p / 10} percent saved") }
            }
            vis(arrive, h.arrive != null)
            if (h.arrive != null && arrive.isChecked != h.arrive) arrive.isChecked = h.arrive
            set(note, h.note)
            vis(newTransfer, h.done)
            vis(donate, h.done && h.donate)
            paintChips()
        }

        fun update(f: BatchFiles?, h: FilesHeader, sv: SavedNote) {
            val newHead = h != head
            val newMarks = h.marks != head.marks
            head = h; saved = sv
            val v = f?.version ?: -1L
            if (f !== files || v != version) {
                files = f; version = v
                if (f == null || hashShown >= f.total) hashShown = -1
                val old = rows
                rows = f?.indices(filter) ?: IntArray(0)
                if (old.size != rows.size || !old.contentEquals(rows)) notifyDataSetChanged()
                else if (rows.isNotEmpty()) notifyItemRangeChanged(1, rows.size, PAYLOAD)
                bindHeader()
            } else {
                if (newHead) bindHeader()
                if (newMarks && rows.isNotEmpty()) notifyItemRangeChanged(1, rows.size, PAYLOAD)   // saved notes
            }
            // the switch follows the remembered choice even when the header did not change (a cancelled picker)
            if (h.arrive != null && arrive.isChecked != h.arrive) arrive.isChecked = h.arrive
        }

        fun refilter() { rows = files?.indices(filter) ?: IntArray(0); notifyDataSetChanged(); paintChips() }

        override fun getItemCount() = 1 + maxOf(rows.size, 1)
        override fun getItemViewType(position: Int) = when { position == 0 -> 0; rows.isEmpty() -> 2; else -> 1 }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = when (viewType) {
            0 -> { (box.parent as? ViewGroup)?.removeView(box); Holder(box) }
            2 -> { (empty.parent as? ViewGroup)?.removeView(empty); empty.layoutParams = RecyclerView.LayoutParams(-1, -2); Holder(empty) }
            else -> Holder(Row(ctx))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val v = holder.itemView) {
                box -> bindHeader()
                empty -> empty.text = when (filter) {
                    Filter.RECEIVED -> "No file received yet."
                    Filter.MISSING -> "Every file is in."
                    Filter.FAILED -> "No file failed."
                    Filter.ALL -> if (files?.known == true) "No files." else "Waiting for the file list."
                }
                is Row -> bindRow(v, rows[position - 1])
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && holder.itemView is Row && position > 0 && position - 1 < rows.size) bindRow(holder.itemView as Row, rows[position - 1], nameToo = false)
            else onBindViewHolder(holder, position)
        }

        private fun bindRow(r: Row, i: Int, nameToo: Boolean = true) {
            val b = files ?: return
            if (i >= b.total) return
            val name = b.name(i)
            if (nameToo || r.name.full != name) r.name.full = name
            val st = b.state(i)
            val label = BatchWords.state(st, b.progress(i), b.reason(i))
            val extra = if (st == FileState.RECEIVED) saved(i) else null
            val line = "${Fmt.bytes(b.size(i))} · $label" + (extra?.let { " · $it" } ?: "")
            if (r.meta.text.toString() != line) r.meta.text = line
            r.meta.setTextColor(when { st == FileState.FAILED || extra?.startsWith("not saved") == true -> Ink.AMBER_TEXT; st == FileState.RECEIVED -> Ink.PRIMARY_TEXT; else -> Ink.ON_VAR })
            val ready = st == FileState.RECEIVED
            val sha = if (ready) b.sha256(i) else null
            val shown = sha != null && hashShown == i
            if (shown) {
                r.shaHex.hex = sha!!
                r.shaCopy.contentDescription = "Copy the SHA-256 of $name"
                r.shaCopy.setOnClickListener { host.onFileRow(i, RowAction.COPY_SHA) }
            }
            vis(r.shaRow, shown)
            rowA11y(r, BatchWords.spoken(name, b.size(i), label) + (extra?.let { ", $it" } ?: ""), ready,
                if (sha != null) "Save, open, share or see its SHA-256" else "Save, open or share")
            r.setOnClickListener(if (ready) View.OnClickListener { menu(r, BatchWords.rowActions(sha != null, shown)) { a -> pick(i, a) } } else null)
            if (!ready) r.isClickable = false
        }

        // a row's menu choice: its hash shown or hidden here, the rest for MainActivity
        private fun pick(i: Int, a: RowAction) {
            when (a) {
                RowAction.SHOW_SHA, RowAction.HIDE_SHA -> {
                    hashShown = if (a == RowAction.SHOW_SHA) i else -1
                    if (rows.isNotEmpty()) notifyItemRangeChanged(1, rows.size, PAYLOAD)
                }
                else -> host.onFileRow(i, a)
            }
        }
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<Holder>() {
        private var items: List<HistoryItem> = emptyList()
        // the item whose SHA-256 shows under its row (asked for from its menu); -1 none
        private var hashShown = -1
        private val box = header()
        private val title = s.text(Type.TITLE_LARGE, Ink.ON).apply { text = "History"; ViewCompat.setAccessibilityHeading(this, true) }
        private val close = s.button(2) { host.onPane(PaneAction.CLOSE) }.apply { text = "Close"; contentDescription = "Close the history" }
        private val lead = s.text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { text = "Files you saved: their names and where they went. LensBolt keeps no copies." }
        private val note = s.text(Type.BODY_MEDIUM, Ink.AMBER_TEXT).apply { visibility = View.GONE }
        private val clear = s.button(2) { host.onPane(PaneAction.CLEAR_HISTORY) }.apply { text = "Clear history" }
        private val empty = s.text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { text = "Nothing saved yet."; setPadding(s.dp(16), s.dp(16), s.dp(16), s.dp(16)) }
        private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        init {
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(close, LinearLayout.LayoutParams(-2, -2))
            box.addView(top, lp())
            box.addView(lead, lp(4))
            box.addView(note, lp(8))
            box.addView(clear, LinearLayout.LayoutParams(-2, -2).apply { topMargin = s.dp(4); gravity = Gravity.END })
        }

        fun update(list: List<HistoryItem>, n: String?) {
            if (list != items) hashShown = -1
            items = list
            note.visibility = if (n == null) View.GONE else View.VISIBLE; note.text = n ?: ""
            clear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            notifyDataSetChanged()
        }

        override fun getItemCount() = 1 + maxOf(items.size, 1)
        override fun getItemViewType(position: Int) = when { position == 0 -> 0; items.isEmpty() -> 2; else -> 1 }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = when (viewType) {
            0 -> { (box.parent as? ViewGroup)?.removeView(box); Holder(box) }
            2 -> { (empty.parent as? ViewGroup)?.removeView(empty); empty.layoutParams = RecyclerView.LayoutParams(-1, -2); Holder(empty) }
            else -> Holder(Row(ctx))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = holder.itemView as? Row ?: return
            val i = position - 1
            val h = items[i]
            r.name.full = h.name
            val date = fmt.format(Date(h.date))
            r.meta.text = HistoryWords.meta(h, date)
            r.meta.setTextColor(Ink.ON_VAR)
            r.meta2.visibility = View.VISIBLE
            r.meta2.text = HistoryWords.where(h)
            // the hash under the row only when asked for (its menu), with Copy: the files list's way
            val shown = h.sha != null && hashShown == i
            if (shown) {
                r.shaHex.hex = h.sha!!
                r.shaCopy.contentDescription = "Copy the SHA-256 of ${h.name}"
                r.shaCopy.setOnClickListener { host.onHistoryRow(i, RowAction.COPY_SHA) }
            }
            r.shaRow.visibility = if (shown) View.VISIBLE else View.GONE
            val acts = HistoryWords.actions(h, shown)
            val can = acts.isNotEmpty()
            rowA11y(r, HistoryWords.spoken(h, date), can, acts.joinToString(", ") { it.label.removeSuffix("…") })
            r.setOnClickListener(if (can) View.OnClickListener { menu(r, acts) { a ->
                when (a) {
                    RowAction.SHOW_SHA, RowAction.HIDE_SHA -> { hashShown = if (a == RowAction.SHOW_SHA) i else -1; notifyDataSetChanged() }
                    else -> host.onHistoryRow(i, a)
                }
            } } else null)
            if (!can) r.isClickable = false
        }
    }

    private companion object { val PAYLOAD = Any() }
}

enum class Pane { NONE, FILES, HISTORY }
