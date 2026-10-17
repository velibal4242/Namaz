package tr.veli.namaz

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Zikir sayacı. forced=true: zorunlu (kilit, geri gelir); false: gönüllü, istediğinde çıkarsın. */
class ZikirActivity : Activity() {
    private lateinit var st: Store
    private var forced = false
    private var lastTap = 0L
    private var idx = 0

    private lateinit var stage: TextView
    private lateinit var title: TextView
    private lateinit var metin: TextView
    private lateinit var anlam: TextView
    private lateinit var hadis: TextView
    private lateinit var counter: TextView
    private lateinit var info: TextView
    private lateinit var tapBtn: Button
    private lateinit var snoozeBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        st = Store.of(this)
        forced = intent.getBooleanExtra("forced", false)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { onBackLike() }
        }
        build()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("forced", false)) forced = true
        build()
        refresh()
    }

    private fun build() {
        val root = Ui.col(this, 20)
        stage = Ui.text(this, "", 14f, Ui.DIM, center = true)
        title = Ui.text(this, "", 26f, Ui.GOLD, bold = true, center = true)
        metin = Ui.text(this, "", 22f, Ui.TXT, bold = true, center = true)
        anlam = Ui.text(this, "", 15f, Ui.DIM, center = true)
        counter = Ui.text(this, "", 64f, Ui.TXT, bold = true, center = true)
        info = Ui.text(this, "", 14f, Ui.DIM, center = true)
        root.addView(stage)
        root.addView(title)
        root.addView(metin)
        root.addView(anlam)
        root.addView(counter)

        val size = Ui.dp(this, 220)
        tapBtn = Button(this).apply {
            text = "Dokun"
            isAllCaps = false
            textSize = 22f
            setTextColor(0xFF10231C.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.GOLD)
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(this@ZikirActivity, 8)
            }
            setOnClickListener { tap() }
        }
        root.addView(tapBtn)
        root.addView(info)

        val card = Ui.card(this)
        hadis = Ui.text(this, "", 15f, Ui.TXT, center = true)
        card.addView(Ui.text(this, "HADİS-İ ŞERİF", 12f, Ui.GOLD, bold = true, center = true))
        card.addView(hadis)
        root.addView(card)

        snoozeBtn = Ui.holdButton(this, "${Zikir.ERTELEME_DK} dk ertele", seconds = 1, msg = "Zikri erteliyorsun") {
            Zikir.snooze(this)
            exit()
        }
        root.addView(snoozeBtn)
        root.addView(Ui.holdButton(this, "Kapat", seconds = 1, msg = "Zikri bırakıyorsun") { exit() })
        Ui.screen(this, root)
    }

    private fun refresh() {
        idx = Zikir.current(this)
        if (idx == -1) {
            finishedScreen()
            return
        }
        val z = Zikir.ITEMS[idx]
        stage.text = "${idx + 1} / ${Zikir.ITEMS.size}   ·   Toplam ${Zikir.total(this)} / ${Zikir.ITEMS.sumOf { it.adet }}"
        title.text = z.baslik
        metin.text = z.metin
        anlam.text = z.anlam
        hadis.text = "${z.hadis}\n(${z.kaynak})"
        counter.text = "${Zikir.count(this, idx)} / ${z.adet}"
        val canSnooze = forced && Zikir.canSnooze()
        snoozeBtn.visibility = if (canSnooze) android.view.View.VISIBLE else android.view.View.GONE
        info.text = when {
            !forced -> "Her dokunuşta bir kez söyle."
            canSnooze -> "Kapatırsan 5 dk sonra, ertelersen ${Zikir.ERTELEME_DK} dk sonra tekrar gelir. ${Zikir.HEDEF}'dan önce bitmeli."
            else -> "Erteleme yok. Kapatırsan 5 dk sonra tekrar gelir."
        }
    }

    private fun tap() {
        if (idx == -1) return
        val z = Zikir.ITEMS[idx]
        val t = now()
        if (z.minGap > 0 && t - lastTap < z.minGap) return
        lastTap = t
        Zikir.add(this, idx)
        val n = Zikir.count(this, idx)
        vibrate(if (n >= z.adet) 400 else if (n % 33 == 0) 120 else 25)
        if (n >= z.adet) {
            st.log("Zikir: ${z.baslik} tamam")
            lastTap = 0L
        }
        refresh()
    }

    private fun finishedScreen() {
        st.put("zForced", "")
        st.log("Günlük zikir tamamlandı")
        Zikir.schedule(this)
        val root = Ui.col(this, 24)
        root.addView(Ui.text(this, "🤲", 56f, center = true))
        root.addView(Ui.text(this, "Bugünkü zikir tamam", 28f, Ui.GOLD, bold = true, center = true))
        root.addView(Ui.text(this, "Allah kabul etsin.", 18f, Ui.TXT, center = true))
        root.addView(Ui.button(this, "Kapat") { exit() })
        Ui.screen(this, root)
    }

    private fun vibrate(ms: Long) {
        try {
            getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun exit() {
        st.put("zForced", "")
        finish()
    }

    /** Geri tuşu kapatmaz; bırakmak için "Kapat"ı 15 sn basılı tutmak gerekir. */
    private fun onBackLike() {
        if (Zikir.done(this)) exit()
    }

    @Deprecated("eski API")
    override fun onBackPressed() = onBackLike()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackLike()
            return true
        }
        // Ses tuşlarıyla da sayabilirsin
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            tap()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

}
