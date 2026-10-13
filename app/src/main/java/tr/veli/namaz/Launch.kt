package tr.veli.namaz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Launch {
    const val CH_ALARM = "namaz_alarm"
    const val CH_TAKIP = "namaz_takip"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val a = NotificationChannel(CH_ALARM, "Namaz uyarıları", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val t = NotificationChannel(CH_TAKIP, "Cami takibi", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(a)
        nm.createNotificationChannel(t)
    }

    private fun show(c: Context, intent: Intent, title: String, text: String, id: Int) {
        channels(c)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(c, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
        try {
            c.startActivity(intent)
        } catch (e: Exception) {
            Store.of(c).log("Ekran açılamadı: ${e.message}")
        }
    }

    fun alert(c: Context, ev: Ev) {
        val title = if (ev.type == "PRE") "${ev.vakit.ad}'e 10 dakika — camiye hazırlan" else "${ev.vakit.ad} vakti"
        show(c, ev.into(Intent(c, AlertActivity::class.java)), title, "Namaz seni bekliyor", 10)
    }

    /**
     * Tek kilit ekranı. mode: nudge (10 sn, kapatılabilir), hard (1 dk, kapatılamaz),
     * final (vakit sonuna kadar, 30 sn susturma). Aynı vakitte süren kilit kısaltılmaz.
     */
    fun lock(c: Context, ev: Ev, endAt0: Long, mode: String = "hard") {
        val st = Store.of(c)
        if (!st.allowLaunch()) return
        var endAt = endAt0
        var m = mode
        val cur = st.activeEv()
        if (st.activeKind() != null && cur != null && cur.vakit == ev.vakit && cur.date == ev.date && st.activeEnd() > now()) {
            endAt = maxOf(endAt, st.activeEnd())
            m = strongest(m, st.str("aMode", "nudge"))
        }
        st.setActive("LOCK", ev, endAt)
        st.put("aMode", m)
        show(
            c, ev.into(Intent(c, PrayLockActivity::class.java)).putExtra("endAt", endAt).putExtra("mode", m),
            "${ev.vakit.ad} — namaza", "Alarm, camiye gidince ya da telefon seccade etiketinde durunca susar.", 11,
        )
    }

    private val RANK = mapOf("nudge" to 0, "hard" to 1, "final" to 2)
    fun strongest(a: String, b: String) = if ((RANK[a] ?: 0) >= (RANK[b] ?: 0)) a else b

    fun ring(c: Context, ev: Ev, endAt: Long) = lock(c, ev, endAt)

    fun voluntary(c: Context, ev: Ev) {
        val i = ev.into(Intent(c, PrayLockActivity::class.java)).putExtra("voluntary", true).putExtra("endAt", ev.end)
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
