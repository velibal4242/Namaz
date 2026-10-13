package tr.veli.namaz

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tüm ayarlar ve durum. Ayar değişiklikleri (kurulum modu dışında) 24 saat sonra geçerli olur. */
class Store private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("namaz", Context.MODE_PRIVATE)

    companion object {
        const val DAY = 24 * 60 * MIN
        fun of(c: Context) = Store(c.applicationContext)
    }

    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun long(k: String, d: Long = 0L): Long = sp.getLong(k, d)
    fun putLong(k: String, v: Long) = sp.edit().putLong(k, v).apply()

    // Konum (vakit hesabı için) — varsayılan Aksaray
    var lat: Double
        get() = str("lat", "38.3726").toDoubleOrNull() ?: 38.3726
        set(v) = put("lat", v.toString())
    var lng: Double
        get() = str("lng", "34.0254").toDoubleOrNull() ?: 34.0254
        set(v) = put("lng", v.toString())

    /** 0 imsak, 1 güneş, 2 öğle, 3 ikindi, 4 akşam, 5 yatsı — dakika düzeltmesi */
    fun offset(i: Int): Int = str("off$i", "0").toIntOrNull() ?: 0
    fun sabahOfset(): Int = str("sabahOfset", "20").toIntOrNull() ?: 20

    // ---- Kurulum modu ve 24 saat kuralı ----
    fun ensureFirstRun() {
        if (long("setupUntil") == 0L) {
            putLong("setupUntil", now() + 2 * DAY)
            log("Uygulama kuruldu. 48 saat kurulum modu.")
        }
    }

    fun inSetup(): Boolean = true // 24 saat bekleme kaldırıldı

    /** @return true: hemen uygulandı, false: 24 saat sonra uygulanacak */
    fun change(k: String, v: String, desc: String): Boolean {
        if (inSetup()) {
            put(k, v)
            log("Ayar: $desc")
            return true
        }
        val arr = JSONArray(str("pending", "[]"))
        arr.put(JSONObject().put("k", k).put("v", v).put("d", desc).put("at", now() + DAY))
        put("pending", arr.toString())
        log("Bekleyen ayar (24 saat): $desc")
        return false
    }

    fun applyPending() {
        val arr = JSONArray(str("pending", "[]"))
        if (arr.length() == 0) return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("at") <= now()) {
                put(o.getString("k"), o.getString("v"))
                log("Uygulandı: " + o.getString("d"))
            } else {
                keep.put(o)
            }
        }
        put("pending", keep.toString())
    }

    fun pending(): List<Pair<String, Long>> {
        val arr = JSONArray(str("pending", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("d") to o.getLong("at") } }
    }

    // ---- Kılındı kayıtları: "cami", "evde", "emanet" ----
    fun status(date: String, v: Vakit): String? = sp.getString("d_${date}_${v.name}", null)

    fun mark(date: String, v: Vakit, how: String) {
        put("d_${date}_${v.name}", how)
        log("${v.ad} ($date): $how")
    }

    // ---- Kayıt defteri ----
    fun log(s: String) {
        val t = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())
        val lines = (str("log").split("\n").filter { it.isNotBlank() } + "$t  $s").takeLast(200)
        put("log", lines.joinToString("\n"))
    }

    // ---- Aktif kilit (yeniden başlatmada geri gelmesi için) ----
    fun setActive(kind: String, ev: Ev, endAt: Long) {
        sp.edit()
            .putString("aKind", kind)
            .putString("aType", ev.type)
            .putString("aVakit", ev.vakit.name)
            .putString("aDate", ev.date)
            .putInt("aDur", ev.dur)
            .putLong("aEvEnd", ev.end)
            .putLong("aEnd", endAt)
            .apply()
    }

    fun clearActive() = sp.edit().remove("aKind").putLong("aEnd", 0L).apply()

    fun activeKind(): String? = sp.getString("aKind", null)
    fun activeEnd(): Long = long("aEnd")
    fun activeEv(): Ev? {
        val v = sp.getString("aVakit", null) ?: return null
        return Ev(now(), str("aType"), Vakit.valueOf(v), str("aDate"), sp.getInt("aDur", 0), long("aEvEnd"))
    }

    // ---- Son konum ve cami içinde olma durumu ----
    fun setLastLoc(la: Double, lo: Double) {
        sp.edit().putString("llat", la.toString()).putString("llng", lo.toString()).putLong("lat_t", now()).apply()
        lat = la
        lng = lo
    }

    fun lastLoc(): Pair<Double, Double>? {
        val a = str("llat").toDoubleOrNull() ?: return null
        val b = str("llng").toDoubleOrNull() ?: return null
        return a to b
    }

    fun lastLocAge(): Long = now() - long("lat_t")

    fun insideMosque(): Boolean = now() - long("insideAt") < 2 * MIN

    // ---- Kilit döngüsü sigortası ----
    /**
     * Son 30 dakikada 10'dan fazla kilit açılmaya çalışıldıysa bir şeyler ters gidiyordur:
     * yeni kilit açılmaz, kayda düşülür. (Normal günde 30 dakikada en fazla 4 kilit olur.)
     */
    fun allowLaunch(): Boolean {
        val t = now()
        val recent = str("launches").split(",").mapNotNull { it.toLongOrNull() }.filter { t - it < 30 * MIN }
        if (recent.size >= 10) {
            log("SİGORTA: 30 dk'da çok fazla kilit, yeni kilit açılmadı")
            return false
        }
        put("launches", (recent + t).joinToString(","))
        return true
    }

    /** Tam kilit yokken: kilit ekranından çıkılırsa geri getirilsin mi? (arama sırasında hayır) */
    fun shouldBounceBack(): Boolean {
        val ev = activeEv() ?: return false
        return activeKind() != null && activeEnd() > now() && now() > long("callUntil") &&
            status(ev.date, ev.vakit) == null && !insideMosque()
    }

    fun clearTest() {
        val e = sp.edit()
        Vakit.values().forEach { e.remove("d_test_${it.name}") }
        e.apply()
    }
}
