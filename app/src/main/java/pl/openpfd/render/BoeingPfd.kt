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
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** PFD Boeinga 737NG/MAX (Common Display System). */
class BoeingPfd : PfdRenderer {
    private val p = Pen()

    private companion object {
        const val BX = 470f
        const val BY = 470f
        const val AW = 230f
        const val AH = 220f
        const val PPD = 9.5f

        const val GREEN = 0xFF3EEA4C.toInt()
        const val CYAN = 0xFF33DDFF.toInt()

        const val SKY = 0xFF1A8FD8.toInt()
        const val GROUND = 0xFF8A4A17.toInt()
        const val TAPE = 0xFF5E5F63.toInt()
        const val VSI_BG = 0xFF4A4B4F.toInt()
        const val ROSE = 0xFF55565A.toInt()
        const val FMA_BG = 0xFF3A3B3E.toInt()
        const val VMO = 340f
        const val MAGENTA = 0xFFEE55DD.toInt()

        const val RCX = BX
        const val RCY = 1215f
        const val RAD = 330f
    }


    private val attClip = Path().apply {
        addRoundRect(RectF(BX - AW, BY - AH, BX + AW, BY + AH), 48f, 48f, Path.Direction.CW)
    }

    override val baroRect = RectF(760f, 785f, 1000f, 860f)

    override fun draw(c: Canvas, d: FlightData) {
        drawAttitude(c, d)
        drawSpeed(c, d)
        drawAltitude(c, d)
        drawVsi(c, d)
        drawCompass(c, d)
        drawFma(c, d)
        drawBaro(c, d)
    }

    // ---------------------------------------------------------------- ADI
    private fun drawAttitude(c: Canvas, d: FlightData) {
        if (!d.attValid) {
            p.frame(c, BX - 40, BY - 110, BX + 40, BY - 60, Col.AMBER, 3f)
            p.text(c, "ATT", BX, BY - 85, 40f, Col.AMBER)
            drawAircraft(c)
            return
        }
        c.save()
        c.clipPath(attClip)
        c.save()
        c.rotate(-d.roll, BX, BY)
        c.translate(0f, d.pitch * PPD)
        p.rect(c, BX - 1000, BY - 3000, BX + 1000, BY, SKY)
        p.rect(c, BX - 1000, BY, BX + 1000, BY + 3000, GROUND)
        p.line(c, BX - 1000, BY, BX + 1000, BY, Col.WHITE, 3f)

        // skala kursu na horyzoncie
        var h = floorTo(d.heading - 30f, 5)
        while (h <= d.heading + 30f) {
            val x = BX + angDiff(h.toFloat(), d.heading) * PPD
            val ten = mod(h, 10) == 0
            p.line(c, x, BY, x, BY + if (ten) 14f else 8f, Col.WHITE, 2.5f)
            if (ten) p.text(c, (mod(h, 360) / 10).toString(), x, BY + 30, 28f, Col.WHITE)
            h += 5
        }

        // drabinka pochylenia (wycięta do okna środkowego)
        c.save()
        c.clipRect(BX - 140, BY - 170 - d.pitch * PPD, BX + 140, BY + 150 - d.pitch * PPD)
        val i0 = ceil((d.pitch - 20f) / 2.5f).toInt()
        val i1 = floor((d.pitch + 20f) / 2.5f).toInt()
        for (i in i0..i1) {
            if (i == 0) continue
            val deg = i * 2.5f
            if (abs(deg) > 90f) continue
            val y = BY - deg * PPD
            val half = when {
                i % 4 == 0 -> 82f
                i % 2 == 0 -> 40f
                else -> 18f
            }
            p.line(c, BX - half, y, BX + half, y, Col.WHITE, 3f)
            if (i % 4 == 0) {
                val lbl = abs(deg).roundToInt().toString()
                p.text(c, lbl, BX - half - 30, y, 36f, Col.WHITE)
                p.text(c, lbl, BX + half + 30, y, 36f, Col.WHITE)
            }
        }
        c.restore()
        c.restore()

        // wektor toru lotu
        if (d.fpv && d.hasTrack && d.speedKt > 5f) {
            c.save()
            c.rotate(-d.roll, BX, BY)
            val dx = angDiff(d.track, d.heading).coerceIn(-20f, 20f) * PPD
            val dy = (d.fpa - d.pitch).coerceIn(-20f, 20f) * PPD
            val x = BX + dx
            val y = BY - dy
            p.circle(c, x, y, 13f, Col.WHITE, 3.5f)
            p.line(c, x - 13, y, x - 38, y, Col.WHITE, 3.5f)
            p.line(c, x + 13, y, x + 38, y, Col.WHITE, 3.5f)
            p.line(c, x, y - 13, x, y - 26, Col.WHITE, 3.5f)
            c.restore()
        }
        c.restore()

        drawPitchChevrons(c, d)
        drawRollScale(c, d)
        drawAircraft(c)
        if (d.raValid && d.raFt < 2500f) {
            val ra = if (d.raFt < 100f) d.raFt.roundToInt() else (d.raFt / 10f).roundToInt() * 10
            p.text(c, ra.toString(), BX, BY + 175f, 46f, if (d.raFt < 200f) Col.AMBER else Col.WHITE)
        }
    }

    /** Czerwone chevrony wskazujące najkrótszą drogę do horyzontu. */
    private fun drawPitchChevrons(c: Canvas, d: FlightData) {
        if (d.pitch <= 30f && d.pitch >= -20f) return
        val dir = if (d.pitch > 0f) 1f else -1f
        c.save()
        c.clipPath(attClip)
        c.rotate(-d.roll, BX, BY)
        for (k in 1..2) {
            val y = BY + dir * (70f + k * 60f)
            p.line(c, BX - 64, y + dir * 40, BX, y, Col.RED, 6f)
            p.line(c, BX, y, BX + 64, y + dir * 40, Col.RED, 6f)
        }
        c.restore()
    }

    private fun drawRollScale(c: Canvas, d: FlightData) {
        val r = 238f
        for (a in intArrayOf(10, 20, 30, 45, 60)) {
            val r2 = if (a == 30 || a == 60) 272f else 256f
            for (sg in intArrayOf(-1, 1)) {
                if (a == 45) {
                    p.radial(c, BX, BY, sg * 45f, r, 252f, Col.WHITE, 3f)
                } else {
                    p.radial(c, BX, BY, (sg * a).toFloat(), r, r2, Col.WHITE, 3f)
                }
            }
        }
        // stały znacznik zera
        p.poly(c, 0, Col.WHITE, 3f, BX, BY - r, BX - 14, BY - r - 22, BX + 14, BY - r - 22)
        // ruchomy wskaźnik nieba + wskaźnik ślizgu
        c.save()
        c.rotate(-d.roll, BX, BY)
        val col = if (abs(d.roll) > 35f) Col.AMBER else Col.WHITE
        p.poly(c, 0, col, 3f, BX, BY - r + 2, BX - 14, BY - r + 24, BX + 14, BY - r + 24)
        val off = (d.slip * 180f).coerceIn(-40f, 40f)
        val slipCol = if (abs(d.slip) > 0.2f) Col.AMBER else col
        p.poly(c, 0, slipCol, 3f,
            BX - 14 + off, BY - r + 28, BX + 14 + off, BY - r + 28,
            BX + 14 + off, BY - r + 38, BX - 14 + off, BY - r + 38)
        c.restore()
    }

    private fun drawAircraft(c: Canvas) {
        val y = BY
        val o = 270f
        val i = 380f
        p.poly(c, Col.BLACK, Col.WHITE, 4f,
            o, y - 9, i, y - 9, i, y + 40, i - 16, y + 40, i - 16, y + 9, o, y + 9)
        p.poly(c, Col.BLACK, Col.WHITE, 4f,
            2 * BX - o, y - 9, 2 * BX - i, y - 9, 2 * BX - i, y + 40,
            2 * BX - i + 16, y + 40, 2 * BX - i + 16, y + 9, 2 * BX - o, y + 9)
        p.rect(c, BX - 9, y - 9, BX + 9, y + 9, Col.BLACK)
        p.frame(c, BX - 9, y - 9, BX + 9, y + 9, Col.WHITE, 4f)
    }

    // ---------------------------------------------------------------- prędkość
    private fun drawSpeed(c: Canvas, d: FlightData) {
        val l = 40f
        val r = 160f
        val t = 170f
        val b = 770f
        val k = 4.2f
        p.rect(c, l, t, r, b, TAPE)
        if (!d.speedValid) {
            p.frame(c, l + 18, BY - 26, r - 18, BY + 26, Col.AMBER, 3f)
            p.text(c, "SPD", (l + r) / 2, BY, 40f, Col.AMBER)
            return
        }
        val s = d.speedKt
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(s - 80f, 10).coerceAtLeast(0)
        while (v <= s + 80f) {
            val y = BY - (v - s) * k
            p.line(c, r - 20, y, r, y, Col.WHITE, 3f)
            if (v % 20 == 0) p.text(c, v.toString(), r - 28, y, 40f, Col.WHITE, Paint.Align.RIGHT)
            v += 10
        }
        if (s + 80f > VMO) {
            var y = BY - (VMO - s) * k
            var red = true
            while (y > t) {
                p.rect(c, r - 12, y - 12, r, y, if (red) Col.RED else Col.BLACK)
                y -= 12f
                red = !red
            }
        }
        c.restore()

        // wektor trendu prędkości (zielony)
        val tr = d.speedTrend.coerceIn(-65f, 65f)
        if (abs(tr) > 2f) {
            val y2 = BY - tr * k
            val x = r + 6
            p.line(c, x, BY, x, y2, GREEN, 3.5f)
            val dir = sign(tr)
            p.line(c, x, y2, x - 9, y2 + dir * 14, GREEN, 3.5f)
            p.line(c, x, y2, x + 9, y2 + dir * 14, GREEN, 3.5f)
        }

        // okno odczytu z bębnem jednostek
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            14f, BY - 32, 118f, BY - 32, 118f, BY - 54, 152f, BY - 54, 152f, BY - 12,
            170f, BY, 152f, BY + 12, 152f, BY + 54, 118f, BY + 54, 118f, BY + 32, 14f, BY + 32)
        val tens = floor(s / 10f).toInt()
        if (tens > 0) p.text(c, tens.toString(), 117f, BY, 54f, Col.WHITE, Paint.Align.RIGHT)
        p.drum(c, 119f, BY - 52, 151f, BY + 52, s, 46f, 50f, Col.WHITE, 135f, Paint.Align.CENTER) { kk ->
            mod(kk, 10).toString()
        }

        // GS i TAS nad taśmą, liczba Macha pod nią
        p.text(c, "GS", 22f, 128f, 30f, Col.WHITE, Paint.Align.LEFT)
        p.text(c, s.roundToInt().toString(), 70f, 126f, 40f, Col.WHITE, Paint.Align.LEFT)
        if (!d.tasKt.isNaN()) {
            p.text(c, "TAS", 22f, 90f, 26f, Col.WHITE, Paint.Align.LEFT)
            p.text(c, d.tasKt.roundToInt().toString(), 84f, 89f, 32f, Col.WHITE, Paint.Align.LEFT)
            val mach = d.tasKt / 600f
            if (mach >= 0.4f) {
                p.text(c, String.format(Locale.US, ".%03d", (mach * 1000).roundToInt().coerceAtMost(999)),
                    (l + r) / 2, b + 36, 38f, Col.WHITE)
            }
        }
    }

    // ---------------------------------------------------------------- wysokość
    private fun drawAltitude(c: Canvas, d: FlightData) {
        val l = 790f
        val r = 905f
        val t = 170f
        val b = 770f
        val k = 0.3f
        p.rect(c, l, t, r, b, TAPE)
        if (!d.altValid) {
            p.frame(c, l + 18, BY - 26, r - 18, BY + 26, Col.AMBER, 3f)
            p.text(c, "ALT", (l + r) / 2, BY, 40f, Col.AMBER)
            return
        }
        val a = d.altFt
        c.save()
        c.clipRect(l, t, r, b)
        var v = floorTo(a - 1100f, 100)
        while (v <= a + 1100f) {
            val y = BY - (v - a) * k
            p.line(c, l, y, l + 16, y, Col.WHITE, 3f)
            if (mod(v, 200) == 0) {
                val av = abs(v)
                val th = av / 1000
                val rest = String.format(Locale.US, "%03d", av % 1000)
                var x = l + 24
                val sign = if (v < 0) "-" else ""
                if (th > 0 || v < 0) {
                    val big = sign + (if (th > 0) th.toString() else "")
                    p.text(c, big, x, y, 42f, Col.WHITE, Paint.Align.LEFT)
                    x += p.width(big, 42f)
                }
                p.text(c, rest, x, y + 2, 32f, Col.WHITE, Paint.Align.LEFT)
                if (mod(v, 1000) == 0) {
                    p.line(c, l + 20, y - 24, r - 6, y - 24, Col.WHITE, 2.5f)
                    p.line(c, l + 20, y + 24, r - 6, y + 24, Col.WHITE, 2.5f)
                }
            }
            v += 100
        }
        c.restore()

        // okno odczytu
        p.poly(c, Col.BLACK, Col.WHITE, 3f,
            772f, BY, 790f, BY - 12, 790f, BY - 32, 880f, BY - 32, 880f, BY - 54,
            935f, BY - 54, 935f, BY + 54, 880f, BY + 54, 880f, BY + 32, 790f, BY + 32, 790f, BY + 12)
        val av = abs(a)
        val th = floor(av / 1000f).toInt()
        val hu = floor((av % 1000f) / 100f).toInt()
        if (th > 0 || a < 0) {
            val big = (if (a < 0) "-" else "") + (if (th > 0) th.toString() else "")
            p.text(c, big, 850f, BY, 54f, Col.WHITE, Paint.Align.RIGHT)
        }
        p.text(c, hu.toString(), 865f, BY + 2, 42f, Col.WHITE)
        p.drum(c, 881f, BY - 52, 934f, BY + 52, av / 20f, 36f, 34f, Col.WHITE, 908f, Paint.Align.CENTER) { kk ->
            String.format(Locale.US, "%02d", mod(kk * 20, 100))
        }

        // wysokość metryczna
        val m = (a * 0.3048f).roundToInt()
        p.rect(c, 800f, BY - 104, 935f, BY - 62, Col.BLACK)
        p.frame(c, 800f, BY - 104, 935f, BY - 62, Col.WHITE, 2.5f)
        p.text(c, m.toString(), 905f, BY - 83, 36f, Col.WHITE, Paint.Align.RIGHT)
        p.text(c, "M", 910f, BY - 81, 26f, CYAN, Paint.Align.LEFT)

        if (!d.altFromBaro) p.text(c, "GPS ALT", (l + r) / 2, t - 22, 30f, Col.AMBER)
    }

    // ---------------------------------------------------------------- wariometr
    private fun vy(v: Float): Float {
        val a = abs(v).coerceAtMost(6000f)
        val g = when {
            a <= 1000f -> a * 0.11f
            a <= 2000f -> 110f + (a - 1000f) * 0.055f
            else -> 165f + (a - 2000f) * 0.01375f
        }
        return BY - sign(v) * g
    }

    private fun drawVsi(c: Canvas, d: FlightData) {
        val l = 942f
        val r = 998f
        p.poly(c, VSI_BG, 0, 0f, l, 290f, l + 22, 230f, r, 230f, r, 710f, l + 22, 710f, l, 650f)
        for (v in intArrayOf(500, 1000, 1500, 2000, 6000)) {
            for (sg in intArrayOf(-1, 1)) {
                val y = vy((sg * v).toFloat())
                val len = if (v == 500 || v == 1500) 8f else 14f
                p.line(c, l + 24, y, l + 24 + len, y, Col.WHITE, 3f)
                if (v == 1000 || v == 2000 || v == 6000) p.text(c, (v / 1000).toString(), l + 12, y, 26f, Col.WHITE)
            }
        }
        p.line(c, l + 22, BY, l + 44, BY, Col.WHITE, 4f)
        if (!d.altValid) return
        val vs = d.vsFpm
        val tipY = vy(vs)
        c.save()
        c.clipRect(l + 24, 228f, r, 712f)
        p.line(c, 1080f, BY, l + 24, tipY, Col.WHITE, 4f)
        c.restore()
        if (abs(vs) >= 400f) {
            val v50 = ((abs(vs) / 50f).roundToInt() * 50)
            p.text(c, v50.toString(), 972f, if (vs > 0) 208f else 734f, 30f, Col.WHITE)
        }
    }

    // ---------------------------------------------------------------- róża kompasowa
    private fun drawCompass(c: Canvas, d: FlightData) {
        c.save()
        c.clipRect(0f, 862f, 1000f, 1000f)
        p.circle(c, RCX, RCY, RAD, ROSE)
        if (!d.attValid) {
            c.restore()
            p.text(c, "HDG", RCX, 930f, 40f, Col.AMBER)
            return
        }
        val h = d.heading
        var deg = floorTo(h - 40f, 5)
        while (deg <= h + 40f) {
            val ang = angDiff(deg.toFloat(), h)
            c.save()
            c.rotate(ang, RCX, RCY)
            val ten = mod(deg, 10) == 0
            p.line(c, RCX, RCY - RAD, RCX, RCY - RAD + if (ten) 24f else 13f, Col.WHITE, 3f)
            if (ten) {
                val n = mod(deg, 360) / 10
                val big = n % 3 == 0
                p.text(c, n.toString(), RCX, RCY - RAD + 50, if (big) 40f else 30f, Col.WHITE)
            }
            c.restore()
            deg += 5
        }
        // linia kąta drogi
        if (d.hasTrack) {
            c.save()
            c.rotate(angDiff(d.track, h), RCX, RCY)
            p.line(c, RCX, RCY - RAD, RCX, RCY - RAD + 120, Col.WHITE, 2.5f)
            p.line(c, RCX - 12, RCY - RAD + 70, RCX + 12, RCY - RAD + 70, Col.WHITE, 2.5f)
            c.restore()
        }
        c.restore()
        // wskaźnik kursu
        p.poly(c, 0, Col.WHITE, 3f, RCX, RCY - RAD + 2, RCX - 14, RCY - RAD - 22, RCX + 14, RCY - RAD - 22)
        val hdg = String.format(Locale.US, "%03d", mod(h.roundToInt(), 360))
        p.text(c, hdg, RCX - 14, 968f, 38f, MAGENTA, Paint.Align.RIGHT)
        p.text(c, if (d.headingMagnetic) "MAG" else "REL",
            RCX + 14, 968f, 32f, if (d.headingMagnetic) GREEN else Col.AMBER, Paint.Align.LEFT)
    }

    // ---------------------------------------------------------------- FMA
    private fun drawFma(c: Canvas, d: FlightData) {
        p.rect(c, 200f, 12f, 740f, 80f, FMA_BG)
        p.line(c, 380f, 16f, 380f, 76f, Col.WHITE, 2.5f)
        p.line(c, 560f, 16f, 560f, 76f, Col.WHITE, 2.5f)
        val flying = d.speedValid && d.speedKt > 30f
        if (flying) p.text(c, "MCP SPD", 290f, 46f, 38f, GREEN)
        if (d.hasTrack) p.text(c, "HDG SEL", 470f, 46f, 38f, GREEN)
        if (d.altValid && abs(d.vsFpm) >= 300f) p.text(c, "V/S", 650f, 46f, 38f, GREEN)
        else if (flying) p.text(c, "ALT HOLD", 650f, 46f, 38f, GREEN)
        p.text(c, "FD", BX, 128f, 48f, Col.WHITE)
    }

    // ---------------------------------------------------------------- baro
    private fun drawBaro(c: Canvas, d: FlightData) {
        val y = 822f
        if (d.std) {
            p.text(c, "STD", 850f, y, 44f, GREEN)
        } else {
            val unit = if (d.inHg) " IN." else " HPA"
            p.text(c, baroText(d) + unit, 985f, y, 42f, GREEN, Paint.Align.RIGHT)
        }
        if (d.std) p.text(c, baroText(d) + if (d.inHg) " IN." else " HPA", 985f, 862f, 28f, Col.WHITE, Paint.Align.RIGHT)
    }
}
