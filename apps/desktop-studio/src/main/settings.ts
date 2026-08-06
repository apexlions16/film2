// Ayarlar (TMDB/HF/GitHub token'lari) SADECE electron-store araciligiyla userData
// klasorunde saklanir — repo'ya asla yazilmaz, loglanmaz, TMDB/huggingface.co/api.github.com
// disinda hicbir yere gonderilmez.
import Store from "electron-store";
import type { SettingsPresence, StudioSettings } from "@shared/types";

interface StoreSchema {
  tmdbApiKey: string;
  hfToken: string;
  githubToken: string;
}

const store = new Store<StoreSchema>({
  name: "film2-studio-settings",
  defaults: {
    tmdbApiKey: "",
    hfToken: "",
    githubToken: "",
  },
});

export function getSettings(): StudioSettings {
  return {
    tmdbApiKey: store.get("tmdbApiKey", ""),
    hfToken: store.get("hfToken", ""),
    githubToken: store.get("githubToken", ""),
  };
}

export function getPresence(): SettingsPresence {
  const settings = getSettings();
  return {
    tmdbApiKey: settings.tmdbApiKey.trim().length > 0,
    hfToken: settings.hfToken.trim().length > 0,
    githubToken: settings.githubToken.trim().length > 0,
  };
}

export function saveSettings(values: Partial<StudioSettings>): SettingsPresence {
  if (values.tmdbApiKey !== undefined) store.set("tmdbApiKey", values.tmdbApiKey.trim());
  if (values.hfToken !== undefined) store.set("hfToken", values.hfToken.trim());
  if (values.githubToken !== undefined) store.set("githubToken", values.githubToken.trim());
  return getPresence();
}
