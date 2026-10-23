package pl.openpfd

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Elementy interfejsu w stylu OpenPDF: czarno-biały, płaskie tła bez rozmycia,
 * przyciski-pigułki #1C1C1C, aktywny element = biały blok z czarną treścią.
 */
object Ui {
    const val INK = 0xFF000000.toInt()
    const val PANEL_HIGH = 0xFF1C1C1C.toInt()
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val TEXT_FAINT = 0x66FFFFFF
    const val RECORD = 0xFFFF3B47.toInt()

    fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    fun shape(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun topRounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    /** Pigułka: tekst na tle #1C1C1C albo biały blok, gdy aktywna. */
    fun pill(ctx: Context, text: String, active: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            val h = ctx.dp(16f)
            val v = ctx.dp(9f)
            setPadding(h, v, h, v)
            setActive(active)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    fun TextView.setActive(active: Boolean) {
        background = shape(if (active) Color.WHITE else PANEL_HIGH, 999f)
        setTextColor(if (active) INK else Color.WHITE)
    }

    /** Kafelek wyboru: tytuł i krótki opis; aktywny = biały blok. */
    fun card(
        ctx: Context, title: String, desc: String, active: Boolean, onClick: () -> Unit,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val h = ctx.dp(14f)
        val v = ctx.dp(11f)
        setPadding(h, v, h, v)
        background = shape(if (active) Color.WHITE else PANEL_HIGH, ctx.dp(18f).toFloat())
        addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (active) INK else Color.WHITE)
        })
        addView(TextView(ctx).apply {
            text = desc
            textSize = 11f
            setTextColor(if (active) 0x99000000.toInt() else TEXT_DIM)
            setPadding(0, ctx.dp(2f), 0, 0)
        })
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    fun label(ctx: Context, text: String, size: Float = 13f, color: Int = TEXT_DIM): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
        }

    /** Wiersz z etykietą i przełącznikiem segmentowym z pigułek. */
    fun segmented(
        ctx: Context, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(10f), 0, ctx.dp(4f))
        addView(label(ctx, title))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(8f), 0, 0)
        }
        val pills = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            val pv = pill(ctx, o, i == selected) {
                pills.forEachIndexed { j, t -> t.setActive(j == i) }
                onSelect(i)
            }
            pills += pv
            row.addView(pv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ctx.dp(8f) })
        }
        addView(row)
    }
}
