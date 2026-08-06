// Ayarlar (TMDB/HF/GitHub token'lari) SADECE electron-store araciligiyla userData
// klasorunde saklanir — repo'ya asla yazilmaz, loglanmaz, TMDB/huggingface.co/api.github.com
// disinda hicbir yere gonderilmez.
import Store from "electron-store";
import { resolveHfAccount } from "@film2/hf-storage";
import type { HfAccount, SettingsPresence, StudioSettings } from "@shared/types";

interface StoredHfAccount extends HfAccount {
  token: string;
}

interface StoreSchema {
  tmdbApiKey: string;
  githubToken: string;
  /** Birden fazla Hugging Face hesabi — biri dolunca digerine otomatik gecmek icin. */
  hfAccounts: StoredHfAccount[];
}

const store = new Store<StoreSchema>({
  name: "film2-studio-settings",
  defaults: {
    tmdbApiKey: "",
    githubToken: "",
    hfAccounts: [],
  },
});

export function getSettings(): StudioSettings {
  return {
    tmdbApiKey: store.get("tmdbApiKey", ""),
    githubToken: store.get("githubToken", ""),
  };
}

export function getPresence(): SettingsPresence {
  const settings = getSettings();
  return {
    tmdbApiKey: settings.tmdbApiKey.trim().length > 0,
    githubToken: settings.githubToken.trim().length > 0,
    hfAccountsCount: store.get("hfAccounts", []).length,
  };
}

export function saveSettings(values: Partial<StudioSettings>): SettingsPresence {
  if (values.tmdbApiKey !== undefined) store.set("tmdbApiKey", values.tmdbApiKey.trim());
  if (values.githubToken !== undefined) store.set("githubToken", values.githubToken.trim());
  return getPresence();
}

/** hf-storage'in ensureShardCapacity/uploadFileWithFailover'inin bekledigi sekil — token dahil.
 * Bu fonksiyon SADECE main process icinde (upload akisinda) cagrilir, renderer'a hic gitmez. */
export function getHfAccountsWithTokens(): { namespace: string; token: string }[] {
  return store.get("hfAccounts", []).map((a) => ({ namespace: a.namespace, token: a.token }));
}

/** Renderer'a gosterilecek liste — token YOK. */
export function listHfAccounts(): HfAccount[] {
  return store.get("hfAccounts", []).map((a) => ({ namespace: a.namespace, fullname: a.fullname }));
}

/**
 * Yeni bir Hugging Face hesabi ekler. Token'i whoami ile dogrular (yanlis/gecersiz
 * token'lar boylece hemen fark edilir), ayni namespace zaten kayitliysa token'ini gunceller.
 */
export async function addHfAccount(token: string): Promise<HfAccount> {
  const trimmed = token.trim();
  if (!trimmed) throw new Error("Hugging Face token bos olamaz.");

  const { namespace, fullname } = await resolveHfAccount(trimmed);
  const accounts = store.get("hfAccounts", []);
  const existingIndex = accounts.findIndex((a) => a.namespace === namespace);
  const entry: StoredHfAccount = { namespace, fullname, token: trimmed };

  if (existingIndex >= 0) {
    accounts[existingIndex] = entry;
  } else {
    accounts.push(entry);
  }
  store.set("hfAccounts", accounts);

  return { namespace, fullname };
}

export function removeHfAccount(namespace: string): void {
  const accounts = store.get("hfAccounts", []).filter((a) => a.namespace !== namespace);
  store.set("hfAccounts", accounts);
}
