# handoff.md — film2

Bu dosya her oturum sonunda güncellenir: ne yapıldı, ne eksik, nasıl test edilir.

## Durum: Faz 0 + Faz 1 — canlı credential'larla doğrulandı, Actions build'leri devrede

### Önemli: gerçek Hugging Face kullanıcı adı `mfilms12`, GitHub'daki `apexlions16` değil

İlk taslakta shard namespace'i yanlışlıkla GitHub kullanıcı adıyla aynı varsayılmıştı.
Gerçek HF hesabı `mfilms12` çıktı, `catalog/shards.json` ve örnek title dosyaları
düzeltildi. İlk gerçek shard repo'su oluşturuldu ve doğrulandı:
https://huggingface.co/datasets/mfilms12/film2-media-01

### Tamamlandı ve gerçek credential'larla canlı doğrulandı

- `packages/catalog-schema` — offline test edildi (geçerli/geçersiz title doğru ayrıştı).
- `packages/tmdb-client` — **gerçek TMDB API key ile canlı test edildi**: `tt0111161` linkinden "Esaretin Bedeli" (The Shawshank Redemption) metadata'sı (oyuncu, poster, backdrop, tür, süre) doğru şekilde çekildi. Türkçe `ı`/`İ` slugify hatası bulunup düzeltildi.
- `packages/hf-storage` — **gerçek HF write token ile canlı test edildi**: `mfilms12/film2-media-01` repo'su oluşturuldu, test dosyası yüklendi, `resolveUrl` ile üretilen link üzerinden dosya gerçekten indirilebildi. `test.mjs`'de Windows'a özgü bir path bug'ı (`URL.pathname` yerine `fileURLToPath` gerekiyordu) bulunup düzeltildi.
- `packages/catalog-client`, `catalog/shards.json`, `catalog/titles/_example-*.json` — tamam.
- `.github/workflows/package-media.yml` + scriptler — yazıldı, henüz gerçek medya dosyasıyla hiç çalıştırılmadı (aşağıya bakın).
- `apps/desktop-player`, `apps/desktop-studio` (Electron) — `npm install`/`build` temiz, player Browser pane'de görsel doğrulandı (2 bug bulunup düzeltildi: CSS keyframe kapsamı, mor renk ihlali).
- `apps/android-player`, `apps/android-studio` (Kotlin/Compose) — ikisi de gerçekten derlendi (`assembleRelease`).

### GitHub Actions — artık tüm build'ler buradan (kullanıcı talebi)

- `HF_TOKEN` ve `TMDB_API_KEY` repo secret'ı olarak eklendi (`gh secret list` ile doğrulandı).
- **Bulunan ve düzeltilen 2 gerçek workflow bug'ı** (ikisi de sadece `gh api`/`gh run list` ile canlı test edilerek ortaya çıktı, statik okumayla görünmüyordu):
  1. `push: {tags: [...], paths: [...]}` birlikte kullanılınca tag push'ları workflow'u **hiç tetiklemiyordu** (v0.1.0 tag'i push edildi, `gh api .../actions/runs` `total_count: 0` döndü). `paths` filtresi tag tetikleyicisinden tamamen kaldırıldı — artık `build-desktop.yml`/`build-android.yml` sadece tag push veya elle `workflow_dispatch` ile çalışıyor.
  2. `build-android.yml`'de `if: ${{ secrets.ANDROID_KEYSTORE_BASE64 != '' }}` GitHub tarafından reddediliyordu (`HTTP 422: Unrecognized named-value: 'secrets'` — step-level `if` içinde `secrets` context'i doğrudan kullanılamıyor). Önce bir adımda `GITHUB_OUTPUT`'a yazılıp sonraki adımın `if`'inde `steps.keystore_check.outputs.present` ile okunacak şekilde düzeltildi.
- v0.1.2 tag'i bu düzeltmelerle atıldı, `build-desktop.yml` (ref v0.1.1) ve `build-android.yml` (ref v0.1.2) `workflow_dispatch` ile elle tetiklendi — imzasız APK/installer üretip GitHub Release'e eklemeleri bekleniyor. Sonucu bu oturumun sonunda ya da GitHub'da Actions sekmesinden görebilirsiniz: https://github.com/apexlions16/film2/actions
- **Bundan sonra**: yeni bir `vX.Y.Z` tag'i push ettiğinizde (kendi makinenizden, `workflow` scope'lu bir token/normal git ile) otomatik build+Release beklenir — ama bizim ortamımızdaki `gh` OAuth token'ı `workflow` scope'suz olduğu için tag push'ları Actions'ı tetiklemedi, ben `gh workflow run ... --ref <tag>` (workflow_dispatch, tag ref'i ile — böylece Release'e ekleme adımı da çalışıyor) ile elle tetikledim. Kendi git/GitHub Desktop'ınızdan attığınız tag'ler muhtemelen sorunsuz tetikler; sorun yaşarsanız Actions sekmesinden "Run workflow" ile elle de tetikleyebilirsiniz.

### Bilinen riskler / sıradaki canlı testte doğrulanması gerekenler

1. **`package-media.mjs` ffmpeg `-var_stream_map` komutu** — henüz gerçek çok-sesli bir dosyayla hiç çalıştırılmadı. İlk gerçek içerik yüklemesinde doğrulanmalı.
2. **Android Hugging Face upload (LFS) akışı** (`HfUploader.kt`) — gerçek token ile henüz test edilmedi, kod içinde TODO notlarıyla işaretli.
3. **GitHub Contents API commit boyut sınırı** (~1MB, base64) — sadece küçük JSON dosyaları için kullanılıyor, tasarım gereği sorun olmamalı.
4. **Android proje yolu** — repo yolu Türkçe karakter içerdiği için `android.overridePathCheck=true` eklendi, dokunmayın.
5. Studio'larda cast/crew/sezon-bölüm listeleri tek tek satır bazında düzenlenemiyor, sadece üst seviye alanlar editable — ileride genişletilebilir.

## Secret/credential durumu

### GitHub repo secrets — TAMAM
- `HF_TOKEN` ✅ eklendi
- `TMDB_API_KEY` ✅ eklendi
- `ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD` — opsiyonel, yok; APK'lar şimdilik imzasız üretiliyor (cihaza kurulabilir, Play Store'a yüklenemez — kişisel kullanım için sorun değil)

### Studio uygulamaları içinde (yerel ayarlar, repoya asla commitlenmez)
- TMDB API key — sohbette paylaştığınız v3 API Anahtarı (repo secret olarak zaten eklendi, Studio'nun Ayarlar ekranına ayrıca siz gireceksiniz — bu dosyaya veya repoya asla yazılmaz)
- Hugging Face write token — sohbette paylaştığınız `hf_...` token (repo secret olarak zaten eklendi, Studio Ayarlar ekranına aynı şekilde siz gireceksiniz)
- GitHub PAT (repo scope) — Studio'nun Contents API + `repository_dispatch` çağırması için kendi PAT'ınızı üretmeniz gerekiyor: https://github.com/settings/tokens (gh CLI'nin kendi token'ı `workflow` scope'suz olduğu için Studio'nun kendi PAT'ini üretmesi daha saglam)

## Yerelde nasıl test edilir (opsiyonel — artık ana yol GitHub Actions)

```bash
npm install   # kok, packages/* icin

# Electron player — credential gerektirmez, "Demo Stream (test)" satırı calisir
cd apps/desktop-player && npm install && npm run dev

# Electron studio
cd apps/desktop-studio && npm install && npm run dev
```

Android için artık yerel `./gradlew` yerine GitHub Actions'taki `build-android.yml`
sonucu (Release altındaki APK) indirilip kurulabilir.

## Sıradaki adımlar

- [ ] `build-desktop.yml`/`build-android.yml` (v0.1.2) çalışmasını bekleyip Release linkini paylaşmak
- [ ] Gerçek IMDb linki + gerçek dosya ile Studio → Actions → HF → Player uçtan uca test
- [ ] `package-media.mjs`'deki ffmpeg komutunu gerçek çok-sesli bir dosyayla doğrulamak
- [ ] Android HF upload (LFS) akışını gerçek token ile doğrulamak
- [ ] İndirme (offline izleme) özelliği — kullanıcı "şimdilik gerek yok" dedi, ileride eklenecek
