package pl.openpfd

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Automatyczne aktualizacje z GitHub Releases. Każdy merge do main publikuje release
 * „v1.0.N”, gdzie N = versionCode. Aplikacja porównuje N z własnym versionCode.
 */
object Updater {
    private const val REPO = "BlackSingularities/OpenPFD"
    @Volatile private var running = false

    fun check(act: Activity, manual: Boolean, onAvailable: (tag: String, url: String) -> Unit) {
        if (running) return
        running = true
        thread(name = "update-check") {
            try {
                val json = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
                val tag = json.getString("tag_name")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url")
                }
                val url = apkUrl
                act.runOnUiThread {
                    if (act.isFinishing) return@runOnUiThread
                    if (code > BuildConfig.VERSION_CODE && url != null) {
                        onAvailable(tag, url)
                    } else if (manual) {
                        Toast.makeText(act, "Masz najnowszą wersję (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) act.runOnUiThread {
                    Toast.makeText(act, "Nie udało się sprawdzić aktualizacji: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                running = false
            }
        }
    }

    fun install(act: Activity, url: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Zezwól OpenPFD na instalowanie aplikacji i spróbuj ponownie", Toast.LENGTH_LONG).show()
            act.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
            )
            return
        }
        Toast.makeText(act, "Pobieranie aktualizacji…", Toast.LENGTH_SHORT).show()
        thread(name = "update-install") {
            try {
                val installer = act.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    try {
                        val len = c.contentLengthLong
                        c.inputStream.use { input ->
                            session.openWrite("base.apk", 0, len).use { out ->
                                input.copyTo(out, 64 * 1024)
                                session.fsync(out)
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(act, id, Intent(act, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                val url = apkUrl
                act.runOnUiThread {
                    Toast.makeText(act, "Błąd aktualizacji: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "OpenPFD")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
