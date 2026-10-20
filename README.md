# BabelRead

[![APK derlemesi](https://github.com/voidrunnerdev/BabelRead/actions/workflows/build-apk.yml/badge.svg)](https://github.com/voidrunnerdev/BabelRead/actions/workflows/build-apk.yml)
[![Son sürüm](https://img.shields.io/github/v/release/voidrunnerdev/BabelRead?include_prereleases&label=son%20s%C3%BCr%C3%BCm)](https://github.com/voidrunnerdev/BabelRead/releases)
[![Lisans: MIT](https://img.shields.io/badge/lisans-MIT-blue.svg)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84.svg)](https://developer.android.com)

BabelRead, manga, manhwa ve webtoon okumak için geliştirilmiş açık kaynak bir Android uygulamasıdır. Mihon/Keiyoushi eklentilerini destekler; kütüphane, çevrimdışı indirme, cihaz üstü konuşma balonu tespiti ve isteğe bağlı çeviri özelliklerini tek yerde toplar.

> **Beta:** BabelRead aktif geliştirme aşamasındadır. Hatalar olabilir; bazı özellikler değişebilir veya kaldırılabilir.

## İndirme

En güncel APK için [Releases](https://github.com/voidrunnerdev/BabelRead/releases/latest) sayfasını kullan. APK'yı indirdikten sonra cihazda "bilinmeyen kaynaklardan yükleme" iznini vererek kurabilirsin.

Sürümler aynı sürüm imzalama anahtarıyla imzalanır; bu sayede yeni APK'yı eskisinin üzerine güncelleme olarak kurabilirsin. İlk beta öncesi hata ayıklama imzalı test sürümünü kullandıysan, imza değiştiği için önce onu kaldırman gerekir.

## Özellikler

### Eklentiler ve kaynaklar
- **Mihon/Keiyoushi uyumluluğu:** Keiyoushi imzasıyla doğrulanan eklenti APK'larını yükler.
- **Eklenti mağazası:** GitHub tabanlı depoları ekleyerek indeksi tarar, güncellemeleri kontrol eder.
- **Dil bazlı varyantlar:** Çok dilli eklentilerde (ör. MangaDex) yalnızca istediğin dil kaynaklarını kurar; seçim kaynak kimliği yerine dil üzerinden eşlenir.
- **Yerel içerik:** CBZ, ZIP, CBR, RAR ve PDF dosyalarını ya da cihazdaki klasörleri kütüphaneye ekler.

### Çeviri ve OCR
- **Balon tespiti:** Uygulamayla gelen RT-DETR-v2 ONNX modeli konuşma balonlarını, diyalog ve ses efekti metinlerini ayırır.
- **OCR:** Google ML Kit ile Latin, Japonca, Korece ve Çince metin tanıma.
- **Çeviri motorları:** Otomatik, Google, DeepL, ML Kit ve isteğe bağlı Gemini desteği.
- **Çeviri düzeltmeleri:** Bir çeviriye uzun basıp kendi karşılığını kaydedebilirsin; düzeltme kalıcı olarak saklanır ve sonraki okumalarda kullanılır.
- **Yerinde çeviri:** Çevrilmiş balona dokunarak orijinal metni görebilirsin.

### Kütüphane ve okuyucu
- Favori, geçmiş ve indirilen bölümler için yerel veritabanı.
- Webtoon için dikey kaydırma, manga için yatay sayfa modu.
- Balon opaklığı, oval balon ve şeffaf altyazı stilleri.
- Bölümleri indirip çevrimdışı okuma.
- Yeni bölüm bildirimleri ve periyodik güncelleme kontrolü.

### Gizlilik
- Uygulama hiçbir manga içeriği veya eklenti deposu barındırmaz.
- Tüm içerik, kullanıcının eklediği kaynaklardan ve eklentilerden alınır.
- API anahtarları APK'ya gömülmez; girilen anahtarlar cihazda şifreli olarak saklanır.

## Teknoloji

| Katman | Teknoloji |
| --- | --- |
| Dil | Kotlin |
| Arayüz | Jetpack Compose, Material 3 |
| Mimari | MVVM, StateFlow, Coroutines |
| Veritabanı | Room, DataStore |
| Eklentiler | Mihon/Keiyoushi ABI, DexClassLoader |
| Görüntü işleme | ONNX Runtime Android, Google ML Kit |
| Ağ | OkHttp, Jsoup |
| Arka plan | WorkManager |

## Derleme

### Gereksinimler
- JDK 17 veya üzeri
- Android SDK 36.1 ve Build Tools 36
- Gradle 9.3.1 (veya güncel Android Studio)

### Android Studio
1. Depoyu klonla:
   ```bash
   git clone https://github.com/voidrunnerdev/BabelRead.git
   cd BabelRead
   ```
2. Projeyi Android Studio'da aç.
3. Gradle senkronizasyonunun bitmesini bekle.
4. `Run > Run 'app'` ile cihazda veya emülatörde çalıştır.

### Komut satırı
```bash
gradle :app:assembleDebug
```

Hata ayıklama APK'sı `app/build/outputs/apk/debug/app-debug.apk` konumunda oluşur.

### Sürüm APK'sı
Sürüm APK'sı `assembleRelease` ile üretilir ve `my-upload-key.jks` dosyasıyla imzalanır:

```bash
KEYSTORE_PATH=/yol/my-upload-key.jks \
STORE_PASSWORD=... \
KEY_PASSWORD=... \
gradle :app:assembleRelease
```

İmza anahtarı depoda tutulmaz. CI, anahtarı `BABELREAD_KEYSTORE_BASE64`, `BABELREAD_STORE_PASSWORD` ve `BABELREAD_KEY_PASSWORD` secret'larından okur.

### GitHub Actions
`.github/workflows/build-apk.yml` yalnızca `v*` etiketi push edildiğinde veya elle çalıştırıldığında tetiklenir. `assembleRelease` ile imzalı sürüm APK'sı üretir, APK'yı artifact olarak yükler ve etiketli çalışmalarda GitHub Release'e ekler.

## Modeller

- **Balon/metin tespiti:** [ogkalu/comic-text-and-bubble-detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector) (RT-DETR-v2, Apache-2.0). Model `app/src/main/assets/models/detector.onnx` konumundadır.
- Modelin lisans metni: [LICENSE-APACHE-2.0.txt](LICENSE-APACHE-2.0.txt)

Model dosyalarını değiştirirsen kullandığın modelin lisansını ve atıf koşullarını kontrol etmelisin. Ayrıntılar için [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Eklenti depoları
BabelRead, Mihon/Keiyoushi ekosistemindeki eklentilerle uyumludur. Depo adresi olarak bir GitHub deposunun kökünü, `index.min.json` bağlantısını veya GitHub Pages adresini ekleyebilirsin. Eklentilerin ve depoların güvenilirliği kullanıcının sorumluluğundadır.

## Lisans
Bu projenin kendi kaynak kodu [MIT Lisansı](LICENSE) altındadır. Üçüncü taraf model ve bileşenlerin lisansları [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) dosyasında listelenmiştir. Manga, manhwa ve webtoon içeriklerinin telif hakları ilgili yazar, çizer ve yayıncılara aittir.

## Yasal uyarı
BabelRead bağımsız bir açık kaynak projedir; Tachiyomi, Mihon, Keiyoushi veya herhangi bir içerik sağlayıcısıyla bağlantılı değildir. Eklenen depoların ve erişilen içeriklerin kullanım sorumluluğu kullanıcıya aittir.
