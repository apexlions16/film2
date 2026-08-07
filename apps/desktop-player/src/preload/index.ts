import { contextBridge, ipcRenderer } from 'electron'

const api = {
  isElectron: true as const,
  platform: process.platform,
  offline: {
    list: () => ipcRenderer.invoke('offline:list'),
    enqueue: (request: unknown) => ipcRenderer.invoke('offline:enqueue', request),
    remove: (key: string) => ipcRenderer.invoke('offline:remove', key),
    localPlayback: (key: string) => ipcRenderer.invoke('offline:localPlayback', key),
    readText: (fileUrl: string) => ipcRenderer.invoke('offline:readText', fileUrl),
    onProgress: (callback: (record: unknown) => void) => {
      const listener = (_event: Electron.IpcRendererEvent, record: unknown): void => callback(record)
      ipcRenderer.on('offline:progress', listener)
      return () => ipcRenderer.removeListener('offline:progress', listener)
    }
  }
}

export type DesktopPlayerApi = typeof api

contextBridge.exposeInMainWorld('film2', api)
