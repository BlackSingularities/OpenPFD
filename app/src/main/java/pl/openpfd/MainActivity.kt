package pl.openpfd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var hub: SensorHub
    private lateinit var pfd: PfdView
    private val handler = Handler(Looper.getMainLooper())
    private val periodicUpdate = object : Runnable {
        override fun run() {
            Updater.check(this@MainActivity, false)
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
        pfd.style = if (prefs.getString("style", "AIRBUS") == "BOEING") PfdView.Style.BOEING else PfdView.Style.AIRBUS
        pfd.onMenu = { showMenu() }
        pfd.onCalibrated = { Toast.makeText(this, "Skalibrowano poziom", Toast.LENGTH_SHORT).show() }
        setContentView(pfd)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        }
        handler.postDelayed(periodicUpdate, 3000L)
    }

    override fun onResume() {
        super.onResume()
        hub.displayRotation = currentRotation()
        hub.start()
        hideSystemUi()
    }

    override fun onPause() {
        hub.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    private fun setStyle(s: PfdView.Style) {
        pfd.style = s
        getSharedPreferences("openpfd", MODE_PRIVATE).edit().putString("style", s.name).apply()
    }

    private fun showMenu() {
        val items = listOf<Pair<String, () -> Unit>>(
            (if (pfd.style == PfdView.Style.AIRBUS) "✓ " else "   ") + "Airbus A320" to { setStyle(PfdView.Style.AIRBUS) },
            (if (pfd.style == PfdView.Style.BOEING) "✓ " else "   ") + "Boeing 737" to { setStyle(PfdView.Style.BOEING) },
            "Kalibruj poziom (też: długie przytrzymanie)" to {
                hub.calibrate(); Toast.makeText(this, "Skalibrowano poziom", Toast.LENGTH_SHORT).show()
            },
            "Resetuj kalibrację" to { hub.resetCalibration() },
            (if (hub.std) "Ciśnienie: przełącz na QNH" else "Ciśnienie: przełącz na STD 1013") to { hub.std = !hub.std },
            (if (hub.inHg) "Jednostki ciśnienia: hPa" else "Jednostki ciśnienia: inHg") to { hub.inHg = !hub.inHg },
            (if (hub.fpv) "Ukryj wektor toru lotu (FPV)" else "Pokaż wektor toru lotu (FPV)") to { hub.fpv = !hub.fpv },
            "Odśwież QNH (METAR)" to { hub.qnhProvider.refresh() },
            "Sprawdź aktualizacje" to { Updater.check(this, true) },
            "O aplikacji" to { showAbout() },
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("OpenPFD v" + BuildConfig.VERSION_NAME)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("OpenPFD")
            .setMessage(
                "Primary Flight Display na prawdziwych danych z telefonu.\n\n" +
                    "• Pochylenie/przechylenie/kurs: wektor rotacji (żyroskop+akcelerometr+magnetometr)\n" +
                    "• Prędkość i kąt drogi: GPS (prędkość względem ziemi)\n" +
                    "• Wysokość i V/S: barometr (lub GPS, gdy go brak)\n" +
                    "• QNH: najbliższy METAR, potem Open-Meteo, potem barometr+GPS\n\n" +
                    "Telefon trzymaj pionowo, ekranem do siebie. Długie przytrzymanie = kalibracja poziomu. " +
                    "Dotknięcie okna ciśnienia = QNH/STD.\n\n" +
                    (if (hub.hasBaro) "Barometr: TAK" else "Barometr: BRAK – wysokość z GPS") +
                    "\n\nNie do nawigacji lotniczej."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
