// contextIsolation acik oldugu icin renderer'a SADECE bu dar `window.api` yuzeyi
// contextBridge ile expose edilir — renderer'in dogrudan Node/ipcRenderer erisimi yoktur.
import { contextBridge, ipcRenderer, type IpcRendererEvent } from "electron";
import type { StudioApi, UploadProgressEvent } from "../shared/types";

const api: StudioApi = {
  settings: {
    getPresence: () => ipcRenderer.invoke("settings:getPresence"),
    getValues: () => ipcRenderer.invoke("settings:getValues"),
    save: (values) => ipcRenderer.invoke("settings:save", values),
  },
  hfAccounts: {
    list: () => ipcRenderer.invoke("hfAccounts:list"),
    add: (token) => ipcRenderer.invoke("hfAccounts:add", token),
    remove: (namespace) => ipcRenderer.invoke("hfAccounts:remove", namespace),
  },
  tmdb: {
    fetchFromImdb: (imdbLinkOrId) => ipcRenderer.invoke("tmdb:fetchFromImdb", imdbLinkOrId),
  },
  catalog: {
    listTitles: () => ipcRenderer.invoke("catalog:listTitles"),
    getTitle: (id) => ipcRenderer.invoke("catalog:getTitle", id),
    saveTitle: (title) => ipcRenderer.invoke("catalog:saveTitle", title),
  },
  files: {
    pickFiles: (options) => ipcRenderer.invoke("files:pickFiles", options),
  },
  upload: {
    start: (request) => ipcRenderer.invoke("upload:start", request),
    onProgress: (callback) => {
      const listener = (_event: IpcRendererEvent, payload: UploadProgressEvent): void => callback(payload);
      ipcRenderer.on("upload:progress", listener);
      return () => ipcRenderer.removeListener("upload:progress", listener);
    },
  },
};

contextBridge.exposeInMainWorld("api", api);
