package tr.veli.namaz

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Cihaz sahibi (Device Owner) yetkisiyle uygulanan kurallar.
 * Kilit ekranı kaldırıldı (13.10 kararı): kilit görevi (lock task) burada ayarlanmaz, başlatılmaz.
 * Kalan kurallar izin/anti-kurcalama amaçlıdır; kaldırılması Veli kararıdır (docs/VELI_KARARLARI.md).
 */
object Policy {
    private val PERMS = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_CONFIG_LOCATION,
    )

    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(c: Context): Boolean = dpm(c).isDeviceOwnerApp(c.packageName)

    fun apply(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        val st = Store.of(c)
        fun t(name: String, f: () -> Unit) {
            try {
                f()
            } catch (e: Exception) {
                st.log("Politika ($name): ${e.message}")
            }
        }
        PERMS.forEach { p ->
            t(p) { d.setPermissionGrantState(a, c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        }
        RESTRICTIONS.forEach { r -> t(r) { d.addUserRestriction(a, r) } }
        t("autoTime") { d.setAutoTimeEnabled(a, true) }
        t("autoZone") { d.setAutoTimeZoneEnabled(a, true) }
        t("location") { d.setLocationEnabled(a, true) }
        t("uninstall") { d.setUninstallBlocked(a, c.packageName, true) }
    }

    /** Cihaz sahipliğini bırakır. Şu an çağıran yok; yalnız elle/ileride kullanım için. */
    fun release(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        RESTRICTIONS.forEach { r -> try { d.clearUserRestriction(a, r) } catch (_: Exception) {} }
        try { d.setUninstallBlocked(a, c.packageName, false) } catch (_: Exception) {}
        try { d.clearDeviceOwnerApp(c.packageName) } catch (_: Exception) {}
        Store.of(c).log("Cihaz sahipliği bırakıldı")
    }
}
