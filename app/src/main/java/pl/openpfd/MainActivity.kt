package pl.openpfd

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import pl.openpfd.Ui.dp
import pl.openpfd.Ui.setActive
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var hub: SensorHub
    private lateinit var pfd: PfdView
    private lateinit var alerts: AlertEngine
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var styleButton: TextView
    private var sheet: View? = null
    private var banner: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideChrome = Runnable {
        topBar.animate().alpha(0f).setDuration(250).withEndAction { topBar.visibility = View.GONE }
    }
    private val alertLoop = object : Runnable {
        override fun run() {
            alerts.update(hub.snapshot(), pfd.style.alerts)
            handler.postDelayed(this, 200L)
        }
    }
    private val periodicUpdate = object : Runnable {
        override fun run() {
            checkUpdates(false)
            handler.postDelayed(this, 6 * 3600_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hub = SensorHub(this)
        pfd = PfdView(this, hub)
        val prefs = getSharedPreferences("openpfd", MODE_PRIVATE)
        alerts = AlertEngine(this)
        alerts.voiceOn = prefs.getBoolean("alerts", true)
        alerts.varioOn = prefs.getBoolean("vario", true)
        pfd.style = PfdView.Style.entries.firstOrNull { it.name == prefs.getString("style", "AIRBUS") }
            ?: PfdView.Style.AIRBUS
        pfd.onTapEmpty = { if (topBar.visibility == View.VISIBLE) hideChrome.run() else showChrome() }
        pfd.onCalibrated = { Toast.makeText(this, "Skalibrowano poziom", Toast.LENGTH_SHORT).show() }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(pfd, FrameLayout.LayoutParams(-1, -1))
        buildTopBar()
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        }
        showChrome()
        handler.postDelayed(periodicUpdate, 3000L)
    }

    override fun onResume() {
        super.onResume()
        hub.displayRotation = currentRotation()
        hub.start()
        alerts.start()
        handler.removeCallbacks(alertLoop)
        handler.post(alertLoop)
        hideSystemUi()
    }

    override fun onPause() {
        handler.removeCallbacks(alertLoop)
        alerts.stop()
        hub.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        alerts.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hub.displayRotation = currentRotation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closeSheet() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) hub.startLocation()
        else Toast.makeText(this, "Bez lokalizacji nie będzie prędkości, toru lotu ani QNH", Toast.LENGTH_LONG).show()
    }

    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ---------------------------------------------------------------- górny pasek

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(Color.BLACK, 999f)
            val p = dp(4f)
            setPadding(p, p, p, p)
        }
        styleButton = Ui.pill(this, styleButtonText(), true) { openPicker() }
        topBar.addView(styleButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4f) })
        topBar.addView(Ui.pill(this, "⚙") { openSheet() })
        root.addView(topBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12f)
            leftMargin = dp(8f)
            rightMargin = dp(8f)
        })
    }

    private fun showChrome() {
        topBar.animate().cancel()
        topBar.visibility = View.VISIBLE
        topBar.animate().alpha(1f).setDuration(150)
        handler.removeCallbacks(hideChrome)
        handler.postDelayed(hideChrome, 4000L)
    }

    private fun styleButtonText() = "✈  " + pfd.style.label + "  ▾"

    private fun setStyle(s: PfdView.Style) {
        pfd.style = s
        styleButton.text = styleButtonText()
        styleButton.setActive(true)
        getSharedPreferences("openpfd", MODE_PRIVATE).edit().putString("style", s.name).apply()
        if (sheet == null) showChrome()
    }

    // ---------------------------------------------------------------- wybór przyrządu

    /** Pełna lista przyrządów pogrupowana według rodzaju statku powietrznego. */
    private fun openPicker() {
        if (sheet != null) return
        handler.removeCallbacks(hideChrome)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        PfdView.Style.entries.groupBy { it.group }.forEach { (group, items) ->
            content.addView(Ui.label(this, group).apply { setPadding(0, dp(14f), 0, dp(8f)) })
            items.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { s ->
                    row.addView(Ui.card(this, s.label, s.desc, s == pfd.style) {
                        setStyle(s)
                        closeSheet()
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginEnd = dp(8f)
                        bottomMargin = dp(8f)
                    })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(row)
            }
        }
        showSheet("Wybierz przyrząd", content)
    }

    // ---------------------------------------------------------------- arkusz ustawień

    /** Wspólny dolny arkusz: przyciemnienie, panel z uchwytem, tytuł i przewijana treść. */
    private fun showSheet(title: String, content: View) {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeSheet() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRounded(Ui.INK, dp(24f).toFloat())
            val p = dp(20f)
            setPadding(p, dp(10f), p, p)
            isClickable = true
        }
        panel.addView(View(this).apply { background = Ui.shape(Ui.TEXT_FAINT, 999f) },
            LinearLayout.LayoutParams(dp(40f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12f)
            })
        panel.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        panel.addView(ScrollView(this).apply { addView(content) })

        val maxW = dp(560f)
        val w = if (resources.displayMetrics.widthPixels > maxW) maxW else ViewGroup.LayoutParams.MATCH_PARENT
        scrim.addView(panel, FrameLayout.LayoutParams(w, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48f)
        })
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.translationY = 600f * resources.displayMetrics.density
        panel.animate().translationY(0f).setDuration(220)
        sheet = scrim
    }

    private fun openSheet() {
        if (sheet != null) return
        handler.removeCallbacks(hideChrome)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(Ui.label(this, "Przyrząd").apply { setPadding(0, dp(10f), 0, dp(8f)) })
        content.addView(Ui.card(this, pfd.style.label, pfd.style.desc, false) {
            closeSheet()
            styleButton.post { openPicker() }
        })
        content.addView(Ui.segmented(this, "Nastawa wysokościomierza", listOf("QNH", "STD 1013"),
            if (hub.std) 1 else 0) { hub.std = it == 1 })
        content.addView(Ui.segmented(this, "Jednostki ciśnienia", listOf("hPa", "inHg"),
            if (hub.inHg) 1 else 0) { hub.inHg = it == 1 })
        content.addView(Ui.segmented(this, "Wektor toru lotu (FPV)", listOf("Wł.", "Wył."),
            if (hub.fpv) 0 else 1) { hub.fpv = it == 0 })
        content.addView(Ui.segmented(this, "Ostrzeżenia głosowe i dźwiękowe", listOf("Wł.", "Wył."),
            if (alerts.voiceOn) 0 else 1) {
            alerts.voiceOn = it == 0
            getSharedPreferences("openpfd", MODE_PRIVATE).edit().putBoolean("alerts", alerts.voiceOn).apply()
        })
        content.addView(Ui.segmented(this, "Wariometr dźwiękowy (szybowiec)", listOf("Wł.", "Wył."),
            if (alerts.varioOn) 0 else 1) {
            alerts.varioOn = it == 0
            getSharedPreferences("openpfd", MODE_PRIVATE).edit().putBoolean("vario", alerts.varioOn).apply()
        })

        content.addView(Ui.label(this, "Czynności").apply { setPadding(0, dp(14f), 0, dp(8f)) })
        val actions = listOf<Pair<String, () -> Unit>>(
            "Kalibruj poziom" to {
                hub.calibrate()
                Toast.makeText(this, "Skalibrowano poziom", Toast.LENGTH_SHORT).show()
            },
            "Resetuj kalibrację" to { hub.resetCalibration() },
            "Odśwież QNH" to { hub.qnhProvider.refresh() },
            "Sprawdź aktualizacje" to { checkUpdates(true) },
            "Test ostrzeżeń" to { alerts.test() },
        )
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (t, a) ->
                row.addView(Ui.pill(this, t) { a() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(8f)
                    bottomMargin = dp(8f)
                })
            }
            content.addView(row)
        }

        val d = hub.snapshot()
        val info = buildString {
            append(if (d.gpsFix) "GPS      ±${d.gpsAccuracy.roundToInt()} m · ${d.sats} SAT\n" else "GPS      szukanie sygnału\n")
            append("QNH      ${d.qnh.roundToInt()} hPa · ${d.qnhSource}\n")
            append("Wysok.   " + (if (!d.altValid) "brak" else if (d.altFromBaro) "barometr" else "GPS (brak barometru)") + "\n")
            append("Kurs     " + (if (d.headingMagnetic) "magnetyczny" else "względny (brak magnetometru)") + "\n")
            append("Teren    " + when {
                d.terrain == null -> "brak (wymaga sieci i GPS)"
                d.raValid -> "RA ${d.raFt.roundToInt()} ft · teren ${d.terrainFt.roundToInt()} ft"
                else -> "pobrany"
            } + "\n")
            append("Wiatr    " + (if (d.windValid) "${d.windDir.roundToInt()}°/${d.windKt.roundToInt()} kt (prognoza)" else "brak") + "\n")
            append("Wersja   ${BuildConfig.VERSION_NAME}")
        }
        content.addView(Ui.label(this, info, 12f).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(12f), 0, dp(4f))
        })
        content.addView(Ui.label(this,
            "Telefon trzymaj pionowo, ekranem do siebie. Przytrzymaj ekran, by wypoziomować. " +
                "Dotknij okna ciśnienia, by przełączyć QNH/STD. Nie do nawigacji lotniczej.",
            12f, Ui.TEXT_FAINT))
        content.addView(Ui.label(this, "Projekt i wykonanie: redzikowski.com", 12f, Ui.TEXT_FAINT).apply {
            setPadding(0, dp(10f), 0, dp(8f))
            setOnClickListener { openUrl("https://redzikowski.com") }
        })
        content.addView(Ui.pill(this, "☕  Postaw kawę") { openUrl("https://buycoffee.to/adamredzikowski") })
        showSheet("Ustawienia", content)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    private fun closeSheet() {
        val s = sheet ?: return
        sheet = null
        s.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(s) }
        showChrome()
    }

    // ---------------------------------------------------------------- aktualizacje

    private fun checkUpdates(manual: Boolean) {
        Updater.check(this, manual) { tag, url -> showBanner(tag, url) }
    }

    private fun showBanner(tag: String, url: String) {
        banner?.let { root.removeView(it) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.shape(Color.BLACK, 999f)
            val p = dp(4f)
            setPadding(dp(16f), p, p, p)
        }
        bar.addView(Ui.label(this, "Dostępna aktualizacja $tag", 14f, Color.WHITE),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
        bar.addView(Ui.pill(this, "Zainstaluj", true) {
            root.removeView(bar)
            banner = null
            Updater.install(this, url)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4f) })
        bar.addView(Ui.pill(this, "✕") {
            root.removeView(bar)
            banner = null
        })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16f)
        })
        banner = bar
    }
}
