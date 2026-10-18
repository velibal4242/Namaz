package tr.veli.namaz

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView

/**
 * Mülk suresi ezber ekranı.
 * Yeni ayet adımları: Tanış → Dinle-tekrarla → Kademeli gizleme (4 seviye) → Ezberden (3 kez) → Önceki ayetlerle zincir.
 * Tekrar: aralıklı tekrar (1-2-4-7-14-30-60-120 gün). Sesli kontrol: telefonun konuşma tanıması (Arapça).
 */
class HifzActivity : Activity() {
    private lateinit var st: Store
    private val GREEN = 0xFF6FCF97.toInt()
    private val TILE0 = 0xFF22493D.toInt()

    // ders akışı
    private sealed class Is {
        data class Ogren(val n: Int, val tekrarIcin: Boolean = false) : Is()
        data class Tekrar(val n: Int) : Is()
    }
    private val kuyruk = ArrayDeque<Is>()
    private var dersToplam = 0

    // ses tanıma geri çağrısı
    private var sesCb: ((List<String>) -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store.of(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (st.long("h_started") == 0L) {
            st.putLong("h_started", now())
            HifzAudio.hepsiniIndir(this)
        }
        Hifz.schedule(this)
        ana()
    }

    override fun onPause() {
        super.onPause()
        // Dinleme modu dışındaki sesler ekrandan çıkınca dursun
        if (!dinlemeModu) HifzAudio.durdur()
    }

    @Deprecated("eski API")
    override fun onBackPressed() {
        if (ekran != "ana") {
            HifzAudio.durdur()
            dinlemeModu = false
            HifzPlayService.durdur(this)
            ana()
        } else {
            super.onBackPressed()
        }
    }

    private var ekran = "ana"
    private var dinlemeModu = false

    // ================================================================ yardımcılar
    private fun t(s: String, sp: Float = 16f, color: Int = Ui.TXT, bold: Boolean = false, center: Boolean = false) =
        Ui.text(this, s, sp, color, bold, center)

    private fun btn(s: String, primary: Boolean = true, f: () -> Unit) = Ui.button(this, s, primary, f)

    private fun satir(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { x ->
            x.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = Ui.dp(this@HifzActivity, 8)
                marginStart = Ui.dp(this@HifzActivity, 3)
                marginEnd = Ui.dp(this@HifzActivity, 3)
            }
            addView(x)
        }
    }

    private fun goster(root: View, ad: String) {
        ekran = ad
        Ui.screen(this, root)
    }

    /** Gizleme seviyeleri: 0 hepsi açık, 1 üçte bir gizli, 2 yarısı gizli, 3 sadece baş harfler, 4 hepsi gizli */
    private fun gizli(seviye: Int, i: Int): Boolean = when (seviye) {
        0 -> false
        1 -> i % 3 == 1
        2 -> i % 2 == 1
        else -> true
    }

    private fun basHarf(w: String): String {
        val baz = HifzCheck.norm(w)
        val kac = if (baz.startsWith("ال") && baz.length > 3) 3 else 1
        val sb = StringBuilder()
        var sayac = 0
        for (ch in w) {
            val isaret = Character.getType(ch) == Character.NON_SPACING_MARK.toInt()
            if (!isaret) {
                if (ch == ' ') break
                if (sayac == kac) break
                sayac++
            }
            sb.append(ch)
        }
        return sb.toString() + "ـــ"
    }

    /**
     * Ayet metni: kelimeye dokununca o kelimenin sesi çalar (ya da gizliyse açılır).
     * renk: kelime başına özel renk (sesli kontrol sonucu için)
     */
    private fun ayetView(
        a: Ayet, seviye: Int = 0, acik: Set<Int> = emptySet(), vurgu: Int = -1,
        renk: Map<Int, Int> = emptyMap(), boyut: Float = 30f, numara: Boolean = true,
        dokun: ((Int) -> Unit)? = null,
    ): TextView {
        val sb = SpannableStringBuilder()
        a.w.forEachIndexed { i, w ->
            if (i > 0) sb.append(" ")
            val sakli = gizli(seviye, i) && i !in acik
            val metin = if (sakli && seviye == 3) basHarf(w) else w
            val s = sb.length
            sb.append(metin)
            val e = sb.length
            when {
                sakli && seviye != 3 -> {
                    sb.setSpan(ForegroundColorSpan(Color.TRANSPARENT), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0xFF2E5A4C.toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                sakli -> sb.setSpan(ForegroundColorSpan(Ui.DIM), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                renk.containsKey(i) -> sb.setSpan(ForegroundColorSpan(renk.getValue(i)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i == vurgu -> sb.setSpan(ForegroundColorSpan(Ui.GOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (dokun != null) {
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(v: View) = dokun(i)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (numara) {
            val s = sb.length
            sb.append("  ﴿${Hifz.arapRakam(a.n)}﴾")
            sb.setSpan(ForegroundColorSpan(Ui.GOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(this).apply {
            text = sb
            textSize = boyut
            setTextColor(Ui.TXT)
            typeface = Hifz.font(this@HifzActivity)
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.5f)
            setPadding(0, Ui.dp(this@HifzActivity, 10), 0, Ui.dp(this@HifzActivity, 10))
            if (dokun != null) {
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
            }
        }
    }

    private fun kart(): LinearLayout = Ui.card(this)

    private fun ilerlemeCubugu(adim: Int, toplam: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 0 until toplam) {
            addView(View(this@HifzActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = Ui.dp(this@HifzActivity, 3).toFloat()
                    setColor(if (i <= adim) Ui.GOLD else TILE0)
                }
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@HifzActivity, 6), 1f).apply {
                    marginStart = Ui.dp(this@HifzActivity, 2)
                    marginEnd = Ui.dp(this@HifzActivity, 2)
                }
            })
        }
    }

    // ================================================================ ana ekran
    private fun ana() {
        HifzAudio.durdur()
        dinlemeModu = false
        val root = Ui.col(this, 18)
        root.addView(t("Mülk Sûresi", 28f, Ui.GOLD, bold = true))
        root.addView(t("Tebâreke · 30 âyet", 15f, Ui.DIM))

        val ezber = Hifz.ezberSayisi(this)
        val yeni = Hifz.bugunkuYeniler(this)
        val tekrar = Hifz.vadesiGelen(this)

        // Bugün
        val bugun = kart()
        bugun.addView(t("Bugün", 18f, Ui.GOLD, bold = true))
        if (Hifz.bugunBitti(this)) {
            bugun.addView(t(if (ezber == 30) "Sûrenin tamamı ezberinde. Bugünkü tekrar bitti. Mâşallah." else "Bugünkü ders tamam. Allah kolaylık versin.", 16f))
            val sonraki = Hifz.siradakiYeni(this)
            if (sonraki != null) bugun.addView(btn("Fazladan: ${sonraki}. ayeti şimdi öğren", false) { dersBaslat(listOf(Is.Ogren(sonraki))) })
        } else {
            val p = mutableListOf<String>()
            if (tekrar.isNotEmpty()) p.add("${tekrar.size} tekrar (${tekrar.joinToString(", ")})")
            if (yeni.isNotEmpty()) p.add("${yeni.size} yeni ayet (${yeni.joinToString(", ")})")
            bugun.addView(t(p.joinToString("\n"), 16f))
            bugun.addView(t("Yaklaşık ${tekrar.size * 1 + yeni.size * 6} dakika", 13f, Ui.DIM))
            bugun.addView(btn("▶  Bugünkü derse başla") {
                dersBaslat(tekrar.map<Int, Is> { Is.Tekrar(it) } + yeni.map { Is.Ogren(it) })
            })
        }
        val kalan = 30 - ezber
        val gun = if (kalan == 0) 0 else (kalan + Hifz.gunluk(this) - 1) / Hifz.gunluk(this)
        bugun.addView(t("Ezber: $ezber / 30   ·   Seri: ${Hifz.seri(this)} gün" + if (kalan > 0) "   ·   Bu hızla $gun günde biter" else "", 13f, Ui.DIM))
        root.addView(bugun)

        // Harita
        val harita = kart()
        harita.addView(t("Âyetler", 18f, Ui.GOLD, bold = true))
        harita.addView(t("Renk: gri öğrenilmedi · sarı yeni · yeşil sağlam. Dokun: o ayeti çalış.", 12f, Ui.DIM))
        for (r in 0 until 5) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0 until 6) {
                val n = r * 6 + k + 1
                val renk = when {
                    !Hifz.ezber(this, n) -> TILE0
                    Hifz.kutu(this, n) >= 3 -> 0xFF2F8F5B.toInt()
                    Hifz.kutu(this, n) >= 1 -> 0xFF8FA83A.toInt()
                    else -> 0xFFB8902F.toInt()
                }
                val vadeli = Hifz.ezber(this, n) && Hifz.vade(this, n) <= Hifz.bugun()
                row.addView(Button(this).apply {
                    text = if (vadeli) "$n•" else "$n"
                    isAllCaps = false
                    textSize = 15f
                    setTextColor(Ui.TXT)
                    background = GradientDrawable().apply {
                        cornerRadius = Ui.dp(this@HifzActivity, 10).toFloat()
                        setColor(renk)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@HifzActivity, 46), 1f).apply {
                        setMargins(Ui.dp(this@HifzActivity, 3), Ui.dp(this@HifzActivity, 3), Ui.dp(this@HifzActivity, 3), Ui.dp(this@HifzActivity, 3))
                    }
                    setOnClickListener { ayetSecenek(n) }
                })
            }
            harita.addView(row)
        }
        root.addView(harita)

        root.addView(btn("🎧  Dinle (tüm sûre / aralık, ekran kapalı da çalar)", false) { dinle() })
        root.addView(btn("📝  Sûre sınavı: ezberden baştan sona", false) { sinav() })
        root.addView(btn("🌙  Yatmadan Mülk: bir kez dinle", false) {
            dinlemeAyar = DinleAyar(1, 30, 1, 1, 0f)
            dinle(otomatik = true)
        })
        root.addView(btn("⚙️  Ayarlar", false) { ayarlar() })

        val fazilet = kart()
        fazilet.addView(t("HADİS-İ ŞERİF", 12f, Ui.GOLD, bold = true, center = true))
        fazilet.addView(t(Hifz.HADIS, 16f, center = true))
        fazilet.addView(t(Hifz.HADIS_KAYNAK, 12f, Ui.DIM, center = true))
        fazilet.addView(t(Hifz.HADIS2, 15f, center = true))
        fazilet.addView(t(Hifz.HADIS2_KAYNAK, 12f, Ui.DIM, center = true))
        root.addView(fazilet)

        root.addView(t("Metin: Tanzil (Hafs). Meal: Elmalılı (sadeleştirilmiş). Sesler everyayah.com / quran.com; bir kez inince internetsiz çalışır (${HifzAudio.inmisSayisi(this)}/30 indi).", 11f, Ui.DIM))
        goster(root, "ana")
    }

    private fun ayetSecenek(n: Int) {
        val ops = mutableListOf("Bu ayeti adım adım öğren", "Bu ayeti tekrar et (kendini sına)", "Dinle")
        AlertDialog.Builder(this).setTitle("$n. ayet")
            .setItems(ops.toTypedArray()) { _, i ->
                when (i) {
                    0 -> dersBaslat(listOf(Is.Ogren(n)))
                    1 -> dersBaslat(listOf(Is.Tekrar(n)))
                    else -> {
                        dinlemeAyar = DinleAyar(n, n, 3, 1, 0f)
                        dinle(otomatik = true)
                    }
                }
            }.show()
    }

    // ================================================================ ders akışı
    private fun dersBaslat(isler: List<Is>) {
        kuyruk.clear()
        kuyruk.addAll(isler)
        dersToplam = isler.size
        sonraki()
    }

    private fun sonraki() {
        HifzAudio.durdur()
        val x = kuyruk.removeFirstOrNull()
        when (x) {
            null -> dersBitti()
            is Is.Ogren -> ogren(x.n, if (x.tekrarIcin) 4 else 0, x.tekrarIcin)
            is Is.Tekrar -> tekrar(x.n)
        }
    }

    private fun dersBaslik(root: LinearLayout, baslik: String) {
        val yapilan = dersToplam - kuyruk.size
        root.addView(t("Ders: $yapilan / $dersToplam", 13f, Ui.DIM))
        root.addView(t(baslik, 22f, Ui.GOLD, bold = true))
    }

    private fun dersBitti() {
        val root = Ui.col(this, 24)
        root.addView(t("🤲", 56f, center = true))
        root.addView(t("Ders tamam", 28f, Ui.GOLD, bold = true, center = true))
        root.addView(t("Ezber: ${Hifz.ezberSayisi(this)} / 30", 18f, center = true))
        root.addView(t("\"Kur'ân okuyun; çünkü o, kıyamet günü okuyanlarına şefaatçi olarak gelecektir.\"", 16f, center = true))
        root.addView(t("Müslim, Salâtü'l-müsâfirîn 252", 12f, Ui.DIM, center = true))
        root.addView(btn("Ana ekrana dön") { ana() })
        goster(root, "bitti")
    }

    // ---------------------------------------------------------------- öğrenme
    /**
     * Adımlar: 0 tanış, 1 dinle-tekrarla, 2..5 gizleme seviyesi 1..4, 6 ezberden, 7 zincir.
     * tekrarIcin: unutulan ayeti yeniden pekiştirme (4'ten başlar, sonunda ezber tarihi değişmez).
     */
    private fun ogren(n: Int, adim: Int, tekrarIcin: Boolean, dogruSeri: Int = 0) {
        val a = Hifz.ayet(this, n)
        val root = Ui.col(this, 16)
        dersBaslik(root, "$n. ayet" + if (tekrarIcin) " · pekiştirme" else "")
        root.addView(ilerlemeCubugu(adim, 8))

        fun ileri() {
            HifzAudio.durdur()
            val zincirVar = n > 1 && Hifz.ezber(this, n - 1)
            when {
                adim < 6 -> ogren(n, adim + 1, tekrarIcin)
                adim == 6 && zincirVar && !tekrarIcin -> ogren(n, 7, tekrarIcin)
                else -> {
                    if (tekrarIcin) Hifz.degerlendir(this, n, 0) else Hifz.ezberlendi(this, n)
                    tebrik(n, tekrarIcin)
                }
            }
        }

        when (adim) {
            0 -> {
                root.addView(t("1 · Tanış: dinle, anlamına bak. Kelimeye dokununca o kelime okunur.", 14f, Ui.DIM))
                val kap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun ciz(v: Int) {
                    kap.removeAllViews()
                    val tv = ayetView(a, vurgu = v, dokun = { i ->
                        HifzAudio.kelimeCal(this, n, i) { ok -> if (!ok) Ui.toast(this, "Kelime sesi inemedi (internet?)") }
                        ciz(i)
                    })
                    kap.addView(tv)
                }
                ciz(-1)
                root.addView(kap)
                root.addView(t(a.tr, 15f, Ui.DIM, center = true))
                root.addView(t(a.meal, 16f, center = true))
                val durum = t("", 14f, Ui.GOLD, center = true)
                root.addView(durum)
                val calAyet = {
                    HifzAudio.ayetCal(this, n, 3, 0f, { o, i ->
                        durum.text = when (o) {
                            "cal" -> "Dinliyorsun (${i + 1}/3)"
                            "hata" -> "Ses inemedi. İnternet açık mı? (Metinden devam edebilirsin)"
                            else -> ""
                        }
                    }) { durum.text = "Bir daha dinlemek için düğmeye bas" }
                }
                root.addView(satir(
                    btn("🔁 Dinle", false) { calAyet() },
                    btn("🐢 Kelime kelime", false) {
                        HifzAudio.kelimeKelime(this, n, a.w.size, { i -> ciz(i) }) { ciz(-1) }
                    },
                ))
                root.addView(btn("Devam →") { ileri() })
                goster(root, "ders")
                calAyet()
            }
            1 -> {
                val kez = Hifz.tekrarSayisi(this)
                root.addView(t("2 · Dinle ve tekrar et: kârî okur, susunca sen sesli oku. $kez kez.", 14f, Ui.DIM))
                root.addView(ayetView(a))
                val durum = t("", 22f, Ui.GOLD, bold = true, center = true)
                root.addView(durum)
                val devam = btn("Devam →") { ileri() }
                val bas = {
                    HifzAudio.ayetCal(this, n, kez, 1.15f, { o, i ->
                        durum.text = when (o) {
                            "cal" -> "🔊 Dinle (${i + 1}/$kez)"
                            "sus" -> "🎙 Şimdi sen oku (${i + 1}/$kez)"
                            else -> "Ses inemedi; ayeti kendin $kez kez sesli oku."
                        }
                    }) { durum.text = "✓ Bitti. Hazırsan devam." }
                }
                root.addView(satir(btn("🔁 Baştan", false) { bas() }, devam))
                goster(root, "ders")
                bas()
            }
            in 2..5 -> {
                val sev = adim - 1
                val acik = mutableSetOf<Int>()
                val aciklama = when (sev) {
                    1 -> "3 · Bazı kelimeler gizli. Ayeti ezberden sesli oku. Takılırsan gizli kelimeye dokun."
                    2 -> "4 · Kelimelerin yarısı gizli. Sesli oku."
                    3 -> "5 · Sadece baş harfler var. Sesli oku."
                    else -> "6 · Hepsi gizli; kutular kelime sayısı kadar. Sesli oku."
                }
                root.addView(t(aciklama, 14f, Ui.DIM))
                val kap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun ciz(renk: Map<Int, Int> = emptyMap(), hepsiAcik: Boolean = false) {
                    kap.removeAllViews()
                    kap.addView(ayetView(a, if (hepsiAcik) 0 else sev, acik, renk = renk, dokun = { i ->
                        if (gizli(sev, i) && i !in acik) {
                            acik.add(i)
                            ciz()
                        } else {
                            HifzAudio.kelimeCal(this, n, i) { }
                        }
                    }))
                }
                ciz()
                root.addView(kap)
                root.addView(t(a.meal, 14f, Ui.DIM, center = true))
                kontrolPaneli(root, a.w, { renk -> ciz(renk, true) }, gecti = {
                    if (acik.size > maxOf(1, a.w.size / 5)) {
                        Ui.toast(this, "Çok ipucu aldın; bu seviyeyi bir daha yapalım.")
                        ogren(n, adim, tekrarIcin)
                    } else ileri()
                }, kaldi = {
                    HifzAudio.ayetCal(this, n, 1, 0f, { _, _ -> }) { }
                    ciz(hepsiAcik = true)
                }, kaldiSonra = { ogren(n, adim, tekrarIcin) })
                goster(root, "ders")
            }
            6 -> {
                val gerek = if (tekrarIcin) 2 else 3
                root.addView(t("7 · Ezberden: metin yok. Ayeti baştan sona sesli oku. Üst üste $gerek doğru gerekiyor ($dogruSeri/$gerek).", 14f, Ui.DIM))
                root.addView(t("﴿${Hifz.arapRakam(n)}﴾", 40f, Ui.GOLD, center = true).apply { typeface = Hifz.font(this@HifzActivity) })
                val ipucu = t("", 26f, Ui.DIM, center = true).apply { typeface = Hifz.font(this@HifzActivity) }
                root.addView(ipucu)
                root.addView(btn("İpucu: ilk kelime", false) { ipucu.text = a.w[0] })
                val kap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                root.addView(kap)
                kontrolPaneli(root, a.w, { renk ->
                    kap.removeAllViews()
                    kap.addView(ayetView(a, renk = renk))
                }, gecti = {
                    if (dogruSeri + 1 >= gerek) ileri() else ogren(n, 6, tekrarIcin, dogruSeri + 1)
                }, kaldi = {
                    kap.removeAllViews()
                    kap.addView(ayetView(a))
                    HifzAudio.ayetCal(this, n, 1, 0f, { _, _ -> }) { }
                }, kaldiSonra = { ogren(n, 6, tekrarIcin, 0) })
                goster(root, "ders")
            }
            else -> {
                val bas = maxOf(1, n - 2)
                val zincir = (bas..n).map { Hifz.ayet(this, it) }
                val kelimeler = zincir.flatMap { it.w }
                root.addView(t("8 · Zincir: ${bas}. ayetten ${n}. ayete kadar ara vermeden sesli oku. Böylece ayetler birbirine bağlanır.", 14f, Ui.DIM))
                root.addView(t(zincir[0].w[0] + " …", 28f, Ui.DIM, center = true).apply { typeface = Hifz.font(this@HifzActivity) })
                val kap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                root.addView(kap)
                kontrolPaneli(root, kelimeler, { renk ->
                    kap.removeAllViews()
                    var ofs = 0
                    zincir.forEach { z ->
                        val r = renk.filterKeys { it >= ofs && it < ofs + z.w.size }.mapKeys { it.key - ofs }
                        kap.addView(ayetView(z, renk = r, boyut = 26f))
                        ofs += z.w.size
                    }
                }, gecti = { ileri() }, kaldi = {
                    kap.removeAllViews()
                    zincir.forEach { kap.addView(ayetView(it, boyut = 26f)) }
                }, kaldiSonra = { ogren(n, 7, tekrarIcin) }, esik = 0.8f)
                goster(root, "ders")
            }
        }
    }

    private fun tebrik(n: Int, tekrarIcin: Boolean) {
        val root = Ui.col(this, 24)
        root.addView(t("✨", 52f, center = true))
        root.addView(t(if (tekrarIcin) "$n. ayet pekişti" else "$n. ayet ezberlendi", 26f, Ui.GOLD, bold = true, center = true))
        root.addView(t(if (tekrarIcin) "Yarın bir daha soracağım." else "Yarın tekrar soracağım; doğru bildikçe aralık açılır (2, 4, 7, 14, 30 gün…).", 15f, Ui.DIM, center = true))
        root.addView(btn(if (kuyruk.isEmpty()) "Bitir" else "Sıradaki →") { sonraki() })
        goster(root, "ders")
    }

    // ---------------------------------------------------------------- tekrar
    private fun tekrar(n: Int) {
        val a = Hifz.ayet(this, n)
        val root = Ui.col(this, 16)
        dersBaslik(root, "Tekrar · $n. ayet")
        root.addView(t("Önceki ayetin sonu aşağıda. Devamını, yani $n. ayeti ezberden sesli oku.", 14f, Ui.DIM))
        if (n > 1) {
            val o = Hifz.ayet(this, n - 1)
            root.addView(t("… " + o.w.takeLast(3).joinToString(" "), 24f, Ui.DIM, center = true).apply { typeface = Hifz.font(this@HifzActivity) })
        } else {
            root.addView(t("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", 24f, Ui.DIM, center = true).apply { typeface = Hifz.font(this@HifzActivity) })
        }
        root.addView(t("﴿${Hifz.arapRakam(n)}﴾", 36f, Ui.GOLD, center = true).apply { typeface = Hifz.font(this@HifzActivity) })
        val meal = t("", 14f, Ui.DIM, center = true)
        root.addView(meal)
        root.addView(btn("İpucu: anlamı", false) { meal.text = a.meal })
        val kap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(kap)
        kontrolPaneli(root, a.w, { renk ->
            kap.removeAllViews()
            kap.addView(ayetView(a, renk = renk))
        }, gecti = {
            // doğru: kolay mı zor mu?
            AlertDialog.Builder(this).setTitle("Nasıldı?")
                .setItems(arrayOf("Rahat okudum", "Zorlandım")) { _, i ->
                    Hifz.degerlendir(this, n, if (i == 0) 2 else 1)
                    sonraki()
                }.setCancelable(false).show()
        }, kaldi = {
            kap.removeAllViews()
            kap.addView(ayetView(a))
            HifzAudio.ayetCal(this, n, 1, 0f, { _, _ -> }) { }
        }, kaldiSonra = {
            // unutulmuş: kısa pekiştirme
            kuyruk.addFirst(Is.Ogren(n, tekrarIcin = true))
            dersToplam++
            sonraki()
        })
        goster(root, "ders")
    }

    // ---------------------------------------------------------------- kontrol paneli
    /**
     * Ezberden okuma sonrası kontrol: sesli kontrol ya da göster-kendin-değerlendir.
     * goster(renk): ayeti renklendirilmiş göster. kaldi: hata sonrası ayeti göster; kaldiSonra: "devam" denince.
     */
    private fun kontrolPaneli(
        root: LinearLayout, kelimeler: List<String>, goster: (Map<Int, Int>) -> Unit,
        gecti: () -> Unit, kaldi: () -> Unit, kaldiSonra: (() -> Unit)? = null, esik: Float = 0.85f,
    ) {
        val sonuc = t("", 16f, Ui.GOLD, bold = true, center = true)
        val alt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dugmeler: () -> View = { View(this) }
        fun basarisiz(mesaj: String) {
            sonuc.setTextColor(Ui.RED)
            sonuc.text = mesaj
            kaldi()
            alt.removeAllViews()
            alt.addView(btn(if (kaldiSonra != null) "Anladım, devam" else "Tekrar dene") {
                if (kaldiSonra != null) kaldiSonra() else { sonuc.text = ""; alt.removeAllViews(); alt.addView(dugmeler()) }
            })
        }
        dugmeler = dugmeler@{
            val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            k.addView(btn("🎤  Sesli kontrol et (okuyacağım)") {
                sesDinle { sonuclar ->
                    val ok = HifzCheck.enIyi(kelimeler, sonuclar)
                    val oran = ok.count { it }.toFloat() / ok.size
                    val renk = ok.indices.associateWith { if (ok[it]) GREEN else Ui.RED }
                    goster(renk)
                    if (oran >= esik) {
                        sonuc.setTextColor(GREEN)
                        sonuc.text = "✓ Doğru (%${(oran * 100).toInt()})"
                        alt.removeAllViews()
                        alt.addView(btn("Devam →") { gecti() })
                    } else {
                        sonuc.setTextColor(Ui.RED)
                        sonuc.text = "Eksik/yanlış: kırmızı kelimeler (%${(oran * 100).toInt()})"
                        alt.removeAllViews()
                        alt.addView(satir(
                            btn("Tekrar dene", false) { sonuc.text = ""; alt.removeAllViews(); alt.addView(dugmeler()) },
                            btn("Hatalıydım", false) { basarisiz("Doğrusunu dinle, sonra yine dene.") },
                        ))
                        alt.addView(btn("Aslında doğru okudum (tanıma hatası)", false) { gecti() })
                    }
                }
            })
            k.addView(btn("👁  Okudum, doğrusunu göster", false) {
                goster(emptyMap())
                alt.removeAllViews()
                alt.addView(t("Kendin karşılaştır:", 14f, Ui.DIM, center = true))
                alt.addView(satir(
                    btn("✓ Doğruydu") { gecti() },
                    btn("✗ Hata yaptım", false) { basarisiz("Doğrusunu dinle; bir daha deneyeceğiz.") },
                ))
            })
            k
        }
        root.addView(sonuc)
        alt.addView(dugmeler())
        root.addView(alt)
    }

    private fun sesDinle(cb: (List<String>) -> Unit) {
        HifzAudio.durdur()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ayeti oku")
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        sesCb = cb
        try {
            startActivityForResult(i, 901)
        } catch (e: ActivityNotFoundException) {
            sesCb = null
            Ui.toast(this, "Telefonda konuşma tanıma yok. \"Okudum, doğrusunu göster\" ile devam et.")
        }
    }

    @Deprecated("eski API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 901) return
        val cb = sesCb ?: return
        sesCb = null
        val list = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (resultCode == RESULT_OK && !list.isNullOrEmpty()) cb(list)
        else Ui.toast(this, "Ses alınamadı. Bir daha dene ya da \"Okudum, doğrusunu göster\"e bas.")
    }

    // ================================================================ sınav
    private fun sinav() {
        val ezberler = (1..30).filter { Hifz.ezber(this, it) }
        if (ezberler.isEmpty()) {
            Ui.toast(this, "Önce birkaç ayet ezberle.")
            return
        }
        val son = ezberler.max()
        AlertDialog.Builder(this).setTitle("Sûre sınavı")
            .setMessage("1. ayetten $son. ayete kadar sırayla ezberden okuyacaksın. Bilemediğin ayet tekrar listesine döner.")
            .setPositiveButton("Başla") { _, _ -> dersBaslat((1..son).filter { Hifz.ezber(this, it) }.map { Is.Tekrar(it) }) }
            .setNegativeButton("Vazgeç", null).show()
    }

    // ================================================================ dinleme
    private data class DinleAyar(var bas: Int, var son: Int, var ayetKez: Int, var turKez: Int, var bosluk: Float)
    private var dinlemeAyar = DinleAyar(1, 30, 1, 1, 0f)
    private var mealGoster = false

    private fun dinle(otomatik: Boolean = false) {
        HifzAudio.durdur()
        dinlemeModu = true
        val ay = dinlemeAyar
        val root = Ui.col(this, 14)
        root.addView(t("Dinle", 24f, Ui.GOLD, bold = true))
        root.addView(t("Bir ayete dokununca oradan başlar. Ekranı kapatsan da okumaya devam eder.", 13f, Ui.DIM))

        val ayarTxt = t("", 14f, Ui.TXT)
        fun ayarYaz() {
            ayarTxt.text = "Aralık ${ay.bas}–${ay.son} · her ayet ${ay.ayetKez}× · tur ${if (ay.turKez == 0) "∞" else "${ay.turKez}×"} · " +
                (if (ay.bosluk > 0) "arada tekrar boşluğu" else "boşluksuz") + " · hız ${Hifz.hiz(this)}"
        }
        ayarYaz()
        root.addView(ayarTxt)
        root.addView(satir(
            btn("Aralık", false) {
                aralikSec(ay.bas, ay.son) { b, s -> ay.bas = b; ay.son = s; ayarYaz() }
            },
            btn("Ayet ×", false) {
                val v = intArrayOf(1, 3, 5, 10, 20)
                AlertDialog.Builder(this).setItems(v.map { "$it kez" }.toTypedArray()) { _, i -> ay.ayetKez = v[i]; ayarYaz() }.show()
            },
            btn("Tur", false) {
                val v = intArrayOf(1, 2, 3, 5, 0)
                AlertDialog.Builder(this).setItems(v.map { if (it == 0) "Sonsuz" else "$it tur" }.toTypedArray()) { _, i -> ay.turKez = v[i]; ayarYaz() }.show()
            },
        ))
        root.addView(satir(
            btn("Boşluk", false) {
                AlertDialog.Builder(this).setItems(arrayOf("Boşluksuz (dinleme)", "Her ayetten sonra tekrar boşluğu (sen de oku)")) { _, i ->
                    ay.bosluk = if (i == 0) 0f else 1.1f; ayarYaz()
                }.show()
            },
            btn("Meal", false) { mealGoster = !mealGoster; dinle() },
        ))

        val satirlar = mutableMapOf<Int, TextView>()
        val sc = ScrollView(this)
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val durum = t("", 15f, Ui.GOLD, bold = true, center = true)

        fun baslat(bas: Int) {
            satirlar.values.forEach { it.setBackgroundColor(Color.TRANSPARENT) }
            HifzAudio.aralikCal(this, bas, ay.son, ay.ayetKez, ay.turKez, ay.bosluk, { n ->
                satirlar.values.forEach { it.setBackgroundColor(Color.TRANSPARENT) }
                satirlar[n]?.let { v ->
                    v.setBackgroundColor(0xFF22493D.toInt())
                    sc.post { sc.smoothScrollTo(0, v.top - Ui.dp(this, 40)) }
                }
                durum.text = "▶ $n. ayet"
            }) {
                durum.text = "Bitti"
                satirlar.values.forEach { it.setBackgroundColor(Color.TRANSPARENT) }
            }
        }

        root.addView(satir(
            btn("▶ Başlat") { baslat(ay.bas) },
            btn("■ Durdur", false) { HifzAudio.durdur(); HifzPlayService.durdur(this); durum.text = "Durdu" },
        ))
        root.addView(durum)

        for (n in 1..30) {
            val a = Hifz.ayet(this, n)
            val v = ayetView(a, boyut = 24f)
            v.setOnClickListener { baslat(n) }
            satirlar[n] = v
            liste.addView(v)
            if (mealGoster) liste.addView(t("$n. ${a.meal}", 13f, Ui.DIM))
        }
        sc.addView(liste)
        sc.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 520))
        root.addView(sc)
        root.addView(btn("← Ana ekran", false) { HifzAudio.durdur(); HifzPlayService.durdur(this); ana() })

        // Bu ekranda dış kaydırma yok; liste kendi içinde kayar
        ekran = "dinle"
        window.decorView.setBackgroundColor(Ui.BG)
        setContentView(root.apply { setBackgroundColor(Ui.BG) })
        if (otomatik) baslat(ay.bas)
    }

    private fun aralikSec(b0: Int, s0: Int, cb: (Int, Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val p1 = NumberPicker(this).apply { minValue = 1; maxValue = 30; value = b0 }
        val p2 = NumberPicker(this).apply { minValue = 1; maxValue = 30; value = s0 }
        box.addView(p1)
        box.addView(TextView(this).apply { text = "  —  "; textSize = 20f })
        box.addView(p2)
        AlertDialog.Builder(this).setTitle("Hangi ayetler?").setView(box)
            .setPositiveButton("Tamam") { _, _ -> cb(minOf(p1.value, p2.value), maxOf(p1.value, p2.value)) }
            .setNegativeButton("Vazgeç", null).show()
    }

    // ================================================================ ayarlar
    private fun ayarlar() {
        val root = Ui.col(this, 18)
        root.addView(t("Ayarlar", 24f, Ui.GOLD, bold = true))

        root.addView(t("Kârî: ${Hifz.kari(this).ad}", 15f))
        root.addView(btn("Kârî değiştir", false) {
            AlertDialog.Builder(this).setItems(Hifz.KARILER.map { it.ad }.toTypedArray()) { _, i ->
                st.put("h_kari", Hifz.KARILER[i].klasor)
                HifzAudio.hepsiniIndir(this)
                ayarlar()
            }.show()
        })

        root.addView(t("Günde yeni ayet: ${Hifz.gunluk(this)}", 15f))
        root.addView(btn("Değiştir", false) {
            val v = arrayOf("1", "2", "3", "4", "5")
            AlertDialog.Builder(this).setItems(v.map { "Günde $it ayet" }.toTypedArray()) { _, i -> st.put("h_perDay", v[i]); Hifz.schedule(this); ayarlar() }.show()
        })

        root.addView(t("Dinle-tekrarla sayısı: ${Hifz.tekrarSayisi(this)}", 15f))
        root.addView(btn("Değiştir", false) {
            val v = arrayOf("3", "5", "7", "10")
            AlertDialog.Builder(this).setItems(v.map { "$it kez" }.toTypedArray()) { _, i -> st.put("h_rep", v[i]); ayarlar() }.show()
        })

        root.addView(t("Okuma hızı: ${Hifz.hiz(this)}", 15f))
        root.addView(btn("Değiştir", false) {
            val v = arrayOf("0.75", "0.85", "1.0", "1.1")
            AlertDialog.Builder(this).setTitle("Hız").setItems(v) { _, i -> st.put("h_speed", v[i]); ayarlar() }.show()
        })

        root.addView(t("Ders hatırlatma saati: ${Hifz.saat(this)} (bitmezse 2 saat sonra bir kez daha)", 15f))
        root.addView(btn("Saati değiştir", false) {
            val (h, m) = Hifz.saat(this).split(":").map { it.toInt() }
            android.app.TimePickerDialog(this, { _, hh, mm ->
                st.put("h_saat", "%02d:%02d".format(hh, mm))
                Hifz.schedule(this)
                ayarlar()
            }, h, m, true).show()
        })

        root.addView(t("İnen sesler: ${HifzAudio.inmisSayisi(this)} / 30", 15f))
        root.addView(btn("Tüm sesleri şimdi indir (internetsiz kullanım için)", false) {
            Ui.toast(this, "İndiriliyor…")
            HifzAudio.hepsiniIndir(this) { ok -> Ui.toast(this, "$ok / 30 ses hazır"); if (ekran == "ayarlar") ayarlar() }
        })

        root.addView(Ui.holdButton(this, "İlerlemeyi sıfırla", seconds = 3, msg = "Sıfırlanıyor") {
            Hifz.sifirla(this)
            Ui.toast(this, "Sıfırlandı")
            ana()
        })
        root.addView(btn("← Geri") { ana() })
        goster(root, "ayarlar")
    }
}
