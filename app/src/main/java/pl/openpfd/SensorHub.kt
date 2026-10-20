package pl.openpfd

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fuzja czujników: wektor rotacji (pochylenie, przechylenie, kurs), akcelerometr (kulka ślizgu),
 * barometr (wysokość, wariometr), GPS (prędkość, kąt drogi, wysokość zapasowa).
 * Telefon traktowany jest jak przyrząd zamontowany pionowo przed pilotem – ekranem do pilota.
 */
class SensorHub(ctx: Context) : SensorEventListener, LocationListener {
    private val app = ctx.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = app.getSharedPreferences("openpfd", Context.MODE_PRIVATE)

    private val magRot: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotSensor: Sensor? = magRot ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val accSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val baroSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val hasBaro get() = baroSensor != null
    val qnhProvider = QnhProvider()
    val terrainProvider = TerrainProvider()
    val windProvider = WindProvider()
    @Volatile var displayRotation = Surface.ROTATION_0

    var std: Boolean
        get() = prefs.getBoolean("std", false)
        set(v) = prefs.edit().putBoolean("std", v).apply()
    var inHg: Boolean
        get() = prefs.getBoolean("inHg", false)
        set(v) = prefs.edit().putBoolean("inHg", v).apply()
    var fpv: Boolean
        get() = prefs.getBoolean("fpv", true)
        set(v) = prefs.edit().putBoolean("fpv", v).apply()

    // --- orientacja ---
    private val rm = FloatArray(9)
    private var rawPitch = 0f
    private var rawRoll = 0f
    private var rawHdg = 0f
    private var attInit = false
    private var attTime = 0L
    private var pitchOff = prefs.getFloat("pitchOff", 0f)
    private var rollOff = prefs.getFloat("rollOff", 0f)
    private var slipF = 0f
    private var gF = 1f
    private var lat = 0.0
    private var lon = 0.0
    private var hasPos = false

    // --- barometr ---
    private var pF = 0f
    private var pTime = 0L
    private var altStdF = 0f
    private var vsBaro = 0f

    // --- GPS ---
    private var gsF = 0f
    private var trendF = 0f
    private var gpsTime = 0L
    private var trackTrue = 0f
    private var hasBearing = false
    private var decl = 0f
    private var declTime = 0L
    private var gpsAltF = Float.NaN
    private var gpsVs = 0f
    private var gpsAcc = 0f
    @Volatile private var sats = 0
    private var locationOn = false

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var n = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) n++
            sats = n
        }
    }

    fun start() {
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        baroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        startLocation()
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locationOn) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this, Looper.getMainLooper())
            lm.registerGnssStatusCallback(gnssCb, Handler(Looper.getMainLooper()))
            locationOn = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        if (locationOn) {
            lm.removeUpdates(this)
            lm.unregisterGnssStatusCallback(gnssCb)
            locationOn = false
        }
    }

    /** Zeruje pochylenie i przechylenie w bieżącym położeniu telefonu. */
    fun calibrate() {
        pitchOff = rawPitch
        rollOff = rawRoll
        prefs.edit().putFloat("pitchOff", pitchOff).putFloat("rollOff", rollOff).apply()
    }

    fun resetCalibration() {
        pitchOff = 0f
        rollOff = 0f
        prefs.edit().putFloat("pitchOff", 0f).putFloat("rollOff", 0f).apply()
    }

    /** Osie ekranu (prawo, góra) w układzie urządzenia, zależnie od obrotu wyświetlacza. */
    private fun screenAxes(): FloatArray = when (displayRotation) {
        Surface.ROTATION_90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f)
        Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f)
        Surface.ROTATION_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> onRotation(e)
            Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            Sensor.TYPE_PRESSURE -> onPressure(e)
        }
    }

    private fun onRotation(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rm, e.values)
        val a = screenAxes()
        // składowa pionowa (Up) wektorów ekranu w układzie świata: R * v
        val rz = rm[6] * a[0] + rm[7] * a[1] + rm[8] * a[2]
        val uz = rm[6] * a[3] + rm[7] * a[4] + rm[8] * a[5]
        // „do przodu” = -Z urządzenia (patrzymy przez telefon jak przez przednią szybę)
        val fx = -rm[2]
        val fy = -rm[5]
        val fz = -rm[8]
        val pitch = Math.toDegrees(asin(fz.coerceIn(-1f, 1f).toDouble())).toFloat()
        val roll = Math.toDegrees(atan2(-rz.toDouble(), uz.toDouble())).toFloat()
        val hdg = wrap360(Math.toDegrees(atan2(fx.toDouble(), fy.toDouble())).toFloat())
        if (!attInit) {
            rawPitch = pitch
            rawRoll = roll
            rawHdg = hdg
            attInit = true
        } else {
            rawPitch += 0.35f * (pitch - rawPitch)
            rawRoll = wrap180(rawRoll + 0.35f * angDiff(roll, rawRoll))
            rawHdg = wrap360(rawHdg + 0.25f * angDiff(hdg, rawHdg))
        }
        attTime = SystemClock.elapsedRealtime()
    }

    private fun onAccel(e: SensorEvent) {
        val a = screenAxes()
        val ar = e.values[0] * a[0] + e.values[1] * a[1] + e.values[2] * a[2]
        val au = e.values[0] * a[3] + e.values[1] * a[4] + e.values[2] * a[5]
        val fwd = -e.values[2]
        val up = sqrt(au * au + fwd * fwd).coerceAtLeast(2f)
        // kalibracja przechylenia przesuwa też zero kulki
        val off = sin(Math.toRadians(rollOff.toDouble())).toFloat() * SensorManager.GRAVITY_EARTH
        val s = -(ar + off) / up
        slipF += 0.08f * (s - slipF)
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
            SensorManager.GRAVITY_EARTH
        gF += 0.1f * (g - gF)
    }

    private fun onPressure(e: SensorEvent) {
        val p = e.values[0]
        val t = e.timestamp
        val dt = if (pTime == 0L) -1f else (t - pTime) / 1e9f
        if (dt <= 0f || dt > 3f) {
            pF = p
            altStdF = stdAlt(p)
            vsBaro = 0f
        } else {
            pF += dt / (0.5f + dt) * (p - pF)
            val alt = stdAlt(pF)
            val rate = (alt - altStdF) / dt * 60f
            altStdF = alt
            vsBaro += dt / (1.2f + dt) * (rate - vsBaro)
        }
        pTime = t
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        val t = loc.elapsedRealtimeNanos
        val dt = if (gpsTime == 0L) -1f else (t - gpsTime) / 1e9f
        val spd = if (loc.hasSpeed()) loc.speed * 1.943844f else 0f
        if (dt <= 0f || dt > 5f) {
            gsF = spd
            trendF = 0f
        } else {
            val prev = gsF
            gsF += dt / (0.6f + dt) * (spd - gsF)
            val acc = (gsF - prev) / dt
            trendF += dt / (2f + dt) * (acc * 10f - trendF)
        }
        if (loc.hasBearing() && loc.speed > 1.5f) {
            trackTrue = loc.bearing
            hasBearing = true
        } else if (loc.speed < 1f) {
            hasBearing = false
        }

        val msl = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) loc.mslAltitudeMeters else loc.altitude
        val now = System.currentTimeMillis()
        if (now - declTime > 60_000L) {
            decl = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), msl.toFloat(), now).declination
            declTime = now
        }
        val altFt = (msl * 3.28084).toFloat()
        if (gpsAltF.isNaN() || dt <= 0f || dt > 5f) {
            gpsAltF = altFt
            gpsVs = 0f
        } else {
            val prev = gpsAltF
            gpsAltF += dt / (1f + dt) * (altFt - gpsAltF)
            gpsVs += dt / (2f + dt) * ((gpsAltF - prev) / dt * 60f - gpsVs)
        }
        gpsAcc = loc.accuracy
        gpsTime = t
        qnhProvider.onLocation(loc.latitude, loc.longitude)
        terrainProvider.onLocation(loc.latitude, loc.longitude)
        windProvider.onLocation(loc.latitude, loc.longitude)
        lat = loc.latitude
        lon = loc.longitude
        hasPos = true
        if (pF > 0f && loc.hasAltitude()) {
            val vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else 99f
            qnhProvider.onGpsBaro(pF, msl, vAcc)
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    fun snapshot(): FlightData {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val gpsOk = gpsTime != 0L && nowNs - gpsTime < 3_000_000_000L
        val attOk = attInit && SystemClock.elapsedRealtime() - attTime < 1500
        val q = if (std) 1013.25f else qnhProvider.qnh
        val baroOk = baroSensor != null && pF > 0f
        val alt: Float
        val vs: Float
        val altOk: Boolean
        when {
            baroOk -> {
                alt = 145366.45f * (1f - (pF / q).pow(0.190284f)); vs = vsBaro; altOk = true
            }
            gpsOk && !gpsAltF.isNaN() -> {
                alt = gpsAltF; vs = gpsVs; altOk = true
            }
            else -> {
                alt = 0f; vs = 0f; altOk = false
            }
        }
        val pitch = rawPitch - pitchOff
        val msl = when {
            baroOk -> 145366.45f * (1f - (pF / qnhProvider.qnh).pow(0.190284f))
            gpsOk && !gpsAltF.isNaN() -> gpsAltF
            else -> Float.NaN
        }
        val grid = terrainProvider.grid
        val terrainFt = if (hasPos) grid?.elevationAt(lat, lon)?.let { it * 3.28084f } ?: Float.NaN else Float.NaN
        val raOk = !terrainFt.isNaN() && !msl.isNaN() && gpsOk
        val trackTrueNow = trackTrue
        val wind = if (!msl.isNaN()) windProvider.windAt(msl) else null
        var tas = Float.NaN
        if (wind != null && gpsOk && hasBearing) {
            val wr = Math.toRadians(wind.first.toDouble())
            val tr = Math.toRadians(trackTrueNow.toDouble())
            val ax = gsF * kotlin.math.sin(tr) + wind.second * kotlin.math.sin(wr)
            val ay = gsF * kotlin.math.cos(tr) + wind.second * kotlin.math.cos(wr)
            tas = sqrt(ax * ax + ay * ay).toFloat()
        }
        val sfcT = windProvider.surfaceTempC
        val oat = if (!sfcT.isNaN() && !msl.isNaN()) sfcT - 1.98f * (msl - (if (terrainFt.isNaN()) 0f else terrainFt)) / 1000f else Float.NaN
        val gsMs = gsF / 1.943844f
        val fpa = if (gpsOk && gsF > 3f)
            Math.toDegrees(atan2((vs * 0.00508f).toDouble(), gsMs.toDouble())).toFloat() else pitch
        return FlightData(
            pitch = pitch,
            roll = wrap180(rawRoll - rollOff),
            heading = rawHdg,
            attValid = attOk,
            headingMagnetic = magRot != null,
            slip = slipF,
            speedKt = if (gpsOk) gsF.coerceAtLeast(0f) else 0f,
            speedValid = gpsOk,
            speedTrend = if (gpsOk) trendF else 0f,
            altFt = alt,
            altValid = altOk,
            altFromBaro = baroOk,
            vsFpm = vs,
            track = wrap360(trackTrue - decl),
            hasTrack = gpsOk && hasBearing,
            fpa = fpa,
            qnh = qnhProvider.qnh,
            qnhSource = qnhProvider.source,
            std = std,
            inHg = inHg,
            fpv = fpv,
            gpsAccuracy = gpsAcc,
            sats = sats,
            gpsFix = gpsOk,
            raFt = if (raOk) (msl - terrainFt) else 0f,
            raValid = raOk,
            terrainFt = terrainFt,
            terrain = grid,
            lat = lat,
            lon = lon,
            mslFt = if (msl.isNaN()) 0f else msl,
            windDir = wind?.let { wrap360(it.first - decl) } ?: 0f,
            windKt = wind?.second ?: 0f,
            windValid = wind != null,
            tasKt = tas,
            oatC = oat,
            drift = if (gpsOk && hasBearing) angDiff(wrap360(trackTrue - decl), rawHdg) else 0f,
            gLoad = gF,
            magVar = decl,
        )
    }

    private fun stdAlt(p: Float) = 145366.45f * (1f - (p / 1013.25f).pow(0.190284f))
}

fun wrap360(a: Float): Float {
    val x = a % 360f
    return if (x < 0f) x + 360f else x
}

fun wrap180(a: Float): Float {
    val x = wrap360(a)
    return if (x > 180f) x - 360f else x
}

fun angDiff(a: Float, b: Float): Float = wrap180(a - b)
