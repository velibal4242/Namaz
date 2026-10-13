# Namaz — kişisel namaz uygulaması (Android)

Veli Bal için. Samsung Galaxy S26 Ultra'da test edilmek üzere.

## Ne yapar

Her vakit aynı kademeli sistem (sabah: güneşe kadar, ikindi: akşam ezanına kadar):

| Dönem | Ne olur |
|---|---|
| Vakit girince | Uyarı ekranı (ayet/hadis) |
| Son 1 saate kadar | Her yarım saatte 10 sn alarm + kilit, "Kapat" ile kapatılabilir |
| Son 1 saat | Her 10 dk'da 1 dk en yüksek seste alarm + kilit, kapatılamaz |
| Son 30 dk | Vakit bitene kadar susmayan alarm + kilit; sadece "30 saniye sustur" |

Kilidi kapatan tek şey: **camiye girmek** (8 dk kalınca kılındı; cuma öğlesi 35 dk) ya da **telefonun 10 dk seccade etiketinin üstünde durması** (kaldırılırsa 20 sn içinde geri konmazsa sayaç sıfırlanır). "Kıldım" beyanı yok.

* Her uyarıda farklı ayet / hadis (82 içerik; hepsi gösterilmeden tekrar etmez).
* Camiler: Aksaray ve Konya illeri + İnegöl ilçesi (OpenStreetMap). Listede olmayan camini sen eklersin (Sefa'nın kodu + 24 saat).
* Ayar değişiklikleri ilk 48 saat (kurulum modu) hariç **24 saat sonra** geçerli olur.
* **Emanet kod:** Sefa'nın telefonundaki Google Authenticator'da 30 saniyede bir değişen "Namaz" kodu. Kilit ekranında "Acil durum" → Sefa'yı ara, kodu iste. SMS'e kod yazılmaz (sadece "Veli kod istiyor" haberi gider), senin telefonunda iz kalmaz. Eşleştirme QR'ı sadece bir kez gösterilir. Eşleştirme yapılana kadar geçici olarak SMS'le kod gider.
* Kilitte açık kalan tek şey: Sefa'yı arama ve 112.

## Kurulum

### 1. APK'yı yükle
GitHub'daki son sürümden (Releases) `namaz-XX.apk` dosyasını telefona indir, aç, "Bilinmeyen uygulamalara izin ver" de, yükle.
Açınca istenen tüm izinleri ver (konumda **"Her zaman izin ver"**).

### 2. Tam kilit (tek seferlik, bilgisayarsız)
Tam kilit için uygulama "cihaz sahibi" olmalı. Android bunu sadece telefonda **hiç hesap yokken** izin veriyor.

1. Yedek al (Samsung Cloud / Google). WhatsApp'ı yedekle.
2. Ayarlar → Hesaplar ve yedekleme → Hesapları yönet: **Google, Samsung ve diğer tüm hesapları geçici olarak kaldır.**
3. Güvenli Klasör ve Çift Messenger açıksa kaldır (bunlar ikinci kullanıcı sayılır).
4. Ayarlar → Telefon hakkında → Yazılım bilgileri → **Yapım numarası**'na 7 kez dokun (Geliştirici seçenekleri açılır).
5. Ayarlar → Geliştirici seçenekleri → **Kablosuz hata ayıklama** aç (Wi-Fi'ye bağlı ol).
6. Play Store'dan **Shizuku** kur. Shizuku'da "Kablosuz hata ayıklama ile başlat" → eşleştirme kodu ile eşleştir → Başlat.
7. Play Store'dan **aShell** kur, aç, Shizuku iznini ver ve şu komutu yaz:

```
dpm set-device-owner tr.veli.namaz/.AdminReceiver
```

8. "Success: Device owner set" yazmalı. Namaz uygulamasını aç: **Tam kilit: AKTİF**.
9. Hesaplarını geri ekle. (Geliştirici seçeneklerini kapatabilirsin.)

Hata "already some accounts on the device" ise hâlâ bir hesap kalmış demektir (Samsung hesabı, Outlook, WhatsApp Business vb.).

### 3. Ayarlar (ilk 48 saatte, anında geçerli)
* Sefa'nın numarası
* **Sefa ile eşleştir (QR):** Sefa Google Authenticator'ı kurar, QR'ı okutur, ekranındaki kodu sen girersin. QR'ı sen okutma.
* NFC etiketi (NTAG213): seccadenin kenarına, telefonu koyacağın yere yapıştır, "Seccade etiketini okut" ile kaydet. Etiket kayıtlı değilken uygulama hafif modda çalışır (sadece 10 sn hatırlatmalar).
* Cami listesi otomatik iner; eksik camini caminin içindeyken "Bulunduğum yeri cami olarak ekle" ile ekle
* Vakitleri Diyanet ile karşılaştır, 1 dk fark varsa "Vakit düzeltme"

## Güvenlik ağı
* Hiçbir kilit vakit sonunu (ve 120 dk'yı) geçemez.
* 30 dakikada 10'dan fazla kilit denemesi olursa yeni kilit açılmaz (hata döngüsüne karşı).
* Uygulama çökerse aktif kilit kaydı silinir; yeniden başlatmada kilit döngüsü olmaz.
* Kilit ekranında NFC okumazsa: "Ekran kilidini aç" düğmesi (kilit görevden çıkmadan telefon şifresi açılır).

## Deneme
Ana ekranda **"Hızlı deneme: bir vakti 7 dakikada yaşa"**: uyarı → 10 sn hatırlatma → 1 dk alarm → susmayan alarm (3 dk). Testte seccade etiketi 1 dk'da açar. Gerçek plana dokunmaz.

## İleride (gerekirse)
* Google Places ile cami tanıma: listede olmayan bir yerde Google'a "burası cami mi" sorulur, cevap listeye kalıcı eklenir. API anahtarı gerekir.
* Türkiye geneli cami listesi.

## Güncelleme
Uygulama 6 saatte bir (ve her açılışta) bu deponun son sürümüne (Releases) bakar, yeni sürüm varsa indirip kurar. Tam kilit açıkken sessiz, değilse Android bir kez onay sorar.

## Geliştirme
Her `main` push'unda GitHub Actions APK derler ve Releases'a koyar. İmza anahtarı `app/namaz.keystore` (güncellemeler aynı anahtarla imzalanmalı).
