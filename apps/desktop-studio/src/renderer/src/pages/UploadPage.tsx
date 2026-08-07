import { useEffect, useMemo, useRef, useState } from "react";
import type { Title, UploadFileSelection, UploadMode, UploadProgressEvent } from "@shared/types";
import { Button } from "../components/Button";
import { ErrorBanner } from "../components/ErrorBanner";
import { errorMessage, unwrap } from "../lib/api";
import type { UploadRouteTarget } from "../lib/route";

interface UploadPageProps { target: UploadRouteTarget; onDone: () => void; onBack: () => void; }
interface LangFile { lang: string; path: string; }
function basename(path: string): string { const normalized = path.replace(/\\/g, "/"); return normalized.substring(normalized.lastIndexOf("/") + 1); }

export function UploadPage({ target, onDone, onBack }: UploadPageProps) {
  const [title, setTitle] = useState<Title | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [mode, setMode] = useState<UploadMode>("separate");
  const [combinedFile, setCombinedFile] = useState<string | null>(null);
  const [videoFile, setVideoFile] = useState<string | null>(null);
  const [audioFiles, setAudioFiles] = useState<LangFile[]>([{ lang: "Türkçe", path: "" }, { lang: "İngilizce", path: "" }]);
  const [subtitleFiles, setSubtitleFiles] = useState<LangFile[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [progress, setProgress] = useState<UploadProgressEvent | null>(null);
  const [finished, setFinished] = useState(false);
  const [fastPath, setFastPath] = useState<boolean | null>(null);
  const unsubscribeRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try { const data = await unwrap(window.api.catalog.getTitle(target.titleId)); if (!cancelled) setTitle(data); }
      catch (err) { if (!cancelled) setLoadError(errorMessage(err)); }
      finally { if (!cancelled) setLoading(false); }
    })();
    return () => { cancelled = true; };
  }, [target.titleId]);

  useEffect(() => () => unsubscribeRef.current?.(), []);

  const episode = useMemo(() => {
    if (target.kind !== "episode" || !title?.seasons) return null;
    const season = title.seasons.find((s) => s.seasonNumber === target.seasonNumber);
    const ep = season?.episodes.find((e) => e.episodeNumber === target.episodeNumber);
    return season && ep ? { season, ep } : null;
  }, [title, target]);

  async function pickSingle(label: string, set: (path: string) => void) {
    try { const files = await unwrap(window.api.files.pickFiles({ label, multi: false })); if (files[0]) set(files[0]); }
    catch (err) { setSubmitError(errorMessage(err)); }
  }

  async function pickForLang(label: string, index: number, list: LangFile[], setList: (v: LangFile[]) => void) {
    try {
      const files = await unwrap(window.api.files.pickFiles({ label, multi: false }));
      if (files[0]) { const next = [...list]; next[index] = { ...next[index], path: files[0] }; setList(next); }
    } catch (err) { setSubmitError(errorMessage(err)); }
  }

  function validate(): UploadFileSelection | null {
    if (mode === "combined") {
      if (!combinedFile) { setSubmitError("Birleşik dosya seçilmedi."); return null; }
      return { mode, combinedFile, audioFiles: {}, subtitleFiles: {} };
    }
    if (!videoFile) { setSubmitError("Video dosyası seçilmedi."); return null; }
    const validAudio = audioFiles.filter((a) => a.lang.trim() && a.path);
    if (validAudio.length === 0) { setSubmitError("En az bir ses dosyası seçilmeli."); return null; }
    const audioMap: Record<string, string> = {};
    validAudio.forEach((a) => { audioMap[a.lang.trim()] = a.path; });
    const subMap: Record<string, string> = {};
    subtitleFiles.filter((s) => s.lang.trim() && s.path).forEach((s) => { subMap[s.lang.trim()] = s.path; });
    return { mode, videoFile, audioFiles: audioMap, subtitleFiles: subMap };
  }

  async function handleSubmit() {
    const selection = validate();
    if (!selection) return;
    setSubmitError(null); setSubmitting(true); setProgress(null); setFinished(false); setFastPath(null);
    unsubscribeRef.current = window.api.upload.onProgress((event) => { setProgress(event); if (event.phase === "done") setFinished(true); });
    try {
      const result = await unwrap(window.api.upload.start({ target: { titleId: target.titleId, kind: target.kind, seasonNumber: target.seasonNumber, episodeNumber: target.episodeNumber }, selection }));
      setFastPath(Boolean(result.fastPath));
      setFinished(true);
    } catch (err) { setSubmitError(errorMessage(err)); }
    finally { setSubmitting(false); unsubscribeRef.current?.(); unsubscribeRef.current = null; }
  }

  const heading = target.kind === "episode"
    ? `${title?.title ?? target.titleId} — S${target.seasonNumber}E${target.episodeNumber}${episode ? `: ${episode.ep.title || "(başlıksız)"}` : ""}`
    : title?.title ?? target.titleId;
  const percent = progress?.percent ?? (progress?.totalFiles ? Math.round(progress.completedFiles / progress.totalFiles * 100) : 0);

  return (
    <div>
      <button className="link-btn" onClick={onBack} style={{ marginBottom: 16 }}>&larr; Kataloğa dön</button>
      <div className="page__header"><div><h1 className="page__title">Medya Ekle / Güncelle</h1><p className="page__subtitle">{loading ? "Yükleniyor…" : heading}</p></div></div>
      {loadError && <ErrorBanner message="İçerik bilgisi alınamadı" detail={loadError} />}

      {!finished && (
        <div className="panel" style={{ maxWidth: 760 }}>
          <div className="panel__title">Hızlı yayın modu</div>
          <p className="panel__desc">Dosya uzantısına güvenilmez. MP4, gerçek MKV veya .mkv diye adlandırılmış MPEG-TS dosyaları FFmpeg tarafından içerikten tanınır; uyumlu video/ses streamleri encode edilmeden yerelde tek MP4'e muxlanır ve final dosya Hugging Face'e yalnızca bir kez yüklenir.</p>
          <div className="radio-row" style={{ marginBottom: 20 }}>
            <label className={`radio-option${mode === "separate" ? " radio-option--checked" : ""}`}>
              <input type="radio" checked={mode === "separate"} onChange={() => setMode("separate")} />
              <span className="radio-option__text"><span className="radio-option__title">Video + ayrı sesler + altyazılar • Önerilen hızlı yol</span><span className="radio-option__desc">video.mkv + tr.mkv + en.mkv gerçekte MPEG-TS olsa bile content sniffing + stream-copy ile tek final MP4 olur.</span></span>
            </label>
            <label className={`radio-option${mode === "combined" ? " radio-option--checked" : ""}`}>
              <input type="radio" checked={mode === "combined"} onChange={() => setMode("combined")} />
              <span className="radio-option__text"><span className="radio-option__title">Hazır birleşik dosya</span><span className="radio-option__desc">Gerçek MP4 doğrudan yüklenir; .mkv/.ts gibi diğer container'larda önce yerel stream-copy MP4 remux denenir. Yalnız gerçekten uyumsuz codec varsa fallback kullanılır.</span></span>
            </label>
          </div>

          {mode === "combined" ? (
            <FilePicker label="Birleşik medya • MP4 / MKV / TS" value={combinedFile} onPick={() => pickSingle("Birleşik medya dosyasını seçin", setCombinedFile)} />
          ) : (
            <div className="stack">
              <FilePicker label="Video dosyası • MP4 / MKV / TS" value={videoFile} onPick={() => pickSingle("Video dosyasını seçin", setVideoFile)} />
              <LangFileList label="Ses dosyaları • AAC/M4A veya MPEG-TS olarak saklanan .mkv/.ts" langPlaceholder="Türkçe, İngilizce…" list={audioFiles} onChangeLang={(i, lang) => { const next = [...audioFiles]; next[i] = { ...next[i], lang }; setAudioFiles(next); }} onPick={(i) => pickForLang("Ses dosyasını seçin", i, audioFiles, setAudioFiles)} onRemove={(i) => setAudioFiles(audioFiles.filter((_, idx) => idx !== i))} onAdd={() => setAudioFiles([...audioFiles, { lang: "", path: "" }])} />
              <LangFileList label="Altyazılar • VTT/SRT • opsiyonel" langPlaceholder="Türkçe, İngilizce…" list={subtitleFiles} onChangeLang={(i, lang) => { const next = [...subtitleFiles]; next[i] = { ...next[i], lang }; setSubtitleFiles(next); }} onPick={(i) => pickForLang("Altyazı dosyasını seçin", i, subtitleFiles, setSubtitleFiles)} onRemove={(i) => setSubtitleFiles(subtitleFiles.filter((_, idx) => idx !== i))} onAdd={() => setSubtitleFiles([...subtitleFiles, { lang: "", path: "" }])} />
            </div>
          )}

          {submitError && <ErrorBanner message="Yükleme başarısız" detail={submitError} />}

          {(submitting || progress) && (
            <div style={{ marginTop: 18 }}>
              <div className="row row--between" style={{ marginBottom: 7 }}><span className="text-dim" style={{ fontSize: 12.5 }}>{progress?.message ?? "Hazırlanıyor…"}</span><b className="mono" style={{ fontSize: 12 }}>%{Math.min(100, percent)}</b></div>
              <div className="progress-bar"><div className="progress-bar__fill" style={{ width: `${Math.min(100, Math.max(2, percent))}%` }} /></div>
            </div>
          )}

          <div style={{ marginTop: 22 }}><Button variant="primary" loading={submitting} onClick={handleSubmit}>Hızlı Yayınla</Button></div>
        </div>
      )}

      {finished && (
        <div className="panel" style={{ maxWidth: 760 }}>
          <div className="processing-banner">
            <span aria-hidden="true">{fastPath ? "✓" : "⏳"}</span>
            <div className="processing-banner__text">
              <span className="processing-banner__title">{fastPath ? "Hazır ve yayında" : "Uyumluluk işlemi devam ediyor"}</span>
              <span className="processing-banner__desc">{fastPath ? "Final tek MP4 doğrudan Hugging Face'e yayınlandı; GitHub Actions remux'u atlandı. Player katalog revision değişimini birkaç saniye içinde görecek." : "Yerel stream-copy gerçekten uyumlu olmadığı için güvenli GitHub fallback'i kullanıldı."}</span>
            </div>
          </div>
          <div className="row" style={{ marginTop: 18 }}><Button variant="primary" onClick={onDone}>Kataloğa dön</Button><Button variant="ghost" onClick={() => { setFinished(false); setProgress(null); setFastPath(null); }}>Başka dosya yükle</Button></div>
        </div>
      )}
    </div>
  );
}

function FilePicker({ label, value, onPick }: { label: string; value: string | null; onPick: () => void }) {
  return <div className="field"><label className="field__label">{label}</label><div className="row"><Button variant="default" type="button" onClick={onPick}>Dosya seç</Button><span className="text-dim" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{value ? basename(value) : "Dosya seçilmedi"}</span></div></div>;
}

function LangFileList({ label, langPlaceholder, list, onChangeLang, onPick, onRemove, onAdd }: { label: string; langPlaceholder: string; list: LangFile[]; onChangeLang: (index: number, lang: string) => void; onPick: (index: number) => void; onRemove: (index: number) => void; onAdd: () => void; }) {
  return (
    <div className="field" style={{ margin: 0 }}><label className="field__label">{label}</label><div className="stack" style={{ gap: 8 }}>
      {list.map((item, i) => <div key={i} className="row"><input className="input" style={{ maxWidth: 145 }} placeholder={langPlaceholder} value={item.lang} onChange={(e) => onChangeLang(i, e.target.value)} /><Button variant="default" size="sm" type="button" onClick={() => onPick(i)}>Dosya seç</Button><span className="text-dim" style={{ flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", fontSize: 12 }}>{item.path ? basename(item.path) : "Dosya seçilmedi"}</span><Button variant="danger" size="sm" type="button" onClick={() => onRemove(i)}>Sil</Button></div>)}
      <Button variant="ghost" size="sm" type="button" onClick={onAdd} style={{ alignSelf: "flex-start" }}>+ Dil ekle</Button>
    </div></div>
  );
}
