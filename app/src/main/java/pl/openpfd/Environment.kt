package pl.openpfd

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Siatka wysokości terenu n×n wokół (lat0, lon0); elev[i*n+j], i – północ, j – wschód. */
class TerrainGrid(
    val lat0: Double, val lon0: Double, val n: Int, val spacingM: Double, val elevM: FloatArray,
) {
    private val half = (n - 1) / 2.0

    /** Przesunięcie punktu (północ, wschód) w metrach od środka siatki. */
    fun offsetM(lat: Double, lon: Double): Pair<Double, Double> {
        val north = (lat - lat0) * 111_320.0
        val east = (lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))
        return north to east
    }

    fun pointNorth(i: Int) = (i - half) * spacingM
    fun pointEast(j: Int) = (j - half) * spacingM

    /** Wysokość terenu (m) interpolowana dwuliniowo albo null poza siatką. */
    fun elevationAt(lat: Double, lon: Double): Float? {
        val (north, east) = offsetM(lat, lon)
        val fi = north / spacingM + half
        val fj = east / spacingM + half
        if (fi < 0 || fj < 0 || fi > n - 1 || fj > n - 1) return null
        val i = floor(fi).toInt().coerceAtMost(n - 2)
        val j = floor(fj).toInt().coerceAtMost(n - 2)
        val di = (fi - i).toFloat()
        val dj = (fj - j).toFloat()
        val a = elevM[i * n + j]
        val b = elevM[i * n + j + 1]
        val c = elevM[(i + 1) * n + j]
        val d = elevM[(i + 1) * n + j + 1]
        return (a * (1 - dj) + b * dj) * (1 - di) + (c * (1 - dj) + d * dj) * di
    }
}

/** Pobiera model wysokości terenu (Open-Meteo / Copernicus DEM 90 m) wokół pozycji. */
class TerrainProvider {
    @Volatile var grid: TerrainGrid? = null
        private set
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastAttempt = 0L

    fun onLocation(lat: Double, lon: Double) {
        if (busy) return
        val g = grid
        val now = SystemClock.elapsedRealtime()
        val needed = g == null || run {
            val (n, e) = g.offsetM(lat, lon)
            sqrt(n * n + e * e) > 4000.0
        }
        if (!needed || now - lastAttempt < 20_000L) return
        lastAttempt = now
        busy = true
        exec.execute {
            try {
                grid = fetch(lat, lon)
            } catch (_: Exception) {
            } finally {
                busy = false
            }
        }
    }

    private fun fetch(lat: Double, lon: Double): TerrainGrid {
        val n = 10
        val spacing = 3000.0
        val half = (n - 1) / 2.0
        val lats = StringBuilder()
        val lons = StringBuilder()
        val cosLat = cos(Math.toRadians(lat))
        for (i in 0 until n) for (j in 0 until n) {
            if (lats.isNotEmpty()) {
                lats.append(','); lons.append(',')
            }
            lats.append(String.format(Locale.US, "%.5f", lat + (i - half) * spacing / 111_320.0))
            lons.append(String.format(Locale.US, "%.5f", lon + (j - half) * spacing / (111_320.0 * cosLat)))
        }
        val json = JSONObject(httpGet("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"))
        val arr = json.getJSONArray("elevation")
        val elev = FloatArray(n * n) { arr.optDouble(it, 0.0).toFloat() }
        return TerrainGrid(lat, lon, n, spacing, elev)
    }
}

/** Wiatr na wysokości (prognoza Open-Meteo: 10 m, 850, 700, 500 hPa) i temperatura przy ziemi. */
class WindProvider {
    private data class Level(val altFt: Float, val dir: Float, val kt: Float)

    @Volatile private var levels: List<Level> = emptyList()
    @Volatile var surfaceTempC: Float = Float.NaN
        private set
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastFetch = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun onLocation(lat: Double, lon: Double) {
        val now = SystemClock.elapsedRealtime()
        val moved = lastLat.isNaN() || abs2(lat - lastLat, lon - lastLon) > 0.3
        val stale = now - lastFetch > (if (levels.isEmpty()) 60_000L else 15 * 60_000L)
        if (busy || !(moved || stale)) return
        busy = true
        lastFetch = now
        lastLat = lat
        lastLon = lon
        exec.execute {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,wind_speed_10m,wind_direction_10m" +
                    "&hourly=wind_speed_850hPa,wind_direction_850hPa,wind_speed_700hPa,wind_direction_700hPa," +
                    "wind_speed_500hPa,wind_direction_500hPa&forecast_hours=1&wind_speed_unit=kn"
                val o = JSONObject(httpGet(url))
                val cur = o.getJSONObject("current")
                val h = o.getJSONObject("hourly")
                fun hv(k: String) = h.getJSONArray(k).optDouble(0, Double.NaN).toFloat()
                val list = listOf(
                    Level(0f, cur.getDouble("wind_direction_10m").toFloat(), cur.getDouble("wind_speed_10m").toFloat()),
                    Level(4780f, hv("wind_direction_850hPa"), hv("wind_speed_850hPa")),
                    Level(9880f, hv("wind_direction_700hPa"), hv("wind_speed_700hPa")),
                    Level(18290f, hv("wind_direction_500hPa"), hv("wind_speed_500hPa")),
                ).filter { !it.dir.isNaN() && !it.kt.isNaN() }
                levels = list
                surfaceTempC = cur.optDouble("temperature_2m", Double.NaN).toFloat()
            } catch (_: Exception) {
            } finally {
                busy = false
            }
        }
    }

    /** (kierunek „z którego wieje” – geograficzny, prędkość w węzłach) lub null. */
    fun windAt(altFt: Float): Pair<Float, Float>? {
        val l = levels
        if (l.isEmpty()) return null
        if (altFt <= l.first().altFt) return l.first().dir to l.first().kt
        if (altFt >= l.last().altFt) return l.last().dir to l.last().kt
        for (k in 0 until l.size - 1) {
            val a = l[k]
            val b = l[k + 1]
            if (altFt in a.altFt..b.altFt) {
                val t = (altFt - a.altFt) / (b.altFt - a.altFt)
                // interpolacja wektorowa
                val ax = a.kt * sin(Math.toRadians(a.dir.toDouble()))
                val ay = a.kt * cos(Math.toRadians(a.dir.toDouble()))
                val bx = b.kt * sin(Math.toRadians(b.dir.toDouble()))
                val by = b.kt * cos(Math.toRadians(b.dir.toDouble()))
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                val dir = wrap360(Math.toDegrees(atan2(x, y)).toFloat())
                return dir to sqrt(x * x + y * y).toFloat()
            }
        }
        return null
    }

    private fun abs2(a: Double, b: Double) = sqrt(a * a + b * b)
}

fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "OpenPFD/" + BuildConfig.VERSION_NAME)
    try {
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}
