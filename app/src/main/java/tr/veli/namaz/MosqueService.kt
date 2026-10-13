package tr.veli.namaz

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.min

/** Vakit boyunca konumu izler. Camiye girince kilidi kaldırır, yeterince kalınca "camide kılındı" işaretler. */
class MosqueService : Service(), LocationListener {
    companion object {
        const val ARRIVED = "tr.veli.namaz.ARRIVED"

        fun start(c: Context, ev: Ev) {
            try {
                c.startForegroundService(ev.into(Intent(c, MosqueService::class.java)))
            } catch (e: Exception) {
                Store.of(c).log("Cami takibi başlatılamadı: ${e.message}")
            }
        }
    }

    private var ev: Ev? = null
    private var insideSince = 0L
    private val h = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Launch.channels(this)
        val e = Ev.from(intent) ?: ev
        if (e == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ev = e
        startForeground(
            2,
            note("${e.vakit.ad}: cami takibi açık", "Camiye girdiğinde kilit kalkar."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.removeUpdates(this)
            if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())
            }
        } catch (ex: SecurityException) {
            Store.of(this).log("Konum izni yok, cami takibi çalışmıyor")
        }
        h.removeCallbacks(stopper)
        h.postDelayed(stopper, maxOf(MIN, e.end - now()))
        return START_REDELIVER_INTENT
    }

    private fun note(title: String, text: String): Notification =
        Notification.Builder(this, Launch.CH_TAKIP)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()

    override fun onLocationChanged(loc: Location) {
        val e = ev ?: return
        val st = Store.of(this)
        st.setLastLoc(loc.latitude, loc.longitude)
        if (st.status(e.date, e.vakit) != null) {
            stopSelf()
            return
        }
        val near = Mosques.nearest(this, loc.latitude, loc.longitude) ?: return
        val inside = loc.accuracy <= 120f && near.second - min(loc.accuracy.toDouble(), 40.0) <= Mosques.RADIUS
        if (!inside) {
            insideSince = 0L
            return
        }
        val t = now()
        val first = insideSince == 0L
        if (first) insideSince = t
        st.putLong("insideAt", t)
        if (first) {
            sendBroadcast(Intent(ARRIVED).setPackage(packageName))
            getSystemService(NotificationManager::class.java)
                .notify(2, note("${near.first.ad} — hoş geldin", "Kilit kalktı. ${e.dur} dk sonra kılındı sayılır."))
        }
        val entry = e.at + 15 * MIN // WATCH vakitten 15 dk önce başlar
        if (t >= entry && t - maxOf(insideSince, entry) >= e.dur * MIN) {
            st.mark(e.date, e.vakit, "cami")
            getSystemService(NotificationManager::class.java)
                .notify(3, Notification.Builder(this, Launch.CH_TAKIP).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("${e.vakit.ad} camide kılındı ✓").setContentText("Allah kabul etsin.").build())
            Scheduler.scheduleNext(this)
            stopSelf()
        }
    }

    override fun onDestroy() {
        h.removeCallbacks(stopper)
        try {
            getSystemService(LocationManager::class.java).removeUpdates(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
