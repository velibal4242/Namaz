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
