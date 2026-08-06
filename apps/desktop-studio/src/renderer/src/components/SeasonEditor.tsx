import type { Episode, Season } from "@shared/types";
import { Button } from "./Button";
import { StatusBadge } from "./StatusBadge";

interface SeasonEditorProps {
  seasons: Season[];
  onChange: (seasons: Season[]) => void;
}

function blankEpisode(episodeNumber: number): Episode {
  return {
    episodeNumber,
    title: "",
    overview: "",
    airDate: undefined,
    stillUrl: "",
    runtimeMinutes: undefined,
    status: "pending",
  };
}

function blankSeason(seasonNumber: number): Season {
  return { seasonNumber, name: `Sezon ${seasonNumber}`, overview: "", posterUrl: "", episodes: [blankEpisode(1)] };
}

export function SeasonEditor({ seasons, onChange }: SeasonEditorProps) {
  function updateSeason(index: number, patch: Partial<Season>) {
    onChange(seasons.map((s, i) => (i === index ? { ...s, ...patch } : s)));
  }
  function removeSeason(index: number) {
    onChange(seasons.filter((_, i) => i !== index));
  }
  function addSeason() {
    const nextNumber = Math.max(0, ...seasons.map((s) => s.seasonNumber)) + 1;
    onChange([...seasons, blankSeason(nextNumber)]);
  }

  function updateEpisode(seasonIndex: number, episodeIndex: number, patch: Partial<Episode>) {
    const season = seasons[seasonIndex];
    const episodes = season.episodes.map((ep, i) => (i === episodeIndex ? { ...ep, ...patch } : ep));
    updateSeason(seasonIndex, { episodes });
  }
  function removeEpisode(seasonIndex: number, episodeIndex: number) {
    const season = seasons[seasonIndex];
    updateSeason(seasonIndex, { episodes: season.episodes.filter((_, i) => i !== episodeIndex) });
  }
  function addEpisode(seasonIndex: number) {
    const season = seasons[seasonIndex];
    const nextNumber = Math.max(0, ...season.episodes.map((e) => e.episodeNumber)) + 1;
    updateSeason(seasonIndex, { episodes: [...season.episodes, blankEpisode(nextNumber)] });
  }

  return (
    <div>
      {seasons.map((season, si) => (
        <div className="season-block" key={si}>
          <div className="season-block__header">
            <input
              className="input"
              style={{ maxWidth: 220, fontWeight: 700 }}
              value={season.name}
              onChange={(e) => updateSeason(si, { name: e.target.value })}
            />
            <span className="season-block__count">
              Sezon {season.seasonNumber} &middot; {season.episodes.length} bolum
            </span>
            <div style={{ marginLeft: "auto" }}>
              <Button variant="danger" size="sm" type="button" onClick={() => removeSeason(si)}>
                Sezonu sil
              </Button>
            </div>
          </div>

          <textarea
            className="textarea"
            placeholder="Sezon ozeti (opsiyonel)"
            value={season.overview ?? ""}
            onChange={(e) => updateSeason(si, { overview: e.target.value })}
            style={{ minHeight: 52, marginBottom: 10 }}
          />

          <div className="stack">
            {season.episodes.map((episode, ei) => (
              <div key={ei} className="episode-row" style={{ gridTemplateColumns: "34px 1fr 140px auto auto", height: "auto", padding: 12 }}>
                <span className="episode-row__number">S{season.seasonNumber}E{episode.episodeNumber}</span>
                <div className="stack" style={{ gap: 6 }}>
                  <input
                    className="input"
                    placeholder="Bolum basligi"
                    value={episode.title}
                    onChange={(e) => updateEpisode(si, ei, { title: e.target.value })}
                  />
                  <textarea
                    className="textarea"
                    placeholder="Bolum ozeti"
                    value={episode.overview}
                    onChange={(e) => updateEpisode(si, ei, { overview: e.target.value })}
                    style={{ minHeight: 42 }}
                  />
                </div>
                <input
                  className="input"
                  type="date"
                  value={episode.airDate ?? ""}
                  onChange={(e) => updateEpisode(si, ei, { airDate: e.target.value || undefined })}
                />
                <StatusBadge status={episode.status} />
                <Button variant="danger" size="sm" type="button" onClick={() => removeEpisode(si, ei)}>
                  Sil
                </Button>
              </div>
            ))}
          </div>
          <Button variant="ghost" size="sm" type="button" onClick={() => addEpisode(si)} style={{ marginTop: 8 }}>
            + Bolum ekle
          </Button>
        </div>
      ))}

      <Button variant="ghost" size="sm" type="button" onClick={addSeason} style={{ marginTop: 16 }}>
        + Sezon ekle
      </Button>
    </div>
  );
}
