package tr.veli.namaz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sefa'nın telefonundaki Google Authenticator ile aynı kodu üreten TOTP (RFC 6238: SHA1, 30 sn, 6 hane).
 * Gizli anahtar sadece eşleştirme sırasında bir kez QR olarak gösterilir.
 */
object Totp {
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun paired(c: Context) = Store.of(c).str("totpPaired") == "1"

    /** Eşleştirme için gizli anahtar (yoksa üretir). Eşleştirildikten sonra null döner. */
    fun secretForPairing(c: Context): String? {
        val st = Store.of(c)
        if (paired(c)) return null
        var s = st.str("totpSecret")
        if (s.isEmpty()) {
            val b = ByteArray(20)
            SecureRandom().nextBytes(b)
            s = encode(b)
            st.put("totpSecret", s)
        }
        return s
    }

    fun uri(secret: String) =
        "otpauth://totp/Namaz:Veli?secret=$secret&issuer=Namaz&digits=6&period=30&algorithm=SHA1"

    /** İlk doğru kod girildiğinde eşleştirmeyi tamamlar; QR bir daha gösterilmez. */
    fun confirmPairing(c: Context, code: String): Boolean {
        if (!matches(c, code)) return false
        Store.of(c).put("totpPaired", "1")
        Store.of(c).log("Sefa'nın Authenticator'ı eşleştirildi")
        return true
    }

    /** Sefa telefon değiştirirse: mevcut geçerli kod (ya da kurulum modu) ile sıfırlanır. */
    fun reset(c: Context) {
        val st = Store.of(c)
        st.put("totpPaired", "")
        st.put("totpSecret", "")
        st.log("Authenticator eşleştirmesi sıfırlandı")
    }

    /** Kodu doğrular (±30 sn tolerans), aynı kod ikinci kez kullanılamaz. */
    fun verify(c: Context, code: String): Boolean {
        if (!paired(c)) return false
        return matches(c, code)
    }

    private fun matches(c: Context, code: String): Boolean {
        val st = Store.of(c)
        val s = st.str("totpSecret")
        if (s.isEmpty() || code.length != 6) return false
        val key = decode(s)
        val step = now() / 30_000L
        val used = st.long("totpUsed", -1L)
        for (d in -1L..1L) {
            val t = step + d
            if (t > used && code == generate(key, t)) {
                st.putLong("totpUsed", t)
                return true
            }
        }
        return false
    }

    fun generate(key: ByteArray, step: Long): String {
        val msg = ByteArray(8)
        var v = step
        for (i in 7 downTo 0) {
            msg[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or
            ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or
            (h[o + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in s.uppercase()) {
            val v = B32.indexOf(ch)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}
