# film2

Kişisel, Netflix-tarzı yayın platformu. Yönetmenin kendi film ve dizi içerikleri
(dublaj + altyazı çoklu dil) için: izleme uygulamaları (masaüstü + Android) ve
içerik yükleme/studio uygulamaları (masaüstü + Android).

Mimari detay için [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), güncel durum ve
kurulum adımları için [handoff.md](handoff.md).

## Klasör yapısı

- `apps/desktop-player` — Electron, izleme uygulaması (Windows installer)
- `apps/desktop-studio` — Electron, içerik yükleme/studio uygulaması
- `apps/android-player` — Kotlin/Compose + Media3 ExoPlayer, izleme APK'sı
- `apps/android-studio` — Kotlin/Compose, içerik yükleme/studio APK'sı
- `packages/catalog-schema` — Katalog veri modeli (TS tipleri + JSON şema doğrulama)
- `packages/catalog-client` — Player uygulamalarının katalogu GitHub'dan okuması
- `packages/tmdb-client` — IMDb linkinden TMDB metadata çekme
- `packages/hf-storage` — Hugging Face çoklu-shard depolama yönetimi
- `catalog/titles/*.json` — Her yapımın metadata + asset kaydı
- `catalog/shards.json` — Aktif Hugging Face dataset repo kayıt defteri
- `.github/workflows` — Paketleme (ffmpeg/HLS) + masaüstü/Android derleme pipeline'ları

## Depolama

Gerçek medya dosyaları (video/ses/altyazı, HLS paketlenmiş) Hugging Face dataset
repo'larında tutulur, bu GitHub reposunda değil. Bu repo yalnızca kod + katalog
metadata'sı barındırır.

## Kurulum

Bkz. [handoff.md](handoff.md) — gerekli secret/credential listesi ve adım adım
yerel test talimatları orada.
