# Film2 TV v1.1 — Samsung Tizen Player

Samsung Tizen TV için yalnızca **izleme/player** uygulamasıdır. Studio/yükleme yönetimi içermez.
Bu sürüm güncel `main` katalog mimarisini ve aktif player branch'lerindeki TV'ye anlamlı özellikleri birleştirir.

## v1.1 yenilikleri

- `catalog/index.json` üzerinden **tek istekte katalog snapshot**; GitHub Contents API / N+1 istek yok.
- `catalog/home.json` editoryal hero ve rafları, özel raflar ve günlük seed ile shuffle.
- Alternatif `posterUrls` / `backdropUrls` havuzlarını kullanma.
- Film/dizi/oyuncu/tür araması.
- Devam Et, izleme ilerlemesi ve son izlenen dizi bölümüne doğrudan devam.
- Listem ve kullanıcı tarafından oluşturulan özel listeler.
- Detay ekranında cast/crew, trailer preview, ilerleme ve sezon/bölüm kartları.
- AVPlay ile progressive MP4/MKV ve legacy HLS fallback.
- Gömülü çoklu ses + harici ses sidecar desteği.
- Gömülü altyazı + uzak/yerel WebVTT ve SRT sidecar desteği.
- `videoVariants` kalite seçimi, kalite değişiminde zamanı koruma.
- Ses, altyazı, kalite ve görüntü oranı tercihlerinin içerik bazında saklanması.
- Altyazı boyutu, dikey konum, renk, arkaplan ve kontur/gölge ayarları.
- Görüntü oranı: fit, crop, stretch, 16:9, 4:3, 21:9.
- Samsung kumandası: yön/OK/Geri/Play/Pause/Rewind/FastForward/Stop.
- Film/bölüm/sezon için Tizen Download API ile çevrimdışı indirme; tamamlanan direct-media içerik yerel AVPlay path'inden açılır.
- 5 dakikada bir katalog revision kontrolü ve otomatik yenileme.
- Tizen 3.0+ uyumlu, eski TV Web Engine'leri için CSS Grid/flex-gap bağımlılığı yok.

## Veri uyumluluğu

Player hem yeni direct-media modelini hem eski HLS kaydını okur:

```json
{
  "asset": {
    "videoUrl": "https://.../video.mp4",
    "masterPlaylistUrl": "https://.../master.m3u8",
    "externalAudioTracks": [],
    "externalSubtitleTracks": [],
    "videoVariants": []
  }
}
```

Çevrimdışı indirme yalnızca `videoUrl` veya `videoVariants` gibi doğrudan indirilebilir medya URL'lerinde gösterilir. HLS-only içerikler çevrimiçi oynatılmaya devam eder.

## Developer Mode ve imza

TV'de Developer Mode, bilgisayarın TV'ye bağlanıp geliştirme uygulaması deploy etmesini sağlar; ancak Samsung'un resmi Tizen politikasına göre gerçek TV'ye kurulan Web uygulamasının yine geçerli bir certificate profile ile imzalanması gerekir. Bu nedenle CI `unsigned.wgt` üretir; fiziksel TV kurulumu için Samsung Author + Distributor certificate (hedef TV DUID dahil) ile sign edilmelidir.

## Windows'tan tek komutla sign + install

Tizen Studio, Samsung TV Extension ve Samsung Certificate Extension kuruluysa:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-tv.ps1 -CertificateProfile "PROFIL_ADI" -TvIp "TV_IP_ADRESI"
```

Script kaynak projeyi build eder, bilgisayardaki certificate profile ile imzalar, TV'ye kurar ve uygulamayı başlatır. Sertifika özel anahtarı repoya yüklenmez.

## Branch izolasyonu

v1.1 geliştirmesi `agent/tizen-tv-player-v2` dalında tutulur. `main`, Android/Windows UX veya medya branch'lerine Tizen commit'i gönderilmez.
