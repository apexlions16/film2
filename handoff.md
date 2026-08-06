# handoff.md — film2

Bu dosya her oturum sonunda güncellenir: ne yapıldı, ne eksik, nasıl test edilir.

## Durum: Faz 0 + Faz 1 — 4 uygulama da CI'da başarıyla derlendi, gerçek kullanımdan bug'lar düzeltildi

**v0.2.3 Release, en güncel**: https://github.com/apexlions16/film2/releases/tag/v0.2.3

**Kullanıcı gerçekten kullanmaya başladı**: `desktop-studio` ile "TOLGSHOW" adlı bir
diziyi TMDB'den doğru şekilde çekip kataloğa ekledi, gerçek bir dosya (2.65GB) yükledi.
Bu gerçek kullanım 3 kritik bug ortaya çıkardı — üçü de bulunup düzeltildi:

### 3. (en kritik) Paketleme workflow'u ya hiç tetiklenmiyor gibi görünüyordu ya da yanlış HLS üretiyordu

Kullanıcı "hiçbir şey tetiklenmiyor" dedi. İnceleme: `repository_dispatch` **gerçekten
çalışıyordu** ama GitHub bu repoda **20-30 dakika gecikmeyle** teslim ediyordu (canlı
kanıtlandı — v0.1.8/v0.2.0 push-tetikli koşumlar saatler sonra gerçekten oluştu).
`workflow_dispatch` ise bu oturumda 15+ kez anında ve güvenilir çalıştı (gh CLI'nin
"Run workflow" ile aynı API). Studio uygulamaları (masaüstü + Android) artık bunu
kullanıyor — `apps/desktop-studio/src/main/github.ts` (`dispatchWorkflow`),
`apps/android-studio/.../PackageMediaDispatcher.kt`.

Gecikmeli de olsa iş sonunda çalıştı ve gerçek bir HLS `master.m3u8` üretti — ama
incelenince yapının **yanlış** olduğu ortaya çıktı: eski ffmpeg `-var_stream_map`
("v:0,a:N" her ses için tekrar) gerçek çoklu-sesli bir dosyada ffmpeg'i "Same
elementary stream found more than once" hatasıyla direkt reddediyordu (yerelde
sentetik Türkçe/İngilizce 2 sesli dosyayla doğrulandı — kullanıcının dosyası tek
sesli olduğu için bu hata şimdiye dek gizli kalmıştı). Ayrıca "tek dosya çoklu ses"
modunda tüm diller yanlışlıkla AYNI ses track'ini alacaktı. Doğru "1 video-only
variant + N audio-only agroup üyesi" HLS yapısına çevrildi — `hls.js`/ExoPlayer'ın
`audioTracks` API'sinin gerçekten anladığı yapı bu. `.github/scripts/package-media.mjs`.

### 2. Android izleme uygulamasına arama eklendi (kullanıcı isteği)

Katalogda film/dizi arama: Browse ekranında sağ üstte arama ikonu, gerçek zamanlı
filtreleme (Türkçe karakter duyarsız). `apps/android-player/.../ui/search/`.

### 1. 2 GiB üzeri dosyalar yüklenemiyordu

### Bulunan ve düzeltilen kritik bug: 2 GiB üzeri dosyalar yüklenemiyordu

Hata Hugging Face'ten değil, Node.js'in `fs.readFile()`'ının kendi sınırından
geliyordu — dosyayı tamamen belleğe okumaya çalışıyor ve 2**31-1 bayt üzerinde
reddediyordu. Film dosyaları bunu kolayca aşıyor. `packages/hf-storage/src/upload.js`
`openAsBlob()` (diskten tembel/lazy okuyan Blob, boyut sınırı yok) kullanacak şekilde
düzeltildi; `package-media.mjs`'deki indirme tarafı da benzer sebeple (Actions
runner'ında hafıza taşması riski) stream'e çevrildi. **Gerçek 2.3GB'lik dosyalarla
hem upload hem download canlı test edildi, ikisi de başarılı.** v0.2.1 ile masaüstü
uygulamaları bu düzeltmeyle yeniden derlendi.

**v0.2.1 Release**: https://github.com/apexlions16/film2/releases/tag/v0.2.1
(`film2 Player` + `Film2 Studio` — büyük dosya düzeltmesiyle; Android APK'lar bu JS
düzeltmesinden etkilenmiyor, v0.2.0'daki hâlâ geçerli)

### Önemli: gerçek Hugging Face kullanıcı adı `mfilms12`, GitHub'daki `apexlions16` değil

İlk taslakta shard namespace'i yanlışlıkla GitHub kullanıcı adıyla aynı varsayılmıştı.
Gerçek HF hesabı `mfilms12` çıktı, `catalog/shards.json` düzeltildi. İlk gerçek shard
repo'su: https://huggingface.co/datasets/mfilms12/film2-media-01

### v0.1.8: tüm 4 uygulama GitHub Actions'ta başarıyla derlendi ✅

- `apps/desktop-player`, `apps/desktop-studio` (Electron) — Windows installer, Release'e eklendi.
- `apps/android-player`, `apps/android-studio` (Kotlin) — imzalı (debug keystore) APK, Release'e eklendi, gerçekten kurulabilir.

desktop-studio'nun derlenmesi 5 farklı gerçek CI hatasından geçti (bkz. asagidaki debug
gunlugu) — en inatçısı electron-builder'ın kendi `app-builder-bin` aracını kendi kendine
silmesiydi, yerelde bizzat tekrar tekrar calistirarak bulundu.

### Yeni özellik (bu oturumun ikinci yarısı): farklı Hugging Face HESABINA otomatik failover

Kullanıcı netleştirdi: "aynı hesapta yeni repo açma" yetmiyor — Hugging Face **hesabı**
tamamen dolarsa, kullanıcı **başka bir hesap** açıp token'ını eklediğinde sistem bunu
tanıyıp otomatik oraya geçmeli. Bu, önceki "aynı hesapta yeni shard" özelliğinden farklı
ve daha kapsamlı bir gereksinim — ayrıca eklendi:

- `packages/hf-storage`: `ensureShardCapacity` artık tek token yerine öncelik sıralı
  hesap listesi alıyor (`[{namespace, token}]`). Hugging Face'in gerçek "kota doldu"
  hatasını (`isQuotaExceededError` — HTTP 402/403 ya da mesajda "quota"/"storage limit")
  tanıyor; aktif shard'ın hesabında yeni repo açmaya çalışıyor, o da kota hatası verirse
  listede SIRADAKI FARKLI hesaba geçiyor. `resolveHfAccount(token)` (whoami) ile token
  yapıştırılınca hangi hesap olduğu otomatik tespit ediliyor — kullanıcı adı elle
  yazılmıyor. `uploadFileWithFailover`/`uploadDirectoryWithFailover` gerçek yükleme
  sırasında da aynı kontrolü yapıp gerekirse hesap değiştiriyor. **Gerçek token'larla
  canlı test edildi**: quota-hatası tanıma, aynı-hesapta yeni shard açma, boş-hesap-listesi
  hata mesajı hepsi doğrulandı.
- `.github/scripts/package-media.mjs` + `package-media.yml`: `HF_ACCOUNTS_JSON` secret'ı
  (opsiyonel, coklu hesap: `[{"namespace":"...","token":"hf_..."}]`) `HF_TOKEN`'a ek
  olarak destekleniyor.
- `apps/desktop-studio`: Ayarlar ekranında artık tek token yerine "Hugging Face
  hesapları" listesi (ekle/kaldır, whoami ile doğrulanır, token asla renderer'a gitmez).
  Yükleme akışı failover kullanıyor, hesap değişince kullanıcıya bildiriyor. **`npm run
  build`/`build:win` temiz geçti** (yerelde, C:/D sürücü sorunu haricinde).
- `apps/android-studio`: Aynı özelliğin Kotlin portu tamamlandı, **`./gradlew
  assembleRelease` ile gerçekten derlendi** (BUILD SUCCESSFUL), kod JS referansıyla
  satır satır karşılaştırılıp doğrulandı.

**v0.2.0 tag'i atılıp tüm 4 uygulama birlikte yeniden tetiklendi — hepsi başarılı,
tek Release altında toplandı.**

**Önemli kullanım notu**: Yeni bir Hugging Face hesabı eklediğinizde bunu HEM Studio
uygulamasının (masaüstü/Android) Ayarlar ekranına HEM de (paketleme pipeline'ının da
görebilmesi için) GitHub'daki `HF_ACCOUNTS_JSON` secret'ına eklemeniz gerekiyor —
ikisi senkron tutulmalı.

### CI debug günlüğü — GitHub Actions üzerinde canlı çalıştırarak bulunan gerçek hatalar (v0.1.0 → v0.1.8)

Statik okumayla hiçbiri görünmüyordu, hepsi gerçek çalıştırmada ortaya çıktı:

1. Tag push + `paths` filtresi birlikte kullanılınca workflow hiç tetiklenmiyordu — `paths` kaldırıldı.
2. `if: ${{ secrets.X != '' }}` step-level'da reddediliyordu (HTTP 422) — `GITHUB_OUTPUT` + `steps.*.outputs` ile düzeltildi.
3. Repo'nun `default_workflow_permissions` ayarı "read" — Release oluşturma 403 veriyordu — `permissions: contents: write` eklendi.
4. `--workspaces=false`, desktop-studio'nun `@film2/*` paketlerini npm registry'den çekmeye çalışmasına sebep oldu — sonra `file:../../packages/X` protokolüyle düzgün çözüldü (bkz. madde 9).
5. electron-builder `node_modules`'ten electron sürümünü auto-detect edemiyordu — `electronVersion` elle sabitlendi.
6. desktop-player çıktısı `dist/`'e yazılıyordu, workflow `release/*.exe` bekliyordu — düzeltildi.
7. Android release APK imzasızdı, Android kurmayı reddediyordu (**kullanıcı bildirdi**) — debug keystore ile imzalama eklendi.
8. İki APK aynı dosya adıyla ayni Release'e yüklenince biri kayboluyordu — her app kendi benzersiz adına (`android-player.apk`/`android-studio.apk`) yeniden adlandırılıyor.
9. desktop-studio `build:win` 4 ayrı denemede farklı hatalarla patladı, sonuncusu (`app-builder-bin` kendi kendini silmesi) yerelde bizzat tekrar tekrar çalıştırılarak bulundu — `@film2/*` bağımlılıkları `file:` protokolüne çevrildi, her app kendi izole `node_modules`'una kavuştu.

### Bilinen riskler / sıradaki canlı testte doğrulanması gerekenler

1. **`package-media.mjs`'in düzeltilmiş HLS yapısı henüz GERÇEK bir kullanıcı dosyasıyla uçtan uca denenmedi** — sadece yerel sentetik test dosyasıyla ve kodu doğrulandı. Kullanıcı gerçekten çoklu sesli bir dosya yükleyince (örn. TOLGSHOW'un başka bir bölümünü Türkçe+İngilizce sesle) son kez doğrulanmalı.
2. Android Hugging Face upload (LFS) akışı (`HfUploader.kt`) — gerçek token ile henüz test edilmedi, kod içinde TODO notlarıyla işaretli.
3. Android proje yolu Türkçe karakter içerdiği için `android.overridePathCheck=true` eklendi, dokunmayın.
4. Studio'larda cast/crew/sezon-bölüm listeleri tek tek satır bazında düzenlenemiyor.
5. Failover sırasında TEK bir yükleme batch'i içinde hesap değişirse (nadir), pipeline'a tek bir `shardId` gönderiliyor — dosyaların hepsinin aynı shard'da olduğu varsayılıyor. Pratikte nadir (birkaç dosya tek seferde yükleniyor); tam çözüm için payload şemasının dosya-başına-shardId taşıyacak şekilde genişletilmesi gerekir.
6. `repository_dispatch` da (gecikmeli) hâlâ workflow'da tanımlı — teorik olarak bir kullanıcı eski/ikili bir tetikleme yaparsa gecikmeli bir kosumla cakismasi ihtimali dusuk ama sifir degil.

## Secret/credential durumu

### GitHub repo secrets — TAMAM
- `HF_TOKEN` ✅, `TMDB_API_KEY` ✅ eklendi
- `HF_ACCOUNTS_JSON` — opsiyonel, birden fazla Hugging Face hesabı eklediğinizde kullanılır (yoksa tek `HF_TOKEN` yeterli)
- `ANDROID_KEYSTORE_BASE64`/vb. — opsiyonel, yok; APK'lar debug keystore ile her zaman kurulabilir üretiliyor

### Studio uygulamaları içinde (yerel ayarlar, repoya asla commitlenmez)
- TMDB API key, Hugging Face hesap(lar)ı — Studio'nun Ayarlar ekranından girilir/eklenir
- GitHub PAT (repo scope) — https://github.com/settings/tokens

## Nasıl indirilir / denenir

**https://github.com/apexlions16/film2/releases/tag/v0.2.3** — 4 dosya:
- `film2.Player-0.1.0-setup.exe` — masaüstü izleme uygulaması
- `Film2.Studio.Setup.0.1.0.exe` — masaüstü içerik yükleme uygulaması (workflow_dispatch + coklu hesap dahil)
- `android-player.apk` — Android izleme uygulaması (arama özelliği dahil)
- `android-studio.apk` — Android içerik yükleme uygulaması (workflow_dispatch + coklu hesap dahil)

Credential'sız hızlı doğrulama: `film2 Player`'ı kurup açın, "Demo Stream (test)"
satırına tıklayın — gerçek oynatma + ses/altyazı track değiştirme çalışır. Android
APK'ları "Bilinmeyen kaynaklardan yükleme"ye izin vererek kurabilirsiniz (Play
Store'dan değil, kişisel imzayla geliyor).

## Sıradaki adımlar

- [ ] Gerçek çok-sesli (Türkçe+İngilizce vb.) bir dosyayla düzeltilmiş HLS yapısını uçtan uca doğrulamak — player'da ses değiştirme gerçekten çalışıyor mu
- [ ] Android Studio'dan gerçek bir dosya yükleyip HF upload (LFS) akışını gerçek token ile doğrulamak
- [ ] İndirme (offline izleme) özelliği — kullanıcı "şimdilik gerek yok" dedi, ileride eklenecek
