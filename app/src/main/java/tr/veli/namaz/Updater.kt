package tr.veli.namaz

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kendi kendine güncelleme: GitHub'daki son sürümü (Releases) kontrol eder, yeniyse indirip kurar.
 * Tam kilit (cihaz sahibi) açıkken kurulum sessizdir; değilse Android bir onay sorar.
 * Ayar: "repo" = "kullaniciadi/namaz" (depo herkese açık olmalı).
 */
object Updater {
    const val DEFAULT_REPO = "velibal4242/namaz"
    fun repo(c: Context) = Store.of(c).str("repo", DEFAULT_REPO).trim()

    fun currentVersion(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Günde bir kez otomatik kontrol */
    fun maybeCheck(c: Context) {
        val st = Store.of(c)
        if (repo(c).isBlank()) return
        if (now() - st.long("updChecked") < 6 * 60 * MIN) return
        st.putLong("updChecked", now())
        check(c) { }
    }

    /** İndirme sürüyor mu (aynı anda ikinci oturum açılmasın) */
    @Volatile var busy = false
    /** Android'in "Yükle" onay ekranı; ön plandaki ekrandan açılır (arka plandan açılamayabiliyor) */
    @Volatile var confirm: Intent? = null
    @Volatile var lastError: String? = null
    @Volatile var sessionId = -1

    /**
     * force=false: aynı sürüm son 15 dakikada indirildiyse tekrar indirmez (döngüyü önler).
     * force=true: kullanıcı "Tekrar indir" dediğinde.
     */
    fun check(c: Context, force: Boolean = false, done: (String) -> Unit) {
        val repo = repo(c)
        if (repo.isEmpty()) {
            done("Güncelleme deposu ayarlı değil")
            return
        }
        if (busy) {
            done("İndirme sürüyor…")
            return
        }
        busy = true
        Thread {
            val st = Store.of(c)
            val msg = try {
                val json = get("https://api.github.com/repos/$repo/releases/latest")
                val o = JSONObject(json)
                val tag = o.getString("tag_name").trimStart('v', 'V').toLongOrNull() ?: 0L
                val assets = o.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                val cur = currentVersion(c)
                when {
                    url == null -> "Sürümde APK yok"
                    tag <= cur -> {
                        confirm = null
                        lastError = null
                        "Güncel (sürüm $cur)"
                    }
                    !force && st.long("updTag") == tag && now() - st.long("updAt") < 15 * MIN ->
                        "Sürüm $tag hazır (yüklü: $cur)"
                    else -> {
                        confirm = null
                        lastError = null
                        install(c, url)
                        st.putLong("updTag", tag)
                        st.putLong("updAt", now())
                        st.log("Güncelleme indirildi: sürüm $tag (yüklü $cur)")
                        "Sürüm $tag hazır (yüklü: $cur)"
                    }
                }
            } catch (e: Exception) {
                "Güncelleme kontrol edilemedi: ${e.message}"
            } finally {
                busy = false
            }
            done(msg)
        }.start()
    }

    private fun get(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "NamazApp")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun install(c: Context, url: String) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        // Android 12+: uygulama kendi yükleyicisiyse (ilk kendi güncellemesinden sonra) onay sormadan kurar
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        // Yarım kalmış eski oturumları temizle
        for (old in pi.mySessions) {
            try { pi.abandonSession(old.sessionId) } catch (_: Exception) {}
        }
        val id = pi.createSession(params)
        sessionId = id
        pi.openSession(id).use { session ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "NamazApp")
            conn.inputStream.use { input ->
                session.openWrite("namaz.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(c, UpdateReceiver::class.java).setAction("tr.veli.namaz.UPDATE")
            val sender = PendingIntent.getBroadcast(
                c, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender
            session.commit(sender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        // Terk edilen eski oturumların geri çağrısı yeni oturumun durumunu bozmasın
        val sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status != PackageInstaller.STATUS_SUCCESS && Updater.sessionId != -1 && sid != -1 && sid != Updater.sessionId) return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.confirm = confirm
                Launch.channels(c)
                val pi = android.app.PendingIntent.getActivity(
                    c, 8, confirm,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                val n = android.app.Notification.Builder(c, Launch.CH_ALARM)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Namaz: yeni sürüm hazır")
                    .setContentText("Yüklemek için dokun")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                c.getSystemService(android.app.NotificationManager::class.java).notify(9, n)
                try { c.startActivity(confirm) } catch (_: Exception) {}
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.confirm = null
            Store.of(c).log("Güncelleme kuruldu")
        } else {
            Updater.confirm = null
            val err = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Yükleme iptal edildi"
            else (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "kod $status")
            Updater.lastError = err
            Store.of(c).log("Güncelleme başarısız: $err")
            c.getSystemService(android.app.NotificationManager::class.java).cancel(9)
        }
    }
}
