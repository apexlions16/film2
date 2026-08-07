import { useCallback, useEffect, useMemo, useState } from "react";
import type { HomeConfig, HomeShelf, Title } from "@shared/types";
import { Button } from "../components/Button";
import { ErrorBanner } from "../components/ErrorBanner";
import { errorMessage, unwrap } from "../lib/api";

function slug(value: string): string {
  return value
    .toLocaleLowerCase("tr-TR")
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48) || `shelf-${Date.now()}`;
}

function lines(value?: string[]): string {
  return (value ?? []).join("\n");
}

function parseLines(value: string): string[] {
  return [...new Set(value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))];
}

export function EditorialPage({ gated, onOpenSettings }: { gated: boolean; onOpenSettings: () => void }) {
  const [titles, setTitles] = useState<Title[]>([]);
  const [config, setConfig] = useState<HomeConfig | null>(null);
  const [selectedTitleId, setSelectedTitleId] = useState<string>("");
  const [posterPool, setPosterPool] = useState("");
  const [backdropPool, setBackdropPool] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [artSaving, setArtSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (gated) return;
    setLoading(true);
    setError(null);
    try {
      const [loadedTitles, loadedHome] = await Promise.all([
        unwrap(window.api.catalog.listTitles()),
        unwrap(window.api.catalog.getHome()),
      ]);
      setTitles(loadedTitles);
      setConfig(loadedHome);
      const first = selectedTitleId || loadedTitles[0]?.id || "";
      setSelectedTitleId(first);
      const title = loadedTitles.find((item) => item.id === first);
      setPosterPool(lines(title?.posterUrls));
      setBackdropPool(lines(title?.backdropUrls));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [gated, selectedTitleId]);

  useEffect(() => { void load(); }, [gated]);

  const selectedTitle = useMemo(() => titles.find((title) => title.id === selectedTitleId) ?? null, [titles, selectedTitleId]);

  if (gated) {
    return (
      <div className="panel">
        <h2 className="panel__title">Editoryal yönetim için ayarlar gerekli</h2>
        <Button variant="primary" onClick={onOpenSettings}>Ayarları Aç</Button>
      </div>
    );
  }

  function updateShelf(index: number, patch: Partial<HomeShelf>) {
    if (!config) return;
    const shelves = [...config.shelves];
    shelves[index] = { ...shelves[index], ...patch };
    setConfig({ ...config, shelves });
  }

  function toggleTitle(collection: string[], titleId: string): string[] {
    return collection.includes(titleId) ? collection.filter((id) => id !== titleId) : [...collection, titleId];
  }

  async function saveHome() {
    if (!config) return;
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      await unwrap(window.api.catalog.saveHome(config));
      setMessage("Ana sayfa rafları kaydedildi. Player birkaç saniye içinde yenilenecek.");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  async function saveArtwork() {
    if (!selectedTitle) return;
    setArtSaving(true);
    setError(null);
    try {
      const updated: Title = {
        ...selectedTitle,
        posterUrls: parseLines(posterPool),
        backdropUrls: parseLines(backdropPool),
      };
      await unwrap(window.api.catalog.saveTitle(updated));
      setTitles((current) => current.map((title) => title.id === updated.id ? updated : title));
      setMessage(`${updated.title} görsel havuzu kaydedildi.`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setArtSaving(false);
    }
  }

  if (!config) {
    return <div className="panel">{loading ? "Editoryal katalog yükleniyor…" : "Editoryal yapı yüklenemedi."}</div>;
  }

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Editoryal Ana Sayfa</h1>
          <p className="page__subtitle">Player'daki hero döngüsünü, popüler rafları ve görsel havuzlarını buradan yönet.</p>
        </div>
        <div className="page__actions">
          <Button variant="ghost" onClick={load} loading={loading}>Yenile</Button>
          <Button variant="primary" onClick={saveHome} loading={saving}>Ana Sayfayı Kaydet</Button>
        </div>
      </div>

      {error && <ErrorBanner message="İşlem başarısız" detail={error} />}
      {message && <div className="panel" style={{ borderColor: "rgba(99,199,190,.45)", color: "var(--text-dim)" }}>{message}</div>}

      <div className="panel">
        <div className="panel__title">Hero Döngüsü</div>
        <p className="panel__desc">Birden fazla içerik seçersen Player her yenilemede/oturumda bunlardan farklı birini öne çıkarır.</p>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(210px,1fr))", gap: 8, marginTop: 12 }}>
          {titles.map((title) => (
            <label key={title.id} className="radio-option" style={{ padding: 10 }}>
              <input
                type="checkbox"
                checked={config.heroTitleIds.includes(title.id)}
                onChange={() => setConfig({ ...config, heroTitleIds: toggleTitle(config.heroTitleIds, title.id) })}
              />
              <span className="radio-option__text"><span className="radio-option__title">{title.title}</span></span>
            </label>
          ))}
        </div>
      </div>

      <div className="stack">
        {config.shelves.map((shelf, index) => (
          <div className="panel" key={shelf.id}>
            <div className="row row--between" style={{ alignItems: "flex-start" }}>
              <div style={{ flex: 1 }}>
                <input className="input" value={shelf.title} onChange={(e) => updateShelf(index, { title: e.target.value, id: shelf.id || slug(e.target.value) })} style={{ fontWeight: 700, fontSize: 16 }} />
                <div className="row" style={{ marginTop: 10, gap: 16 }}>
                  <label className="row" style={{ gap: 6 }}><input type="checkbox" checked={shelf.enabled} onChange={(e) => updateShelf(index, { enabled: e.target.checked })} /> Aktif</label>
                  <label className="row" style={{ gap: 6 }}><input type="checkbox" checked={shelf.shuffle} onChange={(e) => updateShelf(index, { shuffle: e.target.checked })} /> Karıştır</label>
                  <label className="row" style={{ gap: 6 }}>Maks. <input className="input" type="number" min={1} max={100} value={shelf.maxItems} onChange={(e) => updateShelf(index, { maxItems: Math.max(1, Number(e.target.value) || 1) })} style={{ width: 74 }} /></label>
                </div>
              </div>
              <Button variant="danger" size="sm" onClick={() => setConfig({ ...config, shelves: config.shelves.filter((_, i) => i !== index) })}>Rafı Sil</Button>
            </div>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(190px,1fr))", gap: 7, marginTop: 14 }}>
              {titles.map((title) => (
                <label key={title.id} className="radio-option" style={{ padding: 9 }}>
                  <input type="checkbox" checked={shelf.titleIds.includes(title.id)} onChange={() => updateShelf(index, { titleIds: toggleTitle(shelf.titleIds, title.id) })} />
                  <span className="radio-option__text"><span className="radio-option__title">{title.title}</span></span>
                </label>
              ))}
            </div>
          </div>
        ))}
      </div>

      <Button
        variant="ghost"
        onClick={() => {
          const name = "Yeni Raf";
          setConfig({
            ...config,
            shelves: [...config.shelves, { id: `${slug(name)}-${Date.now()}`, title: name, titleIds: [], enabled: true, shuffle: false, maxItems: 20 }],
          });
        }}
      >+ Yeni Raf</Button>

      <div className="panel" style={{ marginTop: 22 }}>
        <div className="panel__title">Film / Dizi Görsel Havuzu</div>
        <p className="panel__desc">Birden fazla poster ve backdrop URL'si ekle. Player yenilendiğinde havuzdan farklı görsel seçer.</p>
        <select
          className="input"
          value={selectedTitleId}
          onChange={(e) => {
            const id = e.target.value;
            setSelectedTitleId(id);
            const title = titles.find((item) => item.id === id);
            setPosterPool(lines(title?.posterUrls));
            setBackdropPool(lines(title?.backdropUrls));
          }}
          style={{ maxWidth: 420, marginBottom: 14 }}
        >
          {titles.map((title) => <option value={title.id} key={title.id}>{title.title}</option>)}
        </select>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
          <div className="field"><label className="field__label">Alternatif poster URL'leri • satır başına bir</label><textarea className="input" value={posterPool} onChange={(e) => setPosterPool(e.target.value)} rows={9} /></div>
          <div className="field"><label className="field__label">Alternatif backdrop URL'leri • satır başına bir</label><textarea className="input" value={backdropPool} onChange={(e) => setBackdropPool(e.target.value)} rows={9} /></div>
        </div>
        <Button variant="primary" onClick={saveArtwork} loading={artSaving}>Görsel Havuzunu Kaydet</Button>
      </div>
    </div>
  );
}
