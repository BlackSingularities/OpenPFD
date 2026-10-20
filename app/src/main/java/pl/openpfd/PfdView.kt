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
import pl.openpfd.render.AirbusPfd
import pl.openpfd.render.AirbusVariant
import pl.openpfd.render.BoeingPfd
import pl.openpfd.render.BoeingVariant
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
    enum class Style(val label: String, val alerts: AlertProfile) {
        AIRBUS("A320", AlertProfile(350f, 115f, false)),
        A350("A350", AlertProfile(340f, 110f, false)),
        ATR("ATR 72", AlertProfile(250f, 95f, false)),
        BOEING("737", AlertProfile(340f, 115f, false)),
        B787("787", AlertProfile(350f, 110f, false)),
        EMBRAER("E190", AlertProfile(320f, 105f, false)),
        GARMIN("G1000", AlertProfile(163f, 48f, false)),
        CESSNA("C172", AlertProfile(163f, 48f, false)),
        HELI("R44", AlertProfile(130f, 0f, false)),
        GLIDER("Szybowiec", AlertProfile(151f, 38f, true)),
    }

    var style = Style.AIRBUS
    /** Dotknięcie poza aktywnymi strefami – pokazanie/ukrycie interfejsu. */
    var onTapEmpty: (() -> Unit)? = null
    var onCalibrated: (() -> Unit)? = null

    private val squares: Map<Style, PfdRenderer> = mapOf(
        Style.AIRBUS to AirbusPfd(AirbusVariant.A320),
        Style.A350 to AirbusPfd(AirbusVariant.A350),
        Style.ATR to AirbusPfd(AirbusVariant.ATR72),
        Style.BOEING to BoeingPfd(BoeingVariant.B737),
        Style.B787 to BoeingPfd(BoeingVariant.B787),
        Style.EMBRAER to BoeingPfd(BoeingVariant.E190),
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

    private var size = 0f
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
        else size > 0f && r.baroRect.contains((x - ox) / size * 1000f, (y - oy) / size * 1000f)
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
        } else {
            size = min(width, height).toFloat()
            ox = (width - size) / 2f
            oy = (height - size) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(size / 1000f, size / 1000f)
            canvas.clipRect(0f, 0f, 1000f, 1000f)
            r.draw(canvas, d)
            canvas.restore()
            drawStatus(canvas, d)
        }
        postInvalidateOnAnimation()
    }

    /** Krótki stan czujników na marginesie poza kwadratem (gdy jest miejsce). */
    private fun drawStatus(c: Canvas, d: FlightData) {
        val gps = if (d.gpsFix) "GPS ±${d.gpsAccuracy.roundToInt()} m · ${d.sats} SAT" else "GPS · szukanie…"
        val qnh = "QNH ${d.qnh.roundToInt()} · ${d.qnhSource}"
        val lh = status.textSize * 1.4f
        if (ox > 140f * dp || oy > lh * 2 + 12f * dp) {
            c.drawText(gps, 16f * dp, height - 12f * dp - lh, status)
            c.drawText(qnh, 16f * dp, height - 12f * dp, status)
        }
    }
}
