package tr.veli.namaz

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Açılışta sürüm kontrolü ve zorunlu güncelleme (forced update) mantığı birim testleri (cihazsız).
 *
 * Test edilen mantık:
 * 1. GitHub Releases etiket (tag) ayrıştırma.
 * 2. Releases JSON varlıklarından (assets) APK bağlantısını çekme.
 * 3. Yüklü sürüm ile uzak sürüm karşılaştırması ve indirme kararı (önbellek, 15 dk koruması, force bayrağı).
 * 4. Açılışta güncelleme zorlaması tetikleme koşulu (shouldPromptUpdate).
 * 5. Diyalog metni oluşturma ve izin/hata durumlarının yansıtılması.
 * 6. Diyalog onay/yükleme eylemlerinin çözümlenmesi (resolveInstallAction).
 * 7. Diyalog iptal edilemezlik kuralı (N4 kararı bağlamında DIALOG_CANCELABLE = false).
 */
class UpdaterUnitTest {

    // ------------------------------------------------------------------ 1. Sürüm etiketi ayrıştırma
    @Test
    fun testParseTag_StandardPrefix() {
        assertEquals(3021L, Updater.parseTag("v3021"))
        assertEquals(3022L, Updater.parseTag("V3022"))
    }

    @Test
    fun testParseTag_WithoutPrefix() {
        assertEquals(3000L, Updater.parseTag("3000"))
        assertEquals(2050L, Updater.parseTag("2050"))
    }

    @Test
    fun testParseTag_InvalidOrEmpty() {
        assertEquals(0L, Updater.parseTag(""))
        assertEquals(0L, Updater.parseTag("v"))
        assertEquals(0L, Updater.parseTag("V"))
        assertEquals(0L, Updater.parseTag("alpha-1"))
        assertEquals(0L, Updater.parseTag("invalid_tag"))
    }

    // ------------------------------------------------------------------ 2. APK URL ayrıştırma
    @Test
    fun testParseApkUrl_Found() {
        val json = """
            [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt"},
                {"name": "namaz-3021.apk", "browser_download_url": "https://example.com/download/namaz-3021.apk"},
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
            ]
        """.trimIndent()
        val assets = JSONArray(json)
        val url = Updater.parseApkUrl(assets)
        assertEquals("https://example.com/download/namaz-3021.apk", url)
    }

    @Test
    fun testParseApkUrl_NotFound() {
        val json = """
            [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt"},
                {"name": "readme.md", "browser_download_url": "https://example.com/readme.md"}
            ]
        """.trimIndent()
        val assets = JSONArray(json)
        val url = Updater.parseApkUrl(assets)
        assertNull(url)
    }

    @Test
    fun testParseApkUrl_EmptyAssets() {
        val assets = JSONArray("[]")
        assertNull(Updater.parseApkUrl(assets))
    }

    // ------------------------------------------------------------------ 3. Sürüm durumu değerlendirme
    @Test
    fun testEvaluateUpdate_NoApkInRelease() {
        val eval = Updater.evaluateUpdate(
            tag = 3025L,
            curVersion = 3020L,
            apkUrl = null,
            lastTag = 0L,
            lastDownloadedAt = 0L,
            nowMs = 1_000_000L,
            force = false
        )
        assertEquals(Updater.UpdateDecision.NO_APK, eval.decision)
        assertEquals("Sürümde APK yok", eval.message)
    }

    @Test
    fun testEvaluateUpdate_CurrentVersionIsEqual() {
        val eval = Updater.evaluateUpdate(
            tag = 3020L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 0L,
            lastDownloadedAt = 0L,
            nowMs = 1_000_000L,
            force = false
        )
        assertEquals(Updater.UpdateDecision.UP_TO_DATE, eval.decision)
        assertEquals("Güncel (sürüm 3020)", eval.message)
    }

    @Test
    fun testEvaluateUpdate_CurrentVersionIsNewer() {
        val eval = Updater.evaluateUpdate(
            tag = 3019L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 0L,
            lastDownloadedAt = 0L,
            nowMs = 1_000_000L,
            force = false
        )
        assertEquals(Updater.UpdateDecision.UP_TO_DATE, eval.decision)
        assertEquals("Güncel (sürüm 3020)", eval.message)
    }

    @Test
    fun testEvaluateUpdate_NewerVersion_CachedWithin15Minutes_NoForce() {
        val now = 1_000_000L
        val fiveMinutesAgo = now - 5 * 60_000L
        val eval = Updater.evaluateUpdate(
            tag = 3025L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 3025L,
            lastDownloadedAt = fiveMinutesAgo,
            nowMs = now,
            force = false
        )
        assertEquals(Updater.UpdateDecision.ALREADY_DOWNLOADED, eval.decision)
        assertEquals("Sürüm 3025 hazır (yüklü: 3020)", eval.message)
    }

    @Test
    fun testEvaluateUpdate_NewerVersion_ForceDownloadOverridesCache() {
        val now = 1_000_000L
        val fiveMinutesAgo = now - 5 * 60_000L
        val eval = Updater.evaluateUpdate(
            tag = 3025L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 3025L,
            lastDownloadedAt = fiveMinutesAgo,
            nowMs = now,
            force = true
        )
        assertEquals(Updater.UpdateDecision.DOWNLOAD_NEEDED, eval.decision)
        assertEquals("Sürüm 3025 hazır (yüklü: 3020)", eval.message)
    }

    @Test
    fun testEvaluateUpdate_NewerVersion_CacheExpiredAfter15Minutes() {
        val now = 1_000_000L
        val sixteenMinutesAgo = now - 16 * 60_000L
        val eval = Updater.evaluateUpdate(
            tag = 3025L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 3025L,
            lastDownloadedAt = sixteenMinutesAgo,
            nowMs = now,
            force = false
        )
        assertEquals(Updater.UpdateDecision.DOWNLOAD_NEEDED, eval.decision)
        assertEquals("Sürüm 3025 hazır (yüklü: 3020)", eval.message)
    }

    @Test
    fun testEvaluateUpdate_NewerVersion_DifferentTag() {
        val now = 1_000_000L
        val fiveMinutesAgo = now - 5 * 60_000L
        val eval = Updater.evaluateUpdate(
            tag = 3026L,
            curVersion = 3020L,
            apkUrl = "https://example.com/namaz.apk",
            lastTag = 3025L, // previous cached tag was 3025
            lastDownloadedAt = fiveMinutesAgo,
            nowMs = now,
            force = false
        )
        assertEquals(Updater.UpdateDecision.DOWNLOAD_NEEDED, eval.decision)
        assertEquals("Sürüm 3026 hazır (yüklü: 3020)", eval.message)
    }

    // ------------------------------------------------------------------ 4. Açılışta zorlama diyaloğu koşulu
    @Test
    fun testShouldPromptUpdate_LaunchGating() {
        // Yeni sürüm hazırsa zorlama diyaloğu açılır
        assertTrue(Updater.shouldPromptUpdate("Sürüm 3025 hazır (yüklü: 3020)"))
        assertTrue(Updater.shouldPromptUpdate("Sürüm 3021 hazır"))

        // Diğer durumlarda ana ekran engellenmez
        assertFalse(Updater.shouldPromptUpdate("Güncel (sürüm 3020)"))
        assertFalse(Updater.shouldPromptUpdate("Sürümde APK yok"))
        assertFalse(Updater.shouldPromptUpdate("Güncelleme deposu ayarlı değil"))
        assertFalse(Updater.shouldPromptUpdate("İndirme sürüyor…"))
        assertFalse(Updater.shouldPromptUpdate("Güncelleme kontrol edilemedi: bağlantı zaman aşımına uğradı"))
    }

    // ------------------------------------------------------------------ 5. Diyalog yükleme aksiyonu çözümü
    @Test
    fun testResolveInstallAction_ConfirmAvailable() {
        val action = Updater.resolveInstallAction(
            confirmAvailable = true,
            hasError = false,
            timeSinceDownloadMs = 5_000L
        )
        assertEquals(Updater.InstallAction.OPEN_CONFIRM, action)
    }

    @Test
    fun testResolveInstallAction_WaitPreparingWithin30Seconds() {
        val action = Updater.resolveInstallAction(
            confirmAvailable = false,
            hasError = false,
            timeSinceDownloadMs = 15_000L
        )
        assertEquals(Updater.InstallAction.WAIT_PREPARING, action)
    }

    @Test
    fun testResolveInstallAction_TimeoutAfter30SecondsRequiresRetry() {
        val action = Updater.resolveInstallAction(
            confirmAvailable = false,
            hasError = false,
            timeSinceDownloadMs = 35_000L
        )
        assertEquals(Updater.InstallAction.RETRY_DOWNLOAD, action)
    }

    @Test
    fun testResolveInstallAction_ErrorRequiresRetry() {
        val action = Updater.resolveInstallAction(
            confirmAvailable = false,
            hasError = true,
            timeSinceDownloadMs = 10_000L
        )
        assertEquals(Updater.InstallAction.RETRY_DOWNLOAD, action)
    }

    // ------------------------------------------------------------------ 6. Diyalog metni ve zorunluluk uyarısı
    @Test
    fun testBuildDialogMessage_WithPermission_NoError() {
        val msg = Updater.buildDialogMessage(
            msg = "Sürüm 3025 hazır (yüklü: 3020)",
            hasInstallPermission = true,
            lastError = null
        )
        assertTrue(msg.contains("Sürüm 3025 hazır (yüklü: 3020)"))
        assertTrue(msg.contains("Güncellenmeden uygulama kullanılamaz."))
        assertFalse(msg.contains("Önce yükleme izni lazım"))
        assertFalse(msg.contains("Son deneme:"))
    }

    @Test
    fun testBuildDialogMessage_WithoutPermission() {
        val msg = Updater.buildDialogMessage(
            msg = "Sürüm 3025 hazır (yüklü: 3020)",
            hasInstallPermission = false,
            lastError = null
        )
        assertTrue(msg.contains("Önce yükleme izni lazım: \"İzin ver\"e bas, açılan ekranda Namaz'a izin ver, geri dön."))
        assertTrue(msg.contains("Güncellenmeden uygulama kullanılamaz."))
    }

    @Test
    fun testBuildDialogMessage_WithError() {
        val msg = Updater.buildDialogMessage(
            msg = "Sürüm 3025 hazır (yüklü: 3020)",
            hasInstallPermission = true,
            lastError = "Yükleme iptal edildi"
        )
        assertTrue(msg.contains("Son deneme: Yükleme iptal edildi"))
        assertTrue(msg.contains("Güncellenmeden uygulama kullanılamaz."))
    }

    // ------------------------------------------------------------------ 7. İptal edilemezlik (N4 kararı)
    @Test
    fun testDialogCancelable_MustBeFalse() {
        // N4 kararı uyarınca açılışta güncelleme diyaloğu iptal edilemez olmalıdır (dışarı dokunarak kapatılamaz)
        assertFalse("Güncelleme zorlaması diyaloğu iptal edilemez (non-cancelable) olmalıdır", Updater.DIALOG_CANCELABLE)
    }
}
