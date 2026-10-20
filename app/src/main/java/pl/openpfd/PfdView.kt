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
import pl.openpfd.render.GarminPfd
import pl.openpfd.render.PfdRenderer
import kotlin.math.min
import kotlin.math.roundToInt

/** Rysuje kwadratowy PFD na środku ekranu oraz pasek stanu czujników poza nim. */
@SuppressLint("ViewConstructor")
class PfdView(ctx: Context, private val hub: SensorHub) : View(ctx) {
    enum class Style { AIRBUS, BOEING, GARMIN }

    var style = Style.AIRBUS
    var onMenu: (() -> Unit)? = null
    var onCalibrated: (() -> Unit)? = null

    private val airbus = AirbusPfd()
    private val boeing = BoeingPfd()
    private val garmin = GarminPfd()
    private val renderer: PfdRenderer
        get() = when (style) {
            Style.AIRBUS -> airbus
            Style.BOEING -> boeing
            Style.GARMIN -> garmin
        }

    private val dp = resources.displayMetrics.density
    private val status = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        textSize = 12f * dp
        typeface = Typeface.MONOSPACE
    }
    private val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 70, 70)
    }
    private val btnTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f * dp
        textAlign = Paint.Align.CENTER
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

    private fun menuCenter(): Pair<Float, Float> = Pair(width - 30f * dp, 30f * dp)

    private fun onTap(x: Float, y: Float) {
        val (mx, my) = menuCenter()
        if ((x - mx) * (x - mx) + (y - my) * (y - my) < (34f * dp) * (34f * dp)) {
            onMenu?.invoke()
            return
        }
        if (size <= 0f) return
        val vx = (x - ox) / size * 1000f
        val vy = (y - oy) / size * 1000f
        if (renderer.baroRect.contains(vx, vy)) {
            hub.std = !hub.std
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } else if (x < ox || x > ox + size || y < oy || y > oy + size) {
            onMenu?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = hub.snapshot()
        size = min(width, height).toFloat()
        ox = (width - size) / 2f
        oy = (height - size) / 2f

        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(size / 1000f, size / 1000f)
        canvas.clipRect(0f, 0f, 1000f, 1000f)
        renderer.draw(canvas, d)
        canvas.restore()

        drawStatus(canvas, d)
        postInvalidateOnAnimation()
    }

    private fun drawStatus(c: Canvas, d: FlightData) {
        val lines = listOf(
            if (d.gpsFix) "GPS  ±${d.gpsAccuracy.roundToInt()} m  ${d.sats} SAT" else "GPS  SZUKANIE… (${d.sats} SAT)",
            "QNH  ${d.qnh.roundToInt()} hPa  ${d.qnhSource}" + if (d.std) "  [STD]" else "",
            "ALT  " + when {
                !d.altValid -> "BRAK"
                d.altFromBaro -> "BAROMETR"
                else -> "GPS (brak barometru)"
            },
            "HDG  " + if (d.headingMagnetic) "MAGNETYCZNY" else "WZGLĘDNY",
            "v" + BuildConfig.VERSION_NAME,
        )
        val lh = status.textSize * 1.3f
        val horizontalMargin = ox > 150f * dp
        var x = 10f * dp
        var y = if (horizontalMargin) 24f * dp else 18f * dp
        if (!horizontalMargin && oy < lines.size * lh + 10f * dp) {
            // brak miejsca – tylko najważniejsza linia na dole
            c.drawText(lines[0] + "   " + lines[1], x, height - 8f * dp, status)
        } else {
            for (s in lines) {
                c.drawText(s, x, y, status)
                y += lh
            }
        }
        x = 0f
        val (mx, my) = menuCenter()
        c.drawCircle(mx, my, 20f * dp, btn)
        c.drawText("≡", mx + x, my + 8f * dp, btnTxt)
    }
}
