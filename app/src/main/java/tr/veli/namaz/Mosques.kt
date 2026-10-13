package tr.veli.namaz

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Cami(val ad: String, val lat: Double, val lng: Double)

/**
 * Cami listesi: Aksaray ve Konya illeri ile İnegöl ilçesi (OpenStreetMap verisi)
 * + kullanıcının eklediği camiler (24 saat kuralı ve emanet kodu ile).
 */
object Mosques {
    const val RADIUS = 60.0
    private var cache: List<Cami>? = null

    private const val QUERY = """
[out:json][timeout:180];
(
  area["name"="Aksaray"]["admin_level"="4"];
  area["name"="Konya"]["admin_level"="4"];
  area["name"="İnegöl"]["admin_level"="6"];
)->.a;
nwr["amenity"="place_of_worship"]["religion"="muslim"](area.a);
out center tags;
"""

    private fun file(c: Context) = File(c.filesDir, "camiler.json")

    fun invalidate() {
        cache = null
    }

    fun all(c: Context): List<Cami> {
        cache?.let { return it }
        val list = mutableListOf<Cami>()
        try {
            val f = file(c)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Cami(o.optString("n", "Cami"), o.getDouble("a"), o.getDouble("o")))
                }
            }
        } catch (_: Exception) {
        }
        list.addAll(user(c))
        cache = list
        return list
    }

    fun osmCount(c: Context): Int = all(c).size - user(c).size

    fun user(c: Context): List<Cami> = try {
        val arr = JSONArray(Store.of(c).str("userMosques", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Cami(o.optString("n", "Camim"), o.getDouble("a"), o.getDouble("o"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun userJsonWith(c: Context, cami: Cami): String {
        val arr = JSONArray(Store.of(c).str("userMosques", "[]"))
        arr.put(JSONObject().put("n", cami.ad).put("a", cami.lat).put("o", cami.lng))
        return arr.toString()
    }

    fun nearest(c: Context, lat: Double, lng: Double): Pair<Cami, Double>? {
        val out = FloatArray(1)
        var best: Pair<Cami, Double>? = null
        for (m in all(c)) {
            Location.distanceBetween(lat, lng, m.lat, m.lng, out)
            val d = out[0].toDouble()
            if (best == null || d < best.second) best = m to d
        }
        return best
    }

    fun download(c: Context, done: (String) -> Unit) {
        Thread {
            val msg = try {
                val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 200_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "NamazApp/0.1 (kisisel)")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(QUERY, "UTF-8")).toByteArray()) }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val els = JSONObject(body).getJSONArray("elements")
                val out = JSONArray()
                for (i in 0 until els.length()) {
                    val e = els.getJSONObject(i)
                    val la: Double
                    val lo: Double
                    if (e.has("lat")) {
                        la = e.getDouble("lat"); lo = e.getDouble("lon")
                    } else if (e.has("center")) {
                        val ce = e.getJSONObject("center"); la = ce.getDouble("lat"); lo = ce.getDouble("lon")
                    } else continue
                    val name = e.optJSONObject("tags")?.optString("name", "Cami") ?: "Cami"
                    out.put(JSONObject().put("n", name).put("a", la).put("o", lo))
                }
                if (out.length() > 0) {
                    file(c).writeText(out.toString())
                    invalidate()
                    Store.of(c).log("Cami listesi indirildi: ${out.length()} cami")
                    "${out.length()} cami indirildi"
                } else "Liste boş geldi, sonra tekrar dene"
            } catch (e: Exception) {
                "İndirilemedi: ${e.message}"
            }
            done(msg)
        }.start()
    }
}
