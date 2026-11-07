package pl.openpfd.feedback

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Okna zgłoszeń dla ekranów zbudowanych z widoków (bez Compose). Logika wysyłki – [Feedback].
 */
object FeedbackViews {
    private val pl get() = Locale.getDefault().language == "pl"
    private fun t(polish: String, english: String) = if (pl) polish else english

    val entryTitle get() = t("Zgłoś błąd lub sugestię", "Report a bug or suggestion")

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun Activity.field(hint: String, lines: Int, email: Boolean = false) = EditText(this).apply {
        this.hint = hint
        if (email) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines
            maxLines = 8
        }
    }

    /** Formularz: błąd albo sugestia, opcjonalny e-mail. */
    fun show(activity: Activity) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
        }
        val kinds = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val bug = RadioButton(activity).apply { id = View.generateViewId(); text = t("Błąd", "Bug"); isChecked = true }
        val feature = RadioButton(activity).apply { id = View.generateViewId(); text = t("Sugestia", "Suggestion") }
        kinds.addView(bug)
        kinds.addView(feature)
        val message = activity.field(t("Co się stało albo czego brakuje?", "What happened or what is missing?"), 4)
        val email = activity.field(t("E-mail (opcjonalnie – jeśli chcesz odpowiedź)", "Email (optional – if you'd like a reply)"), 1, email = true)
        val diagnostics = CheckBox(activity).apply {
            text = t("Dołącz wersję aplikacji i model urządzenia", "Include app version and device model")
            isChecked = true
        }
        box.addView(kinds)
        box.addView(message)
        box.addView(email)
        box.addView(diagnostics)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(entryTitle)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("Wyślij", "Send"), null)
            .setNegativeButton(t("Anuluj", "Cancel"), null)
            .create()
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.setOnClickListener {
                val text = message.text.toString()
                if (text.trim().length < 3) {
                    message.error = t("Opisz problem w kilku słowach.", "Please describe it in a few words.")
                    return@setOnClickListener
                }
                send.isEnabled = false
                val kind = if (feature.isChecked) Feedback.Kind.FEATURE else Feedback.Kind.BUG
                thread {
                    val result = Feedback.sendBlocking(activity, kind, text, email.text.toString(), diagnostics.isChecked)
                    activity.runOnUiThread {
                        if (result.isSuccess) {
                            Toast.makeText(activity, t("Dziękuję! Zgłoszenie dotarło.", "Thank you! Your report was sent."), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            send.isEnabled = true
                            Toast.makeText(activity, t("Nie udało się wysłać. Sprawdź połączenie.", "Couldn't send. Check your connection."), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Pytanie o raport po awarii z poprzedniego uruchomienia. Wołaj w `onCreate`. */
    fun promptCrashIfAny(activity: Activity) {
        val crash = Feedback.pendingCrash(activity) ?: return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
        }
        box.addView(TextView(activity).apply {
            text = t(
                "Ostatnio aplikacja zakończyła działanie przez błąd. Wysłać autorowi raport? Zawiera opis błędu, wersję aplikacji i model urządzenia – bez Twoich danych.",
                "The app recently stopped because of an error. Send a report to the developer? It contains the error details, app version and device model – none of your data.",
            )
        })
        val comment = activity.field(t("Co robiłeś przed awarią? (opcjonalnie)", "What were you doing? (optional)"), 2)
        val email = activity.field(t("E-mail (opcjonalnie)", "Email (optional)"), 1, email = true)
        val details = TextView(activity).apply {
            text = crash.stack.lines().take(30).joinToString("\n")
            typeface = Typeface.MONOSPACE
            textSize = 10f
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        val toggle = TextView(activity).apply {
            text = t("Pokaż szczegóły", "Show details")
            setPadding(0, activity.dp(8), 0, activity.dp(4))
            setOnClickListener {
                details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        box.addView(comment)
        box.addView(email)
        box.addView(toggle)
        box.addView(details)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(t("Aplikacja zamknęła się z błędem", "The app closed unexpectedly"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(t("Wyślij raport", "Send report"), null)
            .setNeutralButton(t("Kopiuj", "Copy")) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("crash", crash.stack))
                Feedback.discardPendingCrash(activity)
            }
            .setNegativeButton(t("Nie wysyłaj", "Don't send")) { _, _ -> Feedback.discardPendingCrash(activity) }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            send.setOnClickListener {
                send.isEnabled = false
                thread {
                    val result = Feedback.sendCrashBlocking(activity, crash, comment.text.toString(), email.text.toString())
                    activity.runOnUiThread {
                        if (result.isSuccess) {
                            Toast.makeText(activity, t("Dziękuję! Raport dotarł.", "Thank you! The report was sent."), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        } else {
                            send.isEnabled = true
                            Toast.makeText(activity, t("Nie udało się wysłać. Sprawdź połączenie.", "Couldn't send. Check your connection."), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
