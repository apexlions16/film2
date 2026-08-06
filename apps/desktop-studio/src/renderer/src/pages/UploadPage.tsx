import { useEffect, useMemo, useRef, useState } from "react";
import type { Title, UploadFileSelection, UploadMode, UploadProgressEvent } from "@shared/types";
import { Button } from "../components/Button";
import { ErrorBanner } from "../components/ErrorBanner";
import { errorMessage, unwrap } from "../lib/api";
import type { UploadRouteTarget } from "../lib/route";

interface UploadPageProps {
  target: UploadRouteTarget;
  onDone: () => void;
  onBack: () => void;
}

interface LangFile {
  lang: string;
  path: string;
}

function basename(path: string): string {
  const normalized = path.replace(/\\/g, "/");
  return normalized.substring(normalized.lastIndexOf("/") + 1);
}

export function UploadPage({ target, onDone, onBack }: UploadPageProps) {
  const [title, setTitle] = useState<Title | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const [mode, setMode] = useState<UploadMode>("combined");
  const [combinedFile, setCombinedFile] = useState<string | null>(null);
  const [videoFile, setVideoFile] = useState<string | null>(null);
  const [audioFiles, setAudioFiles] = useState<LangFile[]>([{ lang: "tr", path: "" }]);
  const [subtitleFiles, setSubtitleFiles] = useState<LangFile[]>([]);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [progress, setProgress] = useState<UploadProgressEvent | null>(null);
  const [finished, setFinished] = useState(false);

  const unsubscribeRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await unwrap(window.api.catalog.getTitle(target.titleId));
        if (!cancelled) setTitle(data);
      } catch (err) {
        if (!cancelled) setLoadError(errorMessage(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [target.titleId]);

  useEffect(() => {
    return () => {
      unsubscribeRef.current?.();
    };
  }, []);

  const episode = useMemo(() => {
    if (target.kind !== "episode" || !title?.seasons) return null;
    const season = title.seasons.find((s) => s.seasonNumber === target.seasonNumber);
    const ep = season?.episodes.find((e) => e.episodeNumber === target.episodeNumber);
    return season && ep ? { season, ep } : null;
  }, [title, target]);

  async function pickSingle(label: string, set: (path: string) => void) {
    try {
      const files = await unwrap(window.api.files.pickFiles({ label, multi: false }));
      if (files[0]) set(files[0]);
    } catch (err) {
      setSubmitError(errorMessage(err));
    }
  }

  async function pickForLang(label: string, index: number, list: LangFile[], setList: (v: LangFile[]) => void) {
    try {
      const files = await unwrap(window.api.files.pickFiles({ label, multi: false }));
      if (files[0]) {
        const next = [...list];
        next[index] = { ...next[index], path: files[0] };
        setList(next);
      }
    } catch (err) {
      setSubmitError(errorMessage(err));
    }
  }

  function validate(): UploadFileSelection | null {
    if (mode === "combined") {
      if (!combinedFile) {
        setSubmitError("Birlesik dosya secilmedi.");
        return null;
      }
      return { mode, combinedFile, audioFiles: {}, subtitleFiles: {} };
    }

    if (!videoFile) {
      setSubmitError("Video dosyasi secilmedi.");
      return null;
    }
    const validAudio = audioFiles.filter((a) => a.lang.trim() && a.path);
    if (validAudio.length === 0) {
      setSubmitError("En az bir ses dosyasi (dil kodu + dosya) secilmeli.");
      return null;
    }
    const audioMap: Record<string, string> = {};
    for (const a of validAudio) audioMap[a.lang.trim()] = a.path;

    const validSubs = subtitleFiles.filter((s) => s.lang.trim() && s.path);
    const subMap: Record<string, string> = {};
    for (const s of validSubs) subMap[s.lang.trim()] = s.path;

    return { mode, videoFile, audioFiles: audioMap, subtitleFiles: subMap };
  }

  async function handleSubmit() {
    const selection = validate();
    if (!selection) return;

    setSubmitError(null);
    setSubmitting(true);
    setProgress(null);
    setFinished(false);

    unsubscribeRef.current = window.api.upload.onProgress((event) => {
      setProgress(event);
      if (event.phase === "done") setFinished(true);
    });

    try {
      await unwrap(
        window.api.upload.start({
          target: { titleId: target.titleId, kind: target.kind, seasonNumber: target.seasonNumber, episodeNumber: target.episodeNumber },
          selection,
        }),
      );
    } catch (err) {
      setSubmitError(errorMessage(err));
    } finally {
      setSubmitting(false);
      unsubscribeRef.current?.();
      unsubscribeRef.current = null;
    }
  }

  const heading =
    target.kind === "episode"
      ? `${title?.title ?? target.titleId} — S${target.seasonNumber}E${target.episodeNumber}${episode ? `: ${episode.ep.title || "(basliksiz)"}` : ""}`
      : title?.title ?? target.titleId;

  return (
    <div>
      <button className="link-btn" onClick={onBack} style={{ marginBottom: 16 }}>
        &larr; Kataloga don
      </button>

      <div className="page__header">
        <div>
          <h1 className="page__title">Dosya Ekle</h1>
          <p className="page__subtitle">{loading ? "Yukleniyor..." : heading}</p>
        </div>
      </div>

      {loadError && <ErrorBanner message="Icerik bilgisi alinamadi" detail={loadError} />}

      {!finished && (
        <div className="panel" style={{ maxWidth: 720 }}>
          <div className="panel__title">Dosya modu</div>
          <div className="radio-row" style={{ marginBottom: 20 }}>
            <label className={`radio-option${mode === "combined" ? " radio-option--checked" : ""}`}>
              <input type="radio" checked={mode === "combined"} onChange={() => setMode("combined")} />
              <span className="radio-option__text">
                <span className="radio-option__title">Tek dosya (birlesik, coklu ses/altyazi track icerir)</span>
                <span className="radio-option__desc">
                  Konteyner icinde birden fazla ses ve/veya altyazi track'i olan tek bir video dosyasi (orn. .mkv).
                </span>
              </span>
            </label>
            <label className={`radio-option${mode === "separate" ? " radio-option--checked" : ""}`}>
              <input type="radio" checked={mode === "separate"} onChange={() => setMode("separate")} />
              <span className="radio-option__text">
                <span className="radio-option__title">Ayri dosyalar (video + dil basina ses + dil basina altyazi)</span>
                <span className="radio-option__desc">
                  Video dosyasi ile ayri ayri dosyalar halinde her dil icin ses ve altyazi.
                </span>
              </span>
            </label>
          </div>

          {mode === "combined" ? (
            <div className="field">
              <label className="field__label">Birlesik dosya</label>
              <div className="row">
                <Button variant="default" type="button" onClick={() => pickSingle("Birlesik medya dosyasini secin", setCombinedFile)}>
                  Dosya sec
                </Button>
                <span className="text-dim" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {combinedFile ? basename(combinedFile) : "Dosya secilmedi"}
                </span>
              </div>
            </div>
          ) : (
            <div className="stack">
              <div className="field" style={{ margin: 0 }}>
                <label className="field__label">Video dosyasi</label>
                <div className="row">
                  <Button variant="default" type="button" onClick={() => pickSingle("Video dosyasini secin", setVideoFile)}>
                    Dosya sec
                  </Button>
                  <span className="text-dim" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                    {videoFile ? basename(videoFile) : "Dosya secilmedi"}
                  </span>
                </div>
              </div>

              <LangFileList
                label="Ses dosyalari (dil basina)"
                langPlaceholder="tr, en, de..."
                list={audioFiles}
                onChangeLang={(i, lang) => {
                  const next = [...audioFiles];
                  next[i] = { ...next[i], lang };
                  setAudioFiles(next);
                }}
                onPick={(i) => pickForLang("Ses dosyasini secin", i, audioFiles, setAudioFiles)}
                onRemove={(i) => setAudioFiles(audioFiles.filter((_, idx) => idx !== i))}
                onAdd={() => setAudioFiles([...audioFiles, { lang: "", path: "" }])}
              />

              <LangFileList
                label="Altyazi dosyalari (dil basina, opsiyonel)"
                langPlaceholder="tr, en, de..."
                list={subtitleFiles}
                onChangeLang={(i, lang) => {
                  const next = [...subtitleFiles];
                  next[i] = { ...next[i], lang };
                  setSubtitleFiles(next);
                }}
                onPick={(i) => pickForLang("Altyazi dosyasini secin", i, subtitleFiles, setSubtitleFiles)}
                onRemove={(i) => setSubtitleFiles(subtitleFiles.filter((_, idx) => idx !== i))}
                onAdd={() => setSubtitleFiles([...subtitleFiles, { lang: "", path: "" }])}
              />
            </div>
          )}

          {submitError && <ErrorBanner message="Yukleme basarisiz" detail={submitError} />}

          {submitting && progress && (
            <div style={{ marginTop: 18 }}>
              <div className="row row--between" style={{ marginBottom: 6 }}>
                <span className="text-dim" style={{ fontSize: 12.5 }}>
                  {progress.message ?? phaseLabel(progress.phase)}
                </span>
                <span className="text-faint mono" style={{ fontSize: 11.5 }}>
                  {progress.completedFiles}/{progress.totalFiles}
                </span>
              </div>
              <div className="progress-bar">
                <div
                  className="progress-bar__fill"
                  style={{ width: `${progress.totalFiles ? (progress.completedFiles / progress.totalFiles) * 100 : 0}%` }}
                />
              </div>
            </div>
          )}

          <div style={{ marginTop: 22 }}>
            <Button variant="primary" loading={submitting} onClick={handleSubmit}>
              Yukle ve paketlemeyi baslat
            </Button>
          </div>
        </div>
      )}

      {finished && (
        <div className="panel" style={{ maxWidth: 720 }}>
          <div className="processing-banner">
            <span aria-hidden="true">&#9203;</span>
            <div className="processing-banner__text">
              <span className="processing-banner__title">Isleniyor&hellip;</span>
              <span className="processing-banner__desc">
                Dosyalar Hugging Face'e yuklendi ve GitHub Actions paketleme workflow'u tetiklendi.
                HLS'e paketleme arka planda devam ediyor — bu ekranda beklemenize gerek yok,
                durumu Katalog ekranindan takip edebilirsiniz.
              </span>
            </div>
          </div>
          <div className="row" style={{ marginTop: 18 }}>
            <Button variant="primary" onClick={onDone}>
              Kataloga don
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setFinished(false);
                setProgress(null);
                setCombinedFile(null);
                setVideoFile(null);
                setAudioFiles([{ lang: "tr", path: "" }]);
                setSubtitleFiles([]);
              }}
            >
              Baska dosya yukle
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

function phaseLabel(phase: UploadProgressEvent["phase"]): string {
  switch (phase) {
    case "uploading":
      return "Dosyalar yukleniyor...";
    case "updating-registry":
      return "Shard kaydi guncelleniyor...";
    case "dispatching":
      return "Paketleme tetikleniyor...";
    case "done":
      return "Tamamlandi.";
    default:
      return "";
  }
}

function LangFileList({
  label,
  langPlaceholder,
  list,
  onChangeLang,
  onPick,
  onRemove,
  onAdd,
}: {
  label: string;
  langPlaceholder: string;
  list: LangFile[];
  onChangeLang: (index: number, lang: string) => void;
  onPick: (index: number) => void;
  onRemove: (index: number) => void;
  onAdd: () => void;
}) {
  return (
    <div className="field" style={{ margin: 0 }}>
      <label className="field__label">{label}</label>
      <div className="stack" style={{ gap: 8 }}>
        {list.map((item, i) => (
          <div key={i} className="row">
            <input
              className="input"
              style={{ maxWidth: 90 }}
              placeholder={langPlaceholder}
              value={item.lang}
              onChange={(e) => onChangeLang(i, e.target.value)}
            />
            <Button variant="default" size="sm" type="button" onClick={() => onPick(i)}>
              Dosya sec
            </Button>
            <span className="text-dim" style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontSize: 12 }}>
              {item.path ? basename(item.path) : "Dosya secilmedi"}
            </span>
            <Button variant="danger" size="sm" type="button" onClick={() => onRemove(i)}>
              Sil
            </Button>
          </div>
        ))}
        <Button variant="ghost" size="sm" type="button" onClick={onAdd} style={{ alignSelf: "flex-start" }}>
          + Dil ekle
        </Button>
      </div>
    </div>
  );
}
