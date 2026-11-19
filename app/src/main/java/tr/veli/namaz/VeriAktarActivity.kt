package tr.veli.namaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import org.json.JSONObject

internal object ReisHandoff {
    const val PACKAGE = "tr.koleji.dogareis"
    const val ACTION = "tr.veli.namaz.VERI_AKTAR"
    const val JSON = "namaz_prefs_json"

    /** Namaz bölümü Doğa Reis 1.0.23'te (versionCode 24) geldi; daha eski Doğa Reis varken alarmlar burada sürer. */
    const val MIN_VERSION = 24L

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0).longVersionCode >= MIN_VERSION
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

class VeriAktarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ReisHandoff.ACTION && callingPackage == ReisHandoff.PACKAGE) {
            val preferences = getSharedPreferences("namaz", MODE_PRIVATE)
            val json = JSONObject(preferences.all).toString()
            setResult(RESULT_OK, Intent().putExtra(ReisHandoff.JSON, json))
            // Eski tam kilit kaldıysa kaldırmayı engellemesin
            Policy.release(this)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
