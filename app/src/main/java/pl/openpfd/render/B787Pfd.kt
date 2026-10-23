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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * PFD Boeinga 787 (Rockwell Collins): horyzont na pełną szerokość, półprzezroczyste
 * taśmy nałożone na niebo i ziemię, łukowa róża kursowa na czarnym tle u dołu.
 */
class B787Pfd : PfdRenderer {
    private val p = Pen()

    private companion object {
        const val ADI_T = 62f
        const val ADI_B = 600f
        const val CX = 500f
        const val CY = 318f
        const val PPD = 9.2f

        // róża kursowa (łuk)
        const val RCX = 500f
        const val RCY = 980f
        const val RAD = 300f

        const val SKY_TOP = 0xFF0A62C8.toInt()
        const val SKY = 0xFF1E90E8.toInt()
        const val GROUND = 0xFF8A4A18.toInt()
        const val GROUND_BOT = 0xFF5A2E0C.toInt()
        const val TAPE = 0x99303338.toInt()
        const val FMA_BG = 0xFF26282C.toInt()
        const val GREEN = 0xFF3EEA4C.toInt()
        const val CYAN = 0xFF33DDFF.toInt()
        const val MAGENTA = 0xFFEE55DD.toInt()
        const val GREY = 0xFF9A9A9A.toInt()
    }

    private val skyPaint = Paint().apply {
        shader = LinearGradient(0f, ADI_T, 0f, CY, SKY_TOP, SKY, Shader.TileMode.CLAMP)
    }
    private val groundPaint = Paint().apply {
        shader = LinearGradient(0f, CY, 0f, ADI_B, GROUND, GROUND_BOT, Shader.TileMode.CLAMP)
    }
    private val adiClip = Path().apply { addRect(0f, ADI_T, 1000f, ADI_B, Path.Direction.CW) }

    override val baroRect = RectF(780f, 612f, 1000f, 672f)

    override fun draw(c: Canvas, d: FlightData) {
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawFma(c, d)
        drawBelowAdi(c, d)
        drawArc(c, d)
    }

    // ---------------------------------------------------------------- ADI
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.rect(c, 0f, ADI_T, 1000f, ADI_B, Col.BLACK)
            p.frame(c, CX - 56, CY - 30, CX + 56, CY + 30, Col.AMBER, 3f)
            p.text(c, "ATT", CX, CY, 44f, Col.AMBER)
            drawAircraft(c)
            return
        }
        c.save()
        c.clipPath(adiClip)
        c.save()
        c.rotate(-d.roll, CX, CY)
        c.translate(0f, d.pitch * PPD)
        c.drawRect(CX - 1400, CY - 3000, CX + 1400, CY, skyPaint)
        c.drawRect(CX - 1400, CY, CX + 1400, CY + 3000, groundPaint)
        p.line(c, CX - 1400, CY, CX + 1400, CY, Col.WHITE, 3f)

        // drabinka pochylenia – widoczna tylko w oknie środkowym
        c.save()
        c.clipRect(CX - 210, CY - 230 - d.pitch * PPD, CX + 210, CY + 210 - d.pitch * PPD)
        val i0 = ceil((d.pitch - 24f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 24f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = CY - deg * PPD
            val half = when {
                i % 4 == 0 -> 96f
                i % 2 == 0 -> 46f
                else -> 22f
            }
            p.line(c, CX - half, y, CX + half, y, Col.WHITE, 3f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, CX - half - 28, y, 30f, Col.WHITE)
                p.text(c, lbl, CX + half + 28, y, 30f, Col.WHITE)
            }
        }
        c.restore()
        c.restore()

        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, CX, CY)
            val dx = angDiff(d.track, d.heading).coerceIn(-18f, 18f) * PPD
            val dy = (d.fpa - d.pitch).coerceIn(-18f, 18f) * PPD
            bird(c, CX + dx, CY - dy, Col.WHITE)
            c.restore()
        }
        c.restore()

        drawRollScale(c, d)
        drawAircraft(c)
    }

    private fun bird(c: Canvas, x: Float, y: Float, color: Int) {
        p.circle(c, x, y, 13f, color, 3.5f)
        p.line(c, x - 13, y, x - 38, y, color, 3.5f)
        p.line(c, x + 13, y, x + 38, y, color, 3.5f)
        p.line(c, x, y - 13, x, y - 28, color, 3.5f)
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        c.save()
        c.clipRect(0f, ADI_T, 1000f, ADI_B)
        val r = 232f
        for (a in intArrayOf(10, 20, 30, 45, 60)) {
            for (s in intArrayOf(-1, 1)) {
                val len = if (a == 30 || a == 60) 26f else 16f
                p.radial(c, CX, CY, (s * a).toFloat(), r, r + len, Col.WHITE, 3f)
            }
        }
        p.poly(c, 0, Col.WHITE, 3f, CX, CY - r, CX - 16, CY - r - 26, CX + 16, CY - r - 26)
        // wskaźnik przechylenia i ślizgu
        c.save()
        c.rotate(-d.roll, CX, CY)
        val col = if (abs(d.roll) > 35f) Col.AMBER else Col.WHITE
        p.poly(c, col, col, 2f, CX, CY - r + 4, CX - 16, CY - r + 30, CX + 16, CY - r + 30)
        val off = (d.slip * 180f).coerceIn(-44f, 44f)
        p.poly(c, 0, if (abs(d.slip) > 0.2f) Col.AMBER else col, 3f,
            CX - 17 + off, CY - r + 36, CX + 17 + off, CY - r + 36,
            CX + 22 + off, CY - r + 48, CX - 22 + off, CY - r + 48)
        c.restore()
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        // „wąsy” Boeinga: czarne pola z białym obrysem
        p.poly(c, Col.BLACK, Col.WHITE, 3.5f,
            CX - 215, CY - 6, CX - 95, CY - 6, CX - 95, CY + 34, CX - 76, CY + 34,
            CX - 76, CY + 10, CX - 215, CY + 10)
        p.poly(c, Col.BLACK, Col.WHITE, 3.5f,
            CX + 215, CY - 6, CX + 95, CY - 6, CX + 95, CY + 34, CX + 76, CY + 34,
            CX + 76, CY + 10, CX + 215, CY + 10)
        p.circle(c, CX, CY, 13f, Col.BLACK)
        p.circle(c, CX, CY, 13f, Col.WHITE, 3.5f)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 120f
        val r = 215f
        val t = 92f
        val b = 560f
        val k = 5.2f
        if (!d.speedValid) {
            p.text(c, "SPD", (l + r) / 2, CY, 44f, Col.AMBER)
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
                p.line(c, r - 22, y, r, y, Col.WHITE, 3f)
                p.text(c, v.toString(), r - 32, y, 40f, Col.WHITE, Paint.Align.RIGHT)
            } else {
                p.line(c, r - 12, y, r, y, Col.WHITE, 3f)
            }
            v += 10
        }
        // VMO – czarno-czerwona „taśma” u góry
        if (s + 60f > 350f) {
            var y = CY - (350f - s) * k
            var red = true
            while (y > t) {
                p.rect(c, r - 12, y - 12, r, y, if (red) Col.RED else Col.BLACK)
                y -= 12f
                red = !red
            }
        }
        // minimalna prędkość manewrowa – bursztynowa kreska
        val yMin = CY - (110f - s) * k
        if (yMin in t..b) p.rect(c, r - 12, yMin, r, b.coerceAtMost(yMin + 200f), Col.AMBER)
        c.restore()

        // strzałka trendu prędkości
        val tr = d.speedTrend.coerceIn(-50f, 50f)
        if (abs(tr) > 2f) {
            val y2 = CY - tr * k
            p.line(c, r + 10, CY, r + 10, y2, GREEN, 4f)
            val dir = sign(tr)
            p.line(c, r + 10, y2, r + 1, y2 + dir * 15, GREEN, 4f)
            p.line(c, r + 10, y2, r + 19, y2 + dir * 15, GREEN, 4f)
        }
        // okno odczytu wychodzące poza taśmę
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            l - 20, CY - 30, r + 6, CY - 30, r + 6, CY - 16, r + 26, CY,
            r + 6, CY + 16, r + 6, CY + 30, l - 20, CY + 30)
        p.text(c, s.roundToInt().toString(), r, CY, 46f, Col.WHITE, Paint.Align.RIGHT)
        p.text(c, "GS " + s.roundToInt(), l - 16, t - 26, 30f, Col.WHITE, Paint.Align.LEFT)
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 790f
        val r = 898f
        val t = 92f
        val b = 560f
        val k = 0.52f
        if (!d.altValid) {
            p.text(c, "ALT", (l + r) / 2, CY, 44f, Col.AMBER)
            return
        }
        val a = d.altFt
        p.rect(c, l, t, r, b, TAPE)
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 500f, 100)
        while (v <= a + 500f) {
            val y = CY - (v - a) * k
            p.line(c, l, y, l + 16, y, Col.WHITE, 3f)
            if (mod(v, 200) == 0) {
                p.text(c, v.toString(), l + 26, y, 34f, Col.WHITE, Paint.Align.LEFT)
            }
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
        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 26, 26f, Col.AMBER)

        // okno odczytu: tysiące/setki + bęben 20 ft
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            l - 24, CY - 34, l - 4, CY, l - 24, CY + 34, r + 6, CY + 34, r + 6, CY - 34)
        val hundreds = floor(a / 100f).toInt()
        p.text(c, if (hundreds < 0) "-" + abs(hundreds) else hundreds.toString(),
            l + 62, CY, 46f, Col.WHITE, Paint.Align.RIGHT)
        p.drum(c, l + 64, CY - 32, r + 4, CY + 32, a / 20f, 28f, 28f, Col.WHITE, l + 86,
            Paint.Align.LEFT) { kk -> String.format(Locale.US, "%02d", mod(kk * 20, 100)) }
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val av = abs(v).coerceAtMost(6000f)
        val g = when {
            av <= 1000f -> av * 0.13f
            av <= 2000f -> 130f + (av - 1000f) * 0.06f
            else -> 190f + (av - 2000f) * 0.0125f
        }
        return CY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 910f
        p.line(c, l, 110f, l, 545f, GREY, 2.5f)
        for (v in intArrayOf(500, 1000, 2000, 6000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val len = if (v == 500) 12f else 22f
                p.line(c, l, y, l + len, y, Col.WHITE, 3f)
                if (v != 500) p.text(c, (v / 1000).toString(), l + 42, y, 28f, Col.WHITE)
            }
        }
        if (!d.altValid) return
        val vs = d.vsFpm
        val col = if (abs(vs) > 2000f) Col.AMBER else Col.WHITE
        c.save()
        c.clipRect(l - 60, 100f, 1000f, 555f)
        p.line(c, l - 60, CY, l + 36, vy(vs), col, 5f)
        c.restore()
        if (abs(vs) >= 400f) {
            val txt = (abs(vs) / 100f).roundToInt().toString()
            val ty = if (vs > 0) 96f else 560f
            p.text(c, txt, l + 30, ty, 30f, col)
        }
    }

    // ---------------------------------------------------------------- FMA
    private fun drawFma(c: Canvas, d: FlightData) {
        p.rect(c, 0f, 0f, 1000f, ADI_T, FMA_BG)
        p.line(c, 0f, ADI_T, 1000f, ADI_T, GREY, 2f)
        for (x in floatArrayOf(330f, 560f, 790f)) p.line(c, x, 6f, x, ADI_T - 6, GREY, 2f)
        val flying = d.speedValid && d.speedKt > 30f
        val y = 30f
        val sz = 30f
        if (flying) {
            p.text(c, "THR REF", 165f, y, sz, GREEN)
            p.frame(c, 165f - p.width("THR REF", sz) / 2 - 10, 8f,
                165f + p.width("THR REF", sz) / 2 + 10, 52f, GREEN, 2.5f)
        }
        p.text(c, if (d.hasTrack) "TRK HOLD" else "HDG HOLD", 445f, y, sz, GREEN)
        if (d.altValid) {
            val mode = if (abs(d.vsFpm) >= 300f) "V/S" else "ALT"
            p.text(c, mode, 675f, y, sz, GREEN)
        }
        p.text(c, "FD", 895f, y, sz, Col.WHITE)
    }

    // ---------------------------------------------------------------- pasek pod ADI
    private fun drawBelowAdi(c: Canvas, d: FlightData) {
        p.rect(c, 0f, ADI_B, 1000f, 1000f, Col.BLACK)
        // liczba Macha (orientacyjnie, z TAS)
        if (!d.tasKt.isNaN() && d.tasKt > 60f) {
            val mach = d.tasKt / 600f
            p.text(c, String.format(Locale.US, ".%03d", (mach * 1000).roundToInt().coerceAtMost(999)),
                130f, ADI_B - 34, 36f, Col.WHITE, Paint.Align.LEFT)
        }
        // radiowysokościomierz
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 50f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            val col = if (d.raFt < 200f) Col.AMBER else GREEN
            p.text(c, "RADIO", 620f, ADI_B - 64, 26f, GREEN, Paint.Align.LEFT)
            p.text(c, ra.toString(), 620f, ADI_B - 30, 40f, col, Paint.Align.LEFT)
        }
        // nastawa barometryczna w żółtej ramce
        val y = 642f
        if (d.std) {
            p.text(c, "STD", 890f, y, 36f, GREEN)
            p.frame(c, 835f, y - 26, 945f, y + 26, Col.AMBER, 2.5f)
        } else {
            p.text(c, "QNH", 790f, y, 28f, Col.WHITE, Paint.Align.LEFT)
            val txt = d.qnh.roundToInt().toString()
            p.text(c, txt, 900f, y, 36f, GREEN)
            p.frame(c, 855f, y - 26, 945f, y + 26, Col.AMBER, 2.5f)
            p.text(c, "HPA", 985f, y, 24f, CYAN, Paint.Align.RIGHT)
        }
        // kurs/znos i wiatr po lewej
        if (d.speedValid) p.text(c, "GS " + d.speedKt.roundToInt(), 24f, 642f, 30f, Col.WHITE, Paint.Align.LEFT)
        if (!d.oatC.isNaN()) p.text(c, "OAT " + d.oatC.roundToInt() + "°C", 24f, 722f, 28f, GREEN, Paint.Align.LEFT)
    }

    // ---------------------------------------------------------------- łukowa róża
    private fun drawArc(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.text(c, "HDG", RCX, RCY - RAD / 2, 44f, Col.AMBER)
            return
        }
        val h = d.heading
        // znacznik kursu bieżącego
        p.poly(c, Col.WHITE, Col.WHITE, 2f,
            RCX, RCY - RAD + 4, RCX - 14, RCY - RAD - 22, RCX + 14, RCY - RAD - 22)
        c.save()
        c.clipRect(0f, ADI_B + 60, 1000f, 1000f)
        c.save()
        c.rotate(-h, RCX, RCY)
        var deg = 0
        while (deg < 360) {
            val ten = deg % 10 == 0
            p.radial(c, RCX, RCY, deg.toFloat(), RAD - if (ten) 26f else 14f, RAD, Col.WHITE,
                if (ten) 3.5f else 2.5f)
            if (deg % 30 == 0) {
                val rad = Math.toRadians(deg.toDouble())
                val rr = RAD - 56
                val tx = RCX + (rr * sin(rad)).toFloat()
                val ty = RCY - (rr * cos(rad)).toFloat()
                val lbl = when (deg) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> (deg / 10).toString()
                }
                c.save()
                c.rotate(deg.toFloat(), tx, ty)
                p.text(c, lbl, tx, ty, 32f, Col.WHITE)
                c.restore()
            }
            deg += 5
        }
        // kąt drogi z GPS – zielona linia od samolotu
        if (d.hasTrack) {
            c.save()
            c.rotate(d.track, RCX, RCY)
            p.line(c, RCX, RCY, RCX, RCY - RAD, GREEN, 3f)
            c.restore()
        }
        c.restore()
        c.restore()
        // podziałka zakresu i symbol samolotu
        p.line(c, RCX - 14, RCY - RAD / 2, RCX + 14, RCY - RAD / 2, Col.WHITE, 3f)
        p.poly(c, Col.BLACK, Col.WHITE, 3.5f, RCX, RCY - 44, RCX - 26, RCY + 4, RCX + 26, RCY + 4)
        if (d.hasTrack) {
            p.text(c, String.format(Locale.US, "TRK %03d°", mod(d.track.roundToInt(), 360)),
                24f, 880f, 28f, MAGENTA, Paint.Align.LEFT)
        }
        p.text(c, if (d.gpsFix) "GPS ±" + d.gpsAccuracy.roundToInt() + " m" else "GPS szukanie…",
            976f, 880f, 26f, GREEN, Paint.Align.RIGHT)
    }
}
