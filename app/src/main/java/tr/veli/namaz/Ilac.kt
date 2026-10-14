package tr.veli.namaz

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Med(val id: String, val ad: String, val doz: String, val saat: String)

/**
 * İlaç takibi. Bilgiler sadece telefonda tutulur (koda yazılmaz).
 * Doz saatinde bildirim; "Aldım" denene kadar her saat tekrar (en fazla 12 saat, 00:00-07:00 arası beklenir).
 */
object Ilac {
    const val CH = "ilac"
    private const val REPEAT_H = 12

    fun all(c: Context): List<Med> = try {
        val arr = JSONArray(Store.of(c).str("meds", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Med(o.getString("id"), o.getString("ad"), o.optString("doz"), o.getString("saat"))
        }.sortedBy { it.saat }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(c: Context, list: List<Med>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("ad", it.ad).put("doz", it.doz).put("saat", it.saat)) }
        Store.of(c).put("meds", arr.toString())
        schedule(c)
    }

    fun add(c: Context, ad: String, doz: String, saat: String) {
        val st = Store.of(c)
        val n = st.long("medSeq") + 1
        st.putLong("medSeq", n)
        save(c, all(c) + Med("m$n", ad.trim(), doz.trim(), saat))
        Store.of(c).log("İlaç eklendi: $ad $saat")
    }

    fun remove(c: Context, id: String) {
        save(c, all(c).filter { it.id != id })
        c.getSystemService(NotificationManager::class.java).cancel(notifId(id))
    }

    /** "HH:mm" -> verilen gün için zaman */
    private fun timeOn(saat: String, dayOffset: Int): Long {
        val (h, m) = saat.split(":").map { it.toInt() }
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun key(m: Med, doseAt: Long) = "ilac_${dateStr(doseAt)}_${m.id}"

    fun taken(c: Context, m: Med, doseAt: Long) = Store.of(c).long(key(m, doseAt)) > 0

    fun markTaken(c: Context, id: String, doseAt: Long) {
        val m = all(c).firstOrNull { it.id == id } ?: return
        Store.of(c).putLong(key(m, doseAt), now())
        Store.of(c).log("İlaç alındı: ${m.ad}")
        c.getSystemService(NotificationManager::class.java).cancel(notifId(id))
        schedule(c)
    }

    /** Şu an hatırlatılması gereken (vakti gelmiş, alınmamış, 12 saati geçmemiş) dozlar */
    fun due(c: Context): List<Pair<Med, Long>> {
        val t = now()
        return all(c).flatMap { m -> listOf(-1, 0).map { m to timeOn(m.saat, it) } }
            .filter { (m, at) -> at <= t + 1000 && t < at + REPEAT_H * 60 * MIN && !taken(c, m, at) }
    }

    /** Bugünkü doz zamanı (ana ekran için) */
    fun todayAt(m: Med) = timeOn(m.saat, 0)

    private fun quiet(t: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        return cal.get(Calendar.HOUR_OF_DAY) < 7
    }

    fun schedule(c: Context) {
        val t = now()
        val cands = mutableListOf<Long>()
        for (m in all(c)) {
            for (d in -1..1) {
                val at = timeOn(m.saat, d)
                if (taken(c, m, at)) continue
                // doz anı + her saat tekrar
                var found = false
                for (k in 0 until REPEAT_H) {
                    val x = at + k * 60 * MIN
                    if (x > t + 1000 && !quiet(x)) {
                        cands.add(x); found = true; break
                    }
                }
                // pencere kapanınca bildirimi temizlemek için
                val close = at + REPEAT_H * 60 * MIN
                if (!found && close > t + 1000) cands.add(close)
            }
        }
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 60, Intent(c, IlacReceiver::class.java).setAction("tr.veli.namaz.ILAC"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = cands.minOrNull()
        if (next == null) {
            am.cancel(pi)
            return
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun notifId(id: String) = 5000 + ((id.removePrefix("m").toIntOrNull() ?: (id.hashCode() and 0x3FF)) % 4000) * 2

    fun notify(c: Context) {
        if (quiet(now())) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "İlaç hatırlatma", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val dueNow = due(c)
        // Penceresi dolan ya da artık beklemeyen ilaçların bildirimini kaldır
        all(c).filter { m -> dueNow.none { it.first.id == m.id } }.forEach { nm.cancel(notifId(it.id)) }
        val st = Store.of(c)
        for ((m, at) in dueNow) {
            // Her ilaç en fazla ~saatte bir uyarır (başka ilacın alarmı onu sıklaştırmasın)
            val lastKey = key(m, at) + "_n"
            if (now() - st.long(lastKey) < 50 * MIN) continue
            st.putLong(lastKey, now())
            val took = Intent(c, IlacReceiver::class.java).setAction("tr.veli.namaz.ILAC_ALDIM")
                .putExtra("id", m.id).putExtra("at", at)
            val tookPi = PendingIntent.getBroadcast(
                c, notifId(m.id), took, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                c, notifId(m.id) + 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val late = ((now() - at) / (60 * MIN)).toInt()
            val n = Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("💊 ${m.ad} ${m.doz}".trim())
                .setContentText(if (late <= 0) "İlaç saati (${m.saat})" else "Henüz almadın — ${m.saat} dozu, $late saat geçti")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Aldım", tookPi).build())
                .build()
            nm.notify(notifId(m.id), n)
        }
    }
}

class IlacReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == "tr.veli.namaz.ILAC_ALDIM") {
            Ilac.markTaken(c, i.getStringExtra("id") ?: return, i.getLongExtra("at", 0L))
            return
        }
        Ilac.notify(c)
        Ilac.schedule(c)
    }
}
