# P0 Planı — PP-OCRv5 ve Çeviri Hattı

## Mutlak kural: derleme yalnızca GitHub CI'da

- Bu proje **telefon içinde derlenmeyecek**.
- Yerel makinede de `gradle`, `./gradlew`, `assembleRelease` veya benzeri APK derlemesi çalıştırılmayacak.
- APK yalnızca GitHub Actions üzerinde üretilecek:
  - İş akışı: `.github/workflows/build-apk.yml`
  - Tetikleme: `workflow_dispatch` veya `v*` etiketi
  - Çıktı: `BabelRead-release-apk` artifact'i
- Telefonda yalnızca CI'dan indirilen APK kurulup test edilecek.
- Kod doğrulaması CI yeşil olana kadar tamamlanmış sayılmayacak.

## P0 hedefleri

- [x] PP-OCRv5 modelini ilk sayfa OCR'ından önce arka planda yükle (prewarm).
- [x] PP model yüklemesi ilk sayfanın OCR süresine eklenmesin.
- [ ] Native `session.run` takılırsa çeviri sırasını kilitlemeyen ayrı işçi/fallback.
- [x] PP OCR döngülerinde iptal kontrolleri bulunsun.
- [x] Balon ön-taraması çeviri cursor'ına bağımlı kalmasın.
- [x] Tek balon hatası tüm sayfayı görünmez yapmasın; başarılı balonlar gösterilsin.
- [x] Kısmi sonuçlar kalıcı cache'e yazılmasın; sonraki açılışta sayfa yeniden denensin.
- [x] `text_bubble` kutusu olmayan balonlar PP OCR'a girmeye devam etsin.

## Uygulama notları

- `PpOcrV5Engine.prewarm()` modeli `Dispatchers.IO` üzerinde hazırlar.
- `MangaViewModel` bölüm açılışında ve PP OCR seçildiğinde prewarm başlatır.
- `startDetectionPrefetch` mevcut sayfa veya çeviri cursor'ından hangisi ileriyse onu referans alır.
- Kısmi sayfa sonucu `_maskedPagesMap` ve `_translatedPagesMap` içine yazılır; `persistTranslatedPage` yalnızca tüm bloklar başarılıysa çalışır.
- `buildPpOcrV5Regions` metin kutularına ek olarak, metin kutusuyla eşleşmeyen balonları da OCR bölgesi olarak ekler.

## CI doğrulama adımları

1. Değişiklikleri `main` dalına gönder.
2. GitHub Actions'ta `Build BabelRead APK` iş akışını `workflow_dispatch` ile başlat.
3. Yeşil build ve `BabelRead-release-apk` artifact'ini doğrula.
4. APK'yı telefona kur; PP-OCRv5 ile ilk sayfa ve sonraki sayfaları test et.
5. Sonuçları bu dosyaya not et.

## İnceleme notu (commit 71556d4)

- Prewarm, iptal kontrolleri, kısmi sayfa gösterimi ve PP bölge fallback'i eklendi.
- Ancak `PpOcrV5Engine.recognizeLine` içindeki native `session.run` hâlâ iptal edilemiyor; model yükleme öne alındı ama çıkarım gerçekten takılırsa çeviri sırası yine bekleyebilir.
- `startDetectionPrefetch` hâlâ `translationPipelineCursor` ile `maxOf` üzerinden ilişkili.
- Kısmi sayfada başarısız balonun orijinal metni, başarılı komşu maskesiyle örtüşürse maskelenme riski var.
- CI run `40862696715` inceleme sırasında iptal edildi; APK üretilmedi.
