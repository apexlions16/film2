import { useState } from "react";
import type { Title } from "@shared/types";
import { Button } from "../components/Button";
import { TextField, TextAreaField } from "../components/TextField";
import { ErrorBanner } from "../components/ErrorBanner";
import { SettingsGate } from "../components/SettingsGate";
import { CastEditor, CrewEditor } from "../components/CastCrewEditor";
import { SeasonEditor } from "../components/SeasonEditor";
import { errorMessage, unwrap } from "../lib/api";
import { extractImdbId, slugify } from "../lib/slug";

interface AddContentPageProps {
  gated: boolean;
  onOpenSettings: () => void;
  onSavedMovie: (titleId: string) => void;
  onSavedSeries: () => void;
}

type Phase = "input" | "not-found" | "editing";

function blankTitle(imdbId: string, type: "movie" | "series"): Title {
  const now = new Date().toISOString();
  return {
    id: imdbId,
    type,
    imdbId,
    title: "",
    originalTitle: "",
    overview: "",
    releaseYear: undefined,
    genres: [],
    runtimeMinutes: undefined,
    posterUrl: "",
    backdropUrl: "",
    cast: [],
    crew: [],
    status: "pending",
    manualEntry: true,
    createdAt: now,
    updatedAt: now,
    seasons: type === "series" ? [{ seasonNumber: 1, name: "Sezon 1", overview: "", posterUrl: "", episodes: [] }] : undefined,
  };
}

export function AddContentPage({ gated, onOpenSettings, onSavedMovie, onSavedSeries }: AddContentPageProps) {
  const [imdbInput, setImdbInput] = useState("");
  const [phase, setPhase] = useState<Phase>("input");
  const [fetching, setFetching] = useState(false);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [draft, setDraft] = useState<Title | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  if (gated) return <SettingsGate onOpenSettings={onOpenSettings} />;

  async function handleFetch() {
    setFetching(true);
    setFetchError(null);
    try {
      const result = await unwrap(window.api.tmdb.fetchFromImdb(imdbInput));
      if (result) {
        setDraft(result);
        setPhase("editing");
      } else {
        setPhase("not-found");
      }
    } catch (err) {
      setFetchError(errorMessage(err));
    } finally {
      setFetching(false);
    }
  }

  function startManual(type: "movie" | "series") {
    const imdbId = extractImdbId(imdbInput) ?? `tt${Date.now()}`;
    setDraft(blankTitle(imdbId, type));
    setPhase("editing");
  }

  function updateDraft(patch: Partial<Title>) {
    setDraft((d) => {
      if (!d) return d;
      const next = { ...d, ...patch };
      // Manuel giriste id, baslik degistikce slug'dan yeniden turetilir.
      if (d.manualEntry && patch.title !== undefined) {
        next.id = slugify(patch.title, d.imdbId);
      }
      return next;
    });
  }

  async function handleSave() {
    if (!draft) return;
    if (!draft.title.trim()) {
      setSaveError("Baslik alani bos birakilamaz.");
      return;
    }
    setSaving(true);
    setSaveError(null);
    try {
      await unwrap(window.api.catalog.saveTitle(draft));
      if (draft.type === "movie") {
        onSavedMovie(draft.id);
      } else {
        onSavedSeries();
      }
    } catch (err) {
      setSaveError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Yeni Icerik Ekle</h1>
          <p className="page__subtitle">
            Bir IMDb linki yapistirip metadatayi TMDB'den otomatik cekin, ya da hicbir eslesme
            yoksa tum alanlari elle doldurun.
          </p>
        </div>
      </div>

      {phase !== "editing" && (
        <div className="panel" style={{ maxWidth: 640 }}>
          <div className="panel__title">IMDb linki</div>
          <p className="panel__desc">
            ör. https://www.imdb.com/title/tt1234567/ ya da dogrudan tt1234567
          </p>
          {fetchError && <ErrorBanner message="TMDB'den veri cekilemedi" detail={fetchError} />}
          <div className="row">
            <input
              className="input"
              placeholder="https://www.imdb.com/title/tt..."
              value={imdbInput}
              onChange={(e) => setImdbInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && imdbInput.trim() && handleFetch()}
            />
            <Button variant="primary" loading={fetching} disabled={!imdbInput.trim()} onClick={handleFetch}>
              Getir
            </Button>
          </div>

          {phase === "not-found" && (
            <div style={{ marginTop: 18 }}>
              <p className="text-dim" style={{ marginBottom: 10 }}>
                TMDB/IMDb'de bu linke ait bir eslesme bulunamadi. Icerigi turune gore manuel
                olarak ekleyebilirsiniz — bu durumda TUM alanlari elle dolduracaksiniz.
              </p>
              <div className="row">
                <Button variant="default" onClick={() => startManual("movie")}>
                  Film olarak manuel gir
                </Button>
                <Button variant="default" onClick={() => startManual("series")}>
                  Dizi olarak manuel gir
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {phase === "editing" && draft && (
        <div>
          {draft.manualEntry && (
            <div className="processing-banner" style={{ marginBottom: 20, background: "var(--status-pending-bg)", borderColor: "rgba(232,187,108,0.3)", color: "var(--status-pending-fg)" }}>
              <span aria-hidden="true">&#9998;</span>
              <div className="processing-banner__text">
                <span className="processing-banner__title">Manuel giris</span>
                <span className="processing-banner__desc">
                  TMDB/IMDb'de veri bulunamadi — asagidaki tum alanlari elle doldurun.
                </span>
              </div>
            </div>
          )}

          {saveError && <ErrorBanner message="Kaydedilemedi" detail={saveError} />}

          <div className="panel">
            <div className="panel__title">Genel bilgiler</div>
            <p className="panel__desc">
              catalog/titles/{draft.id}.json &middot; tur: {draft.type === "movie" ? "Film" : "Dizi"} &middot; IMDb: {draft.imdbId}
            </p>

            {(draft.posterUrl || draft.backdropUrl) && (
              <div className="row" style={{ marginBottom: 18, alignItems: "flex-start" }}>
                {draft.posterUrl && (
                  <img
                    src={draft.posterUrl}
                    alt="Poster"
                    style={{ width: 100, borderRadius: 8, border: "1px solid var(--border)" }}
                  />
                )}
                {draft.backdropUrl && (
                  <img
                    src={draft.backdropUrl}
                    alt="Backdrop"
                    style={{ width: 220, borderRadius: 8, border: "1px solid var(--border)", aspectRatio: "16/9", objectFit: "cover" }}
                  />
                )}
              </div>
            )}

            <div className="field-grid">
              <TextField
                label="Baslik"
                required
                value={draft.title}
                onChange={(e) => updateDraft({ title: e.target.value })}
              />
              <TextField
                label="Orijinal baslik"
                value={draft.originalTitle ?? ""}
                onChange={(e) => updateDraft({ originalTitle: e.target.value })}
              />
            </div>

            <TextAreaField
              label="Ozet"
              value={draft.overview}
              onChange={(e) => updateDraft({ overview: e.target.value })}
            />

            <div className="field-grid field-grid--3">
              <TextField
                label="Cikis yili"
                type="number"
                value={draft.releaseYear ?? ""}
                onChange={(e) => updateDraft({ releaseYear: e.target.value ? Number(e.target.value) : undefined })}
              />
              {draft.type === "movie" && (
                <TextField
                  label="Sure (dakika)"
                  type="number"
                  value={draft.runtimeMinutes ?? ""}
                  onChange={(e) => updateDraft({ runtimeMinutes: e.target.value ? Number(e.target.value) : undefined })}
                />
              )}
              <TextField
                label="Turler (virgulle ayirin)"
                value={draft.genres.join(", ")}
                onChange={(e) =>
                  updateDraft({ genres: e.target.value.split(",").map((g) => g.trim()).filter(Boolean) })
                }
              />
            </div>

            <div className="field-grid">
              <TextField
                label="Poster URL"
                value={draft.posterUrl ?? ""}
                onChange={(e) => updateDraft({ posterUrl: e.target.value })}
              />
              <TextField
                label="Backdrop URL"
                value={draft.backdropUrl ?? ""}
                onChange={(e) => updateDraft({ backdropUrl: e.target.value })}
              />
            </div>
          </div>

          <div className="panel">
            <div className="panel__title">Oyuncular</div>
            <p className="panel__desc">Ilk 20 oyuncu TMDB'den otomatik gelir, dilediginiz gibi duzenleyin.</p>
            <CastEditor cast={draft.cast} onChange={(cast) => updateDraft({ cast })} />
          </div>

          <div className="panel">
            <div className="panel__title">Ekip</div>
            <p className="panel__desc">Yonetmen, senarist, yaratici vb.</p>
            <CrewEditor crew={draft.crew} onChange={(crew) => updateDraft({ crew })} />
          </div>

          {draft.type === "series" && (
            <div className="panel">
              <div className="panel__title">Sezonlar &amp; Bolumler</div>
              <p className="panel__desc">
                Her bolume dosya eklemeyi Katalog ekranindan, bolumu secerek yapabilirsiniz.
              </p>
              <SeasonEditor seasons={draft.seasons ?? []} onChange={(seasons) => updateDraft({ seasons })} />
            </div>
          )}

          <div className="row" style={{ marginTop: 24, marginBottom: 40 }}>
            <Button variant="primary" loading={saving} onClick={handleSave}>
              Kaydet
            </Button>
            <Button
              variant="ghost"
              type="button"
              onClick={() => {
                setDraft(null);
                setPhase("input");
                setImdbInput("");
              }}
            >
              Iptal
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
