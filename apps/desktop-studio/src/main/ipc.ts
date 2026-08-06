// Butun IPC handler kayitlari burada toplanir. Her handler try/catch ile sarilir ve
// IpcResult zarfi doner — renderer hata durumlarini hep ayni sekilde (ok:false) gorur,
// sessizce yutulan bir hata olmaz.
import { ipcMain, type BrowserWindow } from "electron";
import type {
  IpcResult,
  PickFilesOptions,
  Title,
  UploadStartRequest,
  UploadStartResponse,
} from "@shared/types";
import { getPresence, getSettings, saveSettings } from "./settings";
import { fetchTitleByImdb } from "./tmdb";
import { getTitle, listTitles, saveTitle } from "./catalog";
import { pickFiles } from "./files";
import { uploadAndDispatch } from "./hf";

function ok<T>(data: T): IpcResult<T> {
  return { ok: true, data };
}

function fail(err: unknown): IpcResult<never> {
  const message = err instanceof Error ? err.message : String(err);
  return { ok: false, error: { message } };
}

export function registerIpcHandlers(win: BrowserWindow): void {
  ipcMain.handle("settings:getPresence", async () => {
    try {
      return ok(getPresence());
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("settings:getValues", async () => {
    try {
      return ok(getSettings());
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("settings:save", async (_event, values) => {
    try {
      return ok(saveSettings(values));
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("tmdb:fetchFromImdb", async (_event, imdbLinkOrId: string) => {
    try {
      return ok(await fetchTitleByImdb(imdbLinkOrId));
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("catalog:listTitles", async () => {
    try {
      return ok(await listTitles());
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("catalog:getTitle", async (_event, id: string) => {
    try {
      return ok(await getTitle(id));
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("catalog:saveTitle", async (_event, title: Title) => {
    try {
      return ok(await saveTitle(title));
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("files:pickFiles", async (_event, options: PickFilesOptions) => {
    try {
      return ok(await pickFiles(win, options));
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("upload:start", async (_event, request: UploadStartRequest) => {
    try {
      const result: UploadStartResponse = await uploadAndDispatch(
        request.target,
        request.selection,
        (progress) => {
          if (!win.isDestroyed()) {
            win.webContents.send("upload:progress", progress);
          }
        },
      );
      return ok(result);
    } catch (err) {
      return fail(err);
    }
  });
}
