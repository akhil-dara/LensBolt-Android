package com.lensbolt.app

// The receiver screen's layout arithmetic, px in and px out, with no Android types (tested on the JVM).

import kotlin.math.max
import kotlin.math.min

data class Frame(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val width get() = r - l
    val height get() = b - t
}

/** Landscape camera layout: the right column [colLeft, colRight] holds the top bar, banner and panel; Details
 *  sit in the left band [detLeft, detRight], or in the column above the panel when [detailsInColumn]. The camera
 *  image and the guide as placed (moved toward the start when the column needs the room). */
data class Columns(val colLeft: Int, val colRight: Int, val detLeft: Int, val detRight: Int,
                   val image: Frame = Frame(0f, 0f, 0f, 0f), val guide: Frame = Frame(0f, 0f, 0f, 0f)) {
    val detailsInColumn get() = detRight <= detLeft
}

/** Portrait camera layout: the image and the guide (the guide's top just under the top bar), and the band under
 *  the guide [bandTop, bandBottom] that holds the banner, Details and the panel. */
data class Stack(val image: Frame, val guide: Frame, val bandTop: Int, val bandBottom: Int)

object StageMath {
    private const val GUIDE_SHARE = 0.95f
    private const val MARGIN_DP = 12f
    private const val GUIDE_GAP_DP = 4f        // between the top bar and the guide (portrait)
    private const val MIN_BAND_DP = 96f        // the band under the guide never gets shorter (split screen only)
    private const val COL_DP = 240f            // the column's preferred width: the image moves toward the start for it
    private const val COL_MAX_DP = 400f
    private const val COL_USABLE_DP = 200f     // narrower than this: lay out as portrait
    private const val DET_MAX_DP = 320f
    private const val DET_USABLE_DP = 180f     // narrower than this: Details go into the column

    /** The camera image as FIT_CENTER shows it in a w x h window: tall on a portrait window, wide on a
     *  landscape one. `aspect` is the image's long side over its short side. */
    fun image(w: Int, h: Int, aspect: Float): Frame {
        val a = if (h > w) 1f / aspect else aspect
        var iw = w.toFloat(); var ih = iw / a
        if (ih > h) { ih = h.toFloat(); iw = ih * a }
        return Frame((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
    }

    /** The framing guide on an image: a square on its centre, 95% of its short side (the tile held to fill the
     *  corners). */
    fun guideOn(img: Frame): Frame {
        val side = GUIDE_SHARE * min(img.width, img.height)
        return Frame(img.cx - side / 2, img.cy - side / 2, img.cx + side / 2, img.cy + side / 2)
    }

    /** The guide on the centred image. */
    fun square(w: Int, h: Int, aspect: Float): Frame = guideOn(image(w, h, aspect))

    private fun Frame.shift(dx: Float, dy: Float) = Frame(l + dx, t + dy, r + dx, b + dy)

    /** Portrait (and landscape windows too narrow for columns): the image moves so the guide's top sits just under
     *  the top bar (`topH` px tall below the `insTop` inset); everything else goes in the band under the guide,
     *  which no panel leaves. A window too short for that and a [MIN_BAND_DP] band moves the guide up under the
     *  top bar (never past the status bar); only one too short even for that (split screen) lets the band reach
     *  over the guide's lower edge. */
    fun stack(w: Int, h: Int, aspect: Float, insTop: Int, insBottom: Int, topH: Int, density: Float): Stack {
        fun px(dp: Float) = dp * density
        val img = image(w, h, aspect)
        val g = guideOn(img)
        val m = px(MARGIN_DP)
        val bottom = (h - insBottom - m).toInt()
        val minBand = px(MIN_BAND_DP).toInt()
        // the guide's top: under the top bar, or as far up as the band needs, never above the status bar
        val roomTop = bottom - minBand - m - g.height
        val gTop = max(min(insTop + topH + px(GUIDE_GAP_DP), roomTop), insTop.toFloat())
        val dy = gTop - g.t
        var top = kotlin.math.ceil(g.b + dy + m).toInt()
        if (bottom - top < minBand) top = bottom - minBand
        return Stack(img.shift(0f, dy), g.shift(0f, dy), top, bottom)
    }

    /** The landscape camera columns, or null when there is no room for a column beside the guide (then the
     *  stack is used). The image moves toward the start (never past the start margin) until the column has
     *  [COL_DP]; neither the column nor the Details band ever reaches over the guide. */
    fun columns(w: Int, h: Int, insetLeft: Int, insetRight: Int, aspect: Float, density: Float): Columns? {
        fun px(dp: Float) = dp * density
        val img = image(w, h, aspect)
        val g0 = guideOn(img)
        val m = px(MARGIN_DP)
        val colRight = w - insetRight - m
        var dx = min(0f, colRight - px(COL_DP) - m - g0.r)
        dx = max(dx, insetLeft + m - g0.l)
        val g = g0.shift(dx, 0f)
        val colLeft = max(g.r + m, colRight - px(COL_MAX_DP))
        if (colRight - colLeft < px(COL_USABLE_DP)) return null
        val detLeft = insetLeft + m
        val detRight = min(g.l - m, detLeft + px(DET_MAX_DP))
        val inBand = detRight - detLeft >= px(DET_USABLE_DP)
        return Columns(kotlin.math.ceil(colLeft).toInt(), colRight.toInt(), detLeft.toInt(),
            if (inBand) detRight.toInt() else detLeft.toInt(), img.shift(dx, 0f), g)
    }
}
