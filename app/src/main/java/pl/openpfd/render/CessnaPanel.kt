package pl.openpfd.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import pl.openpfd.FlightData
import pl.openpfd.angDiff
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Renderer rysujący na całym ekranie (bez kwadratu). */
interface FullRenderer {
    fun draw(c: Canvas, d: FlightData, w: Float, h: Float)
    fun baroHit(x: Float, y: Float): Boolean
}

/**
 * Cessna 172 – klasyczny „six-pack” analogowych zegarów:
 * prędkościomierz, sztuczny horyzont, wysokościomierz / zakrętomierz, żyrobusola, wariometr.
 * Każdy zegar rysowany jest w układzie o promieniu 100.
 */
class CessnaPanel(private val kind: Kind = Kind.CESSNA) : FullRenderer {
    enum class Kind { CESSNA, HELI, GLIDER }

    private enum class G { ASI, AI, ALT, TC, HI, VSI, VARIO, GMETER, RA }

    private data class Arc(val from: Float, val to: Float, val r: Float, val w: Float, val color: Int)
    private data class AsiCfg(
        val unit: String, val factor: Float, val max: Float, val firstLabel: Int, val labelStep: Int,
        val arcs: List<Arc>, val red: Float, val marks: List<Pair<Float, Int>>, val legend: String,
    )

    private val gauges = when (kind) {
        Kind.CESSNA -> listOf(G.ASI, G.AI, G.ALT, G.TC, G.HI, G.VSI)
        Kind.HELI -> listOf(G.ASI, G.AI, G.ALT, G.RA, G.HI, G.VSI)
        Kind.GLIDER -> listOf(G.ASI, G.VARIO, G.ALT, G.TC, G.HI, G.GMETER)
    }
    private val asiCfg = when (kind) {
        Kind.CESSNA -> AsiCfg("KNOTS", 1f, 200f, 40, 20, listOf(
            Arc(33f, 85f, 86f, 5f, 0xFFF0F0F0.toInt()), Arc(44f, 129f, 80f, 7f, GREEN), Arc(129f, 163f, 80f, 7f, YELLOW)), 163f,
            listOf(55f to Col.WHITE, 62f to CYAN, 74f to CYAN), "Vr 55  Vx 62  Vy 74")
        Kind.HELI -> AsiCfg("KNOTS", 1f, 150f, 20, 20, listOf(Arc(0f, 130f, 80f, 7f, GREEN)), 130f,
            listOf(55f to CYAN), "Vy 55  VNE 130")
        Kind.GLIDER -> AsiCfg("KM/H", 1.852f, 300f, 40, 20, listOf(
            Arc(70f, 85f, 86f, 5f, 0xFFF0F0F0.toInt()), Arc(75f, 180f, 80f, 7f, GREEN), Arc(180f, 280f, 80f, 7f, YELLOW)), 280f,
            listOf(95f to CYAN, 180f to YELLOW), "Vopt 95  VRA 180  VNE 280")
    }
    private val metricAlt = kind == Kind.GLIDER
    private var gMax = 1f
    private var gMin = 1f
    private val p = Pen()
    private val baro = RectF()

    private var lastHdg = Float.NaN
    private var lastT = 0L
    private var turnRate = 0f
    private var avgTe = 0f
    private var lastVarioT = 0L
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    private companion object {
        const val FACE = 0xFF0E0E0F.toInt()
        const val BEZEL = 0xFF050505.toInt()
        const val RIM = 0xFF3C3E42.toInt()
        const val ORANGE = 0xFFFF8A00.toInt()
        const val GREEN = 0xFF1FA82E.toInt()
        const val YELLOW = 0xFFF2D21B.toInt()
        const val RED = 0xFFE02020.toInt()
        const val SKY = 0xFF2F7FCF.toInt()
        const val EARTH = 0xFF6E4320.toInt()
        const val LABEL = 0xFFD8D8D8.toInt()
        const val CYAN = 0xFF33DDFF.toInt()
    }

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastW = 0f
    private var lastH = 0f
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    override fun baroHit(x: Float, y: Float) = baro.contains(x, y)

    override fun draw(c: Canvas, d: FlightData, w: Float, h: Float) {
        updateTurnRate(d)
        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            panelPaint.shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f,
                0xFF34373B.toInt(), 0xFF1A1B1D.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, panelPaint)

        val hb = (h * 0.055f).coerceIn(26f, 64f)
        val cols = if (w >= h) 3 else 2
        val rows = 6 / cols
        val cw = w / cols
        val ch = (h - hb) / rows
        val r = min(cw, ch) * 0.46f
        for (i in 0 until 6) {
            val cx = cw * (i % cols) + cw / 2
            val cy = ch * (i / cols) + ch / 2
            c.save()
            c.translate(cx, cy)
            c.scale(r / 100f, r / 100f)
            bezelBack(c)
            when (gauges[i]) {
                G.ASI -> asi(c, d)
                G.AI -> ai(c, d)
                G.ALT -> {
                    alt(c, d)
                    val s = r / 100f
                    baro.set(cx + 30 * s, cy - 14 * s, cx + 86 * s, cy + 14 * s)
                }
                G.TC -> tc(c, d)
                G.HI -> hi(c, d)
                G.VSI -> vsi(c, d)
                G.VARIO -> vario(c, d)
                G.GMETER -> gmeter(c, d)
                G.RA -> radioAlt(c, d)
            }
            bezelFront(c)
            c.restore()
            screws(c, cx, cy, r)
        }
        statusBar(c, d, w, h, hb)
    }

    /** Pasek stanu pod zegarami: pozycja, nastawa ciśnienia, temperatura, czas. */
    private fun statusBar(c: Canvas, d: FlightData, w: Float, h: Float, hb: Float) {
        p.rect(c, 0f, h - hb, w, h, 0xFF0C0D0F.toInt())
        p.line(c, 0f, h - hb, w, h - hb, 0xFF3C3E42.toInt(), 1.5f)
        val y = h - hb / 2f
        val sz = (hb * 0.42f).coerceIn(11f, 26f)
        val gps = if (d.gpsFix) "GPS ±" + d.gpsAccuracy.roundToInt() + " m · " + d.sats + " SAT" else "GPS · szukanie…"
        p.text(c, gps, w * 0.02f, y, sz, if (d.gpsFix) LABEL else ORANGE, Paint.Align.LEFT)
        val baroTxt = if (d.std) "STD 1013" else "QNH " + d.qnh.roundToInt() + " · " + d.qnhSource
        p.text(c, baroTxt, w * 0.5f, y, sz, LABEL)
        val right = StringBuilder()
        if (!d.oatC.isNaN()) right.append(d.oatC.roundToInt()).append("°C   ")
        if (!d.tasKt.isNaN()) right.append("TAS ").append(d.tasKt.roundToInt()).append("   ")
        right.append(clock.format(Date()))
        p.text(c, right.toString(), w * 0.98f, y, sz, LABEL, Paint.Align.RIGHT)
    }

    private fun updateTurnRate(d: FlightData) {
        val now = System.nanoTime()
        if (!lastHdg.isNaN() && lastT != 0L) {
            val dt = (now - lastT) / 1e9f
            if (dt in 0.001f..1f) {
                val rate = angDiff(d.heading, lastHdg) / dt
                turnRate += dt / (0.8f + dt) * (rate - turnRate)
            }
        }
        lastHdg = d.heading
        lastT = now
    }

    // ---------------------------------------------------------------- obudowa
    private fun bezelBack(c: Canvas) {
        p.circle(c, 0f, 0f, 104f, RIM)
        p.circle(c, 0f, 0f, 99f, BEZEL)
        p.circle(c, 0f, 0f, 93f, FACE)
    }

    private fun bezelFront(c: Canvas) {
        p.circle(c, 0f, 0f, 96f, 0xFF000000.toInt(), 6f)
        // odblask szkła
        arc.shader = null
        arc.color = 0x22FFFFFF
        arc.strokeWidth = 10f
        oval.set(-82f, -82f, 82f, 82f)
        c.drawArc(oval, 200f, 70f, false, arc)
    }

    private fun screws(c: Canvas, cx: Float, cy: Float, r: Float) {
        val o = r * 0.95f
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val x = cx + sx * o
            val y = cy + sy * o
            p.circle(c, x, y, r * 0.055f, 0xFF4A4C50.toInt())
            p.line(c, x - r * 0.04f, y - r * 0.02f, x + r * 0.04f, y + r * 0.02f, 0xFF1A1A1A.toInt(), r * 0.012f)
        }
    }

    private fun needle(c: Canvas, angle: Float, len: Float, width: Float, color: Int) {
        c.save()
        c.rotate(angle)
        p.poly(c, color, 0, 0f, -width, 14f, width, 14f, width * 0.5f, -len + 8, 0f, -len, -width * 0.5f, -len + 8)
        c.restore()
    }

    private fun hub(c: Canvas) {
        p.circle(c, 0f, 0f, 8f, 0xFF202020.toInt())
        p.circle(c, 0f, 0f, 8f, 0xFF606060.toInt(), 1.5f)
    }

    private fun arcBand(c: Canvas, a1: Float, a2: Float, r: Float, w: Float, color: Int) {
        arc.color = color
        arc.strokeWidth = w
        oval.set(-r, -r, r, r)
        c.drawArc(oval, a1 - 90f, a2 - a1, false, arc)
    }

    // ---------------------------------------------------------------- prędkościomierz
    private fun asiAngle(v: Float) = 20f + v.coerceIn(0f, asiCfg.max) / asiCfg.max * 320f

    private fun asi(c: Canvas, d: FlightData) {
        val cfg = asiCfg
        for (a in cfg.arcs) arcBand(c, asiAngle(a.from), asiAngle(a.to), a.r, a.w, a.color)
        p.radial(c, 0f, 0f, asiAngle(cfg.red), 70f, 90f, RED, 4f)
        val minor = if (cfg.max > 200f) 10 else 5
        var v = 0
        while (v <= cfg.max) {
            val major = v % (minor * 2) == 0
            if (v >= cfg.firstLabel - minor || kind == Kind.HELI) {
                p.radial(c, 0f, 0f, asiAngle(v.toFloat()), if (major) 72f else 78f, 90f, Col.WHITE, if (major) 2.5f else 1.5f)
            }
            if (v >= cfg.firstLabel && (v - cfg.firstLabel) % cfg.labelStep == 0) {
                val a = Math.toRadians(asiAngle(v.toFloat()).toDouble())
                p.text(c, v.toString(), (sin(a) * 58).toFloat(), (-cos(a) * 58).toFloat(),
                    if (cfg.max > 200f) 12f else 15f, Col.WHITE, bold = true)
            }
            v += minor
        }
        // znaczniki prędkości charakterystycznych
        for ((v, col) in cfg.marks) {
            p.radial(c, 0f, 0f, asiAngle(v), 88f, 96f, col, 3f)
        }
        p.text(c, "AIRSPEED", 0f, -26f, 10f, LABEL)
        p.text(c, cfg.unit, 0f, 28f, 10f, LABEL)
        // cyfrowy odczyt prędkości względem ziemi
        p.rect(c, -34f, 40f, 34f, 64f, 0xFF000000.toInt())
        p.frame(c, -34f, 40f, 34f, 64f, 0xFF707070.toInt(), 1.5f)
        if (d.speedValid) {
            p.text(c, (d.speedKt * cfg.factor).roundToInt().toString(), 0f, 52f, 16f, Col.WHITE, bold = true)
        } else {
            p.text(c, "NO GPS", 0f, 52f, 11f, ORANGE)
        }
        p.text(c, cfg.legend, 0f, 76f, 8f, LABEL)
        needle(c, asiAngle(if (d.speedValid) d.speedKt * cfg.factor else 0f), 86f, 3.5f, Col.WHITE)
        hub(c)
    }

    // ---------------------------------------------------------------- sztuczny horyzont
    private fun ai(c: Canvas, d: FlightData) {
        val k = 2.4f
        c.save()
        val clip = android.graphics.Path().apply { addCircle(0f, 0f, 92f, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        c.rotate(-d.roll)
        val pitch = d.pitch.coerceIn(-30f, 30f)
        p.rect(c, -200f, -200f, 200f, pitch * k, SKY)
        p.rect(c, -200f, pitch * k, 200f, 200f, EARTH)
        p.line(c, -200f, pitch * k, 200f, pitch * k, Col.WHITE, 2.5f)
        for (deg in intArrayOf(5, 10, 15, 20)) {
            val y = pitch * k - deg * k
            val half = if (deg % 10 == 0) 22f else 12f
            p.line(c, -half, y, half, y, Col.WHITE, 2f)
            val y2 = pitch * k + deg * k
            p.line(c, -half, y2, half, y2, Col.WHITE, 2f)
        }
        // skala przechylenia na obrotowej tarczy
        for (a in intArrayOf(10, 20, 30, 60, 90)) {
            for (sg in intArrayOf(-1, 1)) {
                val long = a == 30 || a == 60 || a == 90
                p.radial(c, 0f, 0f, (sg * a).toFloat(), if (long) 72f else 78f, 92f, Col.WHITE, 2.5f)
            }
        }
        p.poly(c, Col.WHITE, 0, 0f, 0f, -74f, -7f, -90f, 7f, -90f)
        c.restore()
        // stałe elementy obudowy
        p.poly(c, ORANGE, 0, 0f, 0f, -70f, -7f, -58f, 7f, -58f)
        p.line(c, -56f, 0f, -18f, 0f, ORANGE, 5f)
        p.line(c, 18f, 0f, 56f, 0f, ORANGE, 5f)
        p.line(c, -18f, 0f, -10f, 7f, ORANGE, 5f)
        p.line(c, 18f, 0f, 10f, 7f, ORANGE, 5f)
        p.circle(c, 0f, 0f, 4f, ORANGE)
        if (!d.attValid) p.text(c, "GYRO", 0f, 50f, 16f, ORANGE, bold = true)
    }

    // ---------------------------------------------------------------- wysokościomierz
    private fun alt(c: Canvas, d: FlightData) {
        for (i in 0 until 50) {
            val major = i % 5 == 0
            p.radial(c, 0f, 0f, i * 7.2f, if (major) 74f else 82f, 90f, Col.WHITE, if (major) 2.5f else 1.5f)
        }
        for (n in 0..9) {
            val a = Math.toRadians(n * 36.0)
            p.text(c, n.toString(), (sin(a) * 62).toFloat(), (-cos(a) * 62).toFloat(), 18f, Col.WHITE, bold = true)
        }
        p.text(c, "ALT", 0f, -30f, 11f, LABEL)
        p.text(c, if (metricAlt) "100 METERS" else "100 FEET", 0f, 32f, 9f, LABEL)
        // okienko Kollsmana
        p.rect(c, 30f, -14f, 86f, 14f, 0xFF000000.toInt())
        p.frame(c, 30f, -14f, 86f, 14f, 0xFF707070.toInt(), 1.5f)
        val txt = when {
            d.std -> if (d.inHg) "29.92" else "1013"
            d.inHg -> String.format(Locale.US, "%.2f", d.qnh * 0.02953f)
            else -> String.format(Locale.US, "%.0f", d.qnh)
        }
        p.text(c, txt, 58f, 0f, 15f, if (d.std) 0xFF33DDFF.toInt() else Col.WHITE)
        p.text(c, if (d.inHg) "inHg" else "hPa", 58f, 22f, 8f, LABEL)
        if (!d.altValid) {
            p.text(c, "NO DATA", 0f, 52f, 11f, ORANGE)
            hub(c)
            return
        }
        val a = (if (metricAlt) d.altFt * 0.3048f else d.altFt).coerceAtLeast(0f)
        if (!d.altFromBaro) p.text(c, "GPS", 0f, 52f, 10f, ORANGE)
        // wskazówka 10 000 ft (cienka z trójkątem)
        c.save()
        c.rotate((a % 100000f) / 100000f * 360f)
        p.line(c, 0f, 0f, 0f, -80f, Col.WHITE, 1.5f)
        p.poly(c, Col.WHITE, 0, 0f, 0f, -92f, -6f, -80f, 6f, -80f)
        c.restore()
        needle(c, (a % 10000f) / 10000f * 360f, 50f, 6f, Col.WHITE)
        needle(c, (a % 1000f) / 1000f * 360f, 84f, 3.5f, Col.WHITE)
        hub(c)
    }

    // ---------------------------------------------------------------- zakrętomierz
    private fun tc(c: Canvas, d: FlightData) {
        // znaczniki: skrzydła poziomo i zakręt standardowy (3°/s)
        for (a in floatArrayOf(-90f, 90f)) p.radial(c, 0f, 0f, a, 66f, 88f, Col.WHITE, 4f)
        for (a in floatArrayOf(-110f, 110f)) p.radial(c, 0f, 0f, a, 66f, 88f, Col.WHITE, 4f)
        p.text(c, "L", -66f, 40f, 16f, Col.WHITE, bold = true)
        p.text(c, "R", 66f, 40f, 16f, Col.WHITE, bold = true)
        p.text(c, "TURN COORDINATOR", 0f, -54f, 8.5f, LABEL)
        p.text(c, String.format(Locale.US, "%+.1f°/s", turnRate), 0f, -34f, 11f, LABEL)
        p.text(c, "2 MIN", 0f, 32f, 10f, LABEL)
        p.text(c, "NO PITCH", 0f, 80f, 7f, LABEL)
        p.text(c, "INFORMATION", 0f, 87f, 7f, LABEL)

        // libella (kulka)
        arc.color = 0xFFE8E4D0.toInt()
        arc.strokeWidth = 16f
        oval.set(-110f, -150f, 110f, 70f)
        c.drawArc(oval, 62f, 56f, false, arc)
        val ballAng = Math.toRadians((90f - (d.slip * 120f).coerceIn(-24f, 24f)).toDouble())
        val bx = (cos(ballAng) * 110).toFloat()
        val by = (-40 + sin(ballAng) * 110).toFloat()
        p.circle(c, bx, by, 7f, 0xFF000000.toInt())
        p.line(c, -9f, 58f, -9f, 76f, 0xFF000000.toInt(), 2f)
        p.line(c, 9f, 58f, 9f, 76f, 0xFF000000.toInt(), 2f)

        // samolocik pochylony proporcjonalnie do prędkości kątowej zakrętu
        val tilt = (turnRate / 3f * 20f).coerceIn(-35f, 35f)
        c.save()
        c.rotate(tilt)
        p.line(c, -62f, 0f, 62f, 0f, Col.WHITE, 6f)
        p.circle(c, 0f, 0f, 10f, Col.WHITE)
        p.line(c, 0f, -10f, 0f, -24f, Col.WHITE, 4f)
        p.line(c, -14f, -18f, 14f, -18f, Col.WHITE, 3f)
        c.restore()
    }

    // ---------------------------------------------------------------- żyrobusola
    private fun hi(c: Canvas, d: FlightData) {
        val names = mapOf(0 to "N", 30 to "3", 60 to "6", 90 to "E", 120 to "12", 150 to "15",
            180 to "S", 210 to "21", 240 to "24", 270 to "W", 300 to "30", 330 to "33")
        for (deg in 0 until 360 step 5) {
            c.save()
            c.rotate(deg - d.heading)
            val ten = deg % 10 == 0
            p.line(c, 0f, -90f, 0f, if (ten) -76f else -83f, Col.WHITE, if (ten) 2.5f else 1.5f)
            names[deg]?.let { p.text(c, it, 0f, -62f, if (it.length == 1) 18f else 15f, Col.WHITE, bold = true) }
            c.restore()
        }
        for (a in 0 until 360 step 45) {
            if (a == 0) continue
            p.radial(c, 0f, 0f, a.toFloat(), 93f, 98f, ORANGE, 3f)
        }
        p.poly(c, ORANGE, 0, 0f, 0f, -91f, -6f, -100f, 6f, -100f)
        p.line(c, 0f, -88f, 0f, -70f, ORANGE, 3f)
        // samolot w środku
        p.line(c, 0f, -34f, 0f, 30f, ORANGE, 6f)
        p.line(c, -32f, -4f, 32f, -4f, ORANGE, 6f)
        p.line(c, -13f, 26f, 13f, 26f, ORANGE, 5f)
        if (d.hasTrack) {
            c.save()
            c.rotate(angDiff(d.track, d.heading))
            p.poly(c, 0xFFFF55FF.toInt(), 0, 0f, 0f, -74f, 5f, -66f, 0f, -58f, -5f, -66f)
            c.restore()
        }
        // okienko cyfrowe kursu
        p.rect(c, -32f, 48f, 32f, 72f, 0xFF000000.toInt())
        p.frame(c, -32f, 48f, 32f, 72f, 0xFF707070.toInt(), 1.5f)
        if (d.attValid) {
            p.text(c, String.format(Locale.US, "%03d", mod(d.heading.roundToInt(), 360)), 0f, 60f, 16f,
                Col.WHITE, bold = true)
            p.text(c, if (d.headingMagnetic) "MAG" else "REL", 0f, 82f, 8f,
                if (d.headingMagnetic) LABEL else ORANGE)
        } else {
            p.text(c, "OFF", 0f, 60f, 14f, ORANGE, bold = true)
        }
    }

    // ---------------------------------------------------------------- wariometr szybowcowy (TE)
    private fun varioAngle(v: Float) = -90f + v.coerceIn(-5f, 5f) / 5f * 170f

    private fun vario(c: Canvas, d: FlightData) {
        var k = -10
        while (k <= 10) {
            val v = k * 0.5f
            val major = k % 2 == 0
            p.radial(c, 0f, 0f, varioAngle(v), if (major) 70f else 80f, 90f, Col.WHITE, if (major) 3f else 1.5f)
            if (major && k != 0) {
                val a = Math.toRadians(varioAngle(v).toDouble())
                p.text(c, abs(k / 2).toString(), (sin(a) * 56).toFloat(), (-cos(a) * 56).toFloat(), 17f, Col.WHITE, bold = true)
            }
            k++
        }
        arcBand(c, varioAngle(0f), varioAngle(5f), 86f, 4f, GREEN)
        arcBand(c, varioAngle(-5f), varioAngle(0f), 86f, 4f, RED)
        p.text(c, "0", -56f, 0f, 17f, Col.WHITE, bold = true)
        p.text(c, "VARIO", 22f, -16f, 10f, LABEL)
        p.text(c, "m/s  TE", 22f, 14f, 9f, LABEL)
        val te = if (!d.altValid) 0f else {
            val vs = d.vsFpm * 0.00508f
            if (d.speedValid) vs + d.speedKt * 0.514444f * (d.speedTrend * 0.0514444f) / 9.81f else vs
        }
        // średnie wznoszenie z ostatnich ~20 s (igła pomarańczowa)
        val now = System.nanoTime()
        if (lastVarioT != 0L) {
            val dt = ((now - lastVarioT) / 1e9f).coerceIn(0f, 1f)
            avgTe += dt / (20f + dt) * (te - avgTe)
        }
        lastVarioT = now
        p.text(c, String.format(Locale.US, "%+.1f", te), 22f, 40f, 14f, if (te >= 0f) GREEN else ORANGE, bold = true)
        p.text(c, "AVG", 22f, 58f, 8f, LABEL)
        p.text(c, String.format(Locale.US, "%+.1f", avgTe), 22f, 72f, 12f, ORANGE)
        needle(c, varioAngle(avgTe), 70f, 2f, ORANGE)
        needle(c, varioAngle(te), 86f, 3.5f, Col.WHITE)
        hub(c)
    }

    // ---------------------------------------------------------------- G-metr
    private fun gAngle(g: Float) = -135f + (g.coerceIn(-1f, 4f) + 1f) / 5f * 270f

    private fun gmeter(c: Canvas, d: FlightData) {
        gMax = maxOf(gMax, d.gLoad)
        gMin = minOf(gMin, d.gLoad)
        arcBand(c, gAngle(-1f), gAngle(3.5f), 82f, 6f, GREEN)
        arcBand(c, gAngle(3.5f), gAngle(4f), 82f, 6f, RED)
        var k = -10
        while (k <= 40) {
            val g = k / 10f
            val major = k % 10 == 0
            if (k % 5 == 0) p.radial(c, 0f, 0f, gAngle(g), if (major) 70f else 78f, 90f, Col.WHITE, if (major) 3f else 1.5f)
            if (major) {
                val a = Math.toRadians(gAngle(g).toDouble())
                p.text(c, (k / 10).toString(), (sin(a) * 56).toFloat(), (-cos(a) * 56).toFloat(), 17f, Col.WHITE, bold = true)
            }
            k += 5
        }
        p.text(c, "G", 0f, 28f, 18f, LABEL, bold = true)
        p.text(c, String.format(Locale.US, "%.2f", d.gLoad), 0f, 50f, 13f, Col.WHITE)
        p.text(c, String.format(Locale.US, "MAX %.1f   MIN %.1f", gMax, gMin), 0f, 70f, 9f, ORANGE)
        needle(c, gAngle(gMax), 70f, 2f, ORANGE)
        needle(c, gAngle(gMin), 70f, 2f, ORANGE)
        needle(c, gAngle(d.gLoad), 86f, 3.5f, Col.WHITE)
        hub(c)
    }

    // ---------------------------------------------------------------- radiowysokościomierz
    private fun raAngle(ft: Float) = ft.coerceIn(0f, 1000f) / 1000f * 320f - 160f

    private fun radioAlt(c: Canvas, d: FlightData) {
        arcBand(c, raAngle(0f), raAngle(100f), 82f, 6f, RED)
        var v = 0
        while (v <= 1000) {
            val major = v % 100 == 0
            p.radial(c, 0f, 0f, raAngle(v.toFloat()), if (major) 72f else 80f, 90f, Col.WHITE,
                if (major) 2.5f else 1.5f)
            if (v % 200 == 0) {
                val a = Math.toRadians(raAngle(v.toFloat()).toDouble())
                p.text(c, (v / 100).toString(), (sin(a) * 58).toFloat(), (-cos(a) * 58).toFloat(), 16f,
                    Col.WHITE, bold = true)
            }
            v += 50
        }
        p.text(c, "RADIO ALT", 0f, -32f, 10f, LABEL)
        p.text(c, "100 FEET", 0f, 30f, 9f, LABEL)
        p.rect(c, -34f, 42f, 34f, 66f, 0xFF000000.toInt())
        p.frame(c, -34f, 42f, 34f, 66f, 0xFF707070.toInt(), 1.5f)
        if (!d.raValid) {
            p.text(c, "NO TERR", 0f, 54f, 10f, ORANGE)
            hub(c)
            return
        }
        val ra = d.raFt.coerceAtLeast(0f)
        p.text(c, ra.roundToInt().toString(), 0f, 54f, 15f, if (ra < 100f) ORANGE else Col.WHITE, bold = true)
        needle(c, raAngle(ra), 86f, 3.5f, if (ra < 100f) ORANGE else Col.WHITE)
        hub(c)
    }

    // ---------------------------------------------------------------- wariometr
    private fun vsiAngle(v: Float) = -90f + v.coerceIn(-2000f, 2000f) / 2000f * 170f

    private fun vsi(c: Canvas, d: FlightData) {
        var v = -2000
        while (v <= 2000) {
            val major = v % 500 == 0
            p.radial(c, 0f, 0f, vsiAngle(v.toFloat()), if (major) 72f else 80f, 90f, Col.WHITE, if (major) 2.5f else 1.5f)
            if (major && v != 0) {
                val a = Math.toRadians(vsiAngle(v.toFloat()).toDouble())
                p.text(c, (abs(v) / 100).toString(), (sin(a) * 58).toFloat(), (-cos(a) * 58).toFloat(), 16f, Col.WHITE, bold = true)
            }
            v += 100
        }
        p.text(c, "0", -58f, 0f, 16f, Col.WHITE, bold = true)
        p.text(c, "UP", -30f, -40f, 10f, LABEL)
        p.text(c, "DOWN", -30f, 40f, 10f, LABEL)
        p.text(c, "VERTICAL SPEED", 22f, -14f, 8f, LABEL)
        p.text(c, "100 FT PER MIN", 22f, 14f, 8f, LABEL)
        if (d.altValid) {
            val v100 = (d.vsFpm / 50f).roundToInt() * 50
            p.text(c, String.format(Locale.US, "%+d", v100), 22f, 44f, 13f,
                if (abs(d.vsFpm) < 100f) LABEL else Col.WHITE, bold = true)
        } else {
            p.text(c, "NO DATA", 22f, 44f, 10f, ORANGE)
        }
        needle(c, vsiAngle(if (d.altValid) d.vsFpm else 0f), 86f, 3.5f, Col.WHITE)
        hub(c)
    }
}
