package tr.veli.namaz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import java.util.Calendar

class MainActivity : Activity() {
    private lateinit var st: Store

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store.of(this)
        st.ensureFirstRun()
        st.applyPending()
        Launch.channels(this)
        Policy.apply(this)
        askPerms()
        refreshLocation()
        Scheduler.scheduleNext(this)
        if (Mosques.osmCount(this) == 0) {
            Mosques.download(this) { msg -> runOnUiThread { Ui.toast(this, msg); render() } }
        }
    }

    override fun onResume() {
        super.onResume()
        st.applyPending()
        Scheduler.scheduleNext(this)
        Updater.maybeCheck(this)
        render()
    }

    // ------------------------------------------------------------------ ekran
    private fun render() {
        val root = Ui.col(this, 18)
        root.addView(Ui.text(this, "Namaz", 30f, Ui.GOLD, bold = true))
        root.addView(Ui.text(this, "Sonraki: " + st.str("nextEv", "—"), 15f, Ui.DIM))

        // Bugün
        val g = PrayerCalc.gun(this, 0)
        val today = Ui.card(this)
        today.addView(Ui.text(this, "Bugün", 18f, Ui.GOLD, bold = true))
        val starts = listOf(g.imsak, g.ogle, g.ikindi, g.aksam, g.yatsi)
        Vakit.values().forEachIndexed { i, v ->
            val stt = st.status(g.date, v)
            val ends = Scheduler.effEnd(g, v)
            val mark = when {
                stt == "cami" -> "🕌 camide"
                stt == "evde" -> "✅ kıldım"
                stt == "emanet" -> "⚠️ emanet"
                now() > ends -> "❌"
                else -> "⏳"
            }
            today.addView(Ui.text(this, "${v.ad.padEnd(7)} ${hhmm(starts[i])}   $mark", 17f))
            if (stt == null && now() >= starts[i] && now() < ends && true) {
                today.addView(Ui.typeButton(this, "✓ ${v.ad} namazını kıldım", "${v.ad} namazımı kıldım") {
                    st.mark(g.date, v, "evde")
                    Scheduler.scheduleNext(this)
                    Ui.toast(this, "Allah kabul etsin")
                    render()
                })
            }
        }
        today.addView(Ui.text(this, "Güneş ${hhmm(g.gunes)}", 14f, Ui.DIM))
        root.addView(today)

        // Zikir
        val zk = Ui.card(this)
        zk.addView(Ui.text(this, "Günlük zikir", 18f, Ui.GOLD, bold = true))
        Zikir.ITEMS.forEachIndexed { i, z ->
            val n = minOf(Zikir.count(this, i), z.adet)
            zk.addView(Ui.text(this, "${if (n >= z.adet) "✅" else "⏳"} ${z.baslik}: $n / ${z.adet}", 15f))
        }
        if (Zikir.done(this)) {
            zk.addView(Ui.text(this, "Bugün tamam. Allah kabul etsin.", 14f, Ui.DIM))
        } else {
            zk.addView(Ui.text(this, "${"%02d".format(Zikir.BASLA_SAAT)}:00'de ekran gelir; ${Zikir.ERTELEME_DK} dk ertelenebilir, en geç ${"%02d".format(Zikir.SON_ERTELEME_SAAT)}:00. ${"%02d".format(Zikir.HEDEF_SAAT)}:00'den önce bitmeli.", 13f, Ui.DIM))
            zk.addView(Ui.button(this, "Zikre başla / devam et") { Zikir.launch(this, false) })
        }
        root.addView(zk)

        // Seri
        val seri = Ui.card(this)
        seri.addView(Ui.text(this, "Seri: ${streak()} gün", 18f, Ui.GOLD, bold = true))
        seri.addView(Ui.text(this, "Öğle-ikindi camide, diğerleri kılındı sayılan günler", 13f, Ui.DIM))
        for (k in 6 downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -k)
            val d = dateStr(cal.timeInMillis)
            val row = Vakit.values().joinToString(" ") { v ->
                when (st.status(d, v)) {
                    "cami" -> "🕌"
                    "evde" -> "✅"
                    "emanet" -> "⚠️"
                    else -> if (k == 0) "⏳" else "❌"
                }
            }
            seri.addView(Ui.text(this, "${d.substring(5)}   $row", 15f))
        }
        root.addView(seri)

        // İzinler
        val perms = permsCard()
        if (perms.childCount > 1) root.addView(perms)

        // Ayarlar
        root.addView(settingsCard())

        // Test
        val test = Ui.card(this)
        test.addView(Ui.text(this, "Deneme", 18f, Ui.GOLD, bold = true))
        test.addView(Ui.button(this, "Hızlı deneme: bir vakti 7 dakikada yaşa") {
            TestRun.start(this)
            Ui.toast(this, "20 sn: uyarı → 1. dk: 10 sn hatırlatma → 2. dk: 1 dk alarm → 4. dk: susmayan alarm (3 dk)")
        })
        test.addView(Ui.button(this, "Uyarı ekranını göster", false) {
            Launch.alert(this, Ev(now(), "ALERT", Vakit.OGLE, "test", 0, now() + MIN))
        })
        test.addView(Ui.button(this, "Alarm denemesi (3 dk)", false) {
            Launch.lock(this, Ev(now(), "FINAL", Vakit.OGLE, "test", 0, now() + 3 * MIN), now() + 3 * MIN, "final")
        })
        root.addView(test)

        // Kayıt
        val log = Ui.card(this)
        log.addView(Ui.text(this, "Kayıt", 18f, Ui.GOLD, bold = true))
        log.addView(Ui.text(this, st.str("log").split("\n").takeLast(15).reversed().joinToString("\n"), 12f, Ui.DIM))
        root.addView(log)

        Ui.screen(this, root)
    }

    private fun streak(): Int {
        fun ok(d: String) = Vakit.values().all { st.status(d, it) == "cami" || st.status(d, it) == "evde" }
        val cal = Calendar.getInstance()
        if (!ok(dateStr(cal.timeInMillis))) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (n < 3650 && ok(dateStr(cal.timeInMillis))) {
            n++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    private fun settingsCard(): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Ayarlar", 18f, Ui.GOLD, bold = true))
        c.addView(Ui.text(this, "Cami listesi: ${Mosques.osmCount(this)} cami (Aksaray, Konya, İnegöl)", 15f))
        c.addView(Ui.button(this, "Cami listesini yenile", false) {
            Ui.toast(this, "İndiriliyor, 1-2 dakika sürebilir…")
            Mosques.download(this) { msg -> runOnUiThread { Ui.toast(this, msg); render() } }
        })
        val mine = Mosques.user(this)
        c.addView(Ui.text(this, "Eklediğin camiler: " + (if (mine.isEmpty()) "yok" else mine.joinToString { it.ad }), 15f))
        c.addView(Ui.button(this, "Bulunduğum yeri cami olarak ekle", false) { addMosque() })

        val names = listOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")
        c.addView(Ui.text(this, "Vakit düzeltme (Diyanet ile karşılaştır): " +
            names.indices.joinToString { "${names[it]} ${st.offset(it)}" }, 13f, Ui.DIM))
        c.addView(Ui.button(this, "Vakit düzeltme", false) {
            AlertDialog.Builder(this).setTitle("Hangi vakit?").setItems(names.toTypedArray()) { _, i ->
                input("${names[i]} düzeltme (dk, örn -2)", st.offset(i).toString(),
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED) {
                    done(st.change("off$i", it.trim(), "${names[i]} düzeltme ${it.trim()} dk"))
                }
            }.show()
        })

        c.addView(Ui.text(this, "Sürüm ${Updater.currentVersion(this)} · güncellemeler otomatik (6 saatte bir kontrol)", 13f, Ui.DIM))
        c.addView(Ui.button(this, "Güncellemeyi şimdi kontrol et", false) {
            Updater.check(this) { msg -> runOnUiThread { Ui.toast(this, msg) } }
        })

        return c
    }

    private fun done(immediate: Boolean) {
        Ui.toast(this, "Kaydedildi")
        Scheduler.scheduleNext(this)
        render()
    }

    // ------------------------------------------------------------------ yardımcılar
    private fun input(title: String, value: String, type: Int, ok: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = type
            setText(value)
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("Kaydet") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun currentLocation(cb: (Location?) -> Unit) {
        val lm = getSystemService(LocationManager::class.java)
        try {
            val p = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
            else LocationManager.GPS_PROVIDER
            lm.getCurrentLocation(p, null, mainExecutor) { l ->
                if (l != null) st.setLastLoc(l.latitude, l.longitude)
                cb(l)
            }
        } catch (e: Exception) {
            cb(null)
        }
    }

    private fun refreshLocation() = currentLocation { }

    /** Bulunduğun yeri cami olarak ekle. */
    private fun addMosque() {
        currentLocation { l ->
            if (l == null) {
                Ui.toast(this, "Konum alınamadı")
                return@currentLocation
            }
            input("Caminin adı", "", InputType.TYPE_CLASS_TEXT) { name ->
                val json = Mosques.userJsonWith(this, Cami(name.ifBlank { "Camim" }, l.latitude, l.longitude))
                st.change("userMosques", json, "Yeni cami: $name")
                Mosques.invalidate()
                done(true)
            }
        }
    }

    // ------------------------------------------------------------------ izinler
    private fun askPerms() {
        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        render()
    }

    private fun permsCard(): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, "Eksik izinler", 18f, Ui.RED, bold = true))
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            c.addView(Ui.button(this, "Temel izinleri ver") { askPerms() })
        } else if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            c.addView(Ui.button(this, "Konum: \"Her zaman izin ver\" seç") {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            })
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            c.addView(Ui.button(this, "Pil kısıtlamasını kaldır") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
        }
        if (!Settings.canDrawOverlays(this)) {
            c.addView(Ui.button(this, "Diğer uygulamaların üzerinde göster") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
        }
        if (android.os.Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            c.addView(Ui.button(this, "Tam ekran uyarı izni") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        }
        return c
    }
}
