import { useEffect, useState } from "react";
import type { HfAccount, StudioSettings } from "@shared/types";
import { Button } from "../components/Button";
import { TextField } from "../components/TextField";
import { ErrorBanner } from "../components/ErrorBanner";
import { errorMessage, unwrap } from "../lib/api";

interface SettingsPageProps {
  onSaved: () => Promise<unknown>;
}

const EMPTY: StudioSettings = { tmdbApiKey: "", githubToken: "" };

export function SettingsPage({ onSaved }: SettingsPageProps) {
  const [values, setValues] = useState<StudioSettings>(EMPTY);
  const [accounts, setAccounts] = useState<HfAccount[]>([]);
  const [newToken, setNewToken] = useState("");
  const [addingAccount, setAddingAccount] = useState(false);
  const [accountError, setAccountError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  async function loadAccounts() {
    const list = await unwrap(window.api.hfAccounts.list());
    setAccounts(list);
  }

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [data] = await Promise.all([unwrap(window.api.settings.getValues()), loadAccounts()]);
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

  async function handleAddAccount() {
    if (!newToken.trim()) return;
    setAddingAccount(true);
    setAccountError(null);
    try {
      await unwrap(window.api.hfAccounts.add(newToken));
      setNewToken("");
      await loadAccounts();
      await onSaved();
    } catch (err) {
      setAccountError(errorMessage(err));
    } finally {
      setAddingAccount(false);
    }
  }

  async function handleRemoveAccount(namespace: string) {
    setAccountError(null);
    try {
      await unwrap(window.api.hfAccounts.remove(namespace));
      await loadAccounts();
      await onSaved();
    } catch (err) {
      setAccountError(errorMessage(err));
    }
  }

  return (
    <div>
      <div className="page__header">
        <div>
          <h1 className="page__title">Ayarlar</h1>
          <p className="page__subtitle">
            Bu token'lar yalnizca bu bilgisayarda, yerel olarak (electron-store) saklanir; repoya
            yazilmaz, loglanmaz ve TMDB/huggingface.co/api.github.com disinda hicbir yere
            gonderilmez.
          </p>
        </div>
      </div>

      {error && <ErrorBanner message="Ayarlar kaydedilemedi" detail={error} />}

      <div className="panel" style={{ maxWidth: 640, marginBottom: 20 }}>
        <h2 style={{ fontSize: 15, fontWeight: 600, margin: "0 0 4px" }}>Hugging Face hesaplari</h2>
        <p className="field__hint" style={{ marginBottom: 12 }}>
          Birden fazla hesap ekleyebilirsiniz. Aktif hesabin depolama kotasi dolarsa yukleme
          otomatik olarak listedeki bir sonraki hesaba gecer — depolama tukenmeden yeni bir
          Hugging Face hesabi acip token'ini buraya eklemeniz yeterli, baska hicbir sey yapmaniza
          gerek kalmaz.
        </p>

        {loading ? (
          <div className="skeleton" style={{ height: 48 }} />
        ) : (
          <div className="stack" style={{ gap: 8, marginBottom: 12 }}>
            {accounts.length === 0 && (
              <p className="text-dim" style={{ fontSize: 13 }}>
                Henuz hesap eklenmedi. Asagidan bir Hugging Face write token'i ekleyin.
              </p>
            )}
            {accounts.map((account) => (
              <div
                key={account.namespace}
                className="row"
                style={{ justifyContent: "space-between", padding: "8px 12px", background: "var(--surface-2)", borderRadius: 8 }}
              >
                <div>
                  <div style={{ fontWeight: 600, fontSize: 13.5 }}>{account.namespace}</div>
                  {account.fullname && (
                    <div className="text-dim" style={{ fontSize: 12 }}>
                      {account.fullname}
                    </div>
                  )}
                </div>
                <Button variant="danger" size="sm" onClick={() => handleRemoveAccount(account.namespace)}>
                  Kaldir
                </Button>
              </div>
            ))}
          </div>
        )}

        {accountError && <ErrorBanner message="Hesap eklenemedi" detail={accountError} />}

        <div className="row" style={{ gap: 8, alignItems: "flex-end" }}>
          <div style={{ flex: 1 }}>
            <TextField
              label="Yeni Hugging Face write token'i"
              type="password"
              autoComplete="off"
              spellCheck={false}
              placeholder="hf_..."
              value={newToken}
              onChange={(e) => setNewToken(e.target.value)}
            />
          </div>
          <Button variant="primary" loading={addingAccount} onClick={handleAddAccount} disabled={!newToken.trim()}>
            Hesap ekle
          </Button>
        </div>
        <p className="field__hint" style={{ marginTop: 4 }}>
          Token yapistirilinca hangi hesaba ait oldugu otomatik tespit edilir (whoami) — kullanici
          adini elle yazmaniza gerek yok. "Write" yetkili bir token gerekli &rarr;{" "}
          <a href="https://huggingface.co/settings/tokens" target="_blank" rel="noreferrer">
            huggingface.co/settings/tokens
          </a>
        </p>
      </div>

      <div className="panel" style={{ maxWidth: 640 }}>
        {loading ? (
          <div className="stack">
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
