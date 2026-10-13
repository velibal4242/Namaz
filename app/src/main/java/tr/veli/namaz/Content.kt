package tr.veli.namaz

import android.content.Context
import org.json.JSONArray

data class Icerik(val tur: String, val metin: String, val kaynak: String, val etiket: List<String>)

/** Ayet/hadis havuzu. Hepsi gösterilmeden aynısı tekrar gelmez. */
object Content {
    private var cache: List<Icerik>? = null

    fun all(c: Context): List<Icerik> {
        cache?.let { return it }
        val txt = c.assets.open("icerik.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(txt)
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tags = o.optJSONArray("e")
            Icerik(
                o.getString("t"), o.getString("m"), o.optString("k", ""),
                if (tags == null) emptyList() else (0 until tags.length()).map { tags.getString(it) },
            )
        }
        cache = list
        return list
    }

    fun pick(c: Context, tags: List<String>): Icerik {
        val st = Store.of(c)
        val items = all(c)
        var seen = st.str("seen").split(",").mapNotNull { it.toIntOrNull() }.toSet()
        if (seen.size >= items.size) seen = emptySet()
        val fresh = items.indices.filter { it !in seen }
        val tagged = fresh.filter { i -> items[i].etiket.any { it in tags } }
        val pool = if (tagged.isNotEmpty() && Math.random() < 0.65) tagged else fresh
        val idx = pool.random()
        st.put("seen", (seen + idx).joinToString(","))
        return items[idx]
    }
}
