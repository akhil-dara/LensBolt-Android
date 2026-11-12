package com.lensbolt.app

// The receiver's screen: views built in code, one state at a time (UiState), always dark (a camera viewfinder
// world). The preview fits the window (FIT_CENTER) so the framing guide marks what the camera really sees; the
// panels sit in the bands around the image and never cover the guide's centre. MainActivity owns the camera and
// the session and hands this class a UiState; this class owns layout, copy, announcements and the toolbar.

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.camera.view.PreviewView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.min
import kotlin.math.roundToInt

sealed class UiState {
    /** Camera permission: `denied` = permanently denied (only the system settings can turn it on). */
    data class Permission(val denied: Boolean) : UiState()
    data class Aim(val hint: Hint) : UiState()
    /** A tile is read; `name` once the manifest arrives. */
    data class Locked(val name: String?, val hint: Hint) : UiState()
    /** `finishing`: every segment verified, the file being put together (Guidance.finishing). `finishWords`: the
     *  words that name the parts still missing (FinishWords.of), null when there are none; `finishParts`: the parts
     *  they name, "Parts 7, 12–15, 30" (FinishWords.parts). */
    data class Receiving(val name: String, val done: Long, val total: Long, val mbit: Double, val secondsLeft: Long, val hint: Hint,
                         val finishing: Boolean = false, val finishWords: String? = null, val finishParts: String? = null,
                         /** a batch: its summary line ("12 of 40 files · 18.0 MB of 64.0 MB"); the panel offers its list */
                         val batch: String? = null) : UiState()
    /** The verified file. `savingPct` >= 0 while a save copies; `available` false once Android removed it;
     *  `donate`: the support card under the actions (DonatePrompt). */
    data class Done(val name: String, val size: Long, val seconds: Double, val mbit: Double, val savedAs: String?,
                    val savingPct: Int = -1, val note: String? = null, val available: Boolean = true,
                    val donate: Boolean = false, val sha: String? = null) : UiState()
    data class Problem(val title: String, val text: String, val action: Action, val actionLabel: String) : UiState()
    /** A batch is in: its Done screen is the file list (FilesPane, header from [ReceiverScreen.setFiles]). */
    data class BatchDone(val name: String) : UiState()
}

enum class Action { SOURCE, GRANT, SETTINGS, SAVE, OPEN, SHARE, NEW_TRANSFER, RETRY_CAMERA, START_OVER, DONATE_COFFEE, DONATE_SPONSORS, DONATE_LATER, DONATE_NEVER }

enum class CameraMode(val label: String, val menu: String) {
    AUTO("Auto", "Auto (recommended)"), FULL_HD("Full HD", "Full HD"), MAX("Max", "Max resolution");
    companion object {
        /** What the camera menu offers. Max is not: a laptop's tile at 4096x3072 ran a phone's camera at 3-4 fps
         *  with decodes of 0.5-1.2 s, against 30 fps and 35-44 ms at Full HD (2026-11-04). Auto still takes the
         *  large stream for a 1 px tile ([CameraChoice]). */
        val MENU = listOf(AUTO, FULL_HD)
        /** A saved choice: one the menu no longer offers (Max), or none, is Auto. */
        fun fromSaved(s: String?): CameraMode = entries.firstOrNull { it.name == s }?.takeIf { it in MENU } ?: AUTO
    }
}

interface ScreenHost : PaneHost {
    fun onAction(a: Action)
    fun onCameraMode(m: CameraMode)
    /** the camera menu's How it works, Rate LensBolt and Send feedback */
    fun onHelp(item: Help.Item) {}
    fun onDetails(on: Boolean)
    /** the top bar's History; the receiving panel's Files */
    fun onShowPane(p: Pane)
    /** A tap on the preview at (x, y) in the preview's own coordinates: focus there; `done` with whether the
     *  camera reports it focused (false when it refused or has no camera). */
    fun onTapFocus(x: Float, y: Float, done: (Boolean) -> Unit) { done(false) }
}

/** Sizes and times as the screen writes them. */
object Fmt {
    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
    /** "12.4 s" under a minute, then "1:26" or "1:02:03". */
    fun duration(secs: Double): String {
        if (secs < 60) return "%.1f s".format(secs)
        val s = secs.roundToInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
    fun left(secs: Long): String = when {
        secs < 60 -> "$secs s left"
        secs >= 3600 -> "%d:%02d:%02d left".format(secs / 3600, secs / 60 % 60, secs % 60)
        else -> "%d:%02d left".format(secs / 60, secs % 60)
    }
}

// the palette on dark: brand blue for containers (white on #2563EB is 5.2:1), a lighter tone of it for text and
// marks on dark, brand amber for warnings; secondary text tinted slate, never plain grey
internal object Ink {
    const val SURFACE = 0xEB111720.toInt()         // panels over the preview
    const val SOLID = 0xFF0A0E14.toInt()           // behind full-screen states (the preview covered)
    const val TONAL = 0xFF232C3A.toInt()
    const val TRACK = 0xFF2A3445.toInt()
    const val ON = 0xFFF1F5F9.toInt()
    const val ON_VAR = 0xFFAEB9C7.toInt()
    const val PRIMARY = 0xFF2563EB.toInt()
    const val PRIMARY_TEXT = 0xFF8DB2FF.toInt()
    const val AMBER = 0xFFD97706.toInt()
    const val AMBER_TEXT = 0xFFF5B54A.toInt()
    const val AMBER_CONTAINER = 0xF22A1D08.toInt()
    const val ON_AMBER_CONTAINER = 0xFFFDE6C4.toInt()
    const val ICON_CONTAINER = 0x8C000000.toInt()
    const val CARD = 0xFF131A24.toInt()            // the support card on the Done screen's solid background
    fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}

// Material type scale roles (sp, line height sp, medium weight)
internal enum class Type(val sp: Float, val line: Float, val medium: Boolean) {
    HEADLINE_SMALL(24f, 32f, false), TITLE_LARGE(22f, 28f, false), TITLE_MEDIUM(16f, 24f, true),
    BODY_LARGE(16f, 24f, false), BODY_MEDIUM(14f, 20f, false), BODY_SMALL(12f, 16f, false), LABEL_LARGE(14f, 20f, true)
}

/** Icons drawn on a 24-unit grid in one 2-unit stroke. */
class Glyph(private val kind: Kind, var color: Int, density: Float, sizeDp: Int = 24) : Drawable() {
    enum class Kind { CAMERA, DETAILS, VERIFIED, WARN, DOT, HISTORY }
    private val px = (sizeDp * density).roundToInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(c: Canvas) {
        val b = bounds
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f)
        stroke.color = color; fill.color = color
        when (kind) {
            Kind.CAMERA -> {
                c.drawRoundRect(RectF(3f, 7.5f, 21f, 19.5f), 2.5f, 2.5f, stroke)
                c.drawPath(Path().apply { moveTo(8.5f, 7.5f); lineTo(10f, 5f); lineTo(14f, 5f); lineTo(15.5f, 7.5f) }, stroke)
                c.drawCircle(12f, 13.5f, 3.5f, stroke)
            }
            Kind.DETAILS -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                c.drawLine(12f, 11f, 12f, 16.5f, stroke)
                c.drawCircle(12f, 7.75f, 1.25f, fill)
            }
            Kind.VERIFIED -> {
                c.drawCircle(12f, 12f, 11f, fill)
                stroke.color = Color.WHITE; stroke.strokeWidth = 2.2f
                c.drawPath(Path().apply { moveTo(7.3f, 12.4f); lineTo(10.5f, 15.5f); lineTo(16.7f, 9.1f) }, stroke)
                stroke.strokeWidth = 2f
            }
            Kind.WARN -> {
                c.drawPath(Path().apply { moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close() }, stroke)
                c.drawLine(12f, 9.5f, 12f, 14f, stroke)
                c.drawCircle(12f, 17f, 1.2f, fill)
            }
            Kind.DOT -> c.drawCircle(12f, 12f, 12f, fill)
            Kind.HISTORY -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                c.drawPath(Path().apply { moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f) }, stroke)
            }
        }
        c.restore()
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Corner marks around the square the tile should fill, on the camera image as FIT_CENTER shows it. */
/** Where a tap asked the camera to focus: a ring that settles there, blue when the camera reports it focused,
 *  amber when it does not, then fades. It never takes a touch. */
private class FocusRing(ctx: Context, private val px: (Int) -> Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = px(2).toFloat(); color = Color.WHITE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = px(4).toFloat(); color = 0x59000000 }
    private val fade = Runnable { animate().alpha(0f).setDuration(180).start() }
    init { alpha = 0f; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val r = width / 2f - px(3)
        canvas.drawCircle(width / 2f, height / 2f, r, shade)
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
    }
    /** settle at (cx, cy), the centre in the parent's coordinates */
    fun show(cx: Float, cy: Float) {
        removeCallbacks(fade); animate().cancel()
        paint.color = Color.WHITE; invalidate()
        x = cx - width / 2f; y = cy - height / 2f
        scaleX = 1.35f; scaleY = 1.35f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
    }
    fun result(ok: Boolean) {
        paint.color = if (ok) Ink.PRIMARY_TEXT else Ink.AMBER_TEXT; invalidate()
        removeCallbacks(fade); postDelayed(fade, 900)
    }
}

private class GuideView(ctx: Context) : View(ctx) {
    /** the camera image's long side over its short side (4:3 streams) */
    var aspect = 4f / 3f
        set(v) { field = v; invalidate() }
    var tone = Tone.NEUTRAL
        set(v) { if (field != v) { field = v; invalidate() } }
    /** the guide as the layout placed it (StageMath.stack / columns); null: on the centred image */
    var frame: Frame? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val p = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val g = frame ?: StageMath.square(width, height, aspect)
        val r = RectF(g.l, g.t, g.r, g.b)
        paint.color = when (tone) { Tone.NEUTRAL -> Ink.alpha(Color.WHITE, 0xD9); Tone.GOOD -> Ink.PRIMARY_TEXT; Tone.WARN -> Ink.AMBER_TEXT }
        val arm = min(r.width() * 0.13f, 36 * d)
        p.reset()
        p.moveTo(r.left, r.top + arm); p.lineTo(r.left, r.top); p.lineTo(r.left + arm, r.top)
        p.moveTo(r.right - arm, r.top); p.lineTo(r.right, r.top); p.lineTo(r.right, r.top + arm)
        p.moveTo(r.right, r.bottom - arm); p.lineTo(r.right, r.bottom); p.lineTo(r.right - arm, r.bottom)
        p.moveTo(r.left + arm, r.bottom); p.lineTo(r.left, r.bottom); p.lineTo(r.left, r.bottom - arm)
        c.drawPath(p, paint)
    }
}

/** Places the layers. No panel ever covers the guide. Portrait: the top bar, then the guide just under it (the
 *  camera image moves with it), then the banner, Details and the panel in the band under the guide. Landscape:
 *  Details in the left band, top bar, banner and panel in a right column (the image moves toward the start for
 *  it). Full-screen states centre the panel over a solid background. Every panel scrolls rather than clips. */
private class Stage(ctx: Context) : ViewGroup(ctx) {
    var insets: Insets = Insets.NONE
        set(v) { field = v; requestLayout() }
    var full = false
        set(v) { if (field != v) { field = v; requestLayout() } }
    lateinit var guide: GuideView; lateinit var scrim: View; lateinit var top: View
    lateinit var banner: View; lateinit var details: View; lateinit var panel: View; lateinit var live: View
    /** the file list / history (full window under the top bar) while [paneOn] */
    lateinit var pane: View
    var paneOn = false
        set(v) { if (field != v) { field = v; requestLayout() } }
    /** "Changing camera…" over the guide's centre while the camera reopens on another stream */
    lateinit var changing: View
    /** the camera image's offset from its centred place (the preview is moved by it) */
    var onShift: ((Float, Float) -> Unit)? = null
    private val frames = HashMap<View, IntArray>()
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.AT_MOST)
    private fun place(v: View, l: Int, t: Int) { frames[v] = intArrayOf(l, t, l + v.measuredWidth, t + v.measuredHeight) }
    private fun shown(v: View) = v.visibility != GONE

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
        frames.clear()
        for (v in listOf(guide, scrim)) { v.measure(exact(w), exact(h)); place(v, 0, 0) }
        live.measure(exact(1), exact(1)); place(live, 0, 0)
        changing.measure(atMost(w - dp(48)), atMost(h))
        when {
            paneOn -> measurePane(w, h)
            full -> measureFull(w, h)
            else -> {
                val cols = if (w > h) StageMath.columns(w, h, insets.left, insets.right, guide.aspect, d) else null
                if (cols == null) measureStack(w, h) else measureColumns(w, h, cols)
            }
        }
    }

    private fun placeImage(w: Int, h: Int, img: Frame, g: Frame) {
        guide.frame = g
        place(changing, (g.cx - changing.measuredWidth / 2f).toInt(), (g.cy - changing.measuredHeight / 2f).toInt())
        val c = StageMath.image(w, h, guide.aspect)
        onShift?.invoke(img.l - c.l, img.t - c.t)
    }

    // camera states, portrait (and landscape windows too narrow for columns): the top bar, the guide under it,
    // then the banner, Details and the panel in the band under the guide (each scrolls inside its share)
    private fun measureStack(w: Int, h: Int) {
        val m = dp(12); val ins = insets
        val left = ins.left + m; val right = w - ins.right - m; val cw = right - left
        top.measure(exact(w - ins.left - ins.right), atMost(dp(160))); place(top, ins.left, ins.top)
        val st = StageMath.stack(w, h, guide.aspect, ins.top, ins.bottom, top.measuredHeight, d)
        placeImage(w, h, st.image, st.guide)
        var y = st.bandTop; val bottom = st.bandBottom
        if (shown(banner)) { banner.measure(exact(cw), atMost((bottom - y) / 2)); place(banner, left, y); y += banner.measuredHeight + m }
        panel.measure(exact(cw), atMost(bottom - y)); place(panel, left, bottom - panel.measuredHeight)
        if (shown(details)) {
            details.measure(exact(cw), atMost(bottom - panel.measuredHeight - m - y))
            place(details, left, bottom - panel.measuredHeight - m - details.measuredHeight)
        }
    }

    // camera states, landscape: a right column clear of the guide's centre, Details in the left band (or in the
    // column above the panel when that band is narrow)
    private fun measureColumns(w: Int, h: Int, c: Columns) {
        val m = dp(12); val ins = insets
        placeImage(w, h, c.image, c.guide)
        val bottom = h - ins.bottom - m
        val colW = c.colRight - c.colLeft
        top.measure(exact(colW + m), atMost(dp(160))); place(top, c.colLeft, ins.top)
        var y = ins.top + top.measuredHeight
        if (shown(banner)) { banner.measure(exact(colW), atMost((bottom - y) / 2)); place(banner, c.colLeft, y); y += banner.measuredHeight + m }
        panel.measure(exact(colW), atMost(bottom - y)); place(panel, c.colLeft, bottom - panel.measuredHeight)
        if (shown(details)) {
            if (c.detailsInColumn) {
                details.measure(exact(colW), atMost(bottom - panel.measuredHeight - m - y))
                place(details, c.colLeft, bottom - panel.measuredHeight - m - details.measuredHeight)
            } else {
                details.measure(exact(c.detRight - c.detLeft), atMost(bottom - (ins.top + m))); place(details, c.detLeft, ins.top + m)
            }
        }
    }

    // the file list or the history: under the top bar, the whole window's height, at most 640 dp wide
    private fun measurePane(w: Int, h: Int) {
        val ins = insets
        top.measure(exact(w - ins.left - ins.right), atMost(dp(160))); place(top, ins.left, ins.top)
        val y = ins.top + top.measuredHeight
        val avail = w - ins.left - ins.right
        val pw = min(avail, dp(640))
        pane.measure(exact(pw), exact(h - ins.bottom - y)); place(pane, ins.left + (avail - pw) / 2, y)
    }

    // full-screen states (no guide): the panel centred, Details at the bottom (portrait) or in a left band
    private fun measureFull(w: Int, h: Int) {
        val m = dp(12); val ins = insets
        val bottom = h - ins.bottom - m
        val left = ins.left + m; val right = w - ins.right - m; val cw = (right - left).coerceAtLeast(0)
        top.measure(exact(w - ins.left - ins.right), atMost(dp(160))); place(top, ins.left, ins.top)
        var y = ins.top + top.measuredHeight
        if (shown(banner)) { banner.measure(exact(cw), atMost(h / 3)); place(banner, left, y); y += banner.measuredHeight + m }
        var areaL = left; var b = bottom
        if (shown(details)) {
            if (h >= w || cw < dp(560)) {
                details.measure(exact(cw), atMost((bottom - y) * 2 / 5)); place(details, left, bottom - details.measuredHeight)
                b -= details.measuredHeight + m
            } else {
                val dW = min(dp(320), cw * 2 / 5)
                details.measure(exact(dW), atMost(bottom - y)); place(details, left, y)
                areaL = left + dW + m
            }
        }
        val areaW = (right - areaL).coerceAtLeast(0)
        val pw = min(areaW, dp(520))
        panel.measure(exact(pw), atMost(b - y))
        place(panel, areaL + (areaW - pw) / 2, y + ((b - y) - panel.measuredHeight).coerceAtLeast(0) / 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val f = frames[v] ?: continue
            if (v.visibility != GONE) v.layout(f[0], f[1], f[2], f[3])
        }
    }
}

class ReceiverScreen(private val activity: Activity, private val preview: PreviewView, private val host: ScreenHost) {
    private lateinit var focusRing: View
    /** A short tap (no drag) on the preview: the ring there, the camera focused there. */
    private val tapToFocus = object : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var downAt = 0L
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) <= slop && e.eventTime - downAt < 500) {
                        v.performClick()
                        val ring = focusRing as FocusRing
                        ring.show(v.x + e.x, v.y + e.y)
                        host.onTapFocus(e.x, e.y) { ok -> ring.result(ok) }
                    }
                }
            }
            return true
        }
    }
    /** The content view; its children are rebuilt on every configuration change (rotation, font size). */
    val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }

    private val ctx: Context get() = activity
    internal val density get() = activity.resources.displayMetrics.density
    internal fun dp(v: Int) = (v * density).roundToInt()

    private var insets: Insets = Insets.NONE
    private var state: UiState? = null
    private var bannerText: String? = null
    private var detailsOn = false
    private var detailRows: List<Pair<String, String>> = emptyList()
    private var mode = CameraMode.AUTO
    private var aspect = 4f / 3f

    // announcements (TalkBack): at milestones only, never per frame
    private var announcedLock = false
    private var announcedName: String? = null
    private var quarter = 0
    private var announcedDone: String? = null
    private var announcedSaved: String? = null
    private var announcedProblem: String? = null
    private var announcedNote: String? = null
    private var announcedSaveLine: String? = null
    private var announcedSaveProblem: String? = null
    private var bannerKey = 0
    private val wordsSaid = WordsAnnouncer()
    private var previewLive = false
    private var changingOn = false
    private lateinit var changingView: TextView
    private var boxFull: Boolean? = null
    private var titleKey = ""
    private var markKey = ""
    private var hintTone: Tone? = null
    private var detailViews: List<Pair<TextView, TextView>> = emptyList()
    // Details over the camera: a few rows unless All details is on (this run only); the full list elsewhere
    private var detailsAll = false
    private var detailsKey = ""

    private lateinit var stage: Stage
    private lateinit var guide: GuideView
    private lateinit var scrim: View
    private lateinit var live: TextView
    private lateinit var modeBtn: ImageButton
    private lateinit var detailsBtn: ImageButton
    private lateinit var detailsFill: GradientDrawable
    private lateinit var detailsGlyph: Glyph
    private lateinit var banner: LinearLayout
    private lateinit var bannerTextView: TextView
    private lateinit var detailsScroll: ScrollView
    private lateinit var detailsBox: LinearLayout
    private lateinit var panelScroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var mark: ImageView
    private lateinit var tTitle: NameView
    private lateinit var bFiles: Button
    private lateinit var metaRow: LinearLayout
    private lateinit var filesPane: FilesPane
    // the pane over the camera (Files, History) and what it shows; kept across rebuilds
    private var pane = Pane.NONE
    private var paneFiles: BatchFiles? = null
    private var paneHeader = FilesHeader(false, "", "")
    private var paneSaved: SavedNote = { null }
    private var paneHistory: List<HistoryItem> = emptyList()
    private var paneHistoryNote: String? = null
    private lateinit var tBody: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tMeta1: TextView
    private lateinit var tMeta2: TextView
    private lateinit var tVerified: TextView
    // the file's SHA-256 in groups of four, and Copy (to compare with the sender's)
    private lateinit var shaRow: LinearLayout
    private lateinit var tSha: ShaView
    private lateinit var bCopySha: Button
    private var shaShown: String? = null
    private lateinit var tSaved: TextView
    private lateinit var tHint: TextView
    private lateinit var finishBox: LinearLayout
    private lateinit var tWords: TextView
    private lateinit var tParts: TextView
    private lateinit var bCopy: Button
    private lateinit var tNote: TextView
    private lateinit var bPrimary: Button
    private lateinit var rowAB: LinearLayout
    private lateinit var bOpen: Button
    private lateinit var bShare: Button
    private lateinit var bText: Button
    // the guidance line and Start over share a row (the band under the guide is short)
    private lateinit var hintRow: LinearLayout
    private lateinit var bStart: Button
    private lateinit var donateBox: LinearLayout
    private var primaryAction = Action.SAVE
    private var textAction = Action.NEW_TRANSFER
    private var shownKind: Class<*>? = null

    init {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            insets = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (::stage.isInitialized) stage.insets = insets
            WindowInsetsCompat.CONSUMED
        }
        build()
    }

    /** Rebuild every view for the current configuration (sizes in dp and sp are read again); the preview, the
     *  state and the toggles carry over. */
    fun rebuild() {
        boxFull = null; titleKey = ""; markKey = ""; hintTone = null; detailViews = emptyList(); detailsKey = ""
        build()
        state?.let { shownKind = null; render(it) }
        ViewCompat.requestApplyInsets(root)
    }

    fun setImageAspect(longOverShort: Float) { aspect = longOverShort; if (::guide.isInitialized) { guide.aspect = longOverShort; stage.requestLayout() } }

    fun setCameraMode(m: CameraMode) {
        mode = m
        val desc = "Camera mode: ${m.label}"
        modeBtn.contentDescription = desc; modeBtn.tooltipText = desc
    }

    fun setDetailsOn(on: Boolean) {
        detailsOn = on
        detailsScroll.visibility = if (on && !paneShown()) View.VISIBLE else View.GONE
        detailsFill.setColor(if (on) Ink.PRIMARY else Ink.ICON_CONTAINER)
        detailsGlyph.color = Ink.ON; detailsGlyph.invalidateSelf()
        ViewCompat.setStateDescription(detailsBtn, if (on) "On" else "Off")
        // the rows may have arrived while Details were off (Done stops the polling that would refill them)
        if (on) fillDetails()
        stage.requestLayout()
    }

    fun setDetails(rows: List<Pair<String, String>>) {
        detailRows = rows
        if (!detailsOn) return
        fillDetails()
    }

    /** The sender-faster-than-the-camera banner (null hides it); shown over the camera states only. Spoken
     *  when it appears and when its suggestion (`key`, the fps to set) changes, not when the measured rate in
     *  its text wobbles. */
    fun setBanner(text: String?, key: Int = 0) {
        if (text != null && (bannerText == null || key != bannerKey)) announce(text)
        bannerText = text; bannerKey = if (text != null) key else 0
        applyBanner()
    }

    /** Whether the camera feeds the preview (lab screens and Done run with it off): an idle or covered preview
     *  is hidden from TalkBack. */
    fun setPreviewLive(on: Boolean) { previewLive = on; state?.let { applyPreviewA11y(it) } }

    /** Which pane covers the camera (the batch list over a transfer, the history); a batch's Done screen always
     *  shows its list. */
    fun showPane(p: Pane) { if (pane != p) { pane = p; state?.let { shownKind = null; render(it) } } }

    /** The batch's files and the list's header (updated in place: scroll position and focus stay). */
    fun setFiles(files: BatchFiles?, header: FilesHeader, saved: SavedNote) {
        // a run's result once, when it ends (never a line per file saved or failed)
        if (!header.saving) {
            val line = header.saved?.takeIf { it != announcedSaveLine }
            val problem = header.saveProblem?.takeIf { it != announcedSaveProblem }
            if (line != null) announcedSaveLine = line
            if (problem != null) announcedSaveProblem = problem
            listOfNotNull(line, problem).takeIf { it.isNotEmpty() }?.let { announce(it.joinToString(". ")) }
        }
        paneFiles = files; paneHeader = header; paneSaved = saved
        filesPane.setFiles(files, header, saved)
    }

    fun setHistory(items: List<HistoryItem>, note: String?) {
        if (note != null && note != paneHistoryNote) announce(note)
        paneHistory = items; paneHistoryNote = note
        filesPane.setHistory(items, note)
    }

    private fun paneShown() = ::stage.isInitialized && stage.paneOn

    /** "Changing camera…" over the preview while the camera reopens on another stream (camera states only). */
    fun setChanging(on: Boolean) {
        if (on == changingOn) return
        changingOn = on
        if (on) announce("Changing camera")
        state?.let { applyChanging(it) }
    }

    private fun applyChanging(s: UiState) {
        val show = changingOn && (s is UiState.Aim || s is UiState.Locked || s is UiState.Receiving) && !paneShown()
        vis(changingView, show)
    }

    /** A new transfer: milestones are announced again. */
    fun resetAnnouncements() {
        announcedLock = false; announcedName = null; quarter = 0; announcedDone = null; announcedSaved = null
        announcedProblem = null; announcedNote = null; announcedSaveLine = null; announcedSaveProblem = null; wordsSaid.reset()
    }

    // Every poll renders, so every change below is applied only when it differs: views never toggle, text is
    // never re-set to itself, and TalkBack's focus stays where the user put it.
    fun render(s: UiState) {
        val kindChanged = shownKind != s.javaClass
        if (s !is UiState.Problem) announcedProblem = null
        state = s
        shownKind = s.javaClass
        val full = s is UiState.Permission || s is UiState.Done || s is UiState.Problem || s is UiState.BatchDone
        // the file list (a batch's Done screen, or opened over a transfer) and the history cover the camera
        val paneNow = when { pane == Pane.HISTORY -> Pane.HISTORY; s is UiState.BatchDone || pane == Pane.FILES -> Pane.FILES; else -> Pane.NONE }
        if (filesPane.showing != paneNow) filesPane.show(paneNow)
        stage.paneOn = paneNow != Pane.NONE
        vis(filesPane.view, paneNow != Pane.NONE); vis(panelScroll, paneNow == Pane.NONE)
        vis(detailsScroll, detailsOn && paneNow == Pane.NONE)
        stage.full = full
        vis(scrim, full || paneNow != Pane.NONE); vis(guide, !full && paneNow == Pane.NONE)
        if (boxFull != full) { boxFull = full; box.background = if (full) null else rounded(Ink.SURFACE, 16) }
        applyPreviewA11y(s)
        val show = HashSet<View>()
        when (s) {
            is UiState.Permission -> {
                showMark(Glyph.Kind.CAMERA, Ink.PRIMARY_TEXT, 40); show += mark
                title(if (s.denied) "Camera access is off" else "Allow camera access")
                body(if (s.denied) "LensBolt reads the sender's screen with the camera. Turn on Camera for LensBolt in Settings. Nothing you receive leaves this phone."
                     else "LensBolt reads the sender's screen with the camera. Nothing you receive leaves this phone."); show += tBody
                primary(if (s.denied) "Open settings" else "Allow camera", if (s.denied) Action.SETTINGS else Action.GRANT); show += bPrimary
            }
            is UiState.Aim -> {
                guide.tone = s.hint.tone
                title(s.hint.text, color = if (s.hint.tone == Tone.WARN) Ink.AMBER_TEXT else Ink.ON)
                if (s.hint == Hints.SEARCH) { body("On the computer, open lensbolt.com, choose Send and pick a file. Then fill the corners with the square on its screen."); show += tBody }
            }
            is UiState.Locked -> {
                guide.tone = s.hint.tone
                if (s.name == null) title("Locked on") else title(s.name, oneLine = true)
                body(if (s.name == null) "Reading the file name…" else "Getting ready to receive"); show += tBody
                hint(s.hint); show += tHint; show += hintRow
                show += bStart
                if (!announcedLock) { announcedLock = true; announce(if (s.name == null) "Locked on to the sender" else "Locked on. ${s.name}") }
            }
            is UiState.Receiving -> {
                guide.tone = s.hint.tone
                title(s.name, oneLine = true)
                vis(bFiles, s.batch != null)
                // the last segment verifies and the file is unpacked after every block is in: 99% until complete
                val finishing = s.finishing
                val permille = if (s.total > 0) (s.done * 1000 / s.total).toInt().coerceIn(0, 990) else 0
                if (progress.progress != permille) {
                    progress.setProgress(permille, false)
                    ViewCompat.setStateDescription(progress, "${permille / 10} percent")
                }
                show += progress; show += tMeta1; show += tMeta2
                setText(tMeta1, s.batch ?: "${Fmt.bytes(minOf(s.done, s.total))} of ${Fmt.bytes(s.total)} · ${permille / 10}%")
                setText(tMeta2, when {
                    finishing -> "Finishing…"
                    s.mbit > 0 -> "%.2f Mbit/s".format(s.mbit) + (if (s.secondsLeft >= 0) " · " + Fmt.left(s.secondsLeft) else "")
                    else -> "Measuring speed…"
                })
                hint(s.hint); show += tHint; show += hintRow
                // the parts still missing as words to type on the sender; the text is set only when the words change,
                // so a selection the user is making stays
                s.finishWords?.let { setText(tWords, it); show += finishBox }
                // and the parts they name, small, under them (what the sender will send)
                s.finishParts?.let { setText(tParts, it) }
                vis(tParts, s.finishParts != null)
                show += bStart
                announcedLock = true
                // one live-region update per render: two set in a row may be merged and the first never spoken
                val say = ArrayList<String>()
                if (announcedName != s.name) { announcedName = s.name; say += "Receiving ${s.name}, ${Fmt.bytes(s.total)}" }
                val q = if (s.total > 0) (s.done * 4 / s.total).toInt().coerceIn(0, 3) else 0
                if (q > quarter) { quarter = q; say += "${q * 25} percent received" }
                wordsSaid.next(s.finishWords)?.let { say += it }
                if (say.isNotEmpty()) announce(say.joinToString(". "))
            }
            is UiState.Done -> {
                showMark(Glyph.Kind.VERIFIED, Ink.PRIMARY, 48); show += mark
                // a name that fits in two lines is never trimmed (beyond, the middle goes; the extension stays)
                title(s.name, role = Type.HEADLINE_SMALL, twoLines = true)
                body(Fmt.bytes(s.size) + if (s.seconds > 0) " · ${Fmt.duration(s.seconds)} · %.2f Mbit/s".format(s.mbit) else "", tnum = true); show += tBody
                show += tVerified
                s.sha?.let { sha ->
                    if (shaShown != sha) { shaShown = sha; tSha.hex = sha }
                    show += shaRow
                }
                s.savedAs?.let { setText(tSaved, "Saved as $it"); show += tSaved }
                s.note?.let { setText(tNote, it); show += tNote }
                primary(if (s.savingPct >= 0) "Saving ${s.savingPct}%" else "Save", Action.SAVE, s.available && s.savingPct < 0); show += bPrimary
                show += rowAB
                if (bOpen.isEnabled != s.available) bOpen.isEnabled = s.available
                if (bShare.isEnabled != s.available) bShare.isEnabled = s.available
                text("New transfer", Action.NEW_TRANSFER); show += bText
                // below every action, never over one; not announced (it is an offer, not news)
                if (s.donate) show += donateBox
                if (announcedDone != s.name) { announcedDone = s.name; announce("Received ${s.name}. SHA-256 verified.") }
                if (s.savedAs != null && announcedSaved != s.savedAs) { announcedSaved = s.savedAs; announce("Saved as ${s.savedAs}") }
                if (s.note != announcedNote) { announcedNote = s.note; s.note?.let { announce(it) } }
            }
            is UiState.BatchDone -> {
                if (announcedDone != s.name) { announcedDone = s.name; announce("Received ${s.name}. Every file SHA-256 verified.") }
            }
            is UiState.Problem -> {
                showMark(Glyph.Kind.WARN, Ink.AMBER_TEXT, 32); show += mark
                title(s.title)
                body(s.text); show += tBody
                primary(s.actionLabel, s.action); show += bPrimary
                val key = s.title + s.text
                if (announcedProblem != key) { announcedProblem = key; announce("${s.title}. ${s.text}") }
            }
        }
        if (s !is UiState.Receiving) vis(bFiles, false)
        if (tMeta1 in show) show += metaRow
        for (v in listOf(mark, tBody, progress, metaRow, tMeta1, tMeta2, finishBox, tVerified, shaRow, tSaved, hintRow, tHint, bStart, tNote, bPrimary, rowAB, bText, donateBox)) vis(v, v in show)
        applyBanner()
        applyChanging(s)
        if (detailsOn) fillDetails()   // a camera state shows the short list, the others the full one
        if (kindChanged) reveal()
    }

    private fun applyPreviewA11y(s: UiState) {
        val full = s is UiState.Permission || s is UiState.Done || s is UiState.Problem || s is UiState.BatchDone || pane != Pane.NONE
        val want = if (previewLive && !full) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (preview.importantForAccessibility != want) preview.importantForAccessibility = want
    }

    // ---- building ------------------------------------------------------------------------------------------

    private fun build() {
        // the preview stays attached across rebuilds (its surface is never torn down); only the layers above it
        // are built again
        if (preview.parent == null) {
            preview.contentDescription = "Camera view"
            root.addView(preview, FrameLayout.LayoutParams(-1, -1))
            // a tap on the picture focuses there (the ring above the preview, under every layer)
            focusRing = FocusRing(ctx) { dp(it) }
            root.addView(focusRing, FrameLayout.LayoutParams(dp(76), dp(76)))
            preview.setOnTouchListener(tapToFocus)
        }
        if (::stage.isInitialized) root.removeView(stage)
        stage = Stage(ctx).apply {
            insets = this@ReceiverScreen.insets
            // the preview draws the image centred (FIT_CENTER): moved by the layout's offset, it sits under the guide
            onShift = { dx, dy -> if (preview.translationX != dx) preview.translationX = dx; if (preview.translationY != dy) preview.translationY = dy }
        }
        guide = GuideView(ctx).apply { aspect = this@ReceiverScreen.aspect }
        scrim = View(ctx).apply { setBackgroundColor(Ink.SOLID); visibility = View.GONE; isClickable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        live = TextView(ctx).apply {
            setTextColor(Color.TRANSPARENT); textSize = 1f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val top = buildTopBar()
        buildBanner()
        buildDetails()
        buildPanel()
        stage.guide = guide; stage.scrim = scrim; stage.top = top
        stage.banner = banner; stage.details = detailsScroll; stage.panel = panelScroll; stage.live = live
        changingView = text(Type.LABEL_LARGE, Ink.ON).apply {
            text = "Changing camera…"; setPadding(dp(16), dp(10), dp(16), dp(10)); background = rounded(Ink.SURFACE, 20)
            visibility = if (changingOn) View.VISIBLE else View.GONE
        }
        stage.changing = changingView
        val kept = if (::filesPane.isInitialized) filesPane.saveState() else null
        val filter = if (::filesPane.isInitialized) filesPane.filter else Filter.ALL
        filesPane = FilesPane(this, ctx, host).apply { view.visibility = View.GONE; setFilter(filter) }
        filesPane.setFiles(paneFiles, paneHeader, paneSaved); filesPane.setHistory(paneHistory, paneHistoryNote)
        kept?.let { filesPane.restoreState(it) }
        stage.pane = filesPane.view
        // order = TalkBack order: top bar, banner, Details, panel (or the list)
        for (v in listOf(guide, scrim, changingView, top, banner, detailsScroll, panelScroll, filesPane.view, live)) stage.addView(v)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        setCameraMode(mode)
        setDetailsOn(detailsOn)
        if (detailsOn) fillDetails()
    }

    internal fun type(t: TextView, role: Type) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sp)
        t.setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, role.line, activity.resources.displayMetrics).roundToInt())
        t.typeface = if (role.medium) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    internal fun text(role: Type, color: Int) = TextView(ctx).apply { type(this, role); setTextColor(color) }

    internal fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }

    private fun buildTopBar(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(dp(16), 0, dp(4), 0)
        }
        // the LensBolt mark beside the name (decorative: the title says it)
        val logo = ImageView(ctx).apply {
            setImageResource(R.drawable.lensbolt_mark); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        val title = text(Type.TITLE_LARGE, Ink.ON).apply {
            text = "LensBolt"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4 * density, 0f, density, Ink.alpha(Color.BLACK, 0xB0))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        modeBtn = iconButton(Glyph(Glyph.Kind.CAMERA, Ink.ON, density), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ink.ICON_CONTAINER) }) { showModeMenu() }
        detailsGlyph = Glyph(Glyph.Kind.DETAILS, Ink.ON, density)
        detailsFill = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ink.ICON_CONTAINER) }
        detailsBtn = iconButton(detailsGlyph, detailsFill) { host.onDetails(!detailsOn) }.apply {
            contentDescription = "Details"; tooltipText = "Details"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.ToggleButton"; info.isCheckable = true; info.isChecked = detailsOn
                }
            }
        }
        val historyBtn = iconButton(Glyph(Glyph.Kind.HISTORY, Ink.ON, density), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ink.ICON_CONTAINER) }) { host.onShowPane(Pane.HISTORY) }.apply {
            contentDescription = "History"; tooltipText = "History"
        }
        bar.addView(historyBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        bar.addView(modeBtn, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        bar.addView(detailsBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        return bar
    }

    private fun iconButton(icon: Drawable, container: GradientDrawable, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageDrawable(icon); scaleType = ImageView.ScaleType.CENTER
        val pad = dp(4)
        background = RippleDrawable(ColorStateList.valueOf(Ink.alpha(Color.WHITE, 0x33)), InsetDrawable(container, pad), null)
        setOnClickListener { onClick() }
    }

    private fun showModeMenu() {
        val menu = PopupMenu(ctx, modeBtn)
        for ((i, m) in CameraMode.MENU.withIndex()) menu.menu.add(1, i, i, m.menu).isChecked = m == mode
        menu.menu.setGroupCheckable(1, true, true)
        // after the camera modes: the guide again, rating the app, feedback
        val extra = 100
        for ((i, h) in Help.Item.entries.withIndex()) menu.menu.add(2, extra + i, extra + i, h.label)
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId >= extra) host.onHelp(Help.Item.entries[item.itemId - extra]) else host.onCameraMode(CameraMode.MENU[item.itemId])
            true
        }
        menu.show()
    }

    private fun buildBanner() {
        banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Ink.AMBER_CONTAINER); setStroke(dp(1), Ink.alpha(Ink.AMBER, 0xB3)) }
            visibility = View.GONE
        }
        val icon = ImageView(ctx).apply { setImageDrawable(Glyph(Glyph.Kind.WARN, Ink.AMBER_TEXT, density, 20)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        bannerTextView = text(Type.BODY_MEDIUM, Ink.ON_AMBER_CONTAINER)
        banner.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12); topMargin = dp(0) })
        banner.addView(bannerTextView, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun applyBanner() {
        val s = state
        val show = bannerText != null && (s is UiState.Aim || s is UiState.Locked || s is UiState.Receiving) && !paneShown()
        setText(bannerTextView, bannerText ?: "")
        val v = if (show) View.VISIBLE else View.GONE
        if (banner.visibility != v) { banner.visibility = v; stage.requestLayout() }
    }

    private fun buildDetails() {
        detailsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        // the card is the scroll view itself, clipped to its rounded outline: when the band under the guide is short,
        // the rows scroll inside a whole card (on the inner box, the card's bottom edge scrolled out of sight)
        detailsScroll = ScrollView(ctx).apply {
            addView(detailsBox); isVerticalScrollBarEnabled = true; visibility = View.GONE
            background = rounded(Ink.SURFACE, 16); clipToOutline = true
        }
    }

    private fun cameraState() = state.let { it == null || it is UiState.Aim || it is UiState.Locked || it is UiState.Receiving }

    // rows are built once per set of labels; a poll only updates the values that changed. Over the camera the
    // list is short (it shares the band under the guide with the panel) unless All details is on.
    private fun fillDetails() {
        val camera = cameraState()
        val short = camera && !detailsAll
        val rows = if (short) detailRows.filter { it.first in SHORT_ROWS } else detailRows
        val labels = rows.map { it.first }
        val key = "$camera/$short/" + labels.joinToString("|")
        if (key != detailsKey) {
            detailsKey = key
            detailsBox.removeAllViews()
            // over the camera the header carries the All details button (its own 48 dp target spaces it)
            detailsBox.setPadding(dp(16), dp(if (camera) 0 else 12), dp(if (camera) 8 else 16), dp(12))
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(Type.LABEL_LARGE, Ink.ON_VAR).apply { text = "Details"; ViewCompat.setAccessibilityHeading(this, true) },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (camera) head.addView(button(2) { detailsAll = !detailsAll; fillDetails(); stage.requestLayout() }.apply {
                text = if (short) "All details" else "Fewer details"
            }, LinearLayout.LayoutParams(-2, -2))
            detailsBox.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            detailViews = labels.map { label ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
                val l = text(Type.BODY_SMALL, Ink.ON_VAR).apply { text = label }
                val v = text(Type.BODY_SMALL, Ink.ON).apply { fontFeatureSettings = "tnum" }
                row.addView(l, LinearLayout.LayoutParams(dp(92), -2).apply { marginEnd = dp(8) })
                row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
                ViewCompat.setScreenReaderFocusable(row, true)
                detailsBox.addView(row, LinearLayout.LayoutParams(-1, -2))
                l to v
            }
            // the app is open source: its repository, under the details (every build)
            detailsBox.addView(button(2) { host.onAction(Action.SOURCE) }.apply { text = "Source code on GitHub"; contentDescription = "Source code on GitHub (opens the browser)" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        for ((i, row) in rows.withIndex()) setText(detailViews[i].second, row.second)
    }

    internal fun button(style: Int, onClick: () -> Unit) = Button(ctx).apply {
        // style: 0 filled, 1 tonal, 2 text (Material 3 buttons: 40 dp tall inside a 48 dp target)
        isAllCaps = false; stateListAnimator = null
        type(this, Type.LABEL_LARGE)
        val (fill, fg) = when (style) { 0 -> Ink.PRIMARY to Color.WHITE; 1 -> Ink.TONAL to Ink.ON; else -> Color.TRANSPARENT to Ink.PRIMARY_TEXT }
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        setTextColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(Ink.alpha(Ink.ON, 0x61), fg)))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(if (style == 2) Color.TRANSPARENT else Ink.alpha(Ink.ON, 0x1F), fill)))
        }
        val mask = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.WHITE) }
        background = RippleDrawable(ColorStateList.valueOf(Ink.alpha(fg, 0x33)), InsetDrawable(shape, 0, dp(4), 0, dp(4)), InsetDrawable(mask, 0, dp(4), 0, dp(4)))
        minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(64); minimumWidth = dp(64)
        val h = if (style == 2) dp(12) else dp(24)
        setPadding(h, dp(4), h, dp(4))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
        mark = ImageView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        tTitle = NameView(ctx).apply { type(this, Type.TITLE_LARGE); setTextColor(Ink.ON); ViewCompat.setAccessibilityHeading(this, true) }
        tBody = text(Type.BODY_MEDIUM, Ink.ON_VAR)
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = false
            val track = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Ink.TRACK) }
            val bar = ClipDrawable(GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Ink.PRIMARY) }, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, bar)).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
            contentDescription = "Received"
        }
        tMeta1 = text(Type.BODY_MEDIUM, Ink.ON).apply { fontFeatureSettings = "tnum" }
        tMeta2 = text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { fontFeatureSettings = "tnum" }
        tVerified = text(Type.LABEL_LARGE, Ink.PRIMARY_TEXT).apply { text = "SHA-256 verified" }
        tSha = ShaView(ctx).apply { type(this, Type.BODY_MEDIUM); setTextColor(Ink.ON) }
        bCopySha = button(2) {
            val sha = shaShown ?: return@button
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SHA-256", sha))
            announce("SHA-256 copied")
        }.apply { text = "Copy"; contentDescription = "Copy the SHA-256" }
        shaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shaRow.addView(tSha, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        shaRow.addView(bCopySha, LinearLayout.LayoutParams(-2, -2))
        tSaved = text(Type.BODY_MEDIUM, Ink.ON)
        tHint = text(Type.TITLE_MEDIUM, Ink.ON).apply { compoundDrawablePadding = dp(8); gravity = Gravity.CENTER_VERTICAL }
        tNote = text(Type.BODY_MEDIUM, Ink.AMBER_TEXT)
        buildFinish()
        bPrimary = button(0) { host.onAction(primaryAction) }
        bOpen = button(1) { host.onAction(Action.OPEN) }.apply { text = "Open" }
        bShare = button(1) { host.onAction(Action.SHARE) }.apply { text = "Share" }
        rowAB = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        rowAB.addView(bOpen, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        rowAB.addView(bShare, LinearLayout.LayoutParams(0, -2, 1f))
        bText = button(2) { host.onAction(textAction) }
        bStart = button(2) { host.onAction(Action.START_OVER) }.apply { text = "Start over" }
        hintRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hintRow.addView(tHint, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        hintRow.addView(bStart, LinearLayout.LayoutParams(-2, -2))
        buildDonate()

        fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        box.addView(mark, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        box.addView(tTitle, lp())
        box.addView(tBody, lp(4))
        box.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        // a batch: its list one tap away, beside the numbers
        val metaCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        metaCol.addView(tMeta1, LinearLayout.LayoutParams(-1, -2))
        metaCol.addView(tMeta2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        bFiles = button(1) { host.onShowPane(Pane.FILES) }.apply { text = "Files"; contentDescription = "Show the files"; visibility = View.GONE }
        metaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        metaRow.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        metaRow.addView(bFiles, LinearLayout.LayoutParams(-2, -2))
        box.addView(metaRow, lp(8))
        box.addView(finishBox, lp(12))
        box.addView(tVerified, lp(8))
        box.addView(shaRow, lp(4))
        box.addView(tSaved, lp(4))
        box.addView(hintRow, lp(6))
        box.addView(tNote, lp(8))
        box.addView(bPrimary, lp(16))
        box.addView(rowAB, lp(8))
        box.addView(bText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.END })
        box.addView(donateBox, lp(24))
        panelScroll = ScrollView(ctx).apply { addView(box) }
    }

    // the support card (Done, now and then: DonatePrompt): one calm line, the two places to give, and the way out
    private fun buildDonate() { donateBox = donateCard() }

    /** A support card (the Done screen's, and the batch list's header's). */
    internal fun donateCard(): LinearLayout {
        val donateBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Ink.CARD); setStroke(dp(1), Ink.TRACK) }
        }
        val line = text(Type.BODY_MEDIUM, Ink.ON).apply { text = DonatePrompt.TEXT }
        val coffee = button(1) { host.onAction(Action.DONATE_COFFEE) }.apply { text = "Buy me a coffee"; contentDescription = "Buy me a coffee, opens in the browser" }
        val sponsors = button(1) { host.onAction(Action.DONATE_SPONSORS) }.apply { text = "GitHub Sponsors"; contentDescription = "GitHub Sponsors, opens in the browser" }
        val links = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        links.addView(coffee, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        links.addView(sponsors, LinearLayout.LayoutParams(0, -2, 1f))
        val later = button(2) { host.onAction(Action.DONATE_LATER) }.apply { text = "Not now" }
        val never = button(2) { host.onAction(Action.DONATE_NEVER) }.apply { text = "Don't ask again" }
        val out = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        out.addView(never, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        out.addView(later, LinearLayout.LayoutParams(-2, -2))
        donateBox.addView(line, LinearLayout.LayoutParams(-1, -2))
        donateBox.addView(links, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        donateBox.addView(out, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return donateBox
    }

    // the finish words under the progress: a line saying what they are for and a Copy button, then the words large
    // and selectable (long-press copies a part), wrapping between words at any font scale
    private fun buildFinish() {
        finishBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val lead = text(Type.BODY_MEDIUM, Ink.ON_VAR).apply { text = FinishWords.TEXT }
        bCopy = button(2) { copyWords() }.apply { text = COPY; contentDescription = "Copy the finish words" }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(lead, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(bCopy, LinearLayout.LayoutParams(-2, -2))
        tWords = text(Type.HEADLINE_SMALL, Ink.ON).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextIsSelectable(true)
            highlightColor = Ink.alpha(Ink.PRIMARY, 0x8C)
        }
        finishBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        finishBox.addView(tWords, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        tParts = text(Type.BODY_SMALL, Ink.ON_VAR).apply { fontFeatureSettings = "tnum"; visibility = View.GONE }
        finishBox.addView(tParts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    // to the clipboard on this phone (nothing leaves it); the button says Copied for a moment
    private fun copyWords() {
        val words = tWords.text?.toString()?.takeIf { it.isNotBlank() } ?: return
        val cm = activity.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Finish words", words))
        val b = bCopy
        b.removeCallbacks(copyReset)
        b.text = "Copied"
        b.postDelayed(copyReset, 2000)
        announce("Finish words copied")
    }

    private val copyReset = Runnable { if (::bCopy.isInitialized) bCopy.text = COPY }

    // ---- state helpers -------------------------------------------------------------------------------------

    private fun vis(v: View, show: Boolean) { val t = if (show) View.VISIBLE else View.GONE; if (v.visibility != t) v.visibility = t }

    private fun setText(t: TextView, text: CharSequence) { if (t.text.toString() != text.toString()) t.text = text }

    private fun showMark(kind: Glyph.Kind, color: Int, sizeDp: Int) {
        val key = "$kind/$color/$sizeDp"
        if (key != markKey) { markKey = key; mark.setImageDrawable(Glyph(kind, color, density, sizeDp)) }
    }

    // a file name sits on one line, ellipsized in the middle (its extension stays in view; TalkBack reads it
    // whole); other titles wrap up to three lines
    private fun title(t: String, role: Type = Type.TITLE_LARGE, oneLine: Boolean = false, color: Int = Ink.ON, twoLines: Boolean = false) {
        val key = "$role/$oneLine/$twoLines"
        if (key != titleKey) {
            titleKey = key
            type(tTitle, role)
            tTitle.fit = twoLines
            tTitle.maxLines = if (oneLine) 1 else if (twoLines) 2 else 3
            tTitle.ellipsize = if (twoLines) null else if (oneLine) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
            tTitle.contentDescription = null
        }
        if (tTitle.currentTextColor != color) tTitle.setTextColor(color)
        if (twoLines) tTitle.full = t else setText(tTitle, t)
    }

    private fun body(t: String, tnum: Boolean = false) {
        val f = if (tnum) "tnum" else null
        if (tBody.fontFeatureSettings != f) tBody.fontFeatureSettings = f
        setText(tBody, t)
    }

    private fun hint(h: Hint) {
        setText(tHint, h.text)
        if (hintTone == h.tone) return
        hintTone = h.tone
        val c = when (h.tone) { Tone.NEUTRAL -> Ink.ON_VAR; Tone.GOOD -> Ink.PRIMARY_TEXT; Tone.WARN -> Ink.AMBER_TEXT }
        tHint.setTextColor(if (h.tone == Tone.GOOD) Ink.ON else c)
        val dot = Glyph(Glyph.Kind.DOT, c, density, 8).apply { setBounds(0, 0, dp(8), dp(8)) }
        tHint.setCompoundDrawablesRelative(dot, null, null, null)
    }

    private fun primary(label: String, a: Action, enabled: Boolean = true) {
        setText(bPrimary, label); primaryAction = a
        if (bPrimary.isEnabled != enabled) bPrimary.isEnabled = enabled
    }

    private fun text(label: String, a: Action) { setText(bText, label); textAction = a }

    // a state change fades the panel in (150 ms, decelerating); with Remove animations on, it simply appears
    private fun reveal() {
        if (!ValueAnimator.areAnimatorsEnabled()) { box.alpha = 1f; return }
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
    }

    private companion object {
        const val COPY = "Copy"
        // the short Details over the camera: what says whether the tile reads
        val SHORT_ROWS = setOf("Camera", "Decoded", "Codewords", "Cell size")
    }

    internal fun announce(text: String) {
        // a live region speaks when its text changes: the same words twice need a change
        live.text = if (live.text?.toString() == text) "$text " else text
    }
}
