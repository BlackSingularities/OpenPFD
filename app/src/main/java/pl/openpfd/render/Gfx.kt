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

/**
 * Wspólny interfejs motywów PFD. Każdy rysuje w swoim wirtualnym prostokącie
 * [vw] × [vh] o proporcjach prawdziwego wyświetlacza.
 */
interface PfdRenderer {
    val baroRect: RectF
    val vw: Float get() = 1000f
    val vh: Float get() = 1000f
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

    /** Flaga awarii w ramce (jak w prawdziwych PFD): „ATT”, „SPD”, „ALT”… */
    fun flag(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        val w = width(s, size)
        val padX = size * 0.28f
        val l = when (align) {
            Paint.Align.LEFT -> x - padX
            Paint.Align.RIGHT -> x - w - padX
            else -> x - w / 2f - padX
        }
        rect(c, l, y - size * 0.62f, l + w + 2 * padX, y + size * 0.62f, Col.BLACK)
        frame(c, l, y - size * 0.62f, l + w + 2 * padX, y + size * 0.62f, color, size * 0.07f)
        text(c, s, x, y, size, color, align, bold = true)
    }

    /** Czerwone szewrony nadmiernego pochylenia (pitch > 30° lub < −20°). */
    fun pitchChevrons(
        c: Canvas, clip: Path, cx: Float, cy: Float, d: FlightData,
        start: Float, step: Float, half: Float, drop: Float,
    ) {
        if (d.pitch <= 30f && d.pitch >= -20f) return
        val dir = if (d.pitch > 0f) 1f else -1f
        c.save()
        c.clipPath(clip)
        c.rotate(-d.roll, cx, cy)
        for (k in 1..2) {
            val y = cy + dir * (start + k * step)
            line(c, cx - half, y + dir * drop, cx, y, Col.RED, 6f)
            line(c, cx, y, cx + half, y + dir * drop, Col.RED, 6f)
        }
        c.restore()
    }

    /** Symbol wektora toru lotu („ptaszek”). */
    fun bird(c: Canvas, x: Float, y: Float, color: Int, r: Float, arm: Float, stem: Float, w: Float) {
        circle(c, x, y, r, color, w)
        line(c, x - r, y, x - arm, y, color, w)
        line(c, x + r, y, x + arm, y, color, w)
        line(c, x, y - r, x, y - stem, color, w)
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

/** Zdegradowane źródło danych – krótki komunikat i jego kolor. */
data class Degradation(val text: String, val color: Int)

/**
 * Lista ostrzeżeń o jakości danych wspólna dla wszystkich przyrządów: brak GPS, wysokość z GPS
 * zamiast barometru, brak lub nieaktualne QNH, brak sieci, kurs względny, brak modelu terenu.
 */
fun degradations(d: FlightData): List<Degradation> = buildList {
    if (!d.gpsFix) add(Degradation("NO GPS", Col.AMBER))
    if (d.altValid && !d.altFromBaro) add(Degradation("GPS ALT", Col.AMBER))
    if (!d.std) {
        when {
            !d.qnhKnown -> add(Degradation("NO QNH", Col.AMBER))
            d.qnhAgeMin > 90f -> add(Degradation("QNH ${d.qnhAgeMin.roundToInt()}'", Col.AMBER))
        }
    }
    if (!d.netOk) add(Degradation("NO NET", Col.WHITE))
    if (!d.headingMagnetic) add(Degradation("HDG REL", Col.WHITE))
    if (d.gpsFix && d.netOk && d.terrain == null) add(Degradation("NO TERR", Col.WHITE))
}
