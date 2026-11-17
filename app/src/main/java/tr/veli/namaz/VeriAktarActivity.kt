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

    @Suppress("DEPRECATION")
    fun installed(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(PACKAGE, 0)
        true
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
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
