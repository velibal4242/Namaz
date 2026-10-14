# Namaz — proje kuralları

Veli'nin kişisel Android namaz uygulaması (Samsung Galaxy S26 Ultra). Ne yaptığı README.md'de.

## Teknik yapı
- Kotlin, tek modül `app/`, paket `tr.veli.namaz`. Yerelde Android SDK yok; derleme GitHub Actions'ta (`.github/workflows/namaz-apk.yml`, Gradle 8.11.1, JDK 17).
- `main` dalına her push APK derler ve Releases'a koyar. Telefondaki uygulama açılışta ve 6 saatte bir Releases'a bakıp kendini günceller (`Updater.kt`).
  - Bu yüzden `main`'e yalnız çalıştığından emin olunan kod gider; yarım iş ayrı dalda kalır.
  - Yalnız doküman değişikliğinde commit mesajına `[skip ci]` eklenir (gereksiz sürüm çıkmasın).
- İmza anahtarı `app/namaz.keystore` asla değiştirilmez ya da silinmez; değişirse telefondaki uygulama güncellenemez.
- Kayıtlı veriler (namaz geçmişi, zikir, ilaç bilgileri) yalnız telefonda durur; dışarıya gönderilmez.
- Uyarı/alarm davranışı (ertelemede cümleyi harf harf yazma, alarm kademeleri) Veli'nin kararıdır; açık izni olmadan gevşetilmez.

## Uzun/gece boyu çalışma kuralları (Veli, "sabaha kadar çalış")
1. Soruda durma: karar ver, uygula, devam et.
2. Çok önemli ya da geri dönüşsüz işler (veri silme, telefondaki kayıtları bozabilecek değişiklik, imza anahtarı, iş kuralını kökten değiştiren) "sabah sorulacaklar" listesine; bu sırada başka işe devam.
3. Bekleyen işi olabildiğince hazırla (seçenekler hazır, mümkünse her seçenek ayrı dalda) ki sabah seçim tek adım olsun.
4. İş kuralı kararlarında en basit ve geri alınabilir seçenek.
5. Her aşamadan sonra commit.
6. Sabah raporu: alınan her karar (ne, neden, nasıl geri alınır), sabah soruları (seçenekler, öneri, hazır iş), biten/kalan, duraklamalar (nerede, neden).
7. Gerektiğinde inceleme ve paralel iş için ajan çağır.

## Veli'nin genel tercihleri (bütün projelerde aynı)
- Hitap "reis". Türkçe, kısa, doğrudan; gereksiz övgü ve yapay iyimserlik yok.
- Telefondan çalışır: ondan komut çalıştırmasını ya da adres/komut kopyalamasını isteme. Bir yere gitmesi ya da bir adım atması gerekiyorsa doğrudan tıklanabilir bağlantı ver (ör. yeni APK'nın Releases sayfası).
- Çok adımlı işte "devam edeyim mi" diye sorma; işi sonuna kadar götür, aşama bitince kısa rapor ver.
- Model: günlük iş Opus ile. Kritik işte daha güçlü modele (Fable) geçmek gerekiyorsa sormadan geç. Önemli her değişiklik en üst seviyedeki modele (Fable; çağrılamıyorsa Opus) bağımsız ajan olarak incelettirilir; yazan kendi işini incelemez.
- Uygulama içi zorunlu güncelleme korunur: açılışta yeni sürüme bakar, güncellenmeden kullanılmaz.
- Ücretsiz araçlar tercih edilir; gerçekten gerekiyorsa ücretli abonelik/araç önerilebilir.
