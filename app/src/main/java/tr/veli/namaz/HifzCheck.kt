package tr.veli.namaz

/**
 * Sesli kontrol: konuşma tanımanın duyduğu Arapça metni beklenen ayet kelimeleriyle hizalar.
 * Harekeler, vakıf işaretleri, elif/ye/te-merbûta farkları yok sayılır; küçük yazım farklarına tolerans var.
 */
object HifzCheck {
    private val HAREKE = Regex("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
    private val ARAP_DISI = Regex("[^ء-ي]")

    fun norm(w: String): String = HAREKE.replace(w, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        .replace("ء", "")
        .let { ARAP_DISI.replace(it, "") }

    fun kelimeler(s: String): List<String> = s.split(Regex("\\s+")).map { norm(it) }.filter { it.isNotEmpty() }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private fun benzer(a: String, b: String): Boolean {
        if (a == b) return true
        val m = maxOf(a.length, b.length)
        if (m < 3) return false
        val l = lev(a, b)
        return (m >= 3 && l <= 1) || (m >= 7 && l <= 2)
    }

    /** Beklenen her kelime için duyuldu mu? */
    fun hizala(beklenen: List<String>, duyulan: String): BooleanArray {
        val e = beklenen.map { norm(it) }
        val d = kelimeler(duyulan)
        val n = e.size
        val m = d.size
        // dp[i][j] = e[0..i) ile d[0..j) arasında en çok eşleşme
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            var best = maxOf(dp[i - 1][j], dp[i][j - 1])
            if (e[i - 1].isEmpty()) best = maxOf(best, dp[i - 1][j] + 1)
            if (benzer(e[i - 1], d[j - 1])) best = maxOf(best, dp[i - 1][j - 1] + 1)
            if (j >= 2 && benzer(e[i - 1], d[j - 2] + d[j - 1])) best = maxOf(best, dp[i - 1][j - 2] + 1)
            if (i >= 2 && e[i - 2].isNotEmpty() && benzer(e[i - 2] + e[i - 1], d[j - 1])) best = maxOf(best, dp[i - 2][j - 1] + 2)
            dp[i][j] = best
        }
        // geri izleme
        val ok = BooleanArray(n)
        var i = n
        var j = m
        while (i > 0) {
            if (e[i - 1].isEmpty()) {
                ok[i - 1] = true
                i--
                continue
            }
            if (j == 0) break
            when {
                benzer(e[i - 1], d[j - 1]) && dp[i][j] == dp[i - 1][j - 1] + 1 -> { ok[i - 1] = true; i--; j-- }
                j >= 2 && benzer(e[i - 1], d[j - 2] + d[j - 1]) && dp[i][j] == dp[i - 1][j - 2] + 1 -> { ok[i - 1] = true; i--; j -= 2 }
                i >= 2 && e[i - 2].isNotEmpty() && benzer(e[i - 2] + e[i - 1], d[j - 1]) && dp[i][j] == dp[i - 2][j - 1] + 2 -> { ok[i - 1] = true; ok[i - 2] = true; i -= 2; j-- }
                dp[i][j] == dp[i - 1][j] -> i--
                else -> j--
            }
        }
        return ok
    }

    /** Birden çok tanıma sonucundan en iyisini seçer */
    fun enIyi(beklenen: List<String>, sonuclar: List<String>): BooleanArray =
        sonuclar.map { hizala(beklenen, it) }.maxByOrNull { r -> r.count { it } } ?: BooleanArray(beklenen.size)
}
