package tr.veli.namaz

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.widget.Toast
import java.security.SecureRandom

/**
 * Emanet kod.
 * Asıl yol: Sefa'nın telefonundaki Google Authenticator (30 sn'de bir değişen kod). SMS'e kod yazılmaz,
 * sadece "Veli kod istiyor" haberi gider; böylece senin Mesajlar uygulamanda iz kalmaz.
 * Authenticator eşleştirilmeden önce (sadece kurulum aşaması) geçici olarak SMS'le kod gider.
 */
object Emanet {
    fun request(c: Context): String {
        val st = Store.of(c)
        val no = st.str("sefa").trim()
        if (no.isEmpty()) {
            return if (Totp.paired(c)) "Sefa'yı ara, Authenticator'daki Namaz kodunu iste."
            else "Sefa'nın numarası ayarlı değil."
        }
        val text: String
        if (Totp.paired(c)) {
            text = "Veli namaz kilidi icin emanet kodu istiyor. Google Authenticator'daki 'Namaz' kodunu sadece gercek bir zorunlulukta soyle."
        } else {
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            st.put("emCode", code)
            st.putLong("emExp", now() + 15 * MIN)
            text = "Veli - namaz kilidi emanet kodu: $code (15 dk gecerli). Sadece gercek bir zorunlulukta ver."
        }
        return try {
            c.getSystemService(SmsManager::class.java).sendTextMessage(no, null, text, null, null)
            st.log("Emanet kodu istendi")
            "Sefa'ya haber gitti. Onu arayıp kodu iste."
        } catch (e: Exception) {
            "SMS gönderilemedi (${e.message}). Sefa'yı doğrudan ara."
        }
    }

    fun check(c: Context, code: String): Boolean {
        val st = Store.of(c)
        val ok = if (Totp.paired(c)) {
            Totp.verify(c, code)
        } else {
            code.isNotEmpty() && code == st.str("emCode") && now() < st.long("emExp")
        }
        if (ok) {
            st.put("emCode", "")
            st.log("Emanet kodu kullanıldı")
        }
        return ok
    }

    fun callSefa(a: Activity) {
        val no = Store.of(a).str("sefa").trim()
        if (no.isEmpty()) {
            Toast.makeText(a, "Sefa'nın numarası ayarlı değil", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            a.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$no")))
        } catch (e: Exception) {
            a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$no")))
        }
    }
}
