// Player ve Studio uygulamalarinin GitHub'daki katalogu okumasi icin ortak istemci.
// Yerel indirme/kurulum yok — her seferinde GitHub'dan taze cekilir.
// NOT: GitHub Contents API kimliksiz istemciler icin saatte 60 istekle sinirlidir.
// Kisisel kullanim icin yeterlidir; sorun olursa GITHUB_TOKEN ile Authorization header eklenebilir.

const DEFAULT_REPO = "apexlions16/film2";
const DEFAULT_BRANCH = "main";

function contentsUrl(repo, path) {
  return `https://api.github.com/repos/${repo}/contents/${path}`;
}

/**
 * catalog/titles/ altindaki tum title id'lerini listeler (ornek _ dosyalari haric).
 * @param {{ repo?: string, branch?: string }} [options]
 * @returns {Promise<string[]>}
 */
export async function listTitleIds(options = {}) {
  const repo = options.repo ?? DEFAULT_REPO;
  const branch = options.branch ?? DEFAULT_BRANCH;
  const res = await fetch(`${contentsUrl(repo, "catalog/titles")}?ref=${branch}`);
  if (!res.ok) throw new Error(`Katalog listelenemedi: ${res.status}`);
  const entries = await res.json();
  return entries
    .filter((e) => e.type === "file" && e.name.endsWith(".json") && !e.name.startsWith("_"))
    .map((e) => e.name.replace(/\.json$/, ""));
}

/**
 * Tek bir title'in tam JSON'unu ceker.
 * @param {string} id
 * @param {{ repo?: string, branch?: string }} [options]
 */
export async function getTitle(id, options = {}) {
  const repo = options.repo ?? DEFAULT_REPO;
  const branch = options.branch ?? DEFAULT_BRANCH;
  const res = await fetch(`https://raw.githubusercontent.com/${repo}/${branch}/catalog/titles/${id}.json`);
  if (!res.ok) throw new Error(`Title bulunamadi: ${id} (${res.status})`);
  return res.json();
}

/**
 * Tum katalogu (tum title'lari) ceker. Kucuk kisisel katalog beklendigi icin
 * (birkac dizi/film) N+1 istek burada sorun degil.
 * @param {{ repo?: string, branch?: string }} [options]
 */
export async function listTitles(options = {}) {
  const ids = await listTitleIds(options);
  return Promise.all(ids.map((id) => getTitle(id, options)));
}
