package pl.openpfd.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import pl.openpfd.FlightData
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Wspólny interfejs motywów PFD. Rysują w wirtualnym kwadracie 1000×1000. */
interface PfdRenderer {
    val baroRect: RectF
    fun draw(c: Canvas, d: FlightData)
}

object Col {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val RED = 0xFFFF2A2A.toInt()
    const val AMBER = 0xFFFFA200.toInt()
}

class Pen {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }
    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val path = Path()

    /** Tekst wyśrodkowany pionowo względem y. */
    fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, bold: Boolean = false,
    ) {
        txt.textSize = size
        txt.color = color
        txt.textAlign = align
        txt.isFakeBoldText = bold
        c.drawText(s, x, y + size * 0.36f, txt)
    }

    fun width(s: String, size: Float): Float {
        txt.textSize = size
        return txt.measureText(s)
    }

    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun frame(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawRect(l, t, r, b, stroke)
    }

    fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int, w: Float = 0f) {
        if (w <= 0f) {
            fill.color = color
            c.drawCircle(x, y, r, fill)
        } else {
            stroke.color = color
            stroke.strokeWidth = w
            c.drawCircle(x, y, r, stroke)
        }
    }

    private fun build(pts: FloatArray): Path {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        path.close()
        return path
    }

    /** Wielokąt: wypełnienie (fillColor != 0) i/lub obrys (w > 0). */
    fun poly(c: Canvas, fillColor: Int, strokeColor: Int, w: Float, vararg pts: Float) {
        val p = build(pts)
        if (fillColor != 0) {
            fill.color = fillColor
            c.drawPath(p, fill)
        }
        if (w > 0f) {
            stroke.color = strokeColor
            stroke.strokeWidth = w
            c.drawPath(p, stroke)
        }
    }

    /** Odcinek promieniowy wokół (cx, cy) pod kątem a (0 = do góry, + w prawo). */
    fun radial(c: Canvas, cx: Float, cy: Float, a: Float, r1: Float, r2: Float, color: Int, w: Float) {
        val rad = Math.toRadians(a.toDouble())
        val s = sin(rad).toFloat()
        val k = cos(rad).toFloat()
        line(c, cx + r1 * s, cy - r1 * k, cx + r2 * s, cy - r2 * k, color, w)
    }

    /**
     * Bęben z cyframi przewijanymi pionowo (jak w prawdziwych PFD).
     * value – pozycja w krokach bębna, label(k) – etykieta k-tego kroku.
     */
    fun drum(
        c: Canvas, l: Float, t: Float, r: Float, b: Float, value: Float, spacing: Float,
        size: Float, color: Int, x: Float, align: Paint.Align, label: (Int) -> String,
    ) {
        c.save()
        c.clipRect(l, t, r, b)
        val cy = (t + b) / 2f
        val base = floor(value).toInt()
        for (k in base - 3..base + 3) {
            val y = cy + (value - k) * spacing
            if (y < t - size || y > b + size) continue
            text(c, label(k), x, y, size, color, align)
        }
        c.restore()
    }
}

fun baroText(d: FlightData): String =
    if (d.inHg) String.format(Locale.US, "%.2f", d.qnh * 0.0295300f) else d.qnh.roundToInt().toString()

fun floorTo(v: Float, step: Int): Int = (floor(v / step) * step).toInt()

fun mod(a: Int, m: Int): Int = ((a % m) + m) % m
