# handoff.md — film2

Bu dosya her oturum sonunda güncellenir: ne yapıldı, ne eksik, nasıl test edilir.

## Durum: Faz 0 + Faz 1 devam ediyor (ilk oturum)

### Tamamlandı ve doğrulandı (offline, gerçek credential olmadan)

- Repo iskeleti: `apps/`, `packages/`, `catalog/`, `.github/workflows`, `docs/`
- `packages/catalog-schema` — `Title`/`Season`/`Episode` TS tipleri + JSON Schema doğrulama (`validateTitle`). Test edildi: geçerli/geçersiz title doğru ayrıştırıldı.
- `packages/tmdb-client` — `imdbLinkToId`, `slugify` (Türkçe ı/İ dahil), `findByImdbId`, `fetchTitleFromImdbLink`. `imdbLinkToId`/`slugify` offline test edildi. **TMDB'ye gerçek çağrı henüz test edilmedi — TMDB_API_KEY gerekiyor.**
- `packages/hf-storage` — shard registry yönetimi (`getActiveShard`, `ensureShardCapacity`, `recordUsage`), upload fonksiyonları (`uploadFileToShard`, `uploadDirectoryToShard`), `resolveUrl`. Registry mantığı offline test edildi (eşik altında yeni shard açmıyor, kullanım sayacı doğru artıyor). **Gerçek Hugging Face upload/create-repo henüz test edilmedi — HF_TOKEN gerekiyor.**
- `packages/catalog-client` — player uygulamalarının GitHub'dan katalog okuması (`listTitleIds`, `getTitle`, `listTitles`).
- `catalog/shards.json` — ilk shard kaydı (`apexlions16/film2-media-01`, eşik 300GB, ayarlanabilir).
- `catalog/titles/_example-movie.json`, `_example-series.json` — şema örnekleri (`_` ile başladığı için gerçek katalogda listelenmez).
- `.github/workflows/package-media.yml` + `.github/scripts/package-media.mjs` + `update-catalog.mjs` — `repository_dispatch` ile tetiklenen ffmpeg/HLS paketleme pipeline'ı. **Henüz gerçek dosyayla hiç çalıştırılmadı — yazıldı ama canlı test edilmedi.**
- `.github/workflows/build-desktop.yml` — Electron Windows installer derleme + Release'e ekleme.
- `.github/workflows/build-android.yml` — Gradle APK derleme + (opsiyonel) imzalama + Release'e ekleme.
- `apps/desktop-player` (Electron) — Netflix-tarzı browse (asimetrik hero, tür satırları, hover mikro-animasyon), sezon/bölüm seçici, hls.js player ile canlı ses/altyazı track değiştirme, "Demo Stream (test)" satırı (`test-streams.mux.dev` çoklu-ses HLS test akışı). `npm install` + `npm run typecheck` + `npm run build` **temiz gecti**. Renderer taraf ayrıca Browser pane'de bizzat açılıp mock katalogla görsel olarak da doğrulandı; bu sırada 2 gerçek bug bulunup düzeltildi (CSS Modules `@keyframes` kapsam sorunu yüzünden satırlar/empty-state/player kontrolleri görünmez kalıyordu; poster placeholder renk üretimi bazen mora düşebiliyordu, iki sıcak tona sabitlendi).
- `apps/desktop-studio` (Electron) — IMDb link -> TMDB önizleme/manuel form, sezon/bölüm editörü, dosya yükleme (birleşik/ayrı), GitHub Contents API ile katalog commit, `repository_dispatch` tetikleme — hepsi Electron main process'te (token'lar renderer'a hiç sızmıyor, IPC ile izole). `npm install` + `npm run build` **temiz gecti**. Gerçek token'larla network akışı henüz canlı test edilmedi (beklenen).
- `apps/android-player` (Kotlin/Compose + Media3) — aynı Netflix-tarzı UI dili, ExoPlayer ile HLS + track selector bottom sheet, aynı demo akış satırı. **Gerçekten derlendi**: ajan ortamda Android SDK yokken bir tane kurup `./gradlew assembleDebug` ve `assembleRelease` (CI'nin çalıştırdığı komutun birebir aynısı) ile gerçek APK üretti, `lintVitalRelease` de gecti.
- `apps/android-studio` (Kotlin/Compose) — TMDB/GitHub/HF akışlarının Kotlin karşılığı, DataStore ile yerel token saklama, WorkManager ile arka planda (bloklamayan) yükleme + `repository_dispatch`. **Gerçekten derlendi** (aynı yöntemle, gerçek APK üretti).

### Bilinen riskler / ilk canlı testte doğrulanması gerekenler

1. **`package-media.mjs` içindeki ffmpeg `-var_stream_map` komutu** — çoklu ses track'li HLS üretimi ffmpeg sürümüne göre hassas davranabilir. `master.m3u8`'in tam olarak nereye yazıldığı (kod içinde not düşüldü) ilk gerçek dosya yüklemesinde kontrol edilmeli.
2. **Hugging Face upload protokolü (Android tarafı, `apps/android-studio/.../hf/HfUploader.kt`)** — `@huggingface/hub` npm paketi (masaüstü) resmi ve güvenilir, ama Android'de eşdeğer resmi SDK yok; Kotlin tarafı HF'nin commit/preupload/LFS API'sini elle uyguluyor (kod içinde `// TODO: verify against docs` notlarıyla isaretlendi), gerçek token ile doğrulanmadı.
3. **GitHub Contents API ile commit** (Studio uygulamaları) — dosya boyutu sınırı var (~1MB, base64 encode ile Contents API üzerinden gönderilen dosyalar için); bu sadece `catalog/titles/{id}.json` ve `catalog/shards.json` gibi küçük JSON dosyaları için kullanılıyor, büyük medya dosyaları için değil (onlar doğrudan Hugging Face'e gidiyor) — tasarım gereği sorun olmamalı ama ilk testte doğrulanmalı.
4. **Android proje yolu** — repo yolu Türkçe karakter içerdiği (`KENDİ PROJELERİM`) için Android Gradle Plugin varsayılan olarak hata veriyordu; her iki `apps/android-*/gradle.properties` dosyasina `android.overridePathCheck=true` eklendi. Repoyu farklı (ASCII) bir yola klonlarsanız bu ayar zararsızdır, kaldırmaya gerek yok.
5. **Studio uygulamalarında (masaüstü + Android) cast/crew/sezon-bölüm listeleri** üst seviye alanlar kadar detaylı düzenlenebilir değil (başlık/özet/tarih gibi alanlar editable, ama tek tek oyuncu satırı düzenleme yok) — ilk sürüm için kabul edilebilir, istenirse sonraki oturumda genişletilir.

## Gerekli secret/credential'lar

### GitHub repo secrets (Settings → Secrets and variables → Actions)
- `HF_TOKEN` — Hugging Face **write** token (package-media.yml ffmpeg pipeline'ının HF'ye yazması için)
- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` — opsiyonel, APK imzalamak için (yoksa imzasız APK üretilir, cihaza kurulabilir ama Play Store'a yüklenemez — kişisel kullanım için imzasız da yeterli)

### Studio uygulamaları içinde (yerel ayarlar, repoya asla commitlenmez)
- TMDB API key — https://www.themoviedb.org/settings/api
- Hugging Face write token — https://huggingface.co/settings/tokens
- GitHub PAT (repo scope) — https://github.com/settings/tokens (Contents API + repository_dispatch tetiklemek için)

## Yerelde nasıl test edilir

```bash
# Kök bağımlılıklar (packages/* için)
npm install

# tmdb-client canlı test (gerçek TMDB key ile)
cd packages/tmdb-client
TMDB_API_KEY=xxxx IMDB_LINK="https://www.imdb.com/title/tt0111161/" node test.mjs

# hf-storage canlı test (gerçek HF write token ile, kucuk bir test dosyasi yukler)
cd packages/hf-storage
HF_TOKEN=hf_xxxx node test.mjs

# Electron player
cd apps/desktop-player && npm install && npm run dev

# Electron studio
cd apps/desktop-studio && npm install && npm run dev

# Android (Android Studio ile ac, ya da:)
cd apps/android-player && ./gradlew assembleDebug
cd apps/android-studio && ./gradlew assembleDebug
```

Masaüstü uygulamalarında ("Demo Stream (test)" satırına tıklayarak) hiçbir credential girmeden player'ın gerçekten çalıştığını (oynatma + ses/altyazı track değiştirme) doğrulayabilirsiniz — bu ilk denenmesi gereken şey.

## Sıradaki adımlar (bu oturuma sığmadı)

- [ ] Gerçek IMDb linki + gerçek dosya ile uçtan uca test (Studio → Actions → HF → Player)
- [ ] `package-media.mjs`'deki ffmpeg komutunu gerçek çok-sesli bir dosyayla doğrula
- [ ] Android Hugging Face upload (LFS) akışını gerçek token ile doğrula
- [ ] Push sonrası GitHub Actions workflow'larının gerçekten tetiklendiğini doğrula (`gh workflow list`)
- [ ] v0.1.0 tag'i atıp build-desktop.yml/build-android.yml'nin Release oluşturduğunu doğrula
- [ ] İndirme (offline izleme) özelliği — kullanıcı "şimdilik gerek yok" dedi, ileride eklenecek
