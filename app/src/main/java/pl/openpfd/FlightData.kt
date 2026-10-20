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
)
