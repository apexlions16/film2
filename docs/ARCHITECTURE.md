# film2 — Mimari

Kişisel, Netflix-tarzı yayın platformu. Yönetmenin kendi film/dizi içerikleri için:
çoklu dil dublaj + altyazı, tek tıkla izleme, IMDb linkinden otomatik metadata.

## Bileşenler

```
                     ┌─────────────────────┐
                     │   apexlions16/film2   │  (bu repo — GitHub)
                     │  catalog/*.json       │  <- katalog (metadata, asset URL'leri)
                     │  .github/workflows/*   │  <- CI/CD + paketleme pipeline
                     └─────────┬─────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                 │
       ┌──────▼──────┐  ┌──────▼──────┐   ┌──────▼──────┐
       │ desktop-studio│  │ android-studio│   │  (gelecek)   │
       │  (Electron)   │  │  (Kotlin)     │   │  ek studio   │
       └──────┬────────┘  └──────┬────────┘   └─────────────┘
              │ IMDb link -> TMDB │
              │ ham dosya upload  │
              ▼                   ▼
       ┌─────────────────────────────────┐
       │   Hugging Face dataset repo(lar)  │  <- gercek medya (HLS)
       │   film2-media-01, -02, ... (shard) │
       └─────────────────┬───────────────┘
                          │ master.m3u8 + .ts + .vtt
              ┌───────────┼───────────┐
              ▼                       ▼
       ┌─────────────┐         ┌─────────────┐
       │desktop-player│         │android-player│
       │  (Electron)   │         │  (Kotlin)    │
       └─────────────┘         └─────────────┘
```

## Veri akışı

1. **Studio'da içerik ekleme** — kullanıcı IMDb linkini yapıştırır. `packages/tmdb-client`
   linkten `tt...` id çıkarır, TMDB `/find` ile eşleştirir, film/dizi detayını + credits +
   (dizi ise) her sezonun bölüm listesini çeker. TMDB'de yoksa kullanıcı elle girer
   (`manualEntry: true`). Sonuç `catalog/titles/{id}.json` olarak GitHub'a commit edilir
   (`status: "pending"`).

2. **Ham dosya yükleme** — Studio, `catalog/shards.json`'daki aktif Hugging Face dataset
   repo'yu (shard) bulur (`packages/hf-storage`). Doluluk eşiği aşılmışsa otomatik yeni
   shard açılır (`{namespace}/{prefix}-{NN}`). Ham dosyalar (birleşik dosya ya da ayrı
   video/ses-per-dil/altyazı-per-dil) `incoming/{titleId}/...` altına aktif shard'a
   yüklenir.

3. **Paketleme tetikleme** — Studio, GitHub'a `repository_dispatch` (`event_type:
   "package-media"`) gönderir. Payload: `titleId`, `kind` (movie/episode), `shardId`,
   `mode` (combined/separate), ham dosya yolları, diller. Bkz.
   `.github/scripts/package-media.mjs` için tam şema.

4. **`package-media.yml` (GitHub Actions)** — ffmpeg ile ham dosyaları HLS'e paketler:
   çoklu ses track'i (`-var_stream_map`), WebVTT altyazılar (embedded stream'den çıkarılır
   ya da ayrı `.srt`/`.vtt` dosyasından dönüştürülür), `master.m3u8` + segment dosyaları
   üretilir. Çıktı aynı shard'a `media/{titleId}/...` altına yüklenir, `catalog/titles/
   {id}.json` gerçek `asset.masterPlaylistUrl` ile güncellenir ve commit edilir
   (`status: "ready"`). Bu iş arka planda döner — kullanıcı beklemez.

5. **Oynatma** — Player uygulamaları katalogu `packages/catalog-client` (ya da Android
   Kotlin karşılığı) ile GitHub'dan okur, yalnızca `status: "ready"` olan başlıkları
   oynatılabilir gösterir. `hls.js` (Electron) / Media3 ExoPlayer (Android) ile
   `master.m3u8` açılır, ses/altyazı track'i oynatma sırasında anlık değiştirilebilir.

6. **CI/CD** — `v*` tag'i push edilince `build-desktop.yml` (electron-builder, Windows
   installer) ve `build-android.yml` (Gradle `assembleRelease`, iki APK) GitHub
   Release'e otomatik dosya ekler. Kullanıcıya tek link yeter.

## Çoklu-shard depolama neden var

Tek bir Hugging Face dataset repo'su pratik/rahat boyut sınırına yaklaşınca
(`catalog/shards.json` içindeki `sizeThresholdBytes`), `hf-storage` paketi otomatik
olarak bir sonraki repo'yu (`film2-media-02` vb.) açar ve YENİ yüklemeleri oraya
yönlendirir. Eski içerik, hangi shard'da olduğunu `shardId` alanından bildiği için
konumundan hiç taşınmadan okunmaya devam eder. Bu sayede depolama sınırsız şekilde
büyüyebilir, tek bir repo'nun kapasitesine bağlı kalınmaz.

## Neden HLS, neden ham MKV değil

Tarayıcı/Electron/Android'de ham MKV dosyasında oynatma sırasında ses/altyazı track'i
değiştirmek platformlar arası güvenilir değildir. Bunun yerine her yükleme (birleşik ya
da ayrı dosya fark etmeksizin) ffmpeg ile HLS'e paketlenir: video bir kez encode edilir
(mümkünse `-c copy` ile, transcode gerekmez), her dil için ayrı ses "variant"ı ve
WebVTT altyazı track'i üretilir. Hem `hls.js` hem Media3 ExoPlayer bu formatı native
olarak destekler ve track değiştirme oynatma sırasında anlık çalışır — gerçek Netflix
davranışı budur.

## Neden TMDB, neden IMDb scrape değil

IMDb'nin resmi/genel API'si yok; HTML scraping hem ToS'a aykırı hem site yapısı
değiştikçe kırılıyor. TMDB ücretsiz, resmi bir API sunuyor ve IMDb ID ile eşleştirme
(`/find` endpoint) destekliyor — kullanıcı deneyimi aynı kalıyor (IMDb linkini
yapıştırıyor), veri kaynağı güvenilir kalıyor.

## Klasör yapısı

Bkz. kök `README.md`.
