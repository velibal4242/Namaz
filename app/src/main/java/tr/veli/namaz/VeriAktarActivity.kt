package tr.veli.namaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject

/**
 * Doğa Reis'e veri aktarımı sağlayan dışa açık aktivite.
 * Şartname gereği yalnız arayan paket "tr.koleji.dogareis" ise
 * SharedPreferences verisini JSON olarak döndürür; aksi takdirde iptal döner.
 */
class VeriAktarActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage ?: callingActivity?.packageName
        if (caller == "tr.koleji.dogareis") {
            val sp = getSharedPreferences("namaz", Context.MODE_PRIVATE)
            val json = JSONObject()
            for ((k, v) in sp.all) {
                json.put(k, v)
            }
            val resIntent = Intent().apply {
                putExtra("veri_json", json.toString())
                putExtra("data", json.toString())
            }
            setResult(RESULT_OK, resIntent)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
