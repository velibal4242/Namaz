package tr.veli.namaz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kıraat sesleri: ayet ayet indirilir, telefonda saklanır (sonra internetsiz çalışır).
 * Tek oynatıcı; yeni bir çalma başlayınca öncekini iptal eder (token).
 */
object HifzAudio {
    private val h = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    @Volatile private var token = 0
    var calisiyor = false
        private set

    private fun pad3(n: Int) = "%03d".format(n)

    fun dosya(c: Context, k: Kari, n: Int) = File(c.filesDir, "hifz/${k.klasor}/${pad3(Hifz.SURE)}${pad3(n)}.mp3")
    private fun kelimeDosya(c: Context, n: Int, w: Int) = File(c.filesDir, "hifz/wbw/${pad3(Hifz.SURE)}_${pad3(n)}_${pad3(w + 1)}.mp3")

    private fun indir(urls: List<String>, f: File): Boolean {
        if (f.exists() && f.length() > 1000) return true
        f.parentFile?.mkdirs()
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "NamazApp")
                if (conn.responseCode != 200) continue
                val tmp = File(f.path + ".tmp")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() > 1000 && tmp.renameTo(f)) return true
                tmp.delete()
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun ayetUrls(k: Kari, n: Int): List<String> {
        val ad = "${pad3(Hifz.SURE)}${pad3(n)}.mp3"
        return listOf("https://everyayah.com/data/${k.klasor}/$ad", k.yedek + ad)
    }

    fun hazirla(c: Context, n: Int, cb: (File?) -> Unit) {
        val k = Hifz.kari(c)
        val f = dosya(c, k, n)
        if (f.exists() && f.length() > 1000) {
            cb(f)
            return
        }
        Thread {
            val ok = indir(ayetUrls(k, n), f)
            h.post { cb(if (ok) f else null) }
        }.start()
    }

    /** Seçili kârînin 30 ayetini arka planda indirir */
    fun hepsiniIndir(c: Context, bitti: ((Int) -> Unit)? = null) {
        val k = Hifz.kari(c)
        val app = c.applicationContext
        Thread {
            var ok = 0
            for (n in 1..30) if (indir(ayetUrls(k, n), dosya(app, k, n))) ok++
            h.post { bitti?.invoke(ok) }
        }.start()
    }

    fun inmisSayisi(c: Context): Int {
        val k = Hifz.kari(c)
        return (1..30).count { dosya(c, k, it).let { f -> f.exists() && f.length() > 1000 } }
    }

    fun durdur() {
        token++
        h.removeCallbacksAndMessages(null)
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        calisiyor = false
    }

    /** Bir dosyayı çalar; bitince süresini (ms) bildirir. -1 = çalınamadı */
    private fun cal(c: Context, f: File, hiz: Float, my: Int, bitti: (Int) -> Unit) {
        try { mp?.release() } catch (_: Exception) {}
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(f.path)
            p.prepare()
            val sure = p.duration
            p.setOnCompletionListener {
                if (my == token) bitti(sure)
            }
            p.setOnErrorListener { _, _, _ ->
                if (my == token) bitti(-1)
                true
            }
            if (hiz != 1f) p.playbackParams = PlaybackParams().setSpeed(hiz)
            p.start()
        } catch (e: Exception) {
            f.delete() // bozuk indirme olabilir, bir dahakine yeniden insin
            h.post { if (my == token) bitti(-1) }
        }
    }

    /**
     * Bir ayeti [kez] defa çalar. [bosluk] > 0 ise her çalıştan sonra ayet süresi × bosluk kadar
     * susar (sen tekrar et diye). olay: ("cal", i) / ("sus", i) / ("hata", 0)
     */
    fun ayetCal(c: Context, n: Int, kez: Int, bosluk: Float, olay: (String, Int) -> Unit, bitti: () -> Unit) {
        durdur()
        val my = token
        calisiyor = true
        hazirla(c, n) { f ->
            if (my != token) return@hazirla
            if (f == null) {
                calisiyor = false
                olay("hata", 0)
                return@hazirla
            }
            fun tur(i: Int) {
                if (my != token) return
                if (i >= kez) {
                    calisiyor = false
                    bitti()
                    return
                }
                olay("cal", i)
                cal(c, f, Hifz.hiz(c), my) { sure ->
                    if (sure < 0) {
                        calisiyor = false
                        olay("hata", 0)
                        return@cal
                    }
                    if (bosluk > 0f && i < kez) {
                        olay("sus", i)
                        val bekle = (sure / Hifz.hiz(c) * bosluk).toLong() + 600
                        h.postDelayed({ tur(i + 1) }, bekle)
                    } else {
                        tur(i + 1)
                    }
                }
            }
            tur(0)
        }
    }

    /** Tek kelimeyi çalar (quran.com kelime sesleri) */
    fun kelimeCal(c: Context, n: Int, w: Int, bitti: (Boolean) -> Unit) {
        durdur()
        val my = token
        val f = kelimeDosya(c, n, w)
        Thread {
            val url = "https://audio.qurancdn.com/wbw/${pad3(Hifz.SURE)}_${pad3(n)}_${pad3(w + 1)}.mp3"
            val ok = indir(listOf(url), f)
            h.post {
                if (my != token) return@post
                if (!ok) {
                    bitti(false)
                    return@post
                }
                cal(c, f, 1f, my) { bitti(it >= 0) }
            }
        }.start()
    }

    /** Kelime kelime: ayetin kelimelerini sırayla çalar; her kelimede olay(index) */
    fun kelimeKelime(c: Context, n: Int, adet: Int, olay: (Int) -> Unit, bitti: () -> Unit) {
        durdur()
        val my = token
        calisiyor = true
        fun sira(i: Int) {
            if (my != token) return
            if (i >= adet) {
                calisiyor = false
                bitti()
                return
            }
            olay(i)
            val f = kelimeDosya(c, n, i)
            Thread {
                val url = "https://audio.qurancdn.com/wbw/${pad3(Hifz.SURE)}_${pad3(n)}_${pad3(i + 1)}.mp3"
                val ok = indir(listOf(url), f)
                h.post {
                    if (my != token) return@post
                    if (!ok) {
                        calisiyor = false
                        bitti()
                        return@post
                    }
                    cal(c, f, 1f, my) { h.postDelayed({ sira(i + 1) }, 350) }
                }
            }.start()
        }
        sira(0)
    }

    /**
     * Dinleme modu: [bas]..[son] ayetleri, her ayet [ayetKez] kez, bütün aralık [turKez] kez (0 = sonsuz).
     * bosluk: her çalıştan sonra ayet süresi × bosluk kadar sessizlik.
     */
    fun aralikCal(
        c: Context, bas: Int, son: Int, ayetKez: Int, turKez: Int, bosluk: Float,
        olay: (Int) -> Unit, bitti: () -> Unit,
    ) {
        durdur()
        val my = token
        calisiyor = true
        val app = c.applicationContext
        HifzPlayService.baslat(app)
        fun bitir() {
            calisiyor = false
            HifzPlayService.durdur(app)
            bitti()
        }
        fun adim(tur: Int, n: Int, k: Int) {
            if (my != token) return
            when {
                n > son -> if (turKez == 0 || tur + 1 < turKez) adim(tur + 1, bas, 0) else bitir()
                k >= ayetKez -> adim(tur, n + 1, 0)
                else -> {
                    olay(n)
                    hazirla(app, n) { f ->
                        if (my != token) return@hazirla
                        if (f == null) {
                            bitir()
                            return@hazirla
                        }
                        cal(app, f, Hifz.hiz(app), my) { sure ->
                            if (sure < 0) {
                                bitir()
                                return@cal
                            }
                            val bekle = if (bosluk > 0f) (sure / Hifz.hiz(app) * bosluk).toLong() + 400 else 250L
                            h.postDelayed({ adim(tur, n, k + 1) }, bekle)
                        }
                    }
                }
            }
        }
        adim(0, bas, 0)
    }
}

/** Dinleme modunda ekran kapansa da okumanın sürmesi için ön plan hizmeti */
class HifzPlayService : Service() {
    companion object {
        fun baslat(c: Context) {
            try { c.startForegroundService(Intent(c, HifzPlayService::class.java)) } catch (_: Exception) {}
        }
        fun durdur(c: Context) {
            c.stopService(Intent(c, HifzPlayService::class.java))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") {
            HifzAudio.durdur()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hifz_play", "Mülk dinleme", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 72, Intent(this, HifzPlayService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 73, Intent(this, HifzActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "hifz_play")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Mülk suresi okunuyor")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .setOngoing(true)
            .build()
        try {
            startForeground(7001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
