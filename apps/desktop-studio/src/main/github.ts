// GitHub Contents API + repository_dispatch helper. Local git clone YOK — Studio
// Electron uygulamasi katalogu dogrudan GitHub REST API uzerinden okuyup yazar.
// Kullanilan token: Ayarlar ekraninda girilen kullanici PAT'i (repo scope).

const REPO = "apexlions16/film2";
const BRANCH = "main";
const API_BASE = "https://api.github.com";

export class GithubApiError extends Error {
  status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "GithubApiError";
    this.status = status;
  }
}

function authHeaders(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

async function safeText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    return "";
  }
}

interface ContentsFile {
  contentBase64: string;
  sha: string;
}

async function getFileRaw(path: string, token: string): Promise<ContentsFile | null> {
  const url = `${API_BASE}/repos/${REPO}/contents/${path}?ref=${BRANCH}`;
  const res = await fetch(url, { headers: authHeaders(token) });
  if (res.status === 404) return null;
  if (!res.ok) {
    throw new GithubApiError(`GitHub dosya okuma hatasi (${res.status}): ${await safeText(res)}`, res.status);
  }
  const json = (await res.json()) as { content?: string; sha: string; type: string };
  if (json.type !== "file" || json.content === undefined) {
    throw new GithubApiError(`${path} bir dosya degil (klasor olabilir).`);
  }
  return { contentBase64: json.content, sha: json.sha };
}

/**
 * catalog/ altindaki bir JSON dosyasini okur. Dosya yoksa null doner (404 = ilk kayit).
 */
export async function getJsonFile<T>(path: string, token: string): Promise<{ data: T; sha: string } | null> {
  const file = await getFileRaw(path, token);
  if (!file) return null;
  const decoded = Buffer.from(file.contentBase64, "base64").toString("utf-8");
  try {
    return { data: JSON.parse(decoded) as T, sha: file.sha };
  } catch (err) {
    throw new GithubApiError(`${path} gecerli JSON degil: ${(err as Error).message}`);
  }
}

/**
 * catalog/ altindaki bir JSON dosyasini olusturur ya da gunceller. `sha` verilmezse
 * yeni dosya olarak commit edilir (mevcut bir dosyayi sha vermeden guncellemeye
 * calismak GitHub API'de 422 hatasi verir).
 */
export async function putJsonFile(
  path: string,
  data: unknown,
  message: string,
  token: string,
  sha?: string,
): Promise<{ sha: string }> {
  const content = Buffer.from(`${JSON.stringify(data, null, 2)}\n`, "utf-8").toString("base64");
  const url = `${API_BASE}/repos/${REPO}/contents/${path}`;
  const res = await fetch(url, {
    method: "PUT",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({
      message,
      content,
      branch: BRANCH,
      ...(sha ? { sha } : {}),
    }),
  });
  if (!res.ok) {
    throw new GithubApiError(`GitHub dosya yazma hatasi (${res.status}): ${await safeText(res)}`, res.status);
  }
  const json = (await res.json()) as { content: { sha: string } };
  return { sha: json.content.sha };
}

/**
 * repository_dispatch event'i tetikler (orn. "package-media"). package-media.mjs'in
 * bekledigi client_payload sekliyle BIREBIR eslesmeli — bkz. .github/scripts/package-media.mjs.
 */
export async function dispatchRepositoryEvent(
  eventType: string,
  clientPayload: Record<string, unknown>,
  token: string,
): Promise<void> {
  const url = `${API_BASE}/repos/${REPO}/dispatches`;
  const res = await fetch(url, {
    method: "POST",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify({ event_type: eventType, client_payload: clientPayload }),
  });
  if (!res.ok) {
    throw new GithubApiError(`GitHub repository_dispatch hatasi (${res.status}): ${await safeText(res)}`, res.status);
  }
}
