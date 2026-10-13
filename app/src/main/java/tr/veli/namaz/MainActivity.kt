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
import android.nfc.NfcAdapter
import android.nfc.Tag
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

    override fun onPause() {
        try {
            NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        } catch (_: Exception) {
        }
        super.onPause()
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
            if (stt == null && now() >= starts[i] && now() < ends && (st.activeKind() == null || st.activeEnd() < now())) {
                today.addView(Ui.button(this, "🕌 ${v.ad} — namaza duruyorum (seccade etiketi)", false) {
                    Launch.voluntary(this, Ev(now(), "LOCK", v, g.date, 0, ends))
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

        if (st.str("tagSeccade").isEmpty()) {
            val w = Ui.card(this)
            w.addView(Ui.text(this, "Hafif mod", 18f, Ui.GOLD, bold = true))
            w.addView(Ui.text(this, "Seccade etiketi kayıtlı değil: sadece 10 sn'lik hatırlatmalar gelir. Etiketi Ayarlar'dan okuttuğun an tam sistem başlar.", 14f))
            root.addView(w)
        }

        // Tam kilit
        val dev = Ui.card(this)
        dev.addView(Ui.text(this, "Tam kilit", 18f, Ui.GOLD, bold = true))
        if (Policy.isOwner(this)) {
            dev.addView(Ui.text(this, "AKTİF — kilitler atlatılamaz, uygulama silinemez.", 15f))
        } else {
            dev.addView(Ui.text(this, "KAPALI — uyarılar çalışır ama kilit atlatılabilir.", 15f, Ui.RED))
            dev.addView(Ui.text(this, "Kurulum rehberindeki tek komut:", 13f, Ui.DIM))
            dev.addView(Ui.text(this, "dpm set-device-owner tr.veli.namaz/.AdminReceiver", 13f).apply { setTextIsSelectable(true) })
        }
        root.addView(dev)

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
        test.addView(Ui.button(this, "Kilit denemesi (3 dk, etiketle 1 dk'da açılır)", false) {
            Launch.lock(this, Ev(now(), "FINAL", Vakit.OGLE, "test", 0, now() + 3 * MIN), now() + 3 * MIN, "final")
        })
        root.addView(test)

        // Kayıt
        val log = Ui.card(this)
        log.addView(Ui.text(this, "Kayıt", 18f, Ui.GOLD, bold = true))
        log.addView(Ui.text(this, st.str("log").split("\n").takeLast(15).reversed().joinToString("\n"), 12f, Ui.DIM))
        root.addView(log)

        root.addView(Ui.button(this, "Kaldır / kurulum modu (emanet kodu)", false) { emanetDialog() })
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
        val setupLeft = st.long("setupUntil") - now()
        c.addView(
            Ui.text(
                this,
                if (setupLeft > 0) "Kurulum modu: ${setupLeft / (60 * MIN)} saat kaldı — değişiklikler anında geçerli."
                else "Değişiklikler 24 saat sonra geçerli olur.",
                13f, Ui.DIM,
            ),
        )

        val kilit = st.str("namazKilit", "0") == "1"
        c.addView(Ui.text(this, "Namaz kilidi: " + (if (kilit) "AÇIK (ekran kilitlenir)" else "KAPALI (sadece uyarı + alarm)"), 15f))
        c.addView(Ui.button(this, if (kilit) "Namaz kilidini kapat" else "Namaz kilidini aç", false) {
            if (kilit) done(st.change("namazKilit", "0", "Namaz kilidi kapalı"))
            else { st.put("namazKilit", "1"); st.log("Namaz kilidi açıldı"); done(true) }
        })

        c.addView(Ui.text(this, "Sefa: " + st.str("sefa", "ayarlanmadı"), 15f))
        c.addView(Ui.button(this, "Sefa'nın numarası", false) {
            input("Sefa'nın telefon numarası", st.str("sefa"), InputType.TYPE_CLASS_PHONE) {
                done(st.change("sefa", it.trim(), "Sefa numarası"))
            }
        })

        if (Totp.paired(this)) {
            c.addView(Ui.text(this, "Emanet: Sefa'nın Authenticator'ı eşleşti ✓", 15f))
            c.addView(Ui.button(this, "Sefa telefon değiştirdi (yeniden eşleştir)", false) {
                if (st.inSetup()) {
                    Totp.reset(this); render()
                } else codeDialog("Sefa'nın şu anki kodu") { Totp.reset(this); render() }
            })
        } else {
            c.addView(Ui.text(this, "Emanet: Authenticator eşleşmedi — şimdilik SMS ile kod gider", 15f, Ui.RED))
            c.addView(Ui.button(this, "Sefa'nın telefonuyla eşleştir (QR)", true) { pairDialog() })
        }

        c.addView(Ui.text(this, "Seccade etiketi: " + (if (st.str("tagSeccade").isEmpty()) "yok" else "kayıtlı"), 15f))
        c.addView(Ui.button(this, "Seccade etiketini okut", false) { readTag("tagSeccade", "Seccade etiketi") })
        c.addView(Ui.text(this, "Etiketi seccadenin kenarına, telefonu koyacağın yere yapıştır. Namazda telefon etiketin üstünde (ekran yukarı) durur.", 13f, Ui.DIM))

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

        val pend = st.pending()
        if (pend.isNotEmpty()) {
            c.addView(Ui.text(this, "Bekleyen değişiklikler:", 15f, Ui.GOLD))
            pend.forEach { (d, at) ->
                c.addView(Ui.text(this, "• $d — ${(at - now()) / (60 * MIN)} sa sonra", 13f, Ui.DIM))
            }
        }
        return c
    }

    private fun done(immediate: Boolean) {
        Ui.toast(this, if (immediate) "Kaydedildi" else "24 saat sonra geçerli olacak")
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

    private fun readTag(key: String, desc: String) {
        val nfc = NfcAdapter.getDefaultAdapter(this)
        if (nfc == null || !nfc.isEnabled) {
            Ui.toast(this, "NFC kapalı. Ayarlar > Bağlantılar > NFC'yi aç.")
            return
        }
        val dlg = AlertDialog.Builder(this).setTitle(desc)
            .setMessage("Etiketi telefonun arka ortasına dokundur.")
            .setNegativeButton("Vazgeç") { _, _ -> nfc.disableReaderMode(this) }.show()
        nfc.enableReaderMode(
            this,
            { tag: Tag ->
                val hex = tag.id.joinToString("") { "%02X".format(it) }
                runOnUiThread {
                    nfc.disableReaderMode(this)
                    dlg.dismiss()
                    done(st.change(key, hex, desc))
                }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
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

    /** Yeni cami: kurulum modunda serbest, sonra Sefa'nın kodu + 24 saat. */
    private fun addMosque() {
        currentLocation { l ->
            if (l == null) {
                Ui.toast(this, "Konum alınamadı")
                return@currentLocation
            }
            input("Caminin adı", "", InputType.TYPE_CLASS_TEXT) { name ->
                val json = Mosques.userJsonWith(this, Cami(name.ifBlank { "Camim" }, l.latitude, l.longitude))
                if (st.inSetup()) {
                    st.change("userMosques", json, "Yeni cami: $name")
                    Mosques.invalidate()
                    done(true)
                } else {
                    codeDialog("Yeni cami için Sefa'nın kodu") {
                        st.change("userMosques", json, "Yeni cami: $name")
                        Mosques.invalidate()
                        done(false)
                    }
                }
            }
        }
    }

    /** QR sadece eşleşme tamamlanana kadar gösterilir. Sefa okutur, ekranındaki kodu buraya girersin. */
    private fun pairDialog() {
        val secret = Totp.secretForPairing(this) ?: return
        val box = Ui.col(this, 16)
        box.addView(Ui.text(this,
            "1) Sefa telefonuna Google Authenticator kursun.\n2) \"+\" → \"QR kodu tara\" ile bu kodu okutsun.\n3) Sefa'nın ekranında çıkan 6 haneli 'Namaz' kodunu aşağıya yaz.\n\nBu QR eşleşmeden sonra bir daha gösterilmez. Sen okutma.",
            14f, 0xFF000000.toInt()))
        val size = Ui.dp(this, 240)
        box.addView(android.widget.ImageView(this).apply {
            setImageBitmap(Totp.qr(Totp.uri(secret), size))
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        })
        val et = Ui.numberField(this, "Sefa'nın ekranındaki kod").apply { setTextColor(0xFF000000.toInt()) }
        box.addView(et)
        AlertDialog.Builder(this).setTitle("Sefa ile eşleştir").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Onayla") { _, _ ->
                if (Totp.confirmPairing(this, et.text.toString().trim())) Ui.toast(this, "Eşleşti. Artık kodlar Sefa'nın telefonunda.")
                else Ui.toast(this, "Kod tutmadı, tekrar dene")
                render()
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun codeDialog(title: String, ok: () -> Unit) {
        val box = Ui.col(this, 16)
        val et = Ui.numberField(this, "6 haneli kod").apply { setTextColor(0xFF000000.toInt()) }
        box.addView(Ui.button(this, "Sefa'ya haber ver (SMS)", true) { Ui.toast(this, Emanet.request(this)) })
        box.addView(et)
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Onayla") { _, _ ->
                if (Emanet.check(this, et.text.toString().trim())) ok()
                else Ui.toast(this, "Kod hatalı ya da süresi dolmuş")
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun emanetDialog() {
        AlertDialog.Builder(this).setTitle("Emanet kodu gerekir")
            .setItems(arrayOf("Kurulum modunu 2 saat aç", "Tam kilidi kaldır (uygulama silinebilir olur)")) { _, i ->
                codeDialog("Sefa'nın kodu") {
                    if (i == 0) {
                        st.putLong("setupUntil", now() + 120 * MIN)
                        st.log("Kurulum modu 2 saat açıldı (emanet)")
                    } else {
                        Policy.release(this)
                    }
                    render()
                }
            }.show()
    }

    // ------------------------------------------------------------------ izinler
    private fun askPerms() {
        val need = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
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
            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
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
