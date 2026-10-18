package tr.veli.namaz

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import org.json.JSONArray
import java.time.LocalDate
import java.util.Calendar

data class Ayet(val n: Int, val ar: String, val w: List<String>, val tr: String, val meal: String)

data class Kari(val klasor: String, val ad: String, val yedek: String)

/**
 * Mülk (Tebâreke) suresi ezber motoru.
 * Metin: Tanzil "simple" (üç kaynakla karşılaştırıldı), meal: Elmalılı (sadeleştirilmiş), okunuş: Latin harfli.
 * İlerleme yalnız telefonda tutulur.
 */
object Hifz {
    const val SURE = 67
    const val CH = "hifz"

    val KARILER = listOf(
        Kari("Husary_64kbps", "Mahmud Halil el-Husarî (ağır, net — ezber için en iyisi)", "https://verses.quran.com/Husary/mp3/"),
        Kari("Alafasy_128kbps", "Mişârî Râşid el-Afâsî", "https://verses.quran.com/Alafasy/mp3/"),
        Kari("Minshawy_Murattal_128kbps", "Muhammed Sıddîk el-Minşâvî (mürettel)", "https://verses.quran.com/Minshawi/Murattal/mp3/"),
        Kari("Abdul_Basit_Murattal_64kbps", "Abdülbâsıt Abdüssamed (mürettel)", "https://verses.quran.com/AbdulBaset/Murattal/mp3/"),
    )

    /** Tekrar aralıkları (gün): her başarılı tekrarda bir sonraki kutuya geçer */
    val ARALIK = intArrayOf(1, 2, 4, 7, 14, 30, 60, 120)

    const val HADIS = "Kur'ân'da otuz âyetlik bir sûre vardır; sahibine şefaat eder, nihayet onu bağışlatır. O da \"Tebârekellezî biyedihi'l-mülk\" sûresidir."
    const val HADIS_KAYNAK = "Tirmizî, Fezâilü'l-Kur'ân 9; Ebû Dâvûd, Salât 327; İbn Mâce, Edeb 52"
    const val HADIS2 = "Resûlullah (s.a.v.), Elif Lâm Mîm Secde ve Tebâreke sûrelerini okumadan uyumazdı."
    const val HADIS2_KAYNAK = "Tirmizî, Fezâilü'l-Kur'ân 9"

    private var cache: List<Ayet>? = null
    private var font: Typeface? = null

    fun ayetler(c: Context): List<Ayet> {
        cache?.let { return it }
        val txt = c.assets.open("mulk.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(txt)
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val wa = o.getJSONArray("w")
            Ayet(o.getInt("n"), o.getString("ar"), (0 until wa.length()).map { wa.getString(it) }, o.getString("tr"), o.getString("meal"))
        }
        cache = list
        return list
    }

    fun ayet(c: Context, n: Int) = ayetler(c)[n - 1]

    fun font(c: Context): Typeface {
        font?.let { return it }
        val f = try {
            Typeface.createFromAsset(c.assets, "fonts/AmiriQuran-Regular.ttf")
        } catch (_: Exception) {
            Typeface.SERIF
        }
        font = f
        return f
    }

    fun arapRakam(n: Int): String = n.toString().map { (it - '0' + 0x0660).toChar() }.joinToString("")

    // ------------------------------------------------------------ ayarlar
    private fun st(c: Context) = Store.of(c)
    fun gunluk(c: Context) = (st(c).str("h_perDay", "2").toIntOrNull() ?: 2).coerceIn(1, 5)
    fun tekrarSayisi(c: Context) = (st(c).str("h_rep", "5").toIntOrNull() ?: 5).coerceIn(1, 20)
    fun hiz(c: Context) = (st(c).str("h_speed", "1.0").toFloatOrNull() ?: 1f).coerceIn(0.5f, 1.5f)
    fun kari(c: Context): Kari = KARILER.firstOrNull { it.klasor == st(c).str("h_kari", KARILER[0].klasor) } ?: KARILER[0]
    fun saat(c: Context) = st(c).str("h_saat", "20:00")

    // ------------------------------------------------------------ ilerleme
    fun bugun(): Long = LocalDate.now().toEpochDay()
    fun ezber(c: Context, n: Int) = st(c).long("h_l_$n") > 0
    fun kutu(c: Context, n: Int) = st(c).long("h_b_$n").toInt()
    fun vade(c: Context, n: Int) = st(c).long("h_d_$n")
    fun hata(c: Context, n: Int) = st(c).long("h_x_$n").toInt()
    fun ezberSayisi(c: Context) = (1..30).count { ezber(c, it) }
    fun siradakiYeni(c: Context): Int? = (1..30).firstOrNull { !ezber(c, it) }
    fun bugunYeni(c: Context) = st(c).long("h_new_${bugun()}").toInt()

    /** Bugün tekrar edilmesi gereken ayetler */
    fun vadesiGelen(c: Context): List<Int> = (1..30).filter { ezber(c, it) && vade(c, it) <= bugun() }

    /** Bugün öğrenilecek yeni ayetler (günlük hedef kadar) */
    fun bugunkuYeniler(c: Context): List<Int> {
        val kalan = (gunluk(c) - bugunYeni(c)).coerceAtLeast(0)
        return (1..30).filter { !ezber(c, it) }.take(kalan)
    }

    fun bugunBitti(c: Context) = vadesiGelen(c).isEmpty() && bugunkuYeniler(c).isEmpty()

    fun ezberlendi(c: Context, n: Int) {
        val s = st(c)
        if (!ezber(c, n)) s.putLong("h_new_${bugun()}", bugunYeni(c) + 1L)
        s.putLong("h_l_$n", now())
        s.putLong("h_b_$n", 0)
        s.putLong("h_d_$n", bugun() + 1)
        s.log("Mülk: ${n}. ayet ezberlendi")
        gunKontrol(c)
    }

    /** puan: 2 kolay, 1 zor, 0 bilemedim */
    fun degerlendir(c: Context, n: Int, puan: Int) {
        val s = st(c)
        val k = kutu(c, n)
        when (puan) {
            2 -> {
                val nk = minOf(k + 1, ARALIK.size - 1)
                s.putLong("h_b_$n", nk.toLong())
                s.putLong("h_d_$n", bugun() + ARALIK[nk])
            }
            1 -> s.putLong("h_d_$n", bugun() + maxOf(1, ARALIK[k] / 2))
            else -> {
                s.putLong("h_b_$n", 0)
                s.putLong("h_d_$n", bugun() + 1)
                s.putLong("h_x_$n", hata(c, n) + 1L)
            }
        }
        gunKontrol(c)
    }

    private fun gunKontrol(c: Context) {
        if (bugunBitti(c)) st(c).putLong("h_day_${bugun()}", 1)
        schedule(c)
    }

    fun seri(c: Context): Int {
        var d = bugun()
        if (st(c).long("h_day_$d") == 0L) d -= 1
        var n = 0
        while (st(c).long("h_day_$d") > 0) { n++; d-- }
        return n
    }

    fun sifirla(c: Context) {
        val s = st(c)
        for (n in 1..30) listOf("h_l_", "h_b_", "h_d_", "h_x_").forEach { s.putLong(it + n, 0) }
        s.putLong("h_new_${bugun()}", 0)
        s.log("Mülk ezber ilerlemesi sıfırlandı")
        schedule(c)
    }

    // ------------------------------------------------------------ hatırlatma
    private fun saatBugun(c: Context, ekDk: Int): Long {
        val (h, m) = saat(c).split(":").map { it.toIntOrNull() ?: 0 }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, ekDk)
        return cal.timeInMillis
    }

    /** Ders saatinde bir bildirim; bitmediyse 2 saat sonra bir kez daha. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 70, Intent(c, HifzReceiver::class.java).setAction("tr.veli.namaz.HIFZ"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (st(c).long("h_started") == 0L) {
            am.cancel(pi)
            return
        }
        val t = now()
        val adaylar = mutableListOf<Long>()
        if (!bugunBitti(c)) {
            listOf(0, 120).map { saatBugun(c, it) }.filter { it > t + 1000 }.forEach { adaylar.add(it) }
        }
        if (adaylar.isEmpty()) adaylar.add(saatBugun(c, 24 * 60))
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, adaylar.min(), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, adaylar.min(), pi)
        }
    }

    fun notify(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (bugunBitti(c) || ezberSayisi(c) == 0 && st(c).long("h_started") == 0L) {
            nm.cancel(7000)
            return
        }
        nm.createNotificationChannel(NotificationChannel(CH, "Mülk ezberi", NotificationManager.IMPORTANCE_DEFAULT))
        val yeni = bugunkuYeniler(c).size
        val tekrar = vadesiGelen(c).size
        val parcalar = mutableListOf<String>()
        if (yeni > 0) parcalar.add("$yeni yeni ayet")
        if (tekrar > 0) parcalar.add("$tekrar tekrar")
        val open = PendingIntent.getActivity(c, 71, Intent(c, HifzActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("📖 Mülk suresi dersi")
            .setContentText("Bugün: " + parcalar.joinToString(", ") + ". 10 dakikanı al.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(7000, n)
    }
}

class HifzReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Hifz.notify(c)
        Hifz.schedule(c)
    }
}
