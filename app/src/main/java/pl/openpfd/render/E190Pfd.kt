package pl.openpfd.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import pl.openpfd.FlightData
import pl.openpfd.angDiff
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * PFD Embraera E-Jet (Honeywell Primus Epic): czarne tło, wąskie taśmy,
 * kwadratowy horyzont u góry i pełna róża kursowa z polami danych u dołu.
 */
class E190Pfd : PfdRenderer {
    private val p = Pen()

    private companion object {
        const val ADI_L = 155f
        const val ADI_R = 645f
        const val ADI_T = 96f
        const val ADI_B = 520f
        const val CX = 400f
        const val CY = 308f
        const val PPD = 9.4f

        const val RX = 400f
        const val RY = 790f
        const val RR = 168f

        const val SKY = 0xFF1B8FE0.toInt()
        const val GROUND = 0xFF7A4A1E.toInt()
        const val TAPE = 0xFF1C1E22.toInt()
        const val GREEN = 0xFF35E635.toInt()
        const val CYAN = 0xFF2FD8F2.toInt()
        const val MAGENTA = 0xFFE85AD6.toInt()
        const val GREY = 0xFF8E8E8E.toInt()
    }

    private val adiClip = Path().apply {
        addRoundRect(RectF(ADI_L, ADI_T, ADI_R, ADI_B), 16f, 16f, Path.Direction.CW)
    }

    override val vw = 800f

    override val baroRect = RectF(600f, 480f, 800f, 548f)

    override fun draw(c: Canvas, d: FlightData) {
        drawFma(c, d)
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawBaro(c, d)
        drawDataLine(c, d)
        drawRose(c, d)
    }

    // ---------------------------------------------------------------- FMA
    private fun drawFma(c: Canvas, d: FlightData) {
        val y = 34f
        val sz = 30f
        val flying = d.speedValid && d.speedKt > 30f
        p.frame(c, 155f, 10f, 645f, 60f, GREY, 2f)
        p.line(c, 277f, 12f, 277f, 58f, GREY, 2f)
        p.line(c, 400f, 12f, 400f, 58f, GREY, 2f)
        p.line(c, 522f, 12f, 522f, 58f, GREY, 2f)
        if (flying) p.text(c, "SPD", 216f, y, sz, GREEN)
        p.text(c, "AP", 338f, y, sz, GREEN)
        p.text(c, if (d.hasTrack) "TRK" else "HDG", 461f, y, sz, GREEN)
        if (d.altValid) p.text(c, if (abs(d.vsFpm) >= 300f) "V/S" else "ALT", 583f, y, sz, GREEN)
    }

    // ---------------------------------------------------------------- horyzont
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.rect(c, ADI_L, ADI_T, ADI_R, ADI_B, Col.BLACK)
            p.frame(c, ADI_L, ADI_T, ADI_R, ADI_B, Col.AMBER, 3f)
            p.text(c, "ATT", CX, CY, 48f, Col.AMBER)
            drawAircraft(c)
            return
        }
        c.save()
        c.clipPath(adiClip)
        c.save()
        c.rotate(-d.roll, CX, CY)
        c.translate(0f, d.pitch * PPD)
        p.rect(c, CX - 1200, CY - 3000, CX + 1200, CY, SKY)
        p.rect(c, CX - 1200, CY, CX + 1200, CY + 3000, GROUND)
        p.line(c, CX - 1200, CY, CX + 1200, CY, Col.WHITE, 3f)

        val i0 = ceil((d.pitch - 22f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 22f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = CY - deg * PPD
            val half = when {
                i % 4 == 0 -> 88f
                i % 2 == 0 -> 44f
                else -> 20f
            }
            p.line(c, CX - half, y, CX + half, y, Col.WHITE, 3f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, CX - half - 26, y, 30f, Col.WHITE)
                p.text(c, lbl, CX + half + 26, y, 30f, Col.WHITE)
            }
        }
        c.restore()

        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, CX, CY)
            val dx = angDiff(d.track, d.heading).coerceIn(-16f, 16f) * PPD
            val dy = (d.fpa - d.pitch).coerceIn(-16f, 16f) * PPD
            p.circle(c, CX + dx, CY - dy, 12f, GREEN, 3.5f)
            p.line(c, CX + dx - 12, CY - dy, CX + dx - 34, CY - dy, GREEN, 3.5f)
            p.line(c, CX + dx + 12, CY - dy, CX + dx + 34, CY - dy, GREEN, 3.5f)
            p.line(c, CX + dx, CY - dy - 12, CX + dx, CY - dy - 26, GREEN, 3.5f)
            c.restore()
        }
        c.restore()

        drawPitchChevrons(c, d)
        drawRollScale(c, d)
        drawAircraft(c)
        p.frame(c, ADI_L, ADI_T, ADI_R, ADI_B, GREY, 2f)

        // radiowysokościomierz pod horyzontem
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 50f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            p.text(c, ra.toString(), CX, ADI_B - 42, 40f, if (d.raFt < 200f) Col.AMBER else GREEN)
        }
    }

    /** Czerwone chevrony wskazujące najkrótszą drogę do horyzontu. */
    private fun drawPitchChevrons(c: Canvas, d: FlightData) {
        if (d.pitch <= 30f && d.pitch >= -20f) return
        val dir = if (d.pitch > 0f) 1f else -1f
        c.save()
        c.clipPath(adiClip)
        c.rotate(-d.roll, CX, CY)
        for (k in 1..2) {
            val y = CY + dir * (70f + k * 62f)
            p.line(c, CX - 66, y + dir * 42, CX, y, Col.RED, 6f)
            p.line(c, CX, y, CX + 66, y + dir * 42, Col.RED, 6f)
        }
        c.restore()
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        c.save()
        c.clipRect(ADI_L, ADI_T, ADI_R, ADI_B)
        val r = 196f
        for (a in intArrayOf(10, 20, 30, 45, 60)) {
            for (s in intArrayOf(-1, 1)) {
                val len = if (a == 30 || a == 60) 22f else 14f
                p.radial(c, CX, CY, (s * a).toFloat(), r, r + len, Col.WHITE, 3f)
            }
        }
        p.poly(c, 0, Col.WHITE, 3f, CX, CY - r, CX - 14, CY - r - 24, CX + 14, CY - r - 24)
        c.save()
        c.rotate(-d.roll, CX, CY)
        val col = if (abs(d.roll) > 35f) Col.AMBER else Col.WHITE
        p.poly(c, col, col, 2f, CX, CY - r + 4, CX - 14, CY - r + 28, CX + 14, CY - r + 28)
        val off = (d.slip * 160f).coerceIn(-40f, 40f)
        p.poly(c, 0, if (abs(d.slip) > 0.2f) Col.AMBER else col, 3f,
            CX - 15 + off, CY - r + 33, CX + 15 + off, CY - r + 33,
            CX + 20 + off, CY - r + 45, CX - 20 + off, CY - r + 45)
        c.restore()
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        p.poly(c, Col.BLACK, Col.WHITE, 3.5f,
            CX - 178, CY - 6, CX - 86, CY - 6, CX - 86, CY + 30, CX - 70, CY + 30,
            CX - 70, CY + 8, CX - 178, CY + 8)
        p.poly(c, Col.BLACK, Col.WHITE, 3.5f,
            CX + 178, CY - 6, CX + 86, CY - 6, CX + 86, CY + 30, CX + 70, CY + 30,
            CX + 70, CY + 8, CX + 178, CY + 8)
        p.rect(c, CX - 7, CY - 7, CX + 7, CY + 7, Col.BLACK)
        p.frame(c, CX - 7, CY - 7, CX + 7, CY + 7, Col.WHITE, 3.5f)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 36f
        val r = 140f
        val t = 110f
        val b = 506f
        val k = 4.6f
        if (!d.speedValid) {
            p.text(c, "SPD", (l + r) / 2, CY, 42f, Col.AMBER)
            return
        }
        val s = d.speedKt
        p.rect(c, l, t, r, b, TAPE)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(s - 60f, 10).coerceAtLeast(0)
        while (v <= s + 60f) {
            val y = CY - (v - s) * k
            if (v % 20 == 0) {
                p.line(c, r - 20, y, r, y, Col.WHITE, 3f)
                p.text(c, v.toString(), r - 28, y, 38f, Col.WHITE, Paint.Align.RIGHT)
            } else {
                p.line(c, r - 12, y, r, y, Col.WHITE, 3f)
            }
            v += 10
        }
        if (s + 60f > 320f) {
            var y = CY - (320f - s) * k
            var red = true
            while (y > t) {
                p.rect(c, r - 12, y - 12, r, y, if (red) Col.RED else Col.BLACK)
                y -= 12f
                red = !red
            }
        }
        val yMin = CY - (105f - s) * k
        if (yMin > t) p.rect(c, r - 10, yMin.coerceAtLeast(t), r, b, Col.AMBER)
        c.restore()
        p.frame(c, l, t, r, b, GREY, 2f)

        val tr = d.speedTrend.coerceIn(-50f, 50f)
        if (abs(tr) > 2f) {
            val y2 = CY - tr * k
            p.line(c, r + 12, CY, r + 12, y2, CYAN, 4f)
            val dir = sign(tr)
            p.line(c, r + 12, y2, r + 3, y2 + dir * 14, CYAN, 4f)
            p.line(c, r + 12, y2, r + 21, y2 + dir * 14, CYAN, 4f)
        }
        // okno odczytu
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            l - 16, CY - 30, r + 2, CY - 30, r + 2, CY - 14, r + 24, CY,
            r + 2, CY + 14, r + 2, CY + 30, l - 16, CY + 30)
        p.text(c, s.roundToInt().toString(), r - 4, CY, 44f, Col.WHITE, Paint.Align.RIGHT)
        // liczba Macha pod taśmą (orientacyjnie z TAS)
        if (!d.tasKt.isNaN() && d.tasKt > 60f) {
            p.text(c, String.format(Locale.US, ".%03d", (d.tasKt / 600f * 1000).roundToInt().coerceAtMost(999)),
                l, b + 34, 32f, Col.WHITE, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 648f
        val r = 742f
        val t = 110f
        val b = 506f
        val k = 0.42f
        if (!d.altValid) {
            p.text(c, "ALT", (l + r) / 2, CY, 42f, Col.AMBER)
            return
        }
        val a = d.altFt
        p.rect(c, l, t, r, b, TAPE)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 700f, 100)
        while (v <= a + 700f) {
            val y = CY - (v - a) * k
            p.line(c, l, y, l + 16, y, Col.WHITE, 3f)
            if (mod(v, 500) == 0) p.text(c, v.toString(), l + 24, y, 32f, Col.WHITE, Paint.Align.LEFT)
            v += 100
        }
        if (!d.terrainFt.isNaN() && !d.std) {
            val yT = CY - (d.terrainFt - a) * k
            if (yT < b) {
                p.rect(c, l, yT.coerceAtLeast(t), l + 10, b, Col.RED)
                p.line(c, l, yT, r, yT, Col.RED, 3f)
            }
        }
        c.restore()
        p.frame(c, l, t, r, b, GREY, 2f)
        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 24, 26f, Col.AMBER)

        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            l - 20, CY, l - 2, CY - 14, l - 2, CY - 32, r + 14, CY - 32,
            r + 14, CY + 32, l - 2, CY + 32, l - 2, CY + 14)
        val hundreds = floor(a / 100f).toInt()
        p.text(c, if (hundreds < 0) "-" + abs(hundreds) else hundreds.toString(),
            l + 62, CY, 44f, Col.WHITE, Paint.Align.RIGHT)
        p.drum(c, l + 64, CY - 30, r + 12, CY + 30, a / 20f, 26f, 28f, Col.WHITE, l + 84,
            Paint.Align.LEFT) { kk -> String.format(Locale.US, "%02d", mod(kk * 20, 100)) }
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val av = abs(v).coerceAtMost(6000f)
        val g = when {
            av <= 1000f -> av * 0.12f
            av <= 2000f -> 120f + (av - 1000f) * 0.055f
            else -> 175f + (av - 2000f) * 0.0115f
        }
        return CY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 748f
        p.line(c, l, 120f, l, 500f, GREY, 2f)
        for (v in intArrayOf(500, 1000, 2000, 6000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val len = if (v == 500) 10f else 20f
                p.line(c, l, y, l + len, y, Col.WHITE, 3f)
                if (v != 500) p.text(c, (v / 1000).toString(), l + 40, y, 26f, Col.WHITE)
            }
        }
        if (!d.altValid) return
        val vs = d.vsFpm
        val col = if (abs(vs) > 2000f) Col.AMBER else GREEN
        c.save()
        c.clipRect(l - 40, 115f, 800f, 505f)
        p.line(c, l - 40, CY, l + 30, vy(vs), col, 5f)
        c.restore()
    }

    // ---------------------------------------------------------------- baro
    private fun drawBaro(c: Canvas, d: FlightData) {
        val y = 514f
        p.text(c, "BARO", 626f, y, 26f, CYAN, Paint.Align.LEFT)
        if (d.std) {
            p.text(c, "STD", 626f, y + 34, 34f, GREEN, Paint.Align.LEFT)
        } else {
            p.text(c, baroText(d), 626f, y + 34, 34f, GREEN, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- pola danych
    private fun drawDataLine(c: Canvas, d: FlightData) {
        val y = 566f
        p.text(c, "GSPD", 24f, y, 26f, CYAN, Paint.Align.LEFT)
        p.text(c, if (d.speedValid) d.speedKt.roundToInt().toString() + " KT" else "--- KT",
            24f, y + 34, 30f, GREEN, Paint.Align.LEFT)
        p.text(c, "HDG", 200f, y, 26f, CYAN, Paint.Align.LEFT)
        p.text(c, if (d.attValid) String.format(Locale.US, "%03d", mod(d.heading.roundToInt(), 360)) else "---",
            200f, y + 34, 30f, GREEN, Paint.Align.LEFT)
        p.text(c, if (d.headingMagnetic) "MAG" else "REL", 268f, y + 34, 24f,
            if (d.headingMagnetic) CYAN else Col.AMBER, Paint.Align.LEFT)
        p.text(c, if (d.gpsFix) "GPS1" else "FMS1", 24f, y + 76, 26f, MAGENTA, Paint.Align.LEFT)

        // kąt drogi i tor nad różą
        p.text(c, "TRK", 580f, y, 26f, CYAN, Paint.Align.LEFT)
        p.text(c, if (d.hasTrack) String.format(Locale.US, "%03d", mod(d.track.roundToInt(), 360)) else "---",
            580f, y + 34, 30f, MAGENTA, Paint.Align.LEFT)
        if (!d.tasKt.isNaN()) {
            p.text(c, "TAS", 690f, y, 26f, CYAN, Paint.Align.LEFT)
            p.text(c, d.tasKt.roundToInt().toString(), 690f, y + 34, 30f, GREEN, Paint.Align.LEFT)
        }
        if (!d.oatC.isNaN()) {
            p.text(c, "SAT", 690f, y + 76, 26f, CYAN, Paint.Align.LEFT)
            p.text(c, d.oatC.roundToInt().toString() + "°C", 690f, y + 110, 28f, GREEN, Paint.Align.LEFT)
        }
        p.text(c, if (d.gpsFix) "GPS ±" + d.gpsAccuracy.roundToInt() + " m · " + d.sats + " SAT" else "GPS szukanie…",
            24f, 974f, 26f, GREEN, Paint.Align.LEFT)
    }

    // ---------------------------------------------------------------- róża kursowa
    private fun drawRose(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.circle(c, RX, RY, RR, GREY, 3f)
            p.text(c, "HDG", RX, RY, 42f, Col.AMBER)
            return
        }
        val h = d.heading
        p.poly(c, MAGENTA, MAGENTA, 2f,
            RX, RY - RR + 2, RX - 13, RY - RR - 24, RX + 13, RY - RR - 24)
        c.save()
        c.rotate(-h, RX, RY)
        var deg = 0
        while (deg < 360) {
            val ten = deg % 10 == 0
            p.radial(c, RX, RY, deg.toFloat(), RR - if (ten) 24f else 13f, RR, Col.WHITE,
                if (ten) 3.5f else 2.5f)
            if (deg % 30 == 0) {
                val rad = Math.toRadians(deg.toDouble())
                val rr = RR - 46
                val tx = RX + (rr * sin(rad)).toFloat()
                val ty = RY - (rr * cos(rad)).toFloat()
                val lbl = when (deg) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> (deg / 10).toString()
                }
                c.save()
                c.rotate(deg.toFloat(), tx, ty)
                p.text(c, lbl, tx, ty, 28f, Col.WHITE)
                c.restore()
            }
            deg += 5
        }
        // kąt drogi z GPS jako magentowa linia
        if (d.hasTrack) {
            c.save()
            c.rotate(d.track, RX, RY)
            p.line(c, RX, RY - RR, RX, RY + RR, MAGENTA, 3f)
            c.restore()
        }
        c.restore()
        // symbol samolotu
        p.poly(c, 0, Col.WHITE, 3.5f, RX, RY - 26, RX - 22, RY + 16, RX + 22, RY + 16)
        p.line(c, RX, RY + 16, RX, RY + 28, Col.WHITE, 3.5f)
    }
}
