package tr.veli.namaz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

data class ZikirItem(
    val baslik: String,
    val metin: String,
    val anlam: String,
    val hadis: String,
    val kaynak: String,
    /** İki sayım arasında en az bu kadar ms (0 = sınırsız hız) */
    val minGap: Long,
    val adet: Int = 100,
)

/**
 * Günlük zorunlu zikir.
 *  - 18:00'de ekran gelir (kilit). 30 dk ertelenebilir; ertelemeler en geç 20:00'ye kadar.
 *  - 20:00'den sonra ertelenemez; bitene kadar ekran geri gelir. Hedef: 21:00'den önce bitmiş olmak.
 *  - İlerleme kaydedilir; gün içinde istediğin zaman ana ekrandan başlayabilirsin.
 */
object Zikir {
    const val BASLA_SAAT = 18
    const val SON_ERTELEME_SAAT = 20
    const val HEDEF_SAAT = 21
    const val ERTELEME_DK = 30

    val ITEMS = listOf(
        ZikirItem(
            "Tövbe", "Estağfirullâh el-azîm ve etûbü ileyh",
            "Yüce Allah'tan bağışlanma diler, O'na tövbe ederim.",
            "Ey insanlar! Allah'a tövbe edin. Ben günde yüz defa tövbe ediyorum.",
            "Müslim, Zikir 42", 0,
        ),
        ZikirItem(
            "Kelime-i Tevhid", "Lâ ilâhe illallâh",
            "Allah'tan başka ilah yoktur.",
            "Zikrin en faziletlisi lâ ilâhe illallâhtır.",
            "Tirmizî, Deavât 9; İbn Mâce, Edeb 55", 0,
        ),
        ZikirItem(
            "Salavat", "Allâhümme salli alâ seyyidinâ Muhammed",
            "Allah'ım, efendimiz Muhammed'e salât eyle.",
            "Kim bana bir salavat getirirse, Allah da ona on rahmet eder.",
            "Müslim, Salât 70", 0,
        ),
        ZikirItem(
            "Havkale", "Lâ havle ve lâ kuvvete illâ billâh",
            "Güç ve kuvvet ancak Allah'tandır.",
            "Lâ havle ve lâ kuvvete illâ billâh, cennet hazinelerinden bir hazinedir.",
            "Buhârî, Deavât 51; Müslim, Zikir 44", 0,
        ),
        ZikirItem(
            "Tevhid",
            "Lâ ilâhe illallâhu vahdehû lâ şerîke leh, lehü'l-mülkü ve lehü'l-hamdü ve hüve alâ külli şey'in kadîr",
            "Allah'tan başka ilah yoktur; O tektir, ortağı yoktur. Mülk O'nundur, hamd O'nadır. O her şeye kadirdir.",
            "Kim bunu günde yüz defa söylerse on köle azat etmiş gibi sevap alır, ona yüz iyilik yazılır, yüz günahı silinir ve o gün akşama kadar şeytandan korunur.",
            "Buhârî, Bed'ü'l-halk 11; Müslim, Zikir 28", 0,
        ),
    )

    fun today(): String = dateStr(now())

    fun count(c: Context, i: Int, date: String = today()): Int = Store.of(c).long("z_${date}_$i").toInt()

    fun add(c: Context, i: Int) {
        val d = today()
        Store.of(c).putLong("z_${d}_$i", (count(c, i, d) + 1).toLong())
    }

    /** Sıradaki zikir (hepsi bittiyse -1) */
    fun current(c: Context, date: String = today()): Int =
        ITEMS.indices.firstOrNull { count(c, it, date) < ITEMS[it].adet } ?: -1

    fun done(c: Context, date: String = today()) = current(c, date) == -1

    fun total(c: Context, date: String = today()) = ITEMS.indices.sumOf { minOf(count(c, it, date), ITEMS[it].adet) }

    private fun at(hour: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun startAt() = at(BASLA_SAAT)
    fun lastSnooze() = at(SON_ERTELEME_SAAT)

    fun snoozeUntil(c: Context): Long = Store.of(c).long("zSnooze_${today()}")

    fun canSnooze(): Boolean = now() < lastSnooze()

    fun snooze(c: Context) {
        val until = minOf(now() + ERTELEME_DK * MIN, lastSnooze())
        val st = Store.of(c)
        st.putLong("zSnooze_${today()}", until)
        st.put("zForced", "")
        st.log("Zikir ertelendi: ${hhmm(until)}")
        schedule(c)
    }

    /** Zorunlu ekranın açık olması gereken an mı? */
    fun due(c: Context): Boolean {
        if (now() < Scheduler.START) return false
        if (done(c)) return false
        val t = now()
        return t >= startAt() && t >= snoozeUntil(c)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 50, Intent(c, ZikirReceiver::class.java).setAction("tr.veli.namaz.ZIKIR"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val t = now()
        val next = when {
            done(c) -> {
                // yarının başlangıcı
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, BASLA_SAAT)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis
            }
            t < Scheduler.START -> Scheduler.START + BASLA_SAAT * 60 * MIN
            t < startAt() -> startAt()
            t < snoozeUntil(c) -> snoozeUntil(c)
            // zorunlu dönemde ekrandan kaçıldıysa 5 dk'da bir geri gel
            else -> t + 5 * MIN
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun launch(c: Context, forced: Boolean) {
        val i = Intent(c, ZikirActivity::class.java).putExtra("forced", forced)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (forced) Store.of(c).put("zForced", today())
        try {
            c.startActivity(i)
        } catch (e: Exception) {
            Store.of(c).log("Zikir ekranı açılamadı: ${e.message}")
        }
    }

    /** Zorunlu zikir ekranı terk edildiyse geri getirilsin mi? Namaz alarmı çalıyorsa hayır. */
    fun shouldBounce(c: Context): Boolean {
        val st = Store.of(c)
        val namazActive = st.activeKind() != null && st.activeEnd() > now()
        return st.str("zForced") == today() && due(c) && !namazActive && now() > st.long("callUntil")
    }
}

class ZikirReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Zikir.due(c)) Zikir.launch(c, true)
        Zikir.schedule(c)
    }
}
