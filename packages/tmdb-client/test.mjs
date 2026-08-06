// Manuel dogrulama: TMDB_API_KEY ortam degiskeni ile calistirilir.
// Kullanim: TMDB_API_KEY=xxxx IMDB_LINK=https://www.imdb.com/title/tt0111161/ node test.mjs
import { fetchTitleFromImdbLink } from "./src/index.js";

const apiKey = process.env.TMDB_API_KEY;
const imdbLink = process.env.IMDB_LINK ?? "https://www.imdb.com/title/tt0111161/";

if (!apiKey) {
  console.error("TMDB_API_KEY ortam degiskeni gerekli.");
  process.exit(1);
}

const title = await fetchTitleFromImdbLink(imdbLink, apiKey);
if (!title) {
  console.log("TMDB'de bulunamadi -> manualEntry akisina dusmeli.");
} else {
  console.log(JSON.stringify(title, null, 2));
}
