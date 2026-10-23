package pl.openpfd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Parametry ostrzeżeń dla typu statku powietrznego. */
data class AlertProfile(val vmoKt: Float, val vStallKt: Float, val glider: Boolean)

/**
 * Ostrzeżenia: callouty wysokości radiowej, „bank angle”, „sink rate”, „pull up” (synteza mowy)
 * oraz dźwięki syntezowane na żywo: stall horn, clacker przekroczenia prędkości i wariometr.
 */
class AlertEngine(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile private var ttsReady = false
    val synth = ToneSynth()
    private val handler = Handler(Looper.getMainLooper())

    var voiceOn = true
    var varioOn = true

    private var lastRa = Float.NaN
    private val armed = HashSet<Int>()
    private var airborne = false
    private var lastBank = 0L
    private var lastSink = 0L
    private var lastTerrain = 0L
    private var testing = false

    private val callouts = listOf(
        2500 to "twenty five hundred", 1000 to "one thousand", 500 to "five hundred",
        100 to "one hundred", 50 to "fifty", 40 to "forty", 30 to "thirty", 20 to "twenty", 10 to "ten",
    )

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setSpeechRate(1.15f)
            ttsReady = true
        }
    }

    fun start() = synth.start()
    fun stop() = synth.stop()
    fun shutdown() {
        synth.stop()
        tts.shutdown()
    }

    private fun say(text: String, flush: Boolean = false) {
        if (ttsReady) tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text)
    }

    fun update(d: FlightData, p: AlertProfile) {
        if (testing) return
        val now = SystemClock.elapsedRealtime()
        // stan „w powietrzu” z histerezą
        if (d.speedValid && d.speedKt > p.vStallKt * 1.25f + 5f && (!d.raValid || d.raFt > 50f)) airborne = true
        if (!d.speedValid || d.speedKt < 15f || (d.raValid && d.raFt < 8f && d.speedKt < p.vStallKt)) airborne = false

        if (voiceOn && d.raValid && !lastRa.isNaN() && d.vsFpm < -100f && airborne) {
            for ((alt, word) in callouts) {
                if (lastRa > alt && d.raFt <= alt && alt in armed) {
                    armed.remove(alt)
                    say(word, flush = alt <= 100)
                }
            }
        }
        if (d.raValid) for ((alt, _) in callouts) if (d.raFt > alt + 80) armed.add(alt)
        lastRa = if (d.raValid) d.raFt else Float.NaN

        if (voiceOn && airborne && d.attValid && abs(d.roll) > 35f && now - lastBank > 4000) {
            lastBank = now
            say("bank angle, bank angle", flush = true)
        }
        if (voiceOn && airborne && !p.glider && d.altValid) {
            val ra = if (d.raValid) d.raFt else 1000f
            val pullUp = d.vsFpm < -3000f && ra < 2000f
            val sink = d.vsFpm < -1800f && ra < 2500f
            if ((pullUp || sink) && now - lastSink > 3500) {
                lastSink = now
                say(if (pullUp) "pull up, pull up" else "sink rate, sink rate", flush = true)
            }
        }

        if (voiceOn && airborne && now - lastTerrain > 7000 && terrainAhead(d)) {
            lastTerrain = now
            say("terrain ahead, terrain ahead", flush = true)
        }

        synth.overspeed = voiceOn && d.speedValid && d.speedKt > p.vmoKt
        synth.stall = voiceOn && airborne && p.vStallKt > 0f && d.speedKt < p.vStallKt
        synth.vario = if (varioOn && p.glider && d.altValid) teVarioMs(d) else Float.NaN
    }

    /**
     * Teren na kursie: sprawdza wysokość modelu terenu w punktach 20, 40 i 60 s
     * przed samolotem i porównuje ją z przewidywaną wysokością lotu.
     */
    private fun terrainAhead(d: FlightData): Boolean {
        val g = d.terrain ?: return false
        if (!d.hasTrack || !d.gpsFix || d.speedKt < 40f || d.mslFt.isNaN()) return false
        val trkTrue = Math.toRadians((d.track + d.magVar).toDouble())
        val mPerDegLat = 111320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(d.lat))
        if (mPerDegLon < 1.0) return false
        for (sec in intArrayOf(20, 40, 60)) {
            val dist = d.speedKt * 0.514444f * sec
            val lat = d.lat + cos(trkTrue) * dist / mPerDegLat
            val lon = d.lon + sin(trkTrue) * dist / mPerDegLon
            val elevFt = (g.elevationAt(lat, lon) ?: continue) * 3.28084f
            val futureFt = d.mslFt + d.vsFpm * (sec / 60f)
            if (futureFt - elevFt < 300f) return true
        }
        return false
    }

    /** Wariometr z kompensacją energii całkowitej (TE): V/S + V·dV/dt / g. */
    private fun teVarioMs(d: FlightData): Float {
        val vs = d.vsFpm * 0.00508f
        if (!d.speedValid) return vs
        val v = d.speedKt * 0.514444f
        val dvdt = d.speedTrend * 0.514444f / 10f
        return vs + v * dvdt / 9.81f
    }

    fun test() {
        if (testing) return
        testing = true
        say("five hundred. one hundred. bank angle. sink rate. pull up.", flush = true)
        synth.overspeed = false; synth.stall = false; synth.vario = Float.NaN
        val steps = listOf<Pair<Long, () -> Unit>>(
            5500L to { synth.overspeed = true },
            7500L to { synth.overspeed = false; synth.stall = true },
            9500L to { synth.stall = false; synth.vario = 1f },
            10700L to { synth.vario = 3.5f },
            11900L to { synth.vario = -3f },
            13300L to { synth.vario = Float.NaN; testing = false },
        )
        for ((t, a) in steps) handler.postDelayed(a, t)
    }
}

/** Syntezator dźwięku na wątku AudioTrack. */
class ToneSynth {
    @Volatile var vario = Float.NaN
    @Volatile var stall = false
    @Volatile var overspeed = false
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "tone-synth", priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    private fun loop() {
        val sr = 22050
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(min, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            running = false
            return
        }
        track.play()
        val buf = ShortArray(441)
        var n = 0L
        var phV = 0.0
        var phS = 0.0
        var env = 0.0
        val twoPi = 2 * PI
        try {
            while (running) {
                val v = vario
                val st = stall
                val ov = overspeed
                for (i in buf.indices) {
                    val t = n.toDouble() / sr
                    var s = 0.0
                    if (!v.isNaN()) {
                        if (v > 0.15f) {
                            val f = (550.0 + 160.0 * v).coerceAtMost(1800.0)
                            val rate = 1.6 + 1.3 * v
                            val on = (t * rate) % 1.0 < 0.5
                            env += ((if (on) 1.0 else 0.0) - env) * 0.01
                            phV += twoPi * f / sr
                            s += 0.35 * env * sin(phV)
                        } else if (v < -1.8f) {
                            val f = (380.0 + 35.0 * v).coerceAtLeast(180.0)
                            env += (1.0 - env) * 0.01
                            phV += twoPi * f / sr
                            s += 0.25 * env * sin(phV)
                        } else env *= 0.99
                    }
                    if (st) {
                        phS += twoPi * 1650.0 / sr
                        val sq = if (sin(phS) >= 0) 1.0 else -1.0
                        s += 0.18 * sq + 0.12 * sin(phS * 2.01)
                    }
                    if (ov) {
                        val pos = (t * 11.0) % 1.0
                        s += 0.7 * exp(-pos * 60.0) * sin(twoPi * 2300.0 * t) * (if (pos < 0.08) 1.0 else 0.0)
                    }
                    buf[i] = (s.coerceIn(-1.0, 1.0) * 26000).toInt().toShort()
                    n++
                }
                if (phV > 1e6) phV %= twoPi
                if (phS > 1e6) phS %= twoPi
                track.write(buf, 0, buf.size)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
