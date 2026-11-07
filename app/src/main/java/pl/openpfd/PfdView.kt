package pl.openpfd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import pl.openpfd.render.Col
import pl.openpfd.render.Pen
import pl.openpfd.render.degradations
import pl.openpfd.render.AirbusPfd
import pl.openpfd.render.AirbusVariant
import pl.openpfd.render.AtrPfd
import pl.openpfd.render.B787Pfd
import pl.openpfd.render.E190Pfd
import pl.openpfd.render.BoeingPfd
import pl.openpfd.render.CessnaPanel
import pl.openpfd.render.GarminPfd
import pl.openpfd.render.PfdRenderer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rysuje wybrany przyrząd: PFD-y w kwadracie na środku ekranu, panel Cessny na całym ekranie.
 */
@SuppressLint("ViewConstructor")
class PfdView(ctx: Context, private val hub: SensorHub) : View(ctx) {
    /** Przyrząd: nazwa, grupa i opis jako zasoby tekstowe (PL/EN). */
    enum class Style(
        val label: Int, val alerts: AlertProfile, val group: Int, val desc: Int,
    ) {
        AIRBUS(R.string.st_a320, AlertProfile(350f, 115f, false), R.string.group_airliners, R.string.desc_airbus),
        ATR(R.string.st_atr, AlertProfile(250f, 95f, false), R.string.group_airliners, R.string.desc_atr),
        BOEING(R.string.st_737, AlertProfile(340f, 115f, false), R.string.group_airliners, R.string.desc_boeing),
        B787(R.string.st_787, AlertProfile(350f, 110f, false), R.string.group_airliners, R.string.desc_787),
        EMBRAER(R.string.st_e190, AlertProfile(320f, 105f, false), R.string.group_airliners, R.string.desc_e190),
        GARMIN(R.string.st_g1000, AlertProfile(163f, 48f, false), R.string.group_ga, R.string.desc_garmin),
        CESSNA(R.string.st_c172, AlertProfile(163f, 48f, false), R.string.group_ga, R.string.desc_cessna),
        HELI(R.string.st_r44, AlertProfile(130f, 0f, false), R.string.group_heli, R.string.desc_heli),
        GLIDER(R.string.st_glider, AlertProfile(151f, 38f, true), R.string.group_gliders, R.string.desc_glider),
    }

    var style = Style.AIRBUS
    /** Dotknięcie poza aktywnymi strefami – pokazanie/ukrycie interfejsu. */
    var onTapEmpty: (() -> Unit)? = null
    var onCalibrated: (() -> Unit)? = null

    private val squares: Map<Style, PfdRenderer> = mapOf(
        Style.AIRBUS to AirbusPfd(AirbusVariant.A320),
        Style.ATR to AtrPfd(),
        Style.BOEING to BoeingPfd(),
        Style.B787 to B787Pfd(),
        Style.EMBRAER to E190Pfd(),
        Style.GARMIN to GarminPfd(),
    )
    private val panels: Map<Style, CessnaPanel> = mapOf(
        Style.CESSNA to CessnaPanel(CessnaPanel.Kind.CESSNA),
        Style.HELI to CessnaPanel(CessnaPanel.Kind.HELI),
        Style.GLIDER to CessnaPanel(CessnaPanel.Kind.GLIDER),
    )
    private val renderer: PfdRenderer? get() = squares[style]
    private val panel: CessnaPanel get() = panels[style] ?: panels.getValue(Style.CESSNA)

    private val dp = resources.displayMetrics.density
    private val status = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 11f * dp
        typeface = Typeface.MONOSPACE
    }

    private var scale = 0f
    private var ox = 0f
    private var oy = 0f

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            hub.calibrate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onCalibrated?.invoke()
        }
    })

    init {
        keepScreenOn = true
        setBackgroundColor(Color.BLACK)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun onTap(x: Float, y: Float) {
        val r = renderer
        val baroHit = if (r == null) panel.baroHit(x, y)
        else scale > 0f && r.baroRect.contains((x - ox) / scale, (y - oy) / scale)
        if (baroHit) {
            hub.std = !hub.std
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else {
            onTapEmpty?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = hub.snapshot()
        val r = renderer
        if (r == null) {
            panel.draw(canvas, d, width.toFloat(), height.toFloat())
            drawDegradations(canvas, d, width - 10f * dp, 10f * dp, rightAlign = true)
        } else {
            scale = min(width / r.vw, height / r.vh)
            ox = (width - r.vw * scale) / 2f
            oy = (height - r.vh * scale) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(scale, scale)
            canvas.clipRect(0f, 0f, r.vw, r.vh)
            r.draw(canvas, d)
            canvas.restore()
            drawStatus(canvas, d)
            val side = r.vw * scale
            if (ox > 90f * dp) drawDegradations(canvas, d, 12f * dp, oy + 8f * dp, rightAlign = false)
            else if (oy > 30f * dp) drawDegradations(canvas, d, ox + 8f * dp, oy + r.vh * scale + 8f * dp, rightAlign = false, row = true)
            else drawDegradations(canvas, d, ox + side - 8f * dp, oy + 8f * dp, rightAlign = true)
        }
        postInvalidateOnAnimation()
    }

    private val flagPen = Pen()

    /**
     * Flagi jakości danych (NO GPS, GPS ALT, NO QNH, NO NET…) – wspólne dla wszystkich przyrządów.
     * Rysowane w kolumnie (margines boczny) albo w rzędzie (margines pod kwadratem).
     */
    private fun drawDegradations(
        c: Canvas, d: FlightData, x: Float, y: Float, rightAlign: Boolean, row: Boolean = false,
    ) {
        val list = degradations(d)
        if (list.isEmpty()) return
        val size = 12f * dp
        val gap = size * 1.9f
        var cx = x
        var cy = y + size * 0.7f
        for (f in list) {
            val w = flagPen.width(f.text, size) + size * 0.56f
            if (row && cx + w > width) {
                cx = x
                cy += gap
            }
            val align = if (rightAlign) Paint.Align.RIGHT else Paint.Align.LEFT
            val tx = if (rightAlign) cx - size * 0.28f else cx + size * 0.28f
            flagPen.flag(c, f.text, tx, cy, size, if (f.color == Col.WHITE) Color.WHITE else f.color, align)
            if (row) cx += w + size * 0.5f else cy += gap
        }
    }

    /** Krótki stan czujników na marginesie poza kwadratem (gdy jest miejsce). */
    private fun drawStatus(c: Canvas, d: FlightData) {
        val gps = if (d.gpsFix) "GPS ±${d.gpsAccuracy.roundToInt()} m · ${d.sats} SAT" else "GPS · ACQ…"
        val qnh = "QNH ${d.qnh.roundToInt()} · ${d.qnhSource}"
        val lh = status.textSize * 1.4f
        if (ox > 140f * dp || oy > lh * 2 + 12f * dp) {
            c.drawText(gps, 16f * dp, height - 12f * dp - lh, status)
            c.drawText(qnh, 16f * dp, height - 12f * dp, status)
        }
    }
}
