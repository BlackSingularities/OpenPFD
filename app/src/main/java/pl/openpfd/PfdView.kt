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
    enum class Style(val label: String) {
        AIRBUS("A320"), BOEING("737"), GARMIN("G1000"), CESSNA("C172")
    }

    var style = Style.AIRBUS
    /** Dotknięcie poza aktywnymi strefami – pokazanie/ukrycie interfejsu. */
    var onTapEmpty: (() -> Unit)? = null
    var onCalibrated: (() -> Unit)? = null

    private val airbus = AirbusPfd()
    private val boeing = BoeingPfd()
    private val garmin = GarminPfd()
    private val cessna = CessnaPanel()
    private val renderer: PfdRenderer?
        get() = when (style) {
            Style.AIRBUS -> airbus
            Style.BOEING -> boeing
            Style.GARMIN -> garmin
            Style.CESSNA -> null
        }

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
        val baroHit = if (r == null) cessna.baroHit(x, y)
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
            cessna.draw(canvas, d, width.toFloat(), height.toFloat())
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
