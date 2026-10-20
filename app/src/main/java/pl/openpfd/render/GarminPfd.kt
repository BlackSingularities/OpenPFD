package pl.openpfd.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import pl.openpfd.FlightData
import pl.openpfd.angDiff
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** PFD w stylu Garmin G1000/G3000 z widokiem syntetycznym (SVT). */
class GarminPfd : PfdRenderer {
    private val p = Pen()

    private companion object {
        const val CX = 500f
        const val CY = 440f
        const val PPD = 9f

        const val PANEL = 0x99101820.toInt()
        const val PANEL_DARK = 0xCC0A0F16.toInt()
        const val GREEN = 0xFF2EF03A.toInt()
        const val CYAN = 0xFF3FE0FF.toInt()
        const val MAGENTA = 0xFFFF33E6.toInt()
        const val YELLOW = 0xFFF5C800.toInt()
        const val YELLOW_DARK = 0xFFB08A00.toInt()
        const val GREY = 0xFF9AA0A6.toInt()
        const val LADDER = 0xCCE6E6E6.toInt()

        // prędkości charakterystyczne (Cessna 172)
        const val VS0 = 40f
        const val VFE = 85f
        const val VS1 = 48f
        const val VNO = 129f
        const val VNE = 163f

        const val HX = CX
        const val HY = 1075f
        const val HR = 255f
    }

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, CY - 900f, 0f, CY, 0xFF0B3C8C.toInt(), 0xFF6FA8E8.toInt(), Shader.TileMode.CLAMP)
    }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, CY, 0f, CY + 700f, 0xFF3F7A2A.toInt(), 0xFF153F12.toInt(), Shader.TileMode.CLAMP)
    }

    override val baroRect = RectF(690f, 715f, 880f, 775f)

    override fun draw(c: Canvas, d: FlightData) {
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawHsi(c, d)
        drawFma(c, d)
        drawBaro(c, d)
        drawWindOat(c, d)
    }

    // ---------------------------------------------------------------- SVT / horyzont
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.rect(c, 0f, 0f, 1000f, 1000f, 0xFF20262E.toInt())
            p.line(c, 330f, 300f, 670f, 600f, Col.RED, 8f)
            p.line(c, 670f, 300f, 330f, 600f, Col.RED, 8f)
            p.text(c, "AHRS FAIL", CX, 650f, 40f, Col.RED)
            return
        }
        c.save()
        c.rotate(-d.roll, CX, CY)
        c.translate(0f, d.pitch * PPD)
        c.drawRect(CX - 1200, CY - 3000, CX + 1200, CY, skyPaint)
        c.drawRect(CX - 1200, CY, CX + 1200, CY + 3000, groundPaint)
        drawTerrain(c, d)
        p.line(c, CX - 1200, CY, CX + 1200, CY, Col.WHITE, 4f)

        // kursy na linii horyzontu
        var h = floorTo(d.heading - 60f, 10)
        while (h <= d.heading + 60f) {
            val x = CX + angDiff(h.toFloat(), d.heading) * PPD
            p.line(c, x, CY, x, CY + 14, Col.WHITE, 3f)
            if (mod(h, 30) == 0) {
                val n = mod(h, 360)
                p.text(c, if (n == 0) "360" else n.toString(), x, CY + 34, 28f, Col.WHITE)
            }
            h += 10
        }

        // drabinka pochylenia
        c.save()
        c.clipRect(CX - 170, CY - 230 - d.pitch * PPD, CX + 170, CY + 230 - d.pitch * PPD)
        val i0 = ceil((d.pitch - 25f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 25f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = CY - deg * PPD
            val half = when {
                i % 4 == 0 -> 120f
                i % 2 == 0 -> 55f
                else -> 25f
            }
            p.line(c, CX - half, y, CX + half, y, LADDER, 4f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, CX - half - 22, y, 26f, LADDER)
                p.text(c, lbl, CX + half + 22, y, 26f, LADDER)
            }
        }
        c.restore()
        c.restore()

        // znacznik toru lotu (FPM)
        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, CX, CY)
            val x = CX + angDiff(d.track, d.heading).coerceIn(-25f, 25f) * PPD
            val y = CY - (d.fpa - d.pitch).coerceIn(-25f, 25f) * PPD
            p.circle(c, x, y, 13f, GREEN, 4f)
            p.line(c, x - 13, y, x - 36, y, GREEN, 4f)
            p.line(c, x + 13, y, x + 36, y, GREEN, 4f)
            p.line(c, x, y - 13, x, y - 26, GREEN, 4f)
            c.restore()
        }

        drawRollScale(c, d)
        drawAircraft(c)
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 100f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            p.text(c, "RA " + ra, CX + 130f, CY + 200f, 34f, if (d.raFt < 200f) YELLOW else GREEN, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- teren 3D (SVT)
    private val quad = Path()
    private val terrainFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val terrainEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0x30000000
    }
    private var sx = FloatArray(0)
    private var sy = FloatArray(0)
    private var sd = FloatArray(0)

    /** Rzutuje siatkę DEM na widok z kabiny (rzut kątowy zgodny z drabinką pochylenia). */
    private fun drawTerrain(c: Canvas, d: FlightData) {
        val g = d.terrain ?: return
        if (!d.altValid) return
        val n = g.n
        if (sx.size != n * n) {
            sx = FloatArray(n * n); sy = FloatArray(n * n); sd = FloatArray(n * n)
        }
        val (offN, offE) = g.offsetM(d.lat, d.lon)
        val camAlt = d.mslFt * 0.3048f
        val h = Math.toRadians((d.heading + d.magVar).toDouble())
        val sh = sin(h).toFloat()
        val ch = cos(h).toFloat()
        var minE = Float.MAX_VALUE
        var maxE = -Float.MAX_VALUE
        for (k in 0 until n * n) {
            val e = g.elevM[k]
            if (e < minE) minE = e
            if (e > maxE) maxE = e
        }
        for (i in 0 until n) for (j in 0 until n) {
            val k = i * n + j
            val north = (g.pointNorth(i) - offN).toFloat()
            val east = (g.pointEast(j) - offE).toFloat()
            val fwd = east * sh + north * ch
            val lat = east * ch - north * sh
            val up = g.elevM[k] - camAlt
            sd[k] = fwd
            if (fwd > 40f) {
                sx[k] = CX + Math.toDegrees(atan2(lat.toDouble(), fwd.toDouble())).toFloat() * PPD
                sy[k] = CY - Math.toDegrees(atan2(up.toDouble(), fwd.toDouble())).toFloat() * PPD
            }
        }
        // kolejność malarza: od najdalszych
        val cells = ArrayList<Int>((n - 1) * (n - 1))
        for (i in 0 until n - 1) for (j in 0 until n - 1) cells += i * n + j
        cells.sortByDescending { k -> sd[k] + sd[k + 1] + sd[k + n] + sd[k + n + 1] }
        val range = (maxE - minE).coerceAtLeast(1f)
        for (k in cells) {
            val a = k
            val b = k + 1
            val cc = k + n + 1
            val dd = k + n
            if (sd[a] <= 40f || sd[b] <= 40f || sd[cc] <= 40f || sd[dd] <= 40f) continue
            val elev = (g.elevM[a] + g.elevM[b] + g.elevM[cc] + g.elevM[dd]) / 4f
            val clearanceFt = (camAlt - elev) * 3.28084f
            val dist = (sd[a] + sd[b] + sd[cc] + sd[dd]) / 4f
            // cieniowanie od nachylenia (światło z północnego zachodu)
            val slope = ((g.elevM[a] - g.elevM[b]) + (g.elevM[dd] - g.elevM[a]) * -1f) / 3000f
            var col = when {
                clearanceFt < 100f -> 0xFFC8231E.toInt()
                clearanceFt < 1000f -> 0xFFC9A51E.toInt()
                else -> mix(0xFF2F6B22.toInt(), 0xFF8C8A4A.toInt(), (elev - minE) / range * 0.8f)
            }
            col = mix(col, 0xFF000000.toInt(), (0.15f - slope * 2f).coerceIn(0f, 0.35f))
            col = mix(col, 0xFF9DB7CF.toInt(), (dist / 16000f).coerceIn(0f, 0.55f))
            quad.reset()
            quad.moveTo(sx[a], sy[a])
            quad.lineTo(sx[b], sy[b])
            quad.lineTo(sx[cc], sy[cc])
            quad.lineTo(sx[dd], sy[dd])
            quad.close()
            terrainFill.color = col
            c.drawPath(quad, terrainFill)
            c.drawPath(quad, terrainEdge)
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1 - u) + ((b shr sh) and 0xFF) * u).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        val r = 285f
        for (a in intArrayOf(10, 20, 30, 45, 60)) {
            val r2 = if (a == 30 || a == 60) r + 32f else r + 18f
            p.radial(c, CX, CY, a.toFloat(), r, r2, Col.WHITE, 4f)
            p.radial(c, CX, CY, -a.toFloat(), r, r2, Col.WHITE, 4f)
        }
        p.poly(c, Col.WHITE, Col.BLACK, 2f, CX, CY - r - 4, CX - 16, CY - r - 34, CX + 16, CY - r - 34)
        c.save()
        c.rotate(-d.roll, CX, CY)
        val col = if (abs(d.roll) > 45f) YELLOW else Col.WHITE
        p.poly(c, col, Col.BLACK, 2f, CX, CY - r + 2, CX - 16, CY - r + 30, CX + 16, CY - r + 30)
        val off = (d.slip * 180f).coerceIn(-40f, 40f)
        p.poly(c, col, Col.BLACK, 2f,
            CX - 16 + off, CY - r + 34, CX + 16 + off, CY - r + 34,
            CX + 16 + off, CY - r + 44, CX - 16 + off, CY - r + 44)
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        // symbol samolotu w stylu „single cue” (żółte strzałki)
        p.poly(c, YELLOW, Col.BLACK, 2.5f, CX, CY, CX - 150, CY + 48, CX - 112, CY + 48)
        p.poly(c, YELLOW_DARK, Col.BLACK, 2.5f, CX, CY, CX - 112, CY + 48, CX - 70, CY + 48)
        p.poly(c, YELLOW, Col.BLACK, 2.5f, CX, CY, CX + 150, CY + 48, CX + 112, CY + 48)
        p.poly(c, YELLOW_DARK, Col.BLACK, 2.5f, CX, CY, CX + 112, CY + 48, CX + 70, CY + 48)
        p.poly(c, YELLOW, Col.BLACK, 2.5f, CX - 210, CY - 5, CX - 175, CY - 5, CX - 175, CY + 5, CX - 210, CY + 5)
        p.poly(c, YELLOW, Col.BLACK, 2.5f, CX + 210, CY - 5, CX + 175, CY - 5, CX + 175, CY + 5, CX + 210, CY + 5)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 150f
        val r = 265f
        val t = 150f
        val b = 690f
        val k = 5.2f
        p.rect(c, l, t, r, b, PANEL)
        if (!d.speedValid) {
            p.line(c, l, t, r, b, Col.RED, 5f)
            p.line(c, r, t, l, b, Col.RED, 5f)
            return
        }
        val s = d.speedKt
        c.save()
        c.clipRect(l, t, r + 14, b)
        var v = floorTo(s - 60f, 5).coerceAtLeast(0)
        while (v <= s + 60f) {
            val y = CY - (v - s) * k
            val ten = v % 10 == 0
            p.line(c, r - if (ten) 22f else 12f, y, r, y, Col.WHITE, 3f)
            if (ten && v >= 20) p.text(c, v.toString(), r - 30, y, 36f, Col.WHITE, Paint.Align.RIGHT)
            v += 5
        }
        // pasy prędkości
        fun band(from: Float, to: Float, x0: Float, x1: Float, col: Int) {
            p.rect(c, x0, CY - (to - s) * k, x1, CY - (from - s) * k, col)
        }
        band(VS0, VFE, r + 1, r + 6, Col.WHITE)
        band(VS1, VNO, r + 6, r + 13, GREEN)
        band(VNO, VNE, r + 6, r + 13, YELLOW)
        band(VNE, VNE + 300f, r + 1, r + 13, Col.RED)
        c.restore()

        // trend (magenta)
        val tr = d.speedTrend.coerceIn(-55f, 55f)
        if (abs(tr) > 2f) p.rect(c, r - 6, minOf(CY, CY - tr * k), r, maxOf(CY, CY - tr * k), MAGENTA)

        // okno odczytu
        p.poly(c, Col.BLACK, Col.WHITE, 2.5f,
            l - 10, CY - 32, r - 50, CY - 32, r - 50, CY - 50, r - 14, CY - 50, r - 14, CY - 14,
            r + 4, CY, r - 14, CY + 14, r - 14, CY + 50, r - 50, CY + 50, r - 50, CY + 32, l - 10, CY + 32)
        val tens = floor(s / 10f).toInt()
        if (tens > 0) p.text(c, tens.toString(), r - 52, CY, 52f, Col.WHITE, Paint.Align.RIGHT)
        p.drum(c, r - 49, CY - 48, r - 15, CY + 48, s, 44f, 48f, Col.WHITE, r - 32, Paint.Align.CENTER) { kk ->
            mod(kk, 10).toString()
        }

        // GS
        p.rect(c, l, b + 10, r, b + 56, PANEL_DARK)
        p.text(c, "GS", l + 10, b + 33, 28f, Col.WHITE, Paint.Align.LEFT)
        p.text(c, s.roundToInt().toString(), r - 10, b + 33, 34f, Col.WHITE, Paint.Align.RIGHT)
        if (!d.tasKt.isNaN()) {
            p.rect(c, l, b + 62, r, b + 108, PANEL_DARK)
            p.text(c, "TAS", l + 10, b + 85, 28f, Col.WHITE, Paint.Align.LEFT)
            p.text(c, d.tasKt.roundToInt().toString(), r - 10, b + 85, 34f, Col.WHITE, Paint.Align.RIGHT)
        }
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 690f
        val r = 820f
        val t = 150f
        val b = 690f
        val k = 0.6f
        p.rect(c, l, t, r, b, PANEL)
        if (!d.altValid) {
            p.line(c, l, t, r, b, Col.RED, 5f)
            p.line(c, r, t, l, b, Col.RED, 5f)
            return
        }
        val a = d.altFt
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 500f, 20)
        while (v <= a + 500f) {
            val y = CY - (v - a) * k
            val hundred = mod(v, 100) == 0
            p.line(c, l, y, l + if (hundred) 20f else 10f, y, Col.WHITE, 2.5f)
            if (hundred) p.text(c, v.toString(), r - 12, y, 34f, Col.WHITE, Paint.Align.RIGHT)
            v += 20
        }
        c.restore()

        p.poly(c, Col.BLACK, Col.WHITE, 2.5f,
            l - 16, CY, l, CY - 14, l, CY - 32, r - 50, CY - 32, r - 50, CY - 50,
            r + 4, CY - 50, r + 4, CY + 50, r - 50, CY + 50, r - 50, CY + 32, l, CY + 32, l, CY + 14)
        val hundreds = floor(a / 100f).toInt()
        p.text(c, hundreds.toString(), r - 52, CY, 50f, Col.WHITE, Paint.Align.RIGHT)
        p.drum(c, r - 49, CY - 48, r + 3, CY + 48, a / 20f, 36f, 36f, Col.WHITE, r - 22, Paint.Align.CENTER) { kk ->
            String.format(Locale.US, "%02d", mod(kk * 20, 100))
        }
        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 20, 28f, YELLOW)
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val a = abs(v).coerceAtMost(4300f)
        val g = when {
            a <= 1000f -> a * 0.08f
            a <= 2000f -> 80f + (a - 1000f) * 0.07f
            else -> 150f + (a - 2000f) * 0.04f
        }
        return CY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 835f
        val r = 900f
        p.rect(c, l, CY - 250, r, CY + 250, PANEL)
        for (v in intArrayOf(500, 1000, 1500, 2000, 4000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val major = v == 1000 || v == 2000 || v == 4000
                p.line(c, l, y, l + if (major) 16f else 9f, y, Col.WHITE, 2.5f)
                if (major) p.text(c, ((sg * v) / 1000).toString(), r - 6, y, 26f, Col.WHITE, Paint.Align.RIGHT)
            }
        }
        p.line(c, l, CY, l + 20, CY, Col.WHITE, 3f)
        if (!d.altValid) return
        val vs = d.vsFpm
        val y = vy(vs)
        val txt = if (abs(vs) >= 100f) ((vs / 50f).roundToInt() * 50).toString() else ""
        p.poly(c, Col.BLACK, Col.WHITE, 2.5f, l + 2, y, l + 20, y - 22, 985f, y - 22, 985f, y + 22, l + 20, y + 22)
        p.text(c, txt, 978f, y, 32f, Col.WHITE, Paint.Align.RIGHT)
    }

    // ---------------------------------------------------------------- HSI
    private fun drawHsi(c: Canvas, d: FlightData) {
        c.save()
        c.clipRect(0f, 800f, 1000f, 1000f)
        p.circle(c, HX, HY, HR, PANEL_DARK)
        p.circle(c, HX, HY, HR, Col.WHITE, 2f)
        if (!d.attValid) {
            c.restore()
            return
        }
        val h = d.heading
        for (deg in 0 until 360 step 5) {
            val ang = angDiff(deg.toFloat(), h)
            if (abs(ang) > 60f) continue
            c.save()
            c.rotate(ang, HX, HY)
            val ten = deg % 10 == 0
            p.line(c, HX, HY - HR, HX, HY - HR + if (ten) 22f else 12f, Col.WHITE, 3f)
            if (deg % 30 == 0) {
                val lbl = when (deg) {
                    0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"
                    else -> (deg / 10).toString()
                }
                p.text(c, lbl, HX, HY - HR + 46, 34f, Col.WHITE)
            }
            c.restore()
        }
        if (d.hasTrack) {
            c.save()
            c.rotate(angDiff(d.track, h), HX, HY)
            p.poly(c, MAGENTA, Col.BLACK, 2f, HX, HY - HR + 4, HX + 9, HY - HR + 16, HX, HY - HR + 28, HX - 9, HY - HR + 16)
            c.restore()
        }
        // wskaźnik GPS (zielona strzałka kursu)
        p.poly(c, GREEN, Col.BLACK, 2f, HX, HY - HR + 30, HX - 16, HY - HR + 62, HX + 16, HY - HR + 62)
        p.line(c, HX, HY - HR + 62, HX, 1000f, GREEN, 6f)
        c.restore()

        // okno kursu
        p.poly(c, Col.WHITE, Col.BLACK, 2f, HX, HY - HR - 2, HX - 12, HY - HR - 20, HX + 12, HY - HR - 20)
        p.rect(c, HX - 62, 742f, HX + 62, 792f, Col.BLACK)
        p.frame(c, HX - 62, 742f, HX + 62, 792f, GREY, 2f)
        p.text(c, String.format(Locale.US, "%03d°", mod(h.roundToInt(), 360)), HX, 767f, 38f, Col.WHITE)
        p.text(c, if (d.headingMagnetic) "MH" else "REL", HX - 150, 900f, 28f, GREEN)
        if (d.hasTrack) p.text(c, String.format(Locale.US, "TRK %03d°", mod(d.track.roundToInt(), 360)), HX + 170, 900f, 28f, MAGENTA)
    }

    // ---------------------------------------------------------------- pasek trybów
    private fun drawFma(c: Canvas, d: FlightData) {
        p.fill.color = PANEL_DARK
        c.drawRoundRect(RectF(270f, 8f, 730f, 92f), 34f, 34f, p.fill)
        val flying = d.speedValid && d.speedKt > 30f
        p.text(c, if (d.hasTrack) "TRK" else "ROL", 360f, 34f, 32f, GREEN)
        p.text(c, "AP", 470f, 34f, 32f, GREY)
        p.text(c, "AT", 530f, 34f, 32f, GREY)
        val vert = when {
            !d.altValid -> "PIT"
            abs(d.vsFpm) >= 300f -> "VS"
            flying -> "ALT"
            else -> "PIT"
        }
        p.text(c, vert, 640f, 34f, 32f, GREEN)
        p.line(c, 420f, 18f, 420f, 50f, GREY, 2f)
        p.line(c, 580f, 18f, 580f, 50f, GREY, 2f)
        val pressureAlt = d.altFt + if (d.std) 0f else (1013.25f - d.qnh) * 27.3f
        val fl = (pressureAlt / 100f).roundToInt().coerceAtLeast(0)
        val src = if (d.gpsFix) "GPS" else "NO GPS"
        p.text(c, String.format(Locale.US, "%s | FL%03d", src, fl), CX, 70f, 28f, Col.WHITE)
    }

    // ---------------------------------------------------------------- wiatr i OAT
    private fun drawWindOat(c: Canvas, d: FlightData) {
        if (d.windValid) {
            p.rect(c, 15f, 815f, 205f, 885f, PANEL_DARK)
            c.save()
            c.rotate(d.windDir - d.heading + 180f, 50f, 850f)
            p.line(c, 50f, 875f, 50f, 825f, Col.WHITE, 4f)
            p.poly(c, Col.WHITE, 0, 0f, 50f, 878f, 41f, 860f, 59f, 860f)
            c.restore()
            p.text(c, String.format(Locale.US, "%03d°", mod(d.windDir.roundToInt(), 360)), 135f, 834f, 28f, Col.WHITE)
            p.text(c, d.windKt.roundToInt().toString() + "KT", 135f, 866f, 28f, Col.WHITE)
        }
        if (!d.oatC.isNaN()) {
            p.text(c, "OAT", 20f, 935f, 26f, Col.WHITE, Paint.Align.LEFT)
            p.text(c, d.oatC.roundToInt().toString() + "°C", 80f, 935f, 30f, Col.WHITE, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- baro
    private fun drawBaro(c: Canvas, d: FlightData) {
        p.rect(c, baroRect.left, baroRect.top, baroRect.right, baroRect.bottom, PANEL_DARK)
        val y = baroRect.centerY()
        if (d.std) {
            p.text(c, "STD BARO", baroRect.centerX(), y, 32f, CYAN)
        } else {
            val txt = if (d.inHg) String.format(Locale.US, "%.2fIN", d.qnh * 0.02953f)
            else String.format(Locale.US, "%.0fHPA", d.qnh)
            p.text(c, txt, baroRect.centerX(), y, 34f, CYAN)
        }
    }
}
