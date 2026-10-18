package tr.veli.namaz

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Bir olay.
 * type: PRE (hazırlan), ALERT (uyarı), LOCK (cami kilidi), RING (ev alarmı + NFC akışı), WATCH (cami takibi)
 * dur: dakika (RING için 0 = vakit sonuna kadar; WATCH için camide kalma süresi)
 * end: vakit için kilitlerin ulaşabileceği son an
 */
data class Ev(val at: Long, val type: String, val vakit: Vakit, val date: String, val dur: Int, val end: Long) {
    fun into(i: Intent): Intent = i
        .putExtra("at", at)
        .putExtra("type", type)
        .putExtra("vakit", vakit.name)
        .putExtra("date", date)
        .putExtra("dur", dur)
        .putExtra("end", end)

    companion object {
        fun from(i: Intent?): Ev? {
            if (i == null) return null
            val v = i.getStringExtra("vakit") ?: return null
            return Ev(
                i.getLongExtra("at", 0L),
                i.getStringExtra("type") ?: "",
                Vakit.valueOf(v),
                i.getStringExtra("date") ?: "",
                i.getIntExtra("dur", 0),
                i.getLongExtra("end", 0L),
            )
        }
    }
}

object Scheduler {
    /** Sistem 14 Ekim 2026 00:00'da (İstanbul) başlar; öncesinde alarm/zikir yok. */
    const val START = 1791925200000L

    fun at21(date: String): Long {
        val cal = Calendar.getInstance()
        val p = date.split("-")
        cal.clear()
        cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 21, 0, 0)
        return cal.timeInMillis
    }

    val LOCKS = setOf("NUDGE", "HARD", "FINAL", "LOCK", "RING")


    /** Vaktin kilitler açısından bitişi: sabah güneş, ikindi akşam ezanı. */
    fun effEnd(g: Gun, v: Vakit): Long = when (v) {
        Vakit.SABAH -> g.gunes
        Vakit.OGLE -> g.ikindi
        Vakit.IKINDI -> g.aksam
        Vakit.AKSAM -> g.yatsi
        // Yatsı en geç 21:00; yazın yatsı geç girerse vakitten en az 45 dk sonra
        Vakit.YATSI -> maxOf(at21(g.date), g.yatsi + 45 * MIN)
    }

    fun entry(g: Gun, v: Vakit): Long = when (v) {
        Vakit.SABAH -> g.imsak
        Vakit.OGLE -> g.ogle
        Vakit.IKINDI -> g.ikindi
        Vakit.AKSAM -> g.aksam
        Vakit.YATSI -> g.yatsi
    }

    fun events(ctx: Context, dayOffset: Int): List<Ev> {
        val st = Store.of(ctx)
        val g = PrayerCalc.gun(ctx, dayOffset)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        val cuma = cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val out = mutableListOf<Ev>()

        for (v in Vakit.values()) {
            val start = entry(g, v)
            val end = effEnd(g, v)
            fun add(at: Long, type: String, dur: Int) {
                if (type in LOCKS && (at < start || at >= end)) return
                out.add(Ev(at, type, v, g.date, dur, end))
            }

            val dwell = if (v == Vakit.OGLE && cuma) 35 else 8
            add(start - 15 * MIN, "WATCH", dwell)
            if (v.cami) add(start - 10 * MIN, "PRE", 0)
            add(start, "ALERT", 0)

            val hardFrom = end - 60 * MIN
            val finalAt = end - 30 * MIN

            // 1) Son 1 saate kadar: her yarım saatte 10 sn (kapatılabilir)
            var t = start + 30 * MIN
            while (t < hardFrom) {
                add(t, "NUDGE", 0)
                t += 30 * MIN
            }
            // 2) Son 1 saat: her 10 dk'da 1 dk, kapatılamaz
            t = maxOf(hardFrom, start)
            while (t < finalAt) {
                add(t, "HARD", 1)
                t += 10 * MIN
            }
            // 3) Son 30 dk: vakit sonuna kadar susmaz (30 sn susturma)
            add(maxOf(finalAt, start), "FINAL", 0)
        }

        // Aynı saniyeye düşen olayları 1 sn kaydır
        val sorted = out.sortedBy { it.at }.toMutableList()
        for (i in 1 until sorted.size) {
            if (sorted[i].at <= sorted[i - 1].at) sorted[i] = sorted[i].copy(at = sorted[i - 1].at + 1000L)
        }
        return sorted
    }

    fun nextEvent(ctx: Context): Ev? {
        val st = Store.of(ctx)
        val t = now()
        return (-1..1).flatMap { events(ctx, it) }
            .filter { it.at > t + 500 && it.at >= START && st.status(it.date, it.vakit) == null }
            .minByOrNull { it.at }
    }

    fun scheduleNext(ctx: Context) {
        Zikir.schedule(ctx)
        Ilac.schedule(ctx)
        Hifz.schedule(ctx)
        val st = Store.of(ctx)
        val next = nextEvent(ctx) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = next.into(Intent(ctx, AlarmReceiver::class.java).setAction("tr.veli.namaz.EV"))
        val pi = PendingIntent.getBroadcast(
            ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        }
        st.put("nextEv", "${hhmm(next.at)} · ${next.vakit.ad} · ${tur(next.type)}")
    }

    fun tur(type: String) = when (type) {
        "PRE" -> "hazırlan uyarısı"
        "ALERT" -> "uyarı"
        "NUDGE" -> "10 sn hatırlatma"
        "HARD" -> "1 dk alarm + kilit"
        "FINAL" -> "susmayan alarm + kilit"
        "LOCK", "RING" -> "alarm + kilit"
        "WATCH" -> "cami takibi başlar"
        else -> type
    }
}
