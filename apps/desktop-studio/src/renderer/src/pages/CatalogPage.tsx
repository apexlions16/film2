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
    try {
      const data = await unwrap(window.api.catalog.listTitles());
      setTitles(data);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!gated) load();
  }, [gated, load]);

  if (gated) return <SettingsGate onOpenSettings={onOpenSettings} />;

  const selected = titles?.find((t) => t.id === selectedId) ?? null;

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Katalog</h1>
          <p className="page__subtitle">
            catalog/titles/ altindaki tum icerikler ve paketleme durumlari. Player'in gordugu ayni veri.
          </p>
        </div>
        <div className="page__actions">
          <Button variant="ghost" onClick={load} loading={loading}>
            Yenile
          </Button>
          <Button variant="primary" onClick={onAddContent}>
            + Yeni Icerik
          </Button>
        </div>
      </div>

      {error && <ErrorBanner message="Katalog yuklenemedi" detail={error} />}

      {selected ? (
        <TitleDetail title={selected} onBack={() => setSelectedId(null)} onAttachFiles={onAttachFiles} />
      ) : loading && !titles ? (
        <div className="catalog-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="skeleton" style={{ aspectRatio: "2/3", borderRadius: 10 }} />
          ))}
        </div>
      ) : titles && titles.length > 0 ? (
        <div className="catalog-grid">
          {titles.map((title) => (
            <button key={title.id} className="catalog-card" onClick={() => setSelectedId(title.id)}>
              <div
                className="catalog-card__poster"
                style={title.posterUrl ? { backgroundImage: `url(${title.posterUrl})` } : undefined}
              >
                {!title.posterUrl && "Poster yok"}
              </div>
              <div className="catalog-card__body">
                <div className="catalog-card__title">{title.title || "(basliksiz)"}</div>
                <div className="catalog-card__meta">
                  <span>{title.releaseYear ?? "—"}</span>
                  <StatusBadge status={title.status} />
                </div>
              </div>
            </button>
          ))}
        </div>
      ) : (
        !loading && (
          <EmptyState
            title="Katalog bos"
            description="Henuz hicbir icerik eklenmedi. Bir IMDb linki ile ya da manuel olarak baslayin."
            action={
              <Button variant="primary" onClick={onAddContent}>
                + Yeni Icerik Ekle
              </Button>
            }
          />
        )
      )}
    </div>
  );
}

function TitleDetail({
  title,
  onBack,
  onAttachFiles,
}: {
  title: Title;
  onBack: () => void;
  onAttachFiles: (target: UploadRouteTarget) => void;
}) {
  return (
    <div>
      <button className="link-btn" onClick={onBack} style={{ marginBottom: 16 }}>
        &larr; Kataloga don
      </button>

      <div className="panel">
        <div className="row" style={{ alignItems: "flex-start", gap: 20 }}>
          {title.posterUrl && (
            <img src={title.posterUrl} alt="" style={{ width: 130, borderRadius: 10, border: "1px solid var(--border)" }} />
          )}
          <div style={{ flex: 1 }}>
            <div className="row" style={{ gap: 12, marginBottom: 6 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>{title.title}</h2>
              <StatusBadge status={title.status} />
            </div>
            <p className="text-dim" style={{ marginBottom: 10 }}>
              {title.releaseYear ?? "—"} &middot; {title.type === "movie" ? "Film" : "Dizi"} &middot;{" "}
              {title.genres.join(", ") || "tur belirtilmedi"}
            </p>
            <p style={{ maxWidth: "70ch" }}>{title.overview || <span className="text-faint">Ozet girilmemis.</span>}</p>

            {title.type === "movie" && (
              <div style={{ marginTop: 16 }}>
                <Button variant="primary" onClick={() => onAttachFiles({ titleId: title.id, kind: "movie" })}>
                  Dosya Ekle
                </Button>
              </div>
            )}
          </div>
        </div>
      </div>

      {title.type === "series" &&
        (title.seasons ?? []).map((season) => (
          <div className="panel" key={season.seasonNumber}>
            <div className="panel__title">{season.name}</div>
            <p className="panel__desc">{season.episodes.length} bolum</p>
            <div className="stack">
              {season.episodes.map((episode) => (
                <div key={episode.episodeNumber} className="episode-row">
                  <span className="episode-row__number">
                    S{season.seasonNumber}E{episode.episodeNumber}
                  </span>
                  <span className="episode-row__title">{episode.title || "(basliksiz bolum)"}</span>
                  <span className="episode-row__air">{episode.airDate ?? ""}</span>
                  <StatusBadge status={episode.status} />
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      onAttachFiles({
                        titleId: title.id,
                        kind: "episode",
                        seasonNumber: season.seasonNumber,
                        episodeNumber: episode.episodeNumber,
                      })
                    }
                  >
                    Dosya Ekle
                  </Button>
                </div>
              ))}
            </div>
          </div>
        ))}
    </div>
  );
}
