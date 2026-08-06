import { useEffect, useState } from "react";
import type { StudioSettings } from "@shared/types";
import { Button } from "../components/Button";
import { TextField } from "../components/TextField";
import { ErrorBanner } from "../components/ErrorBanner";
import { errorMessage, unwrap } from "../lib/api";

interface SettingsPageProps {
  onSaved: () => Promise<unknown>;
}

const EMPTY: StudioSettings = { tmdbApiKey: "", hfToken: "", githubToken: "" };

export function SettingsPage({ onSaved }: SettingsPageProps) {
  const [values, setValues] = useState<StudioSettings>(EMPTY);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await unwrap(window.api.settings.getValues());
        if (!cancelled) setValues(data);
      } catch (err) {
        if (!cancelled) setError(errorMessage(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      await unwrap(window.api.settings.save(values));
      await onSaved();
      setSavedAt(Date.now());
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Ayarlar</h1>
          <p className="page__subtitle">
            Bu uc token yalnizca bu bilgisayarda, yerel olarak (electron-store) saklanir; repoya
            yazilmaz, loglanmaz ve TMDB/huggingface.co/api.github.com disinda hicbir yere
            gonderilmez. Studio'nun geri kalanini kullanabilmek icin ucu de gerekli.
          </p>
        </div>
      </div>

      {error && <ErrorBanner message="Ayarlar kaydedilemedi" detail={error} />}

      <div className="panel" style={{ maxWidth: 640 }}>
        {loading ? (
          <div className="stack">
            <div className="skeleton" style={{ height: 64 }} />
            <div className="skeleton" style={{ height: 64 }} />
            <div className="skeleton" style={{ height: 64 }} />
          </div>
        ) : (
          <div className="stack">
            <div>
              <TextField
                label="TMDB API Anahtari"
                type="password"
                autoComplete="off"
                spellCheck={false}
                placeholder="ör. 3b1e2c..."
                value={values.tmdbApiKey}
                onChange={(e) => setValues((v) => ({ ...v, tmdbApiKey: e.target.value }))}
              />
              <p className="field__hint" style={{ marginTop: 4 }}>
                themoviedb.org &rarr; Ayarlar &rarr; API &rarr;{" "}
                <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noreferrer">
                  themoviedb.org/settings/api
                </a>
              </p>
            </div>

            <div>
              <TextField
                label="Hugging Face Yazma Token'i"
                type="password"
                autoComplete="off"
                spellCheck={false}
                placeholder="hf_..."
                value={values.hfToken}
                onChange={(e) => setValues((v) => ({ ...v, hfToken: e.target.value }))}
              />
              <p className="field__hint" style={{ marginTop: 4 }}>
                "Write" yetkili bir token gerekli &rarr;{" "}
                <a href="https://huggingface.co/settings/tokens" target="_blank" rel="noreferrer">
                  huggingface.co/settings/tokens
                </a>
              </p>
            </div>

            <div>
              <TextField
                label="GitHub Personal Access Token"
                type="password"
                autoComplete="off"
                spellCheck={false}
                placeholder="ghp_... veya github_pat_..."
                value={values.githubToken}
                onChange={(e) => setValues((v) => ({ ...v, githubToken: e.target.value }))}
              />
              <p className="field__hint" style={{ marginTop: 4 }}>
                "repo" scope'lu bir PAT gerekli (apexlions16/film2'ye yazma yetkisi) &rarr;{" "}
                <a href="https://github.com/settings/tokens" target="_blank" rel="noreferrer">
                  github.com/settings/tokens
                </a>
              </p>
            </div>

            <div className="row" style={{ marginTop: 6 }}>
              <Button variant="primary" loading={saving} onClick={handleSave}>
                Kaydet
              </Button>
              {savedAt && !saving && !error && (
                <span className="text-dim" style={{ fontSize: 12.5 }}>
                  Kaydedildi.
                </span>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
