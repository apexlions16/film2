# handoff.md — film2

Bu dosya her oturum sonunda güncellenir: ne yapıldı, ne eksik, nasıl test edilir.

## Durum: Faz 0 + Faz 1 — canlı credential'larla doğrulandı, Actions build'leri devrede

### Önemli: gerçek Hugging Face kullanıcı adı `mfilms12`, GitHub'daki `apexlions16` değil

İlk taslakta shard namespace'i yanlışlıkla GitHub kullanıcı adıyla aynı varsayılmıştı.
Gerçek HF hesabı `mfilms12` çıktı, `catalog/shards.json` ve örnek title dosyaları
düzeltildi. İlk gerçek shard repo'su oluşturuldu ve doğrulandı:
https://huggingface.co/datasets/mfilms12/film2-media-01

### Çoklu-shard (depolama dolunca otomatik yeni Hugging Face dataset açma) — canlı doğrulandı

İstenen özellik: bir HF dataset repo'su dolunca otomatik yeni bir tane açılıp yeni
yüklemeler oraya yönlenecek, eski içerik eski yerinden okunmaya devam edecek. Bu
zaten `packages/hf-storage`'da (`ensureShardCapacity`) vardı ve hem `apps/desktop-studio`
(`src/main/hf.ts`) hem `apps/android-studio` (`ShardRegistryManager.kt`) hem de
paketleme pipeline'ı (`.github/scripts/package-media.mjs`) bunu upload öncesi çağırıyor
— kodu tekrar okuyup doğruladım. Ayrıca **gerçek bir testle kanıtladım**: eşiği geçici
olarak düşürüp `ensureShardCapacity`'yi gerçek HF token'ıyla çalıştırdım,
`mfilms12/film2-media-02` gerçekten oluştu, eski shard pasifleşti/yenisi aktifleşti,
sonra test repo'sunu sildim (gerçek `catalog/shards.json` hiç değişmedi, hâlâ sadece
`-01` var). Mekanizma çalışıyor; yeni shard'lar gerçek kullanım sırasında otomatik
açılacak, siz hiçbir şey yapmayacaksınız.

### Tamamlandı ve gerçek credential'larla canlı doğrulandı

- `packages/tmdb-client` — gerçek TMDB key ile canlı test edildi (tt0111161 → doğru metadata). Türkçe ı/İ slugify hatası düzeltildi.
- `packages/hf-storage` — gerçek HF token ile canlı test edildi (repo oluşturma, upload, indirme). Windows path bug'ı düzeltildi.
- `apps/android-player`, `apps/android-studio` — **GitHub Actions'ta gerçekten derlendi VE Release'e yüklendi, ikisi de indirilip kurulabilir durumda.**
- `apps/desktop-player` — **GitHub Actions'ta gerçekten derlendi, installer üretti.**
- `apps/desktop-studio` — deniyoruz, bkz. aşağıdaki "CI debug günlüğü" — sürekli yeni gerçek hatalar çıktı, her biri düzeltildi, son deneme (v0.1.7) sonucu bekleniyor.

### CI debug günlüğü — GitHub Actions üzerinde canlı çalıştırarak bulunan gerçek hatalar

Statik okumayla hiçbiri görünmüyordu, hepsi gerçek çalıştırmada ortaya çıktı:

1. **Tag push + `paths` filtresi birlikte kullanılınca workflow hiç tetiklenmiyordu** (v0.1.0: 0 run). `paths` filtresi tag tetikleyicisinden kaldırıldı.
2. **`if: ${{ secrets.X != '' }}` step-level'da reddediliyordu** (HTTP 422). Onceki adımda `GITHUB_OUTPUT`'a yazılıp `steps.*.outputs` ile okunacak sekilde duzeltildi.
3. **Repo'nun `default_workflow_permissions` ayarı "read"** — Release oluşturma 403 ile patlıyordu. `permissions: contents: write` eklendi.
4. **`--workspaces=false` ile izole npm kurulumu**, desktop-studio'nun kendi `@film2/*` paketlerini npm registry'den çekmeye çalışıp 404 almasına sebep oldu (bu paketler gerçek npm paketi değil, workspace symlink'i). Normal workspace kurulumuna dönüldü.
5. **electron-builder `node_modules`'ten electron sürümünü auto-detect edemiyordu** (hoisting yüzünden). `electronVersion` her iki app'te elle sabitlendi (43.3.0 / 32.2.6).
6. **desktop-player çıktısı `dist/`'e yazılıyordu ama workflow `release/*.exe` bekliyordu.** electron-builder.yml düzeltildi.
7. **Android release APK tamamen imzasız üretiliyordu — Android "App not installed" diyip reddediyordu.** Kullanıcı bunu bizzat bildirdi. Her iki Android app'te release buildType artık AGP'nin otomatik ürettiği debug keystore'uyla imzalanıyor (gerçek `ANDROID_KEYSTORE_BASE64` secret'ı eklenirse CI zaten üstüne gerçek imza atıyor).
8. **İki APK da aynı varsayılan dosya adıyla ("app-release.apk") aynı Release'e yüklenince biri diğerinin üzerine yazılıyordu** — v0.1.5 Release'inde sadece 1 apk vardı. Her app artık kendi benzersiz adına (`android-player.apk` / `android-studio.apk`) yeniden adlandırılıyor. v0.1.6'da doğrulandı: ikisi de Release'de.
9. **desktop-studio'nun `build:win`'i 3 ayrı denemede aynı `app-builder-bin ENOENT` hatasıyla patladı** (rastgele değil, deterministik). Sebep: package.json'daki `@film2/*` girdileri "dependencies" altındaydı, electron-builder onları gerçek npm paketi sanıp kendi paketleme aracıyla işlemeye çalışıyordu. Bu paketler zaten Rollup ile derleme anında bundle içine gömülüyor, çalışma zamanında ayrıca node_modules'te durmalarına gerek yok — `devDependencies`'e taşındı (v0.1.7).

**v0.1.7 şu an test ediliyor** — desktop-player zaten defalarca başarılı oldu, desktop-studio'nun bu son düzeltmeyle geçip geçmediği bu oturumun sonunda ya da https://github.com/apexlions16/film2/actions adresinden görülebilir.

### Bilinen riskler / sıradaki canlı testte doğrulanması gerekenler

1. **`package-media.mjs` ffmpeg `-var_stream_map` komutu** — henüz gerçek çok-sesli bir dosyayla hiç çalıştırılmadı.
2. **Android Hugging Face upload (LFS) akışı** (`HfUploader.kt`) — gerçek token ile henüz test edilmedi, kod içinde TODO notlarıyla işaretli.
3. **Android proje yolu** — repo yolu Türkçe karakter içerdiği için `android.overridePathCheck=true` eklendi, dokunmayın.
4. Studio'larda cast/crew/sezon-bölüm listeleri tek tek satır bazında düzenlenemiyor, sadece üst seviye alanlar editable.

## Secret/credential durumu

### GitHub repo secrets — TAMAM
- `HF_TOKEN` ✅, `TMDB_API_KEY` ✅ eklendi
- `ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/`ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD` — opsiyonel, yok; APK'lar artık debug keystore ile her zaman kurulabilir imzalı üretiliyor (Play Store'a yüklenemez ama kişisel kullanım için sorun değil)

### Studio uygulamaları içinde (yerel ayarlar, repoya asla commitlenmez)
- TMDB API key, Hugging Face write token — sohbette paylaştığınız değerler (repo secret olarak zaten eklendi), Studio'nun Ayarlar ekranına ayrıca siz gireceksiniz
- GitHub PAT (repo scope) — Studio'nun Contents API + `repository_dispatch` çağırması için kendi PAT'ınızı üretmeniz gerekiyor: https://github.com/settings/tokens

## Yerelde nasıl test edilir (opsiyonel — artık ana yol GitHub Actions)

```bash
npm install   # kok, packages/* icin

# Electron player — credential gerektirmez, "Demo Stream (test)" satırı calisir
cd apps/desktop-player && npm install && npm run dev
```

Masaüstü/Android uygulamalarını artık GitHub Releases'ten indirip kurmanız yeterli:
https://github.com/apexlions16/film2/releases

## Sıradaki adımlar

- [ ] v0.1.7'nin desktop-studio'da da geçtiğini doğrulamak, geçerse temiz bir vX.Y.Z ile son bir kez tüm 4 uygulamayı birlikte tetikleyip tek bir Release altında toplamak
- [ ] Gerçek IMDb linki + gerçek dosya ile Studio → Actions → HF → Player uçtan uca test
- [ ] `package-media.mjs`'deki ffmpeg komutunu gerçek çok-sesli bir dosyayla doğrulamak
- [ ] Android HF upload (LFS) akışını gerçek token ile doğrulamak
- [ ] İndirme (offline izleme) özelliği — kullanıcı "şimdilik gerek yok" dedi, ileride eklenecek
