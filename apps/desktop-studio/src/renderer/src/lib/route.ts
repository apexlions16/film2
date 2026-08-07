export type Route =
  | { name: "catalog" }
  | { name: "add" }
  | { name: "editorial" }
  | { name: "settings" }
  | {
      name: "upload";
      titleId: string;
      kind: "movie" | "episode";
      seasonNumber?: number;
      episodeNumber?: number;
    };

export interface UploadRouteTarget {
  titleId: string;
  kind: "movie" | "episode";
  seasonNumber?: number;
  episodeNumber?: number;
}
