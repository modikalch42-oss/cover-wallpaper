package dev.modi.coverwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Things the wallpaper can show. Each draws a full, opaque frame. */
interface Scene {
    /** [t] = animation clock (seconds, only runs while music plays), [paused] = 0 playing … 1 paused. */
    fun draw(c: Canvas, w: Int, h: Int, t: Float, paused: Float)
    /** True if the scene moves while music is playing. */
    val animated: Boolean
}

private fun centreCrop(b: Bitmap, w: Int, h: Int, zoom: Float = 1f, dst: RectF = RectF()): RectF {
    val s = max(w / b.width.toFloat(), h / b.height.toFloat()) * zoom
    val dw = b.width * s
    val dh = b.height * s
    dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    return dst
}

private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

/** The user's own wallpaper (or a calm gradient if they haven't picked one). */
class IdleScene(private val image: Bitmap?) : Scene {
    override val animated = false
    private val dst = RectF()
    private val gradient = Paint(Paint.DITHER_FLAG)
    private var gh = -1

    override fun draw(c: Canvas, w: Int, h: Int, t: Float, paused: Float) {
        if (image != null && !image.isRecycled) {
            c.drawBitmap(image, null, centreCrop(image, w, h, dst = dst), filterPaint)
            return
        }
        if (gh != h) {
            gh = h
            gradient.shader = LinearGradient(
                0f, 0f, w * 0.4f, h.toFloat(),
                Color.rgb(24, 24, 38), Color.rgb(58, 22, 44), Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradient)
    }
}

/** A still image filling the screen — the motion art's first frame, shown while the video loads. */
class FullScreenScene(private val image: Bitmap) : Scene {
    override val animated = false
    private val dst = RectF()
    override fun draw(c: Canvas, w: Int, h: Int, t: Float, paused: Float) {
        c.drawColor(Color.BLACK)
        c.drawBitmap(image, null, centreCrop(image, w, h, dst = dst), filterPaint)
    }
}

/**
 * Album cover floating in the centre over a soft, blurred wash of its own colours,
 * with a two-layer shadow tinted by the artwork. It breathes very slightly while
 * playing and settles back (smaller, dimmer) when paused — like Apple Music's player.
 */
class CoverScene(private val art: Bitmap, density: Float) : Scene {
    override val animated = true

    private val backdrop: Bitmap = Blur.backdrop(art)
    private val shadowTint: Int = Blur.shadowColour(art)

    private val dp = density
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(1.45f)
            postConcat(ColorMatrix().apply { setScale(0.62f, 0.62f, 0.62f, 1f) })
        })
    }
    private val vignette = Paint(Paint.DITHER_FLAG)
    private val topBottom = Paint(Paint.DITHER_FLAG)
    private val ambientShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contactShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, 0.75f * density)
        color = Color.argb(28, 255, 255, 255)
    }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val shaderMatrix = Matrix()
    private val src = RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
    private val card = RectF()
    private val bgDst = RectF()
    private var sizedFor = -1L

    init { artPaint.shader = shader }

    private fun size(w: Int, h: Int) {
        val key = w.toLong() shl 32 or h.toLong()
        if (key == sizedFor) return
        sizedFor = key
        vignette.shader = RadialGradient(
            w / 2f, h * CARD_Y, max(w, h) * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        topBottom.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            floatArrayOf(0f, 0.22f, 0.72f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Float, paused: Float) {
        size(w, h)
        val tau = 2f * PI.toFloat()
        val fw = w.toFloat()
        val fh = h.toFloat()

        // Backdrop: the cover's colours, heavily blurred, drifting slowly
        centreCrop(backdrop, w, h, zoom = 1.35f, dst = bgDst)
        bgDst.offset(sin(tau * t / 37f) * fw * 0.05f, sin(tau * t / 29f + 1f) * fh * 0.025f)
        c.drawBitmap(backdrop, null, bgDst, bgPaint)
        c.drawRect(0f, 0f, fw, fh, vignette)
        c.drawRect(0f, 0f, fw, fh, topBottom)

        // Card
        val base = min(fw * 0.80f, fh * 0.42f)
        val breathe = 1f + 0.010f * sin(tau * t / 7f) * (1f - paused)
        val s = base * (1f - 0.12f * paused) * breathe
        val cx = fw / 2f
        val cy = fh * CARD_Y
        card.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        val r = s * 0.035f
        val lift = 1f - 0.55f * paused

        ambientShadow.color = Color.BLACK
        ambientShadow.setShadowLayer(44f * dp * (0.6f + 0.4f * lift), 0f, 26f * dp * lift, shadowTint)
        c.drawRoundRect(card, r, r, ambientShadow)

        contactShadow.color = Color.BLACK
        contactShadow.setShadowLayer(10f * dp, 0f, 5f * dp * lift, Color.argb(95, 0, 0, 0))
        c.drawRoundRect(card, r, r, contactShadow)

        shaderMatrix.setRectToRect(src, card, Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(shaderMatrix)
        c.drawRoundRect(card, r, r, artPaint)
        c.drawRoundRect(card, r, r, rim)

        if (paused > 0.01f) {
            dim.color = Color.argb((60 * paused).toInt(), 0, 0, 0)
            c.drawRoundRect(card, r, r, dim)
        }
    }

    companion object {
        /** Vertical position of the cover's centre (0 = top, 1 = bottom). */
        const val CARD_Y = 0.47f
    }
}

/** Cheap, smooth blur: shrink, box-blur three times, and let bilinear scaling do the rest. */
object Blur {
    fun backdrop(src: Bitmap): Bitmap {
        val soft = software(src)
        val tiny = Bitmap.createScaledBitmap(soft, 24, 24, true)
        val mid = Bitmap.createScaledBitmap(tiny, 144, 144, true).copy(Bitmap.Config.ARGB_8888, true)
        boxBlur(mid, 9, 3)
        return mid
    }

    /** The cover's average colour, darkened — a shadow that looks like the art is casting it. */
    fun shadowColour(src: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(software(src), 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(one, hsv)
        hsv[1] = min(1f, hsv[1] * 1.2f)
        hsv[2] = hsv[2] * 0.28f
        return Color.HSVToColor(185, hsv)
    }

    private fun software(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b

    private fun boxBlur(b: Bitmap, radius: Int, passes: Int) {
        val w = b.width
        val h = b.height
        var px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var tmp = IntArray(w * h)
        repeat(passes) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun pass(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) input[line * w + k] else input[k * w + line]
            }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sr += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sb += p and 0xff
            }
            for (i in 0 until len) {
                val o = if (horizontal) line * w + i else i * w + line
                out[o] = (0xff shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = at(i + r + 1)
                val sub = at(i - r)
                sr += ((add shr 16) and 0xff) - ((sub shr 16) and 0xff)
                sg += ((add shr 8) and 0xff) - ((sub shr 8) and 0xff)
                sb += (add and 0xff) - (sub and 0xff)
            }
        }
    }
}
