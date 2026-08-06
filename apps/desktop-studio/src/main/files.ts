// Native dosya secici — renderer'da nodeIntegration kapali oldugu icin dosya secimi
// IPC uzerinden main sureçte dialog.showOpenDialog ile yapilir.
import { dialog, type BrowserWindow } from "electron";
import type { PickFilesOptions } from "@shared/types";

const MEDIA_EXTENSIONS = [
  "mkv",
  "mp4",
  "mov",
  "avi",
  "webm",
  "m4v",
  "mp3",
  "aac",
  "flac",
  "wav",
  "m4a",
  "opus",
  "ogg",
  "srt",
  "vtt",
  "ass",
  "ssa",
];

export async function pickFiles(win: BrowserWindow, options: PickFilesOptions): Promise<string[]> {
  const result = await dialog.showOpenDialog(win, {
    title: options.label,
    properties: options.multi ? ["openFile", "multiSelections"] : ["openFile"],
    filters: [
      { name: "Medya dosyalari", extensions: MEDIA_EXTENSIONS },
      { name: "Tum dosyalar", extensions: ["*"] },
    ],
  });
  if (result.canceled) return [];
  return result.filePaths;
}
