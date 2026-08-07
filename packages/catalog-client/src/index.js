// Player ve Studio uygulamalarinin katalogu okudugu ortak istemci.
// ONEMLI: Player tarafinda GitHub REST Contents API KULLANILMAZ.
// Public/anonymous Contents API 60 istek/saat/IP sinirina sahip oldugu icin katalog,
// GitHub Actions tarafindan uretilen tek bir raw snapshot'tan okunur.

const DEFAULT_REPO = "apexlions16/film2";
const DEFAULT_BRANCH = "main";

function rawUrl(repo, branch, path) {
  return `https://raw.githubusercontent.com/${repo}/${branch}/${path}`;
}

export async function getCatalogSnapshot(options = {}) {
  const repo = options.repo ?? DEFAULT_REPO;
  const branch = options.branch ?? DEFAULT_BRANCH;
  const nonce = Date.now();
  const res = await fetch(`${rawUrl(repo, branch, "catalog/index.json")}?v=${nonce}`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Katalog snapshot alinamadi: ${res.status}`);
  const snapshot = await res.json();
  if (!snapshot || !Array.isArray(snapshot.titles)) {
    throw new Error("catalog/index.json gecersiz: titles dizisi yok");
  }
  return snapshot;
}

export async function listTitleIds(options = {}) {
  const snapshot = await getCatalogSnapshot(options);
  return snapshot.titles.map((title) => title.id).filter(Boolean);
}

export async function getTitle(id, options = {}) {
  const repo = options.repo ?? DEFAULT_REPO;
  const branch = options.branch ?? DEFAULT_BRANCH;
  const res = await fetch(`${rawUrl(repo, branch, `catalog/titles/${id}.json`)}?v=${Date.now()}`, {
    cache: "no-store",
  });
  if (!res.ok) throw new Error(`Title bulunamadi: ${id} (${res.status})`);
  return res.json();
}

export async function listTitles(options = {}) {
  const snapshot = await getCatalogSnapshot(options);
  return snapshot.titles;
}
