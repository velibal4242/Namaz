package tr.veli.namaz

import android.app.Application

/**
 * Güvenlik ağı: uygulama çökerse aktif kilit kaydı silinir.
 * Böylece "çöktü → yeniden açıldı → kilitledi → yine çöktü" döngüsü oluşmaz.
 */
class NamazApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val st = Store.of(this)
                st.clearActive()
                st.log("ÇÖKME: ${e.javaClass.simpleName}: ${e.message}".take(200))
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
