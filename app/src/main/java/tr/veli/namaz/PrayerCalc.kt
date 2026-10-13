package tr.veli.namaz

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

enum class Vakit(val ad: String, val etiket: String) {
    SABAH("Sabah", "sabah"),
    OGLE("Öğle", "ogle"),
    IKINDI("İkindi", "ikindi"),
    AKSAM("Akşam", "aksam"),
    YATSI("Yatsı", "yatsi");

    /** Öğle ve ikindi camide kılınır; diğerleri ev akışıyla (NFC). */
    val cami: Boolean get() = this == OGLE || this == IKINDI
}

/** Bir günün vakitleri (epoch ms). */
data class Gun(
    val date: String,
    val imsak: Long,
    val gunes: Long,
    val ogle: Long,
    val ikindi: Long,
    val aksam: Long,
    val yatsi: Long,
)

const val MIN = 60_000L

fun dateStr(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
fun hhmm(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
fun now(): Long = System.currentTimeMillis()

/**
 * Diyanet usulü vakit hesabı: imsak 18°, yatsı 17°, ikindi asr-ı evvel,
 * temkinler: güneş -7, öğle +5, ikindi +4, akşam +7 dakika.
 * Kalan küçük farklar uygulamadaki "vakit düzeltme" ayarıyla giderilir.
 */
object PrayerCalc {
    private fun dtr(d: Double) = d * Math.PI / 180.0
    private fun rtd(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(dtr(d))
    private fun dcos(d: Double) = cos(dtr(d))
    private fun dtan(d: Double) = tan(dtr(d))
    private fun darcsin(x: Double) = rtd(asin(x))
    private fun darccos(x: Double) = rtd(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = rtd(atan2(y, x))
    private fun darccot(x: Double) = rtd(atan(1.0 / x))
    private fun fixA(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixH(a: Double) = a - 24.0 * floor(a / 24.0)

    private fun julian(y0: Int, m0: Int, d: Int): Double {
        var y = y0
        var m = m0
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun sunPos(jd: Double): DoubleArray {
        val dd = jd - 2451545.0
        val g = fixA(357.529 + 0.98560028 * dd)
        val q = fixA(280.459 + 0.98564736 * dd)
        val l = fixA(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * dd
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixH(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return doubleArrayOf(decl, eqt)
    }

    fun gun(ctx: Context, dayOffset: Int): Gun {
        val st = Store.of(ctx)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return compute(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            st.lat, st.lng, IntArray(6) { st.offset(it) },
        )
    }

    fun compute(y: Int, m: Int, d: Int, lat: Double, lng: Double, off: IntArray): Gun {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(y, m - 1, d, 0, 0, 0)
        val midnight = cal.timeInMillis
        val tz = TimeZone.getDefault().getOffset(midnight + 12 * 3600_000L) / 3600_000.0
        val jDate = julian(y, m, d) - lng / (15.0 * 24.0)

        fun midDay(h: Double): Double = fixH(12.0 - sunPos(jDate + h / 24.0)[1])
        fun angleTime(angle: Double, h: Double, ccw: Boolean): Double {
            val decl = sunPos(jDate + h / 24.0)[0]
            val noon = midDay(h)
            val t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + if (ccw) -t else t
        }
        fun asr(h: Double): Double {
            val decl = sunPos(jDate + h / 24.0)[0]
            val angle = -darccot(1.0 + dtan(abs(lat - decl)))
            return angleTime(angle, h, false)
        }

        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0)
        repeat(3) {
            t = doubleArrayOf(
                angleTime(18.0, t[0], true),
                angleTime(0.833, t[1], true),
                midDay(t[2]),
                asr(t[3]),
                angleTime(0.833, t[4], false),
                angleTime(17.0, t[5], false),
            )
        }
        val temkin = intArrayOf(0, -7, 5, 4, 7, 0)
        fun ms(i: Int): Long {
            val local = t[i] + tz - lng / 15.0
            val raw = midnight + (local * 3600_000.0).roundToLong() + (temkin[i] + off[i]) * MIN
            return ((raw + 30_000L) / MIN) * MIN
        }
        return Gun(dateStr(midnight), ms(0), ms(1), ms(2), ms(3), ms(4), ms(5))
    }
}
