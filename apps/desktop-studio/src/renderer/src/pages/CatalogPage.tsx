import { useCallback, useEffect, useState } from "react";
import type { Title } from "@shared/types";
import { Button } from "../components/Button";
import { StatusBadge } from "../components/StatusBadge";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { SettingsGate } from "../components/SettingsGate";
import { errorMessage, unwrap } from "../lib/api";
import type { UploadRouteTarget } from "../lib/route";

interface CatalogPageProps {
  gated: boolean;
  onOpenSettings: () => void;
  onAddContent: () => void;
  onAttachFiles: (target: UploadRouteTarget) => void;
}

export function CatalogPage({ gated, onOpenSettings, onAddContent, onAttachFiles }: CatalogPageProps) {
  const [titles, setTitles] = useState<Title[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try { setTitles(await unwrap(window.api.catalog.listTitles())); }
    catch (err) { setError(errorMessage(err)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { if (!gated) void load(); }, [gated, load]);
  if (gated) return <SettingsGate onOpenSettings={onOpenSettings} />;

  const selected = titles?.find((t) => t.id === selectedId) ?? null;

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Katalog</h1>
          <p className="page__subtitle">Player'ın gördüğü canlı katalog. Medya, trailer ve kalite yönetimi aynı ekranda.</p>
        </div>
        <div className="page__actions">
          <Button variant="ghost" onClick={load} loading={loading}>Yenile</Button>
          <Button variant="primary" onClick={onAddContent}>+ Yeni İçerik</Button>
        </div>
      </div>

      {error && <ErrorBanner message="Katalog yüklenemedi" detail={error} />}

      {selected ? (
        <TitleDetail title={selected} onBack={() => setSelectedId(null)} onAttachFiles={onAttachFiles} onReload={load} />
      ) : loading && !titles ? (
        <div className="catalog-grid">{Array.from({ length: 6 }).map((_, i) => <div key={i} className="skeleton" style={{ aspectRatio: "2/3", borderRadius: 10 }} />)}</div>
      ) : titles && titles.length > 0 ? (
        <div className="catalog-grid">
          {titles.map((title) => (
            <button key={title.id} className="catalog-card" onClick={() => setSelectedId(title.id)}>
              <div className="catalog-card__poster" style={title.posterUrl ? { backgroundImage: `url(${title.posterUrl})` } : undefined}>{!title.posterUrl && "Poster yok"}</div>
              <div className="catalog-card__body">
                <div className="catalog-card__title">{title.title || "(başlıksız)"}</div>
                <div className="catalog-card__meta"><span>{title.releaseYear ?? "—"}</span><StatusBadge status={title.status} /></div>
              </div>
            </button>
          ))}
        </div>
      ) : !loading ? (
        <EmptyState title="Katalog boş" description="Henüz içerik eklenmedi." action={<Button variant="primary" onClick={onAddContent}>+ Yeni İçerik Ekle</Button>} />
      ) : null}
    </div>
  );
}

function TitleDetail({
  title,
  onBack,
  onAttachFiles,
  onReload,
}: {
  title: Title;
  onBack: () => void;
  onAttachFiles: (target: UploadRouteTarget) => void;
  onReload: () => Promise<void>;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function uploadTrailer() {
    setError(null); setMessage(null);
    try {
      const files = await unwrap(window.api.files.pickFiles({ label: "Trailer / önizleme MP4 dosyasını seçin", multi: false }));
      if (!files[0]) return;
      setBusy("trailer");
      await unwrap(window.api.media.uploadTrailer({ titleId: title.id, localPath: files[0] }));
      setMessage("Trailer Hugging Face'e yüklendi ve Player kataloğuna bağlandı.");
      await onReload();
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(null); }
  }

  async function generateQuality(target: UploadRouteTarget) {
    setError(null); setMessage(null); setBusy(`quality-${target.kind}-${target.seasonNumber ?? 0}-${target.episodeNumber ?? 0}`);
    try {
      await unwrap(window.api.media.generateQualities({ ...target, heights: [720, 480] }));
      setMessage("720p + 480p kalite işi başlatıldı. Kaynak MP4'e dokunulmayacak; ses track'leri korunacak.");
    } catch (err) { setError(errorMessage(err)); }
    finally { setBusy(null); }
  }

  return (
    <div>
      <button className="link-btn" onClick={onBack} style={{ marginBottom: 16 }}>&larr; Kataloğa dön</button>
      {error && <ErrorBanner message="İşlem başarısız" detail={error} />}
      {message && <div className="panel" style={{ color: "var(--text-dim)", borderColor: "rgba(99,199,190,.4)" }}>{message}</div>}

      <div className="panel">
        <div className="row" style={{ alignItems: "flex-start", gap: 20 }}>
          {title.posterUrl && <img src={title.posterUrl} alt="" style={{ width: 130, borderRadius: 10, border: "1px solid var(--border)" }} />}
          <div style={{ flex: 1 }}>
            <div className="row" style={{ gap: 12, marginBottom: 6 }}><h2 style={{ fontSize: 18, fontWeight: 700 }}>{title.title}</h2><StatusBadge status={title.status} /></div>
            <p className="text-dim" style={{ marginBottom: 10 }}>{title.releaseYear ?? "—"} &middot; {title.type === "movie" ? "Film" : "Dizi"} &middot; {title.genres.join(", ") || "tür belirtilmedi"}</p>
            <p style={{ maxWidth: "70ch" }}>{title.overview || <span className="text-faint">Özet girilmemiş.</span>}</p>
            <div className="row" style={{ marginTop: 16, flexWrap: "wrap" }}>
              {title.type === "movie" && <Button variant="primary" onClick={() => onAttachFiles({ titleId: title.id, kind: "movie" })}>Dosya Ekle / Güncelle</Button>}
              <Button variant="ghost" loading={busy === "trailer"} onClick={uploadTrailer}>{title.trailerUrl ? "Trailer Değiştir" : "Trailer Yükle"}</Button>
              {title.type === "movie" && title.asset?.videoUrl && (
                <Button variant="ghost" loading={busy?.startsWith("quality-")} onClick={() => generateQuality({ titleId: title.id, kind: "movie" })}>720p + 480p Üret</Button>
              )}
            </div>
            {title.trailerUrl && <p className="text-faint" style={{ marginTop: 8, fontSize: 11 }}>Trailer hazır • detay sayfasında sessiz autoplay</p>}
          </div>
        </div>
      </div>

      {title.type === "series" && (title.seasons ?? []).map((season) => (
        <div className="panel" key={season.seasonNumber}>
          <div className="panel__title">{season.name}</div>
          <p className="panel__desc">{season.episodes.length} bölüm</p>
          <div className="stack">
            {season.episodes.map((episode) => (
              <div key={episode.episodeNumber} className="episode-row">
                <span className="episode-row__number">S{season.seasonNumber}E{episode.episodeNumber}</span>
                <span className="episode-row__title">{episode.title || "(başlıksız bölüm)"}</span>
                <span className="episode-row__air">{episode.airDate ?? ""}</span>
                <StatusBadge status={episode.status} />
                {episode.asset?.videoUrl && (
                  <Button variant="ghost" size="sm" loading={busy === `quality-episode-${season.seasonNumber}-${episode.episodeNumber}`} onClick={() => generateQuality({ titleId: title.id, kind: "episode", seasonNumber: season.seasonNumber, episodeNumber: episode.episodeNumber })}>Kalite</Button>
                )}
                <Button variant="ghost" size="sm" onClick={() => onAttachFiles({ titleId: title.id, kind: "episode", seasonNumber: season.seasonNumber, episodeNumber: episode.episodeNumber })}>Dosya Ekle</Button>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
