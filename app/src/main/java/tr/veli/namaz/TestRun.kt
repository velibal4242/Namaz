package tr.veli.namaz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Hızlı deneme: bir vaktin akışını ~7 dakikaya sıkıştırır.
 * Gerçek vakit planına dokunmaz; kayıtlar "test" tarihine yazılır.
 * Testte seccade etiketi bekleme süresi 1 dk'dır.
 */
object TestRun {
    fun start(c: Context) {
        val st = Store.of(c)
        st.clearTest()
        st.put("launches", "")
        val t = now()
        val end = t + 7 * MIN
        val plan = listOf(
            Ev(t + 20_000, "ALERT", Vakit.OGLE, "test", 0, end),
            Ev(t + 60_000, "NUDGE", Vakit.OGLE, "test", 0, end),
            Ev(t + 2 * MIN, "HARD", Vakit.OGLE, "test", 1, end),
            Ev(t + 4 * MIN, "FINAL", Vakit.OGLE, "test", 0, end),
        )
        val am = c.getSystemService(AlarmManager::class.java)
        plan.forEachIndexed { i, ev ->
            val pi = PendingIntent.getBroadcast(
                c, 100 + i,
                ev.into(Intent(c, AlarmReceiver::class.java).setAction("tr.veli.namaz.TEST$i")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ev.at, pi)
            }
        }
        st.log("Hızlı deneme başladı (10 dk)")
    }
}
