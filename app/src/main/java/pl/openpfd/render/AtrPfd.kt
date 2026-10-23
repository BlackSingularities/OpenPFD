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
 * PFD ATR 72-600 (Thales): prostokątny horyzont u góry, wąskie taśmy po bokach,
 * pełna róża kursowa (HSI) w dolnej połowie ekranu.
 */
class AtrPfd : PfdRenderer {
    private val p = Pen()

    private companion object {
        // horyzont
        const val ADI_L = 219f
        const val ADI_R = 717f
        const val ADI_T = 100f
        const val ADI_B = 573f
        const val CX = 463f
        const val CY = 340f
        const val PPD = 11f           // jednostek na stopień pochylenia
        const val BALL_R = 253f       // promień jaśniejszej „kuli” horyzontu

        // róża kursowa
        const val RX = 463f
        const val RY = 802f
        const val RR = 162f

        const val SKY = 0xFF2A4FA8.toInt()
        const val GROUND = 0xFF6E4A1E.toInt()
        const val BALL = 0x22FFFFFF
        const val GREEN = 0xFF27E327.toInt()
        const val CYAN = 0xFF2BD9F0.toInt()
        const val MAGENTA = 0xFFE54BD2.toInt()
        const val YELLOW = 0xFFFFE000.toInt()
        const val TAPE = 0xFF2A2A2A.toInt()
        const val GREY = 0xFF8C8C8C.toInt()
    }

    private val ballClip = Path().apply {
        addCircle(CX, CY, BALL_R, Path.Direction.CW)
        op(Path().apply { addRect(ADI_L, ADI_T, ADI_R, ADI_B, Path.Direction.CW) }, Path.Op.INTERSECT)
    }
    private val adiClip = Path().apply { addRect(ADI_L, ADI_T, ADI_R, ADI_B, Path.Direction.CW) }

    override val baroRect = RectF(640f, 560f, 860f, 620f)

    override fun draw(c: Canvas, d: FlightData) {
        drawFma(c, d)
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawHeadingLine(c, d)
        drawRose(c, d)
        drawSideInfo(c, d)
    }

    // ---------------------------------------------------------------- FMA
    private fun drawFma(c: Canvas, d: FlightData) {
        for (x in floatArrayOf(255f, 455f, 625f, 715f)) p.line(c, x, 6f, x, 56f, GREY, 2.5f)
        val y = 26f
        val sz = 28f
        val flying = d.speedValid && d.speedKt > 30f
        if (flying) p.text(c, if (d.hasTrack) "TRK HOLD" else "HDG HOLD", 352f, y, sz, GREEN)
        if (d.altValid) p.text(c, if (abs(d.vsFpm) >= 300f) "V/S" else "ALT", 614f, y, sz, GREEN)
        p.text(c, "AP", 748f, y, sz, GREEN)
        p.text(c, "|", 776f, y, sz, GREY)
        p.text(c, "FD", 804f, y, sz, GREEN)
        if (d.gpsFix) p.text(c, "←", 860f, y, sz, GREEN)
    }

    // ---------------------------------------------------------------- horyzont
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.rect(c, ADI_L, ADI_T, ADI_R, ADI_B, Col.BLACK)
            p.text(c, "ATT", CX, CY, 56f, Col.RED)
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

        // drabinka pochylenia: długie co 10°, krótsze co 5° i 2,5°
        val i0 = ceil((d.pitch - 24f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 24f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = CY - deg * PPD
            val half = when {
                i % 4 == 0 -> 62f
                i % 2 == 0 -> 34f
                else -> 16f
            }
            p.line(c, CX - half, y, CX + half, y, Col.WHITE, 3f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, CX - half - 30, y, 32f, Col.WHITE)
                p.text(c, lbl, CX + half + 30, y, 32f, Col.WHITE)
            }
        }
        c.restore()

        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, CX, CY)
            val dx = angDiff(d.track, d.heading).coerceIn(-18f, 18f) * PPD
            val dy = (d.fpa - d.pitch).coerceIn(-18f, 18f) * PPD
            bird(c, CX + dx, CY - dy, GREEN)
            c.restore()
        }
        c.restore()

        // przejaśniona „kula” horyzontu – charakterystyczna dla ATR
        c.save()
        c.clipPath(ballClip)
        p.rect(c, ADI_L, ADI_T, ADI_R, ADI_B, BALL)
        c.restore()

        drawRollScale(c, d)
        drawAircraft(c)

        // radiowysokościomierz w ramce pod horyzontem
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 50f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            val col = if (d.raFt < 200f) Col.AMBER else GREEN
            p.rect(c, CX - 42, ADI_B - 74, CX + 42, ADI_B - 24, Col.BLACK)
            p.frame(c, CX - 42, ADI_B - 74, CX + 42, ADI_B - 24, col, 2.5f)
            p.text(c, ra.toString(), CX, ADI_B - 49, 36f, col)
        }
    }

    private fun bird(c: Canvas, x: Float, y: Float, color: Int) {
        p.circle(c, x, y, 13f, color, 4f)
        p.line(c, x - 13, y, x - 40, y, color, 4f)
        p.line(c, x + 13, y, x + 40, y, color, 4f)
        p.line(c, x, y - 13, x, y - 28, color, 4f)
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        c.save()
        c.clipRect(ADI_L, ADI_T, ADI_R, ADI_B)
        for (a in intArrayOf(10, 20, 30, 45, 60)) {
            for (s in intArrayOf(-1, 1)) {
                val len = if (a == 30 || a == 60) 22f else 14f
                p.radial(c, CX, CY, (s * a).toFloat(), BALL_R - 24, BALL_R - 24 + len, Col.WHITE, 3f)
            }
        }
        // stały indeks zera
        p.poly(c, 0, Col.WHITE, 3f,
            CX, CY - BALL_R + 24, CX - 15, CY - BALL_R + 2, CX + 15, CY - BALL_R + 2)
        // ruchomy wskaźnik przechylenia ze ślizgiem
        c.save()
        c.rotate(-d.roll, CX, CY)
        val col = if (abs(d.roll) > 35f) Col.AMBER else YELLOW
        val top = CY - BALL_R + 28
        p.poly(c, col, col, 2f, CX, top, CX - 15, top + 24, CX + 15, top + 24)
        val off = (d.slip * 170f).coerceIn(-40f, 40f)
        val slipCol = if (abs(d.slip) > 0.2f) Col.AMBER else col
        p.poly(c, 0, slipCol, 3f,
            CX - 16 + off, top + 29, CX + 16 + off, top + 29,
            CX + 21 + off, top + 41, CX - 21 + off, top + 41)
        c.restore()
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        p.poly(c, Col.BLACK, YELLOW, 4f,
            CX - 218, CY - 7, CX - 118, CY - 7, CX - 118, CY + 32, CX - 104, CY + 32,
            CX - 104, CY + 7, CX - 218, CY + 7)
        p.poly(c, Col.BLACK, YELLOW, 4f,
            CX + 218, CY - 7, CX + 118, CY - 7, CX + 118, CY + 32, CX + 104, CY + 32,
            CX + 104, CY + 7, CX + 218, CY + 7)
        p.rect(c, CX - 8, CY - 8, CX + 8, CY + 8, Col.BLACK)
        p.frame(c, CX - 8, CY - 8, CX + 8, CY + 8, YELLOW, 4f)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 116f
        val r = 199f
        val t = 120f
        val b = 541f
        val k = 4.3f
        if (!d.speedValid) {
            p.text(c, "SPD", (l + r) / 2, CY, 44f, Col.RED)
            return
        }
        val s = d.speedKt
        p.rect(c, l, t, r, b, TAPE)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(s - 70f, 10).coerceAtLeast(0)
        while (v <= s + 70f) {
            val y = CY - (v - s) * k
            if (v % 20 == 0) {
                p.line(c, l + 4, y, l + 24, y, Col.WHITE, 3f)
                p.text(c, v.toString(), r - 8, y, 36f, Col.WHITE, Paint.Align.RIGHT)
            } else {
                p.line(c, l + 4, y, l + 16, y, Col.WHITE, 3f)
            }
            v += 10
        }
        // VMO – pas czerwono-biały przy krawędzi
        if (s + 70f > 250f) {
            var y = CY - (250f - s) * k
            var red = true
            while (y > t) {
                p.rect(c, r - 14, y - 12, r, y, if (red) Col.RED else Col.WHITE)
                y -= 12f
                red = !red
            }
        }
        // minimalna prędkość – magenta przy dolnej krawędzi
        val yMin = CY - (95f - s) * k
        if (yMin > t) p.rect(c, r - 10, yMin.coerceAtLeast(t), r, b, MAGENTA)
        c.restore()
        p.frame(c, l, t, r, b, GREY, 2.5f)

        // strzałka trendu prędkości
        val tr = d.speedTrend.coerceIn(-60f, 60f)
        if (abs(tr) > 2f) {
            val y2 = CY - tr * k
            val x = r + 34
            p.line(c, x, CY, x, y2, YELLOW, 4f)
            val dir = sign(tr)
            p.line(c, x, y2, x - 9, y2 + dir * 15, YELLOW, 4f)
            p.line(c, x, y2, x + 9, y2 + dir * 15, YELLOW, 4f)
        }
        // okno odczytu w żółtej ramce, wychodzące poza taśmę
        p.poly(c, Col.BLACK, YELLOW, 3.5f,
            l - 4, CY - 26, r + 10, CY - 26, r + 30, CY, r + 10, CY + 26, l - 4, CY + 26)
        p.text(c, s.roundToInt().toString(), r + 4, CY, 42f, GREEN, Paint.Align.RIGHT)
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 733f
        val r = 822f
        val t = 120f
        val b = 541f
        val k = 0.43f
        if (!d.altValid) {
            p.text(c, "ALT", (l + r) / 2, CY, 44f, Col.RED)
            return
        }
        val a = d.altFt
        p.rect(c, l, t, r, b, TAPE)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 800f, 100)
        while (v <= a + 800f) {
            val y = CY - (v - a) * k
            if (mod(v, 500) == 0) {
                p.line(c, l + 2, y, l + 20, y, Col.WHITE, 3f)
                val lbl = String.format(Locale.US, "%s%03d", if (v < 0) "-" else "", abs(v) / 100)
                p.text(c, lbl, l + 26, y, 34f, Col.WHITE, Paint.Align.LEFT)
            } else {
                p.line(c, l + 2, y, l + 14, y, Col.WHITE, 3f)
            }
            v += 100
        }
        if (!d.terrainFt.isNaN() && !d.std) {
            val yT = CY - (d.terrainFt - a) * k
            if (yT < b) {
                p.rect(c, l, yT.coerceAtLeast(t), l + 12, b, Col.RED)
                p.line(c, l, yT, r, yT, Col.RED, 3f)
            }
        }
        c.restore()
        p.frame(c, l, t, r, b, GREY, 2.5f)
        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 22, 26f, Col.AMBER)

        // okno odczytu: setki + bęben 20 ft, wchodzące na pole wariometru
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            l - 6, CY - 30, 905f, CY - 30, 905f, CY + 30, l - 6, CY + 30)
        val hundreds = floor(a / 100f).toInt()
        p.text(c, if (hundreds < 0) "-" + abs(hundreds) else hundreds.toString(),
            860f, CY, 44f, GREEN, Paint.Align.RIGHT)
        p.drum(c, 862f, CY - 28, 903f, CY + 28, a / 20f, 26f, 26f, GREEN, 883f, Paint.Align.CENTER) { kk ->
            String.format(Locale.US, "%02d", mod(kk * 20, 100))
        }
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val av = abs(v).coerceAtMost(6000f)
        val g = when {
            av <= 1000f -> av * 0.125f
            av <= 2000f -> 125f + (av - 1000f) * 0.055f
            else -> 180f + (av - 2000f) * 0.0125f
        }
        return CY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 826f
        val r = 905f
        p.rect(c, l, 120f, r, 541f, TAPE)
        p.frame(c, l, 120f, r, 541f, GREY, 2.5f)
        for (v in intArrayOf(500, 1000, 2000, 6000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val len = if (v == 500) 10f else 18f
                p.line(c, l + 2, y, l + 2 + len, y, Col.WHITE, 3f)
                if (v != 500) p.text(c, (v / 1000).toString(), l + 40, y, 26f, Col.WHITE)
            }
        }
        if (!d.altValid) {
            p.text(c, "V/S", (l + r) / 2, CY - 70, 28f, Col.RED)
            return
        }
        val vs = d.vsFpm
        val col = if (abs(vs) > 2000f) Col.AMBER else GREEN
        val tipY = vy(vs)
        c.save()
        c.clipRect(l, 122f, r, 539f)
        p.line(c, r + 30, CY, l + 4, tipY, col, 5f)
        c.restore()
    }

    // ---------------------------------------------------------------- kurs i baro
    private fun drawHeadingLine(c: Canvas, d: FlightData) {
        val y = 596f
        if (d.attValid) {
            p.text(c, "HDG", 222f, y, 30f, Col.WHITE, Paint.Align.LEFT)
            val h = mod(d.heading.roundToInt(), 360)
            p.text(c, String.format(Locale.US, "%03d°", h), 292f, y, 32f, CYAN, Paint.Align.LEFT)
            if (!d.headingMagnetic) p.text(c, "REL", 390f, y, 26f, Col.AMBER, Paint.Align.LEFT)
        } else {
            p.text(c, "HDG", 222f, y, 30f, Col.RED, Paint.Align.LEFT)
        }
        if (d.std) {
            p.text(c, "STD", 717f, y, 32f, CYAN, Paint.Align.RIGHT)
        } else {
            p.text(c, d.qnh.roundToInt().toString(), 700f, y, 30f, CYAN, Paint.Align.RIGHT)
            p.text(c, "/", 702f, y, 30f, Col.WHITE, Paint.Align.LEFT)
            p.text(c, String.format(Locale.US, "%.2f", d.qnh * 0.0295300f),
                716f, y, 30f, CYAN, Paint.Align.LEFT)
        }
    }

    // ---------------------------------------------------------------- róża kursowa
    private fun drawRose(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.circle(c, RX, RY, RR, GREY, 3f)
            p.text(c, "HDG", RX, RY, 44f, Col.RED)
            return
        }
        val h = d.heading
        // znacznik kursu bieżącego (lubber line)
        p.poly(c, Col.WHITE, Col.WHITE, 2f,
            RX, RY - RR - 4, RX - 12, RY - RR - 26, RX + 12, RY - RR - 26)
        c.save()
        c.rotate(-h, RX, RY)
        var deg = 0
        while (deg < 360) {
            val ten = deg % 10 == 0
            val len = if (ten) 26f else 14f
            p.radial(c, RX, RY, deg.toFloat(), RR - len, RR, Col.WHITE, if (ten) 3.5f else 2.5f)
            if (deg % 30 == 0) {
                val rad = Math.toRadians(deg.toDouble())
                val rr = RR - 48
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
                p.text(c, lbl, tx, ty, 30f, Col.WHITE)
                c.restore()
            }
            deg += 5
        }
        // kąt drogi (GPS) jako zielona linia przez różę
        if (d.hasTrack) {
            c.save()
            c.rotate(d.track, RX, RY)
            p.line(c, RX, RY - RR, RX, RY + RR, GREEN, 3f)
            p.circle(c, RX, RY - RR + 34, 12f, GREEN, 3f)
            c.restore()
        }
        // strzałka kierunku wiatru (magenta) – „skąd wieje”
        if (d.windValid) {
            c.save()
            c.rotate(d.windDir + 180f, RX, RY)
            p.poly(c, 0, MAGENTA, 3.5f,
                RX, RY - RR + 6, RX - 14, RY - RR + 34, RX - 5, RY - RR + 34,
                RX - 5, RY - 46, RX + 5, RY - 46, RX + 5, RY - RR + 34, RX + 14, RY - RR + 34)
            p.poly(c, 0, MAGENTA, 3.5f,
                RX - 5, RY + 46, RX + 5, RY + 46, RX + 5, RY + RR - 6, RX - 5, RY + RR - 6)
            c.restore()
        }
        c.restore()

        // skala odchylenia i symbol samolotu
        for (i in intArrayOf(-2, -1, 1, 2)) p.circle(c, RX + i * 46f, RY, 6f, Col.WHITE, 2.5f)
        p.line(c, RX - 60, RY, RX + 60, RY, YELLOW, 4f)
        p.line(c, RX, RY - 24, RX, RY + 28, YELLOW, 4f)
        p.line(c, RX - 20, RY + 22, RX + 20, RY + 22, YELLOW, 4f)
        for (s in intArrayOf(-1, 1)) p.line(c, RX + s * (RR + 6), RY, RX + s * (RR + 30), RY, Col.WHITE, 3f)
    }

    // ---------------------------------------------------------------- informacje boczne
    private fun drawSideInfo(c: Canvas, d: FlightData) {
        val xr = 985f
        p.text(c, "TCAS", xr, 566f, 26f, GREEN, Paint.Align.RIGHT)
        p.text(c, "ABOVE", xr, 594f, 26f, GREEN, Paint.Align.RIGHT)
        p.text(c, "XPDR", xr, 622f, 26f, GREEN, Paint.Align.RIGHT)
        p.text(c, "ALT", xr, 650f, 26f, GREEN, Paint.Align.RIGHT)

        p.text(c, if (d.gpsFix) "GPS1" else "FMS1", 720f, 700f, 26f, MAGENTA, Paint.Align.LEFT)
        p.line(c, 720f, 718f, 860f, 718f, MAGENTA, 2f)
        p.text(c, "TRK", 720f, 740f, 26f, Col.WHITE, Paint.Align.LEFT)
        p.text(c, if (d.hasTrack) String.format(Locale.US, "%03d°", mod(d.track.roundToInt(), 360)) else "---°",
            792f, 740f, 26f, GREEN, Paint.Align.LEFT)
        p.text(c, if (d.speedValid) "GS " + d.speedKt.roundToInt() else "GS ---",
            720f, 772f, 26f, GREEN, Paint.Align.LEFT)
        if (!d.tasKt.isNaN()) p.text(c, "TAS " + d.tasKt.roundToInt(), 720f, 800f, 26f, GREEN, Paint.Align.LEFT)
        if (d.windValid) {
            p.text(c, String.format(Locale.US, "%03d°/%d", mod(d.windDir.roundToInt(), 360), d.windKt.roundToInt()),
                720f, 828f, 26f, GREEN, Paint.Align.LEFT)
        }
        if (!d.oatC.isNaN()) p.text(c, "OAT " + d.oatC.roundToInt() + "°C", 720f, 856f, 26f, GREEN, Paint.Align.LEFT)

        p.text(c, if (d.raValid) "TERR" else "TERM", 30f, 868f, 28f, GREEN, Paint.Align.LEFT)
        p.frame(c, 24f, 928f, 258f, 990f, GREY, 2.5f)
        p.text(c, "GPS", 40f, 948f, 24f, GREEN, Paint.Align.LEFT)
        p.text(c, if (d.gpsFix) "±" + d.gpsAccuracy.roundToInt() + " m · " + d.sats + " SAT" else "szukanie…",
            40f, 974f, 24f, GREEN, Paint.Align.LEFT)
    }
}
