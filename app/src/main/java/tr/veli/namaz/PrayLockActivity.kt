package tr.veli.namaz

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.nfc.tech.TagTechnology
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
 * Tek kilit ekranı. Her vakit için aynı kural:
 *  LOCKED : alarm en yüksek seste, kilit açılana kadar çalar.
 *  PRAY   : telefon seccadedeki etiketin üstünde duruyor; dwell dakikası dolunca "kılındı".
 *           Telefon etiketten kaldırılırsa 20 sn içinde geri konmazsa sayaç sıfırlanır, LOCKED'a döner.
 *  Camiye girmek (MosqueService) kilidi anında açar; camide kalma MosqueService'te sayılır.
 * Beyan yok, düğme yok.
 *
 * voluntary=true: vakit içinde kendi isteğinle "namaza duruyorum" (alarm yok, iptal edilebilir).
 */
class PrayLockActivity : Activity() {
    enum class S { LOCKED, PRAY }

    companion object {
        const val PRAY_MIN = 10
        const val GRACE_MS = 20_000L
    }

    private lateinit var st: Store
    private lateinit var ev: Ev
    private var s = S.LOCKED
    private var voluntary = false
    private var mode = "hard"
    private var silentUntil = 0L
    private var endAt = 0L
    private var prayEnd = 0L
    private var tagLostAt = 0L
    private var tech: TagTechnology? = null
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
        voluntary = intent.getBooleanExtra("voluntary", false)
        mode = intent.getStringExtra("mode") ?: "hard"
        endAt = intent.getLongExtra("endAt", now() + 5 * MIN)
        registerReceiver(arrived, IntentFilter(MosqueService.ARRIVED), Context.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { onBackLike() }
        }
        buildUi()
        setState(S.LOCKED)
        h.post(tick)
    }

    private fun prayMinutes() = if (ev.date == "test") 1 else PRAY_MIN

    /** Namaz kilidi kapalıyken (varsayılan) sadece alarm + uyarı: ekrandan çıkılabilir, alarm çalmaya devam eder. */
    private val lockOn get() = st.str("namazKilit", "0") == "1"

    private fun onBackLike() {
        when {
            voluntary -> cancelVoluntary()
            !lockOn -> moveTaskToBack(true)
        }
    }

    private fun buildUi() {
        val root = Ui.col(this, 24)
        root.addView(Ui.text(this, if (ev.vakit == Vakit.SABAH) "☀️" else "🕌", 48f, center = true))
        root.addView(Ui.text(this, "${ev.vakit.ad} vakti", 30f, Ui.GOLD, bold = true, center = true))
        status = Ui.text(this, "", 18f, Ui.TXT, bold = true, center = true)
        root.addView(status)
        countdown = Ui.text(this, "", 28f, Ui.TXT, bold = true, center = true)
        root.addView(countdown)
        val loc = st.lastLoc()
        if (loc != null) {
            Mosques.nearest(this, loc.first, loc.second)?.let { (c, d) ->
                root.addView(Ui.text(this, "En yakın cami: ${c.ad} · ${d.toInt()} m", 14f, Ui.DIM, center = true))
            }
        }
        root.addView(Ui.text(this, (if (lockOn) "Kilit açılır" else "Alarm tamamen susar") + ": camiye girince ya da telefon ${prayMinutes()} dk seccade etiketinde durunca.", 14f, Ui.DIM, center = true))
        if (!lockOn && !voluntary) root.addView(Ui.text(this, "Telefonu kullanabilirsin; alarm arkada çalmaya devam eder.", 13f, Ui.DIM, center = true))
        val tags = mutableListOf(ev.vakit.etiket, "kalk", "genel")
        if (ev.vakit.cami) tags.add("cami")
        root.addView(Ui.icerik(this, Content.pick(this, tags)))
        root.addView(Ui.button(this, "Etiket okumuyor mu? Ekran kilidini aç", false) {
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        })
        if (!voluntary && mode == "nudge") {
            root.addView(Ui.button(this, "Kapat") { done(null, "kapatıldı") })
        }
        if (!voluntary && mode == "final") {
            root.addView(Ui.button(this, "30 saniye sustur") {
                silentUntil = now() + 30_000L
                stopRing()
            })
        }
        if (voluntary) {
            root.addView(Ui.button(this, "Vazgeç", false) { cancelVoluntary() })
        } else {
            root.addView(Ui.emanetPanel(this) { done("emanet", "emanet kodu") })
        }
        Ui.screen(this, root)
    }

    private fun setState(n: S) {
        s = n
        when (n) {
            S.LOCKED -> {
                if (!voluntary && now() >= silentUntil) startRing()
                tagLostAt = 0L
                status.text = lockedText()
            }
            S.PRAY -> {
                stopRing()
                prayEnd = now() + prayMinutes() * MIN
                status.text = "Telefon seccadede. Namazını kıl, Allah kabul etsin."
            }
        }
        if (!voluntary) st.setActive("LOCK", ev, maxOf(endAt, prayEnd))
    }

    private fun lockedText() = when {
        voluntary -> "Telefonu seccadedeki etiketin üstüne koy (ekran yukarı)."
        mode == "final" -> "Vakit çıkıyor! Alarm susmayacak. Camiye git ya da telefonu seccade etiketine koy."
        mode == "nudge" -> "Namaz vakti. Kılmadın."
        else -> "Vakit daralıyor. Camiye git ya da telefonu seccade etiketine koy."
    }

    private fun onTick() {
        val t = now()
        when (s) {
            S.LOCKED -> {
                if (voluntary) {
                    countdown.text = ""
                    return
                }
                if (mode == "final") {
                    if (silentUntil in 1..t) {
                        silentUntil = 0L
                        startRing()
                    } else if (silentUntil > t) {
                        status.text = "Susturuldu: ${(silentUntil - t) / 1000} sn. Yola çık!"
                    }
                    if (silentUntil == 0L && status.text != lockedText()) status.text = lockedText()
                }
                if (t >= endAt) {
                    done(null, "süre doldu")
                    return
                }
                countdown.text = "Kilit: " + mmss(endAt - t)
            }
            S.PRAY -> {
                val connected = try { tech?.isConnected == true } catch (_: Exception) { false }
                if (!connected) {
                    if (tagLostAt == 0L) tagLostAt = t
                    val left = GRACE_MS - (t - tagLostAt)
                    if (left <= 0) {
                        st.log("${ev.vakit.ad}: telefon etiketten kaldırıldı, sayaç sıfırlandı")
                        closeTech()
                        setState(S.LOCKED)
                        return
                    }
                    status.text = "Telefon etiketten kalktı! ${left / 1000} sn içinde geri koy."
                } else {
                    if (tagLostAt != 0L) {
                        tagLostAt = 0L
                        status.text = "Telefon seccadede. Namazını kıl, Allah kabul etsin."
                    }
                }
                if (t >= prayEnd) {
                    done("evde", "seccade etiketi ${prayMinutes()} dk")
                    return
                }
                countdown.text = mmss(prayEnd - t)
            }
        }
    }

    private fun mmss(ms: Long) = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private fun onTag(tag: Tag) {
        val hex = tag.id.joinToString("") { "%02X".format(it) }
        if (hex != st.str("tagSeccade")) {
            runOnUiThread { Ui.toast(this, "Bu seccade etiketi değil") }
            return
        }
        val t: TagTechnology? = NfcA.get(tag) ?: NfcV.get(tag) ?: NfcF.get(tag) ?: NfcB.get(tag) ?: IsoDep.get(tag)
        try {
            t?.connect()
        } catch (_: Exception) {
        }
        runOnUiThread {
            closeTech()
            tech = t
            if (s == S.LOCKED) setState(S.PRAY) else tagLostAt = 0L
        }
    }

    private fun closeTech() {
        try { tech?.close() } catch (_: Exception) {}
        tech = null
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

    private fun cancelVoluntary() {
        closeTech()
        h.removeCallbacks(tick)
        finish()
    }

    private fun done(how: String?, why: String) {
        if (how != null) st.mark(ev.date, ev.vakit, how) else st.log("${ev.vakit.ad}: kilit bitti ($why)")
        stopRing()
        closeTech()
        st.clearActive()
        h.removeCallbacks(tick)
        Policy.exitLock(this)
        finish()
        Scheduler.scheduleNext(this)
    }

    override fun onResume() {
        super.onResume()
        if (!voluntary && lockOn) Policy.enterLock(this)
        try {
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
            NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
                this, { tag: Tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                extras,
            )
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try {
            NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val e2 = intent.getLongExtra("endAt", 0L)
        if (e2 > endAt) endAt = e2
        val m2 = intent.getStringExtra("mode") ?: return
        if (voluntary) return
        val stronger = Launch.strongest(m2, mode)
        if (stronger != mode) {
            mode = stronger
            buildUi()
            if (s == S.LOCKED) {
                silentUntil = 0L
                setState(S.LOCKED)
            } else {
                status.text = "Telefon seccadede. Namazını kıl, Allah kabul etsin."
            }
        } else if (s == S.LOCKED && now() >= silentUntil) {
            startRing()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackLike()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Geri tuşu kapalı")
    override fun onBackPressed() = onBackLike()

    /** Tam kilit yokken ana sayfa tuşu engellenemez: çıkılırsa yarım saniye sonra geri gelir. */
    override fun onStop() {
        super.onStop()
        if (voluntary || !lockOn || isFinishing || Policy.isOwner(this)) return
        val app = applicationContext
        val again = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Handler(Looper.getMainLooper()).postDelayed({
            if (Store.of(app).shouldBounceBack()) {
                try { app.startActivity(again) } catch (_: Exception) {}
            }
        }, 500)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        stopRing()
        closeTech()
        try {
            unregisterReceiver(arrived)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
