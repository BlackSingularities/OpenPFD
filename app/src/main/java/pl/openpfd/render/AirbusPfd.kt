package pl.openpfd.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import pl.openpfd.FlightData
import pl.openpfd.angDiff
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** Wariant kolorystyki i ograniczeń rodziny Airbus / Thales. */
data class AirbusVariant(
    val skyTop: Int, val sky: Int, val ground: Int, val groundBottom: Int,
    val tape: Int, val vsiBg: Int, val vmo: Float, val fd: String, val athr: String,
) {
    companion object {
        val A320 = AirbusVariant(0xFF1C9AEA.toInt(), 0xFF1C9AEA.toInt(), 0xFF8C4B1D.toInt(), 0xFF8C4B1D.toInt(),
            0xFF5D5D5D.toInt(), 0xFF3F3F3F.toInt(), 350f, "1 FD 2", "A/THR")
    }
}

/** PFD w stylu Airbusa (A320). */
class AirbusPfd(private val vr: AirbusVariant = AirbusVariant.A320) : PfdRenderer {
    private val p = Pen()

    private companion object {
        const val CX = 431f
        const val CY = 512f
        const val PPD = 10.5f          // jednostek na stopień pochylenia

        const val GREEN = 0xFF2BF22B.toInt()
        const val CYAN = 0xFF1FE6FF.toInt()
        const val YELLOW = 0xFFFFF200.toInt()
        const val GREY_LINE = 0xFFA0A0A0.toInt()
    }

    private val tape = vr.tape
    private val vsiBg = vr.vsiBg
    private val vmo = vr.vmo
    private val skyPaint = Paint().apply {
        shader = LinearGradient(0f, CY - 450f, 0f, CY, vr.skyTop, vr.sky, Shader.TileMode.CLAMP)
    }
    private val groundPaint = Paint().apply {
        shader = LinearGradient(0f, CY, 0f, CY + 450f, vr.ground, vr.groundBottom, Shader.TileMode.CLAMP)
    }

    private val attClip = Path().apply {
        addCircle(CX, CY, 292f, Path.Direction.CW)
        op(Path().apply { addRect(199f, 250f, 663f, 776f, Path.Direction.CW) }, Path.Op.INTERSECT)
    }

    override val baroRect = RectF(715f, 815f, 995f, 895f)

    override fun draw(c: Canvas, d: FlightData) {
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawHeading(c, d)
        drawFma(c, d)
        drawBaro(c, d)
        drawWind(c, d)
    }

    // ---------------------------------------------------------------- horyzont
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.text(c, "ATT", CX, CY - 60, 52f, Col.RED)
            drawAircraft(c)
            return
        }
        c.save()
        c.clipPath(attClip)
        c.save()
        c.rotate(-d.roll, CX, CY)
        c.translate(0f, d.pitch * PPD)
        c.drawRect(CX - 1000, CY - 3000, CX + 1000, CY, skyPaint)
        c.drawRect(CX - 1000, CY, CX + 1000, CY + 3000, groundPaint)
        p.line(c, CX - 1000, CY, CX + 1000, CY, Col.WHITE, 3f)

        // znaczniki kursu na linii horyzontu
        val h0 = floorTo(d.heading - 30, 10)
        var h = h0
        while (h <= d.heading + 30) {
            val x = CX + angDiff(h.toFloat(), d.heading) * PPD
            p.line(c, x, CY, x, CY + 14, Col.WHITE, 3f)
            h += 10
        }

        // drabinka pochylenia co 2,5°
        val i0 = ceil((d.pitch - 22f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 22f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = CY - deg * PPD
            val half = when {
                i % 4 == 0 -> 62f
                i % 2 == 0 -> 30f
                else -> 13f
            }
            p.line(c, CX - half, y, CX + half, y, Col.WHITE, 3.2f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, CX - half - 34, y, 36f, Col.WHITE)
                p.text(c, lbl, CX + half + 34, y, 36f, Col.WHITE)
            }
        }
        c.restore()

        // wektor toru lotu („bird”)
        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, CX, CY)
            val dx = angDiff(d.track, d.heading).coerceIn(-20f, 20f) * PPD
            val dy = ((d.fpa - d.pitch).coerceIn(-20f, 20f)) * PPD
            bird(c, CX + dx, CY - dy, GREEN)
            c.restore()
        }
        c.restore()

        drawRollScale(c, d)
        drawAircraft(c)
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 50f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            p.text(c, ra.toString(), CX, 728f, 48f, if (d.raFt < 200f) Col.AMBER else GREEN)
        }
    }

    private fun bird(c: Canvas, x: Float, y: Float, color: Int) {
        p.circle(c, x, y, 14f, color, 4f)
        p.line(c, x - 14, y, x - 42, y, color, 4f)
        p.line(c, x + 14, y, x + 42, y, color, 4f)
        p.line(c, x, y - 14, x, y - 30, color, 4f)
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        for (a in intArrayOf(10, 20, 30, 45)) {
            val r2 = if (a == 30) 324f else 310f
            p.radial(c, CX, CY, a.toFloat(), 293f, r2, Col.WHITE, 3.5f)
            p.radial(c, CX, CY, -a.toFloat(), 293f, r2, Col.WHITE, 3.5f)
        }
        // ograniczenie przechylenia 67° (znaczniki „=”)
        for (s in intArrayOf(-1, 1)) {
            p.radial(c, CX, CY, s * 65.5f, 292f, 308f, GREEN, 3f)
            p.radial(c, CX, CY, s * 68.5f, 292f, 308f, GREEN, 3f)
        }
        // stały indeks zera
        p.poly(c, 0, YELLOW, 3.5f, CX, CY - 293, CX - 15, CY - 318, CX + 15, CY - 318)

        // ruchomy wskaźnik przechylenia + indeks ślizgu (beta)
        c.save()
        c.rotate(-d.roll, CX, CY)
        val bankWarn = abs(d.roll) > 45f
        val col = if (bankWarn) Col.AMBER else YELLOW
        p.poly(c, 0, col, 3.5f, CX, CY - 290, CX - 15, CY - 266, CX + 15, CY - 266)
        val off = (d.slip * 180f).coerceIn(-45f, 45f)
        val slipCol = if (abs(d.slip) > 0.2f) Col.AMBER else col
        p.poly(c, 0, slipCol, 3.5f,
            CX - 17 + off, CY - 262, CX + 17 + off, CY - 262,
            CX + 22 + off, CY - 250, CX - 22 + off, CY - 250)
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        val y = CY
        p.poly(c, Col.BLACK, YELLOW, 4f,
            212f, y - 8, 312f, y - 8, 312f, y + 36, 297f, y + 36, 297f, y + 8, 212f, y + 8)
        p.poly(c, Col.BLACK, YELLOW, 4f,
            2 * CX - 212f, y - 8, 2 * CX - 312f, y - 8, 2 * CX - 312f, y + 36,
            2 * CX - 297f, y + 36, 2 * CX - 297f, y + 8, 2 * CX - 212f, y + 8)
        p.rect(c, CX - 9, y - 9, CX + 9, y + 9, Col.BLACK)
        p.frame(c, CX - 9, y - 9, CX + 9, y + 9, YELLOW, 4f)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 18f
        val r = 150f
        val t = 245f
        val b = 780f
        val k = 4.75f
        if (!d.speedValid) {
            p.text(c, "SPD", (l + r) / 2, CY, 50f, Col.RED)
            return
        }
        val s = d.speedKt
        p.rect(c, l, t, r, b, tape)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(s - 70f, 10).coerceAtLeast(0)
        while (v <= s + 70f) {
            val y = CY - (v - s) * k
            p.line(c, r - 20, y, r, y, Col.WHITE, 3.2f)
            if (v % 20 == 0) p.text(c, v.toString(), r - 28, y, 42f, Col.WHITE, Paint.Align.RIGHT)
            v += 10
        }
        // vmo – czerwono-czarny „barber pole”
        if (s + 70f > vmo) {
            var y = CY - (vmo - s) * k
            var red = true
            while (y > t) {
                p.rect(c, r - 14, y - 12, r, y, if (red) Col.RED else Col.BLACK)
                y -= 12f
                red = !red
            }
        }
        c.restore()
        p.line(c, l, t, r + 4, t, Col.WHITE, 3f)
        p.line(c, l, b, r + 4, b, Col.WHITE, 3f)
        p.line(c, r, t, r, b, Col.WHITE, 3f)

        // strzałka trendu prędkości (10 s)
        val tr = d.speedTrend.coerceIn(-55f, 55f)
        if (abs(tr) > 2f) {
            val y2 = CY - tr * k
            val x = r + 12
            p.line(c, x, CY, x, y2, YELLOW, 4f)
            val dir = sign(tr)
            p.line(c, x, y2, x - 10, y2 + dir * 16, YELLOW, 4f)
            p.line(c, x, y2, x + 10, y2 + dir * 16, YELLOW, 4f)
        }
        // linia odniesienia
        p.line(c, l, CY, r + 2, CY, YELLOW, 6f)
        p.poly(c, YELLOW, YELLOW, 1f, r, CY, r + 24, CY - 14, r + 24, CY + 14)
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 745f
        val r = 862f
        val t = 245f
        val b = 780f
        val k = 0.475f
        if (!d.altValid) {
            p.text(c, "ALT", (l + r) / 2, CY, 50f, Col.RED)
            return
        }
        val a = d.altFt
        p.rect(c, l, t, r, b, tape)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 700f, 100)
        while (v <= a + 700f) {
            val y = CY - (v - a) * k
            p.line(c, r - 18, y, r, y, Col.WHITE, 3.2f)
            if (mod(v, 500) == 0) {
                val lbl = (if (v < 0) "-" else "") + String.format(Locale.US, "%03d", abs(v) / 100)
                p.text(c, lbl, l + 12, y, 40f, Col.WHITE, Paint.Align.LEFT)
            }
            v += 100
        }
        if (!d.terrainFt.isNaN() && !d.std) {
            val yT = CY - (d.terrainFt - a) * k
            if (yT < b) {
                p.rect(c, r - 16, yT.coerceAtLeast(t), r, b, Col.RED)
                p.line(c, l, yT, r, yT, Col.RED, 3f)
            }
        }
        c.restore()
        p.line(c, l - 4, t, r, t, Col.WHITE, 3f)
        p.line(c, l - 4, b, r, b, Col.WHITE, 3f)
        p.line(c, r, t, r, b, Col.WHITE, 3f)

        // okno odczytu: setki + bęben 20 ft
        p.poly(c, Col.BLACK, YELLOW, 4f,
            l + 2, CY - 30, 822f, CY - 30, 822f, CY - 50, 878f, CY - 50,
            878f, CY + 50, 822f, CY + 50, 822f, CY + 30, l + 2, CY + 30)
        val hundreds = floor(a / 100f).toInt()
        val big = if (hundreds < 0) "-" + abs(hundreds) else hundreds.toString()
        p.text(c, big, 818f, CY, 56f, GREEN, Paint.Align.RIGHT)
        p.drum(c, 824f, CY - 48, 876f, CY + 48, a / 20f, 38f, 36f, GREEN, 850f, Paint.Align.CENTER) { kk ->
            String.format(Locale.US, "%02d", mod(kk * 20, 100))
        }
        // źródło wysokości (bez barometru – GPS)
        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 22, 30f, Col.AMBER)
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val a = abs(v).coerceAtMost(6000f)
        val g = when {
            a <= 1000f -> a * 0.15f
            a <= 2000f -> 150f + (a - 1000f) * 0.07f
            else -> 220f + (a - 2000f) * 0.0175f
        }
        return CY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 900f
        val r = 992f
        p.poly(c, vsiBg, 0, 0f, l, 280f, l + 32, 208f, r, 208f, r, 816f, l + 32, 816f, l, 744f)
        for (v in intArrayOf(500, 1000, 1500, 2000, 6000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val len = if (v == 500 || v == 1500) 10f else 18f
                p.line(c, l + 34, y, l + 34 + len, y, Col.WHITE, 3f)
                if (v == 1000 || v == 2000 || v == 6000) {
                    p.text(c, (v / 1000).toString(), l + 18, y, 30f, Col.WHITE)
                }
            }
        }
        p.line(c, l + 30, CY, l + 58, CY, YELLOW, 5f)
        if (!d.altValid) {
            p.text(c, "V/S", (l + r) / 2 + 8, CY - 80, 34f, Col.RED)
            return
        }
        val vs = d.vsFpm
        val col = if (abs(vs) > 6000f) Col.AMBER else GREEN
        val tipY = vy(vs)
        c.save()
        c.clipRect(l + 34, 205f, r, 820f)
        p.line(c, 1110f, CY, l + 34, tipY, col, 5f)
        c.restore()
        if (abs(vs) >= 200f) {
            val txt = String.format(Locale.US, "%02d", (abs(vs) / 100f).roundToInt())
            val ty = (tipY + if (vs > 0) -30f else 30f).coerceIn(230f, 795f)
            p.rect(c, l + 40, ty - 18, l + 84, ty + 18, Col.BLACK)
            p.text(c, txt, l + 62, ty, 32f, col)
        }
    }

    // ---------------------------------------------------------------- kurs
    private fun drawHeading(c: Canvas, d: FlightData) {
        val l = 199f
        val r = 663f
        val t = 902f
        val b = 982f
        val k = 10.3f
        if (!d.attValid) {
            p.text(c, "HDG", CX, (t + b) / 2, 46f, Col.RED)
            return
        }
        val h = d.heading
        p.rect(c, l, t, r, b, tape)
        c.save()
        c.clipRect(l, t, r, b)
        var deg = floorTo(h - 28f, 5)
        while (deg <= h + 28f) {
            val x = CX + (deg - h) * k
            val ten = mod(deg, 10) == 0
            p.line(c, x, t, x, t + if (ten) 22f else 12f, Col.WHITE, 3f)
            if (ten) {
                val n = mod(deg, 360) / 10
                val big = n % 3 == 0
                p.text(c, n.toString(), x, t + 50, if (big) 42f else 32f, Col.WHITE)
            }
            deg += 5
        }
        c.restore()
        p.line(c, l, t, r, t, Col.WHITE, 3f)
        // znacznik kąta drogi (GPS)
        if (d.hasTrack) {
            val dx = angDiff(d.track, h)
            if (abs(dx) < 22f) {
                val x = CX + dx * k
                p.poly(c, 0, GREEN, 3.5f, x, t + 2, x + 10, t + 14, x, t + 26, x - 10, t + 14)
            }
        }
        p.line(c, CX, t - 34, CX, t + 18, YELLOW, 6f)
        if (!d.headingMagnetic) p.text(c, "HDG REL", r + 70, (t + b) / 2, 28f, Col.AMBER)
    }

    // ---------------------------------------------------------------- FMA
    private fun drawFma(c: Canvas, d: FlightData) {
        for (x in floatArrayOf(205f, 410f, 615f, 805f)) p.line(c, x, 8f, x, 182f, GREY_LINE, 3f)
        val y1 = 40f
        val y2 = 88f
        val sz = 38f
        val flying = d.speedValid && d.speedKt > 30f
        if (flying) p.text(c, "SPEED", 102f, y1, sz, GREEN)
        if (d.altValid && abs(d.vsFpm) >= 300f) {
            val v = ((d.vsFpm / 100f).roundToInt() * 100)
            val vs = (if (v > 0) "+" else "") + v
            p.txt.textSize = sz
            val w1 = p.width("V/S ", sz)
            val w2 = p.width(vs, sz)
            val x0 = 307f - (w1 + w2) / 2
            p.text(c, "V/S ", x0, y1, sz, GREEN, Paint.Align.LEFT)
            p.text(c, vs, x0 + w1, y1, sz, CYAN, Paint.Align.LEFT)
        } else if (flying) {
            p.text(c, "ALT", 307f, y1, sz, GREEN)
        }
        if (d.hasTrack) p.text(c, "TRACK", 512f, y1, sz, GREEN)
        else if (flying) p.text(c, "HDG", 512f, y1, sz, GREEN)
        if (d.gpsFix) {
            p.text(c, "GPS", 710f, y1, sz, Col.WHITE)
            p.text(c, "PRIMARY", 710f, y2, sz, Col.WHITE)
        }
        p.text(c, vr.fd, 902f, y2, sz, Col.WHITE)
        if (flying && vr.athr.isNotEmpty()) p.text(c, vr.athr, 902f, 136f, sz, Col.WHITE)
    }

    // ---------------------------------------------------------------- GS / TAS / wiatr
    private fun drawWind(c: Canvas, d: FlightData) {
        val x = 18f
        if (d.speedValid) p.text(c, "GS " + d.speedKt.roundToInt(), x, 830f, 32f, Col.WHITE, Paint.Align.LEFT)
        if (!d.tasKt.isNaN()) p.text(c, "TAS " + d.tasKt.roundToInt(), x, 870f, 32f, Col.WHITE, Paint.Align.LEFT)
        if (d.windValid) {
            val txt = String.format(Locale.US, "%03d°/%d", mod(d.windDir.roundToInt(), 360), d.windKt.roundToInt())
            p.text(c, txt, x, 915f, 32f, GREEN, Paint.Align.LEFT)
            c.save()
            c.rotate(d.windDir - d.heading + 180f, 172f, 915f)
            p.line(c, 172f, 935f, 172f, 895f, GREEN, 4f)
            p.poly(c, GREEN, 0, 0f, 172f, 935f, 164f, 920f, 180f, 920f)
            c.restore()
        }
        if (d.hasTrack && abs(d.drift) >= 1f) {
            val side = if (d.drift > 0) "R" else "L"
            p.text(c, "DRIFT " + abs(d.drift).roundToInt() + "°" + side, x, 958f, 28f, CYAN, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- baro
    private fun drawBaro(c: Canvas, d: FlightData) {
        val y = 855f
        if (d.std) {
            p.frame(c, 770f, y - 26, 860f, y + 26, YELLOW, 3f)
            p.text(c, "STD", 815f, y, 42f, CYAN)
        } else {
            p.text(c, "QNH", 735f, y, 36f, Col.WHITE, Paint.Align.LEFT)
            p.text(c, baroText(d), 990f, y, 42f, CYAN, Paint.Align.RIGHT)
        }
    }
}
