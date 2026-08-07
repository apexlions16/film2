# Film2 — Samsung Tizen TV Player

Bu klasör yalnızca **izleme/player uygulamasıdır**. Studio/yükleme özelliği içermez.
Mevcut `main` ve diğer geliştirme branch'lerinden bağımsız çalışır ve katalogu çalışma
anında `apexlions16/film2` reposunun `main` dalından okur.

## Özellikler

- Samsung TV Web Application / Tizen 3.0+ proje yapısı.
- Samsung `webapis.avplay` tabanlı oynatma.
- Progressive MP4/MKV (`asset.videoUrl`) ve eski HLS (`asset.masterPlaylistUrl`) desteği.
- Medyaya gömülü çoklu ses track'lerini AVPlay ile anlık değiştirme.
- `externalAudioTracks` için yan ses dosyasını HTML5 Audio ile oynatıp AVPlay saatine
  periyodik senkronlama. En iyi sonuç için sidecar sesin video ile aynı başlangıç ve
  sürede hazırlanmış olması gerekir.
- Gömülü altyazıları AVPlay callback'lerinden kendi overlay'inde gösterme.
- Uzak `externalSubtitleTracks` WebVTT dosyalarını indirip kendi cue parser'ıyla gösterme.
- `videoVariants` varsa kalite değiştirme; mevcut zaman korunarak yeni URL açılır.
- Samsung kumandası: yön tuşları, OK, Geri, Play/Pause, Rewind/FastForward, Stop.
- Film ve dizi/bölüm desteği.
- Yerel `localStorage` ile kaldığın yerden devam etme ve ses/altyazı/kalite tercihi.
- `catalog/version.json` 5 dakikada bir kontrol edilir; katalog değişirse ana ekranda
  otomatik yenilenir. Böylece içerik eklemeleri için Tizen kod branch'ini rebase etmek
  gerekmez.

## Katalog uyumluluğu

Player hem eski hem yeni asset biçimini toleranslı okur:

```json
{
  "asset": {
    "videoUrl": "https://.../video.mp4",
    "masterPlaylistUrl": "https://.../master.m3u8",
    "audioLanguages": ["eng", "tur"],
    "subtitleLanguages": ["eng", "tur"],
    "externalAudioTracks": [],
    "externalSubtitleTracks": [
      { "language": "tur", "label": "Türkçe", "url": "https://.../subs_tr.vtt" }
    ],
    "videoVariants": [
      { "label": "1080p", "height": 1080, "width": 1920, "url": "https://.../1080.mp4" }
    ]
  }
}
```

`videoUrl` varsa önceliklidir; yoksa `masterPlaylistUrl` fallback olarak kullanılır.

## Tizen Studio ile çalıştırma

1. Samsung TV Extension kurulu Tizen Studio'yu açın.
2. Bu `apps/tizen-tv-player` klasörünü mevcut Web Application olarak workspace'e alın.
3. Samsung Certificate Extension ile TV dağıtım sertifikanızı oluşturun/seçin.
4. TV'de Developer Mode'u açın ve bilgisayarın IP adresini tanımlayın.
5. Device Manager'dan TV'ye bağlanın.
6. Projeyi `Run As > Tizen Web Application` ile çalıştırın.
7. `.wgt` almak için projeyi build edin ve seçili Samsung certificate profile ile
   package/sign işlemini yapın.

## Medya notları

- En sorunsuz ortak profil: MP4 + H.264/H.265 video + AAC/AC3/DD+ ses.
- Yeni Samsung modellerinde DTS desteklenmediği için içeriklerde AAC/AC3/DD+ alternatif
  ses bulunması önerilir.
- Harici WebVTT altyazılar bu uygulamada TV'ye dosya olarak indirilmez; doğrudan HTTPS
  üzerinden okunup uygulama overlay'inde çizilir.
- Harici audio sidecar senkronizasyonu player saatine göre düzeltilir. Tam örnek doğruluk
  isteniyorsa çoklu dublajların aynı MP4/MKV içinde gömülü audio track olarak tutulması
  daha güvenilirdir ve AVPlay tarafından doğrudan seçilebilir.

## Branch izolasyonu

Bu uygulama için önerilen geliştirme dalı `agent/tizen-tv-player`'dır. `main` veya açık
Android/medya branch'lerine Tizen geliştirmesi sırasında commit atılmamalıdır.
