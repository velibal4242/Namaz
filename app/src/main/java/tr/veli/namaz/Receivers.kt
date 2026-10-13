package tr.veli.namaz

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val st = Store.of(c)
        st.applyPending()
        val ev = Ev.from(i)
        val t = now()
        if (ev != null && st.status(ev.date, ev.vakit) == null) {
            when (ev.type) {
                "PRE", "ALERT" -> Launch.alert(c, ev)
                "NUDGE" -> if (t < ev.end && !st.insideMosque()) Launch.lock(c, ev, minOf(t + 5 * MIN, ev.end), "nudge")
                "HARD" -> if (t < ev.end && !st.insideMosque()) Launch.lock(c, ev, minOf(t + MIN, ev.end), "hard")
                "FINAL" -> if (t < ev.end && !st.insideMosque()) Launch.lock(c, ev, minOf(ev.end, t + 120 * MIN), "final")
                "LOCK", "RING" -> if (t < ev.end && !st.insideMosque()) {
                    Launch.lock(c, ev, minOf(t + maxOf(ev.dur, 1) * MIN, ev.end), "hard")
                }
                "WATCH" -> MosqueService.start(c, ev)
            }
        }
        Scheduler.scheduleNext(c)
        Updater.maybeCheck(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val st = Store.of(c)
        st.applyPending()
        Policy.apply(c)
        Scheduler.scheduleNext(c)
        // Yeniden başlatıldıysa yarım kalan kilidi geri getir
        val kind = st.activeKind()
        val end = st.activeEnd()
        val ev = st.activeEv()
        if (kind != null && ev != null && end > now() && st.status(ev.date, ev.vakit) == null) {
            st.log("Yeniden başlatma: kilit geri getirildi")
            Launch.lock(c, ev, end, st.str("aMode", "hard"))
        }
        // Cami takibi süren bir vakitteysek yeniden başlat
        Scheduler.events(c, 0).filter { it.type == "WATCH" && it.at <= now() && it.end > now() }
            .forEach { if (st.status(it.date, it.vakit) == null) MosqueService.start(c, it) }
    }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Policy.apply(context)
    }
}
