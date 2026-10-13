package tr.veli.namaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object Ui {
    const val BG = 0xFF0E2A22.toInt()
    const val CARD = 0xFF163A30.toInt()
    const val GOLD = 0xFFD9B44A.toInt()
    const val TXT = 0xFFF4F1E8.toInt()
    const val DIM = 0xFFA9BDB4.toInt()
    const val RED = 0xFFE0675A.toInt()
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun col(c: Context, pad: Int = 20): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
    }

    fun text(
        c: Context, s: String, sp: Float = 16f, color: Int = TXT,
        bold: Boolean = false, center: Boolean = false,
    ): TextView = TextView(c).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(c, 4), 0, dp(c, 4))
        setLineSpacing(0f, 1.15f)
    }

    fun button(c: Context, label: String, primary: Boolean = true, onClick: () -> Unit): Button = Button(c).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(if (primary) 0xFF10231C.toInt() else TXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 14).toFloat()
            setColor(if (primary) GOLD else CARD)
            if (!primary) setStroke(dp(c, 1), 0xFF2E5A4C.toInt())
        }
        setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 8) }
        setOnClickListener { onClick() }
    }

    /**
     * Basılı tutunca çalışan düğme: [seconds] saniye kesintisiz basılı tutulmalı.
     * Erken bırakılırsa sıfırlanır. Ertelemeyi bilinçli hâle getirmek için.
     */
    fun holdButton(c: Context, label: String, seconds: Int = 15, msg: String = "Namazı erteliyorsun", onDone: () -> Unit): Button {
        val b = button(c, "$label  (${seconds} sn basılı tut)", false) {}
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var start = 0L
        val step = object : Runnable {
            override fun run() {
                val left = seconds - ((now() - start) / 1000).toInt()
                if (left <= 0) {
                    b.text = label
                    start = 0L
                    try {
                        c.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                            .vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } catch (_: Exception) {
                    }
                    onDone()
                    return
                }
                b.text = "$msg… $left"
                h.postDelayed(this, 200)
            }
        }
        b.setOnClickListener(null)
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    start = now()
                    h.post(step)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(step)
                    if (start != 0L) b.text = "$label  (${seconds} sn basılı tut)"
                    start = 0L
                }
            }
            true
        }
        return b
    }

    private fun norm(s: String): String = s.lowercase(java.util.Locale("tr"))
        .map { when (it) { 'ı' -> 'i'; 'ğ' -> 'g'; 'ü' -> 'u'; 'ş' -> 's'; 'ö' -> 'o'; 'ç' -> 'c'; 'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'; else -> it } }
        .filter { it.isLetter() }.joinToString("")

    /**
     * Yazarak onaylanan düğme: açılan pencerede [phrase] cümlesi harf harf yazılmalı (yapıştırma kapalı).
     */
    fun typeButton(a: Activity, label: String, phrase: String, primary: Boolean = false, onDone: () -> Unit): Button =
        button(a, label, primary) {
            val box = col(a, 16)
            box.addView(TextView(a).apply {
                text = "Şu cümleyi yaz:\n\n\"$phrase\""
                textSize = 18f
                setTextColor(0xFF000000.toInt())
            })
            val et = EditText(a).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isLongClickable = false
                setTextIsSelectable(false)
                customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                    override fun onPrepareActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
                    override fun onActionItemClicked(m: android.view.ActionMode?, item: android.view.MenuItem?) = false
                    override fun onDestroyActionMode(m: android.view.ActionMode?) {}
                }
                customInsertionActionModeCallback = customSelectionActionModeCallback
                setTextColor(0xFF000000.toInt())
                textSize = 18f
            }
            box.addView(et)
            val dlg = android.app.AlertDialog.Builder(a).setTitle(label).setView(box)
                .setPositiveButton("Onayla", null)
                .setNegativeButton("Vazgeç", null).create()
            dlg.setOnShowListener {
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (norm(et.text.toString()) == norm(phrase)) {
                        dlg.dismiss()
                        onDone()
                    } else {
                        toast(a, "Cümleyi aynen yaz")
                    }
                }
            }
            dlg.show()
        }

    fun card(c: Context): LinearLayout = col(c, 16).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(c, 18).toFloat()
            setColor(CARD)
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(c, 12) }
    }

    fun screen(a: Activity, content: View) {
        a.window.decorView.setBackgroundColor(BG)
        a.setContentView(ScrollView(a).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        })
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    fun numberField(c: Context, hint: String): EditText = EditText(c).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        this.hint = hint
        setTextColor(TXT)
        setHintTextColor(DIM)
        textSize = 20f
        gravity = Gravity.CENTER
    }

    /** Ayet/hadis kartı */
    fun icerik(c: Context, i: Icerik): LinearLayout = card(c).apply {
        val etiket = when (i.tur) {
            "ayet" -> "AYET-İ KERİME"
            "hadis" -> "HADİS-İ ŞERİF"
            else -> "HATIRLATMA"
        }
        addView(text(c, etiket, 12f, GOLD, bold = true, center = true))
        addView(text(c, i.metin, 21f, TXT, center = true))
        if (i.kaynak.isNotEmpty()) addView(text(c, i.kaynak, 13f, DIM, center = true))
    }

    /** Kilit ekranlarındaki "Acil durum" bölümü: Sefa'ya kod, kod girişi, arama, 112 */
    fun emanetPanel(a: Activity, onUnlock: () -> Unit): View {
        val wrap = col(a, 0)
        val inner = col(a, 0).apply { visibility = View.GONE }
        val toggle = text(a, "Acil durum ▾", 14f, DIM, center = true).apply {
            setPadding(0, dp(a, 16), 0, dp(a, 8))
            setOnClickListener {
                inner.visibility = if (inner.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        inner.addView(
            text(a, "Sadece gerçek bir zorunluluk için. Sefa'yı ara, telefonundaki Namaz kodunu iste.", 13f, DIM, center = true),
        )
        inner.addView(button(a, "Sefa'ya haber ver (SMS)", false) { toast(a, Emanet.request(a)) })
        val et = numberField(a, "6 haneli kod")
        inner.addView(et)
        inner.addView(button(a, "Kodu gir, kilidi aç", false) {
            if (Emanet.check(a, et.text.toString().trim())) onUnlock()
            else toast(a, "Kod hatalı ya da süresi dolmuş")
        })
        inner.addView(button(a, "Sefa'yı ara", false) {
            Store.of(a).putLong("callUntil", now() + 5 * MIN)
            Emanet.callSefa(a)
        })
        inner.addView(button(a, "112 Acil", false) {
            Store.of(a).putLong("callUntil", now() + 10 * MIN)
            a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        })
        wrap.addView(toggle)
        wrap.addView(inner)
        return wrap
    }
}
