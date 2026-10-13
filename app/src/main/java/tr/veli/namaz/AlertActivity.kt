package tr.veli.namaz

import android.app.Activity
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** Tam ekran uyarı (kilit yok). Her seferinde farklı ayet/hadis. */
class AlertActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val ev = Ev.from(intent)
        val vakit = ev?.vakit ?: Vakit.OGLE
        val root = Ui.col(this, 24)
        val baslik = if (ev?.type == "PRE") "${vakit.ad}'e 10 dakika" else "${vakit.ad} vakti girdi"
        val alt = if (vakit.cami) "Cami seni bekliyor. Kalk, yola çık." else "Kalk, abdestini al."
        root.addView(Ui.text(this, "🕌", 48f, center = true))
        root.addView(Ui.text(this, baslik, 28f, Ui.GOLD, bold = true, center = true))
        root.addView(Ui.text(this, alt, 18f, Ui.TXT, center = true))
        val tags = mutableListOf(vakit.etiket, "genel")
        if (vakit.cami) tags.add("cami")
        root.addView(Ui.icerik(this, Content.pick(this, tags)))
        root.addView(Ui.button(this, "Tamam, kalkıyorum") { finish() })
        Ui.screen(this, root)
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri)?.play()
        } catch (_: Exception) {
        }
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ finish() }, 3 * MIN)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
