package pl.openpfd

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Prawdziwe QNH: najbliższy METAR (aviationweather.gov), w razie braku – ciśnienie zredukowane
 * do poziomu morza z Open-Meteo, a bez sieci – wyliczone z barometru telefonu i wysokości GPS.
 */
class QnhProvider {
    @Volatile var qnh: Float = 1013.25f
        private set
    @Volatile var source: String = "BRAK DANYCH"
        private set

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var fromNetwork = false
    private var lastFetch = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun onLocation(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        val moved = lastLat.isNaN() || distKm(lat, lon, lastLat, lastLon) > 30.0
        val stale = now - lastFetch > (if (fromNetwork) 10 * 60_000L else 60_000L)
        if (!busy && (moved || stale)) fetch(lat, lon)
    }

    fun refresh() {
        if (!lastLat.isNaN() && !busy) fetch(lastLat, lastLon)
    }

    /** Zapasowe QNH z barometru (hPa) i wysokości MSL z GPS (m), gdy nie ma sieci. */
    fun onGpsBaro(pressureHpa: Float, mslMeters: Double, vAccuracy: Float) {
        if (fromNetwork || vAccuracy > 15f) return
        val q = pressureHpa / (1.0 - mslMeters / 44330.77).pow(5.25588)
        if (q in 900.0..1100.0) {
            qnh = if (source == "GPS+BARO") qnh * 0.98f + q.toFloat() * 0.02f else q.toFloat()
            source = "GPS+BARO"
        }
    }

    private fun fetch(lat: Double, lon: Double) {
        busy = true
        lastLat = lat
        lastLon = lon
        lastFetch = SystemClock.elapsedRealtime()
        exec.execute {
            try {
                val r = metar(lat, lon) ?: openMeteo(lat, lon)
                if (r != null) {
                    qnh = r.first
                    source = r.second
                    fromNetwork = true
                }
            } catch (_: Exception) {
            } finally {
                busy = false
            }
        }
    }

    private fun metar(lat: Double, lon: Double): Pair<Float, String>? = try {
        val d = 1.2
        val url = "https://aviationweather.gov/api/data/metar?bbox=" +
            "${lat - d},${lon - d * 1.6},${lat + d},${lon + d * 1.6}&format=json"
        val arr = JSONArray(get(url))
        var best: Pair<Float, String>? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val altim = o.optDouble("altim", Double.NaN)
            if (altim.isNaN() || altim < 850 || altim > 1100) continue
            val dist = distKm(lat, lon, o.optDouble("lat", lat), o.optDouble("lon", lon))
            if (dist < bestDist) {
                bestDist = dist
                best = altim.toFloat() to (o.optString("icaoId", "METAR") + " METAR")
            }
        }
        best
    } catch (_: Exception) {
        null
    }

    private fun openMeteo(lat: Double, lon: Double): Pair<Float, String>? = try {
        val o = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=pressure_msl"))
        val p = o.getJSONObject("current").getDouble("pressure_msl")
        if (p in 850.0..1100.0) p.toFloat() to "OPEN-METEO" else null
    } catch (_: Exception) {
        null
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "OpenPFD/" + BuildConfig.VERSION_NAME)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun distKm(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val dy = (a2 - a1) * 111.2
        val dx = (o2 - o1) * 111.2 * cos(Math.toRadians((a1 + a2) / 2))
        return sqrt(dx * dx + dy * dy)
    }
}
