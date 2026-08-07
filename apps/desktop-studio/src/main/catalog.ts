import { listTitles as listTitlesRemote } from "@film2/catalog-client";
import type { HomeConfig, Title } from "@shared/types";
import { getJsonFile, putJsonFile } from "./github";
import { getSettings } from "./settings";

const HOME_PATH = "catalog/home.json";
const VERSION_PATH = "catalog/version.json";

function titlePath(id: string): string {
  return `catalog/titles/${id}.json`;
}

function requireGithubToken(): string {
  const { githubToken } = getSettings();
  if (!githubToken) throw new Error("GitHub token ayarlanmamis. Once Ayarlar ekranindan girin.");
  return githubToken;
}

export async function listTitles(): Promise<Title[]> {
  const titles = (await listTitlesRemote()) as Title[];
  return titles.sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
}

export async function getTitle(id: string): Promise<Title> {
  const githubToken = requireGithubToken();
  const file = await getJsonFile<Title>(titlePath(id), githubToken);
  if (!file) throw new Error(`catalog/titles/${id}.json bulunamadi.`);
  return file.data;
}

export async function touchCatalogVersion(): Promise<void> {
  const githubToken = requireGithubToken();
  const existing = await getJsonFile<{ revision: string }>(VERSION_PATH, githubToken);
  await putJsonFile(
    VERSION_PATH,
    { revision: new Date().toISOString() },
    "chore(catalog): player revision (windows-studio)",
    githubToken,
    existing?.sha,
  );
}

export async function saveTitle(title: Title, touchVersion = true): Promise<{ sha: string }> {
  const githubToken = requireGithubToken();
  const path = titlePath(title.id);
  const existing = await getJsonFile<Title>(path, githubToken);
  const now = new Date().toISOString();
  const payload: Title = {
    ...title,
    createdAt: title.createdAt || now,
    updatedAt: now,
  };
  const message = existing
    ? `chore(catalog): ${title.id} guncellendi (windows-studio)`
    : `chore(catalog): ${title.id} eklendi (windows-studio)`;
  const result = await putJsonFile(path, payload, message, githubToken, existing?.sha);
  if (touchVersion) await touchCatalogVersion();
  return result;
}

export async function getHomeConfig(): Promise<HomeConfig> {
  const githubToken = requireGithubToken();
  const file = await getJsonFile<HomeConfig>(HOME_PATH, githubToken);
  return file?.data ?? {
    heroTitleIds: [],
    shelves: [],
    updatedAt: new Date(0).toISOString(),
  };
}

export async function saveHomeConfig(config: HomeConfig): Promise<void> {
  const githubToken = requireGithubToken();
  const existing = await getJsonFile<HomeConfig>(HOME_PATH, githubToken);
  await putJsonFile(
    HOME_PATH,
    { ...config, updatedAt: new Date().toISOString() },
    "chore(catalog): editoryal ana sayfa guncellendi (windows-studio)",
    githubToken,
    existing?.sha,
  );
  await touchCatalogVersion();
}
