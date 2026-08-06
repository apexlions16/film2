const IMAGE_BASE = "https://image.tmdb.org/t/p";

export function posterUrl(path, size = "w780") {
  return path ? `${IMAGE_BASE}/${size}${path}` : "";
}

export function backdropUrl(path, size = "w1280") {
  return path ? `${IMAGE_BASE}/${size}${path}` : "";
}

export function profileUrl(path, size = "w300") {
  return path ? `${IMAGE_BASE}/${size}${path}` : "";
}

export function stillUrl(path, size = "w500") {
  return path ? `${IMAGE_BASE}/${size}${path}` : "";
}
