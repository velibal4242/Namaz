package tr.veli.namaz

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Namaz alarm ekranı (kilit yok).
 *  nudge: "Kıldım" ya da 15 sn basılı tutarak "Ertele"ye kadar çalar (en fazla 5 dk).
 *  hard : 1 dk çalar.
 *  final: vakit sonuna kadar çalar; "30 saniye sustur" (15 sn basılı tutarak).
 * Tamamen susturan: "Kıldım" ya da camiye girmek. Ekrandan çıkılabilir, alarm arkada çalmaya devam eder.
 */
class PrayLockActivity : Activity() {
    private lateinit var st: Store
    private lateinit var ev: Ev
    private var mode = "hard"
    private var endAt = 0L
    private var silentUntil = 0L
    private var player: MediaPlayer? = null
    private lateinit var status: TextView
    private lateinit var countdown: TextView

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            h.postDelayed(this, 1000)
        }
    }
    private val arrived = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = done(null, "camiye girdi")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        st = Store.of(this)
        val e = Ev.from(intent)
        if (e == null) {
            finish()
            return
        }
        ev = e
        mode = intent.getStringExtra("mode") ?: "hard"
        endAt = intent.getLongExtra("endAt", now() + MIN)
        registerReceiver(arrived, IntentFilter(MosqueService.ARRIVED), Context.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { moveTaskToBack(true) }
        }
        buildUi()
        startRing()
        h.post(tick)
    }

    private fun buildUi() {
        val root = Ui.col(this, 24)
        root.addView(Ui.text(this, if (ev.vakit == Vakit.SABAH) "☀️" else "🕌", 48f, center = true))
        root.addView(Ui.text(this, "${ev.vakit.ad} vakti", 30f, Ui.GOLD, bold = true, center = true))
        status = Ui.text(this, statusText(), 18f, Ui.TXT, bold = true, center = true)
        root.addView(status)
        countdown = Ui.text(this, "", 26f, Ui.TXT, bold = true, center = true)
        root.addView(countdown)
        val ad = ev.vakit.ad
        root.addView(Ui.typeButton(this, "✓ Kıldım", "$ad namazımı kıldım", primary = true) { done("evde", "kıldım") })
        when (mode) {
            "nudge" -> root.addView(Ui.typeButton(this, "Ertele", "$ad namazımı birazdan kılacağım") { done(null, "ertelendi") })
            "final" -> root.addView(Ui.typeButton(this, "30 saniye sustur", "$ad namazımı birazdan kılacağım") {
                silentUntil = now() + 30_000L
                stopRing()
            })
        }
        val loc = st.lastLoc()
        if (loc != null) {
            Mosques.nearest(this, loc.first, loc.second)?.let { (c, d) ->
                root.addView(Ui.text(this, "En yakın cami: ${c.ad} · ${d.toInt()} m", 14f, Ui.DIM, center = true))
            }
        }
        val tags = mutableListOf(ev.vakit.etiket, "kalk", "genel")
        if (ev.vakit.cami) tags.add("cami")
        root.addView(Ui.icerik(this, Content.pick(this, tags)))
        root.addView(Ui.text(this, "Ekrandan çıkabilirsin; alarm \"Kıldım\" diyene ya da camiye girene kadar çalmaya devam eder.", 13f, Ui.DIM, center = true))
        Ui.screen(this, root)
    }

    private fun statusText() = when (mode) {
        "final" -> "Vakit çıkıyor! Kalk, namazını kıl."
        "nudge" -> "Namaz vakti. Henüz kılmadın."
        else -> "Vakit daralıyor. Namazını kıl."
    }

    private fun onTick() {
        val t = now()
        if (t >= endAt) {
            done(null, "süre doldu")
            return
        }
        if (mode == "final") {
            if (silentUntil in 1..t) {
                silentUntil = 0L
                status.text = statusText()
                startRing()
            } else if (silentUntil > t) {
                status.text = "Susturuldu: ${(silentUntil - t) / 1000} sn"
            }
        }
        countdown.text = "%02d:%02d".format((endAt - t) / 60000, ((endAt - t) / 1000) % 60)
    }

    private fun startRing() {
        if (player != null) return
        try {
            val am = getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@PrayLockActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (ex: Exception) {
            st.log("Alarm sesi çalınamadı: ${ex.message}")
        }
        try {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopRing() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        } catch (_: Exception) {
        }
    }

    private fun done(how: String?, why: String) {
        if (how != null) st.mark(ev.date, ev.vakit, how) else st.log("${ev.vakit.ad}: alarm bitti ($why)")
        stopRing()
        st.clearActive()
        h.removeCallbacks(tick)
        finish()
        Scheduler.scheduleNext(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val e2 = intent.getLongExtra("endAt", 0L)
        if (e2 > endAt) endAt = e2
        val m2 = intent.getStringExtra("mode") ?: return
        val stronger = Launch.strongest(m2, mode)
        if (stronger != mode) {
            mode = stronger
            silentUntil = 0L
            buildUi()
        }
        if (now() >= silentUntil) startRing()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("eski API")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        stopRing()
        try {
            unregisterReceiver(arrived)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
