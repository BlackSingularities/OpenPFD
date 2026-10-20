package pl.openpfd

/** Migawka stanu „samolotu” wyliczona z czujników telefonu i GPS. */
data class FlightData(
    val pitch: Float,          // stopnie, + nos w górę
    val roll: Float,           // stopnie, + prawe skrzydło w dół
    val heading: Float,        // kurs magnetyczny, stopnie
    val attValid: Boolean,
    val headingMagnetic: Boolean,
    val slip: Float,           // przyspieszenie poprzeczne / g (kulka)
    val speedKt: Float,        // prędkość względem ziemi (GPS), węzły
    val speedValid: Boolean,
    val speedTrend: Float,     // przyrost prędkości w ciągu 10 s, węzły
    val altFt: Float,          // wysokość barometryczna względem QNH/STD, stopy
    val altValid: Boolean,
    val altFromBaro: Boolean,
    val vsFpm: Float,          // prędkość pionowa, ft/min
    val track: Float,          // kąt drogi magnetyczny
    val hasTrack: Boolean,
    val fpa: Float,            // kąt toru lotu, stopnie
    val qnh: Float,            // hPa
    val qnhSource: String,
    val std: Boolean,
    val inHg: Boolean,
    val fpv: Boolean,
    val gpsAccuracy: Float,
    val sats: Int,
    val gpsFix: Boolean,
    val raFt: Float = 0f,          // wysokość nad terenem (z modelu terenu), stopy
    val raValid: Boolean = false,
    val terrainFt: Float = Float.NaN,
    val terrain: TerrainGrid? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val mslFt: Float = 0f,         // wysokość nad poziomem morza (QNH lub GPS)
    val windDir: Float = 0f,       // kierunek wiatru magnetyczny („z”)
    val windKt: Float = 0f,
    val windValid: Boolean = false,
    val tasKt: Float = Float.NaN,
    val oatC: Float = Float.NaN,
    val drift: Float = 0f,         // kąt znosu = kąt drogi − kurs
    val gLoad: Float = 1f,
    val magVar: Float = 0f,        // deklinacja magnetyczna (E +)
)
