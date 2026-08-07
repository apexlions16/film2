import { ipcMain, type BrowserWindow } from "electron";
import type {
  HomeConfig,
  IpcResult,
  PickFilesOptions,
  QualityGenerateRequest,
  Title,
  TrailerUploadRequest,
  UploadStartRequest,
  UploadStartResponse,
} from "@shared/types";
import { addHfAccount, getPresence, getSettings, listHfAccounts, removeHfAccount, saveSettings } from "./settings";
import { fetchTitleByImdb } from "./tmdb";
import { getHomeConfig, getTitle, listTitles, saveHomeConfig, saveTitle } from "./catalog";
import { pickFiles } from "./files";
import { uploadAndDispatch } from "./hf";
import { generateQualities, uploadTrailer } from "./media-admin";

function ok<T>(data: T): IpcResult<T> { return { ok: true, data }; }
function fail(err: unknown): IpcResult<never> {
  const message = err instanceof Error ? err.message : String(err);
  return { ok: false, error: { message } };
}

export function registerIpcHandlers(win: BrowserWindow): void {
  ipcMain.handle("settings:getPresence", async () => { try { return ok(getPresence()); } catch (err) { return fail(err); } });
  ipcMain.handle("settings:getValues", async () => { try { return ok(getSettings()); } catch (err) { return fail(err); } });
  ipcMain.handle("settings:save", async (_event, values) => { try { return ok(saveSettings(values)); } catch (err) { return fail(err); } });

  ipcMain.handle("hfAccounts:list", async () => { try { return ok(listHfAccounts()); } catch (err) { return fail(err); } });
  ipcMain.handle("hfAccounts:add", async (_event, token: string) => { try { return ok(await addHfAccount(token)); } catch (err) { return fail(err); } });
  ipcMain.handle("hfAccounts:remove", async (_event, namespace: string) => { try { removeHfAccount(namespace); return ok(undefined); } catch (err) { return fail(err); } });

  ipcMain.handle("tmdb:fetchFromImdb", async (_event, imdbLinkOrId: string) => { try { return ok(await fetchTitleByImdb(imdbLinkOrId)); } catch (err) { return fail(err); } });

  ipcMain.handle("catalog:listTitles", async () => { try { return ok(await listTitles()); } catch (err) { return fail(err); } });
  ipcMain.handle("catalog:getTitle", async (_event, id: string) => { try { return ok(await getTitle(id)); } catch (err) { return fail(err); } });
  ipcMain.handle("catalog:saveTitle", async (_event, title: Title) => { try { return ok(await saveTitle(title)); } catch (err) { return fail(err); } });
  ipcMain.handle("catalog:getHome", async () => { try { return ok(await getHomeConfig()); } catch (err) { return fail(err); } });
  ipcMain.handle("catalog:saveHome", async (_event, config: HomeConfig) => { try { await saveHomeConfig(config); return ok(undefined); } catch (err) { return fail(err); } });

  ipcMain.handle("files:pickFiles", async (_event, options: PickFilesOptions) => { try { return ok(await pickFiles(win, options)); } catch (err) { return fail(err); } });

  ipcMain.handle("upload:start", async (_event, request: UploadStartRequest) => {
    try {
      const result: UploadStartResponse = await uploadAndDispatch(
        request.target,
        request.selection,
        (progress) => {
          if (!win.isDestroyed()) win.webContents.send("upload:progress", progress);
        },
      );
      return ok(result);
    } catch (err) {
      return fail(err);
    }
  });

  ipcMain.handle("media:uploadTrailer", async (_event, request: TrailerUploadRequest) => {
    try { return ok(await uploadTrailer(request)); } catch (err) { return fail(err); }
  });
  ipcMain.handle("media:generateQualities", async (_event, request: QualityGenerateRequest) => {
    try { await generateQualities(request); return ok(undefined); } catch (err) { return fail(err); }
  });
}
