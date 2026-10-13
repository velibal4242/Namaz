# Namaz — kişisel namaz uygulaması (Android)

Veli Bal için. Samsung Galaxy S26 Ultra'da test edilmek üzere.

## Ne yapar

**Namaz** (kilit yok, sadece uyarı + alarm; sabah güneşe, ikindi akşam ezanına kadar):

| Dönem | Ne olur |
|---|---|
| Vakit girince | Uyarı ekranı (ayet/hadis) |
| Son 1 saate kadar | Her yarım saatte alarm; "Kıldım" ya da "Ertele" ile susar, en fazla 5 dk |
| Son 1 saat | Her 10 dk'da 1 dk en yüksek seste alarm |
| Son 30 dk | Vakit bitene kadar susmayan alarm; "30 saniye sustur" |

**Tek dokunuşla geçmek yok:** ertelemek için "Öğle namazımı birazdan kılacağım", kıldıysan "Öğle namazımı kıldım" cümlesini harf harf yazarsın (yapıştırma kapalı). Alarmı tamamen susturan: **"Kıldım"** ya da **camiye girmek** (8 dk kalınca camide kılındı; cuma öğlesi 35 dk). Ekrandan çıkılabilir, alarm arkada çalar.

**Günlük zikir** (100'er): Tövbe → Lâ ilâhe illallâh → Salavat → Lâ havle → Tevhid. 18:00'de ekran gelir; kapatılırsa 5 dk, ertelenirse 30 dk sonra tekrar gelir (ikisi de 15 sn basılı tutarak) (erteleme 20:00'ye kadar). Hedef 21:00. Dokunuşlar arası bekleme yok; ses tuşlarıyla da sayılır.

* Her uyarıda farklı ayet / hadis (82 içerik).
* Camiler: Aksaray ve Konya illeri + İnegöl ilçesi (OpenStreetMap); eksik camini "Bulunduğum yeri cami olarak ekle" ile eklersin.

## Kurulum

### 1. APK'yı yükle
GitHub'daki son sürümden (Releases) `namaz-XX.apk` dosyasını telefona indir, aç, "Bilinmeyen uygulamalara izin ver" de, yükle.
Açınca istenen tüm izinleri ver (konumda **"Her zaman izin ver"**).

### 2. Ayarlar
* Cami listesi otomatik iner; eksik camini caminin içindeyken "Bulunduğum yeri cami olarak ekle" ile ekle
* Vakitleri Diyanet ile karşılaştır, 1 dk fark varsa "Vakit düzeltme"

## Deneme
Ana ekranda **"Hızlı deneme: bir vakti 7 dakikada yaşa"**: uyarı → 10 sn hatırlatma → 1 dk alarm → susmayan alarm (3 dk). Gerçek plana dokunmaz.

## İleride (gerekirse)
* Google Places ile cami tanıma: listede olmayan bir yerde Google'a "burası cami mi" sorulur, cevap listeye kalıcı eklenir. API anahtarı gerekir.
* Türkiye geneli cami listesi.

## Güncelleme
Uygulama 6 saatte bir (ve her açılışta) bu deponun son sürümüne (Releases) bakar, yeni sürüm varsa indirip kurar. Tam kilit açıkken sessiz, değilse Android bir kez onay sorar.

## Geliştirme
Her `main` push'unda GitHub Actions APK derler ve Releases'a koyar. İmza anahtarı `app/namaz.keystore` (güncellemeler aynı anahtarla imzalanmalı).
