package tr.veli.namaz

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager

/** Cihaz sahibi (Device Owner) yetkisiyle uygulanan kurallar. */
object Policy {
    private val DIALERS = listOf(
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.server.telecom",
    )

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

    private fun installed(c: Context, p: String): Boolean = try {
        c.packageManager.getPackageInfo(p, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

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
        val pkgs = (listOf(c.packageName) + DIALERS.filter { installed(c, it) }).toTypedArray()
        t("lockTask") { d.setLockTaskPackages(a, pkgs) }
        t("lockFeatures") { d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
    }

    fun release(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        RESTRICTIONS.forEach { r -> try { d.clearUserRestriction(a, r) } catch (_: Exception) {} }
        try { d.setUninstallBlocked(a, c.packageName, false) } catch (_: Exception) {}
        try { d.setLockTaskPackages(a, arrayOf()) } catch (_: Exception) {}
        try { d.clearDeviceOwnerApp(c.packageName) } catch (_: Exception) {}
        Store.of(c).log("Tam kilit kaldırıldı (emanet kodu ile)")
    }

    fun enterLock(a: Activity) {
        if (!isOwner(a)) return
        try {
            if (dpm(a).isLockTaskPermitted(a.packageName)) a.startLockTask()
        } catch (e: Exception) {
            Store.of(a).log("Kilit başlatılamadı: ${e.message}")
        }
    }

    fun exitLock(a: Activity) {
        try { a.stopLockTask() } catch (_: Exception) {}
    }
}
