import { contextBridge } from 'electron'

// Minimal, read-only surface exposed to the renderer. No IPC channels for
// filesystem/network access are needed yet — the renderer talks to GitHub
// and Hugging Face directly over fetch()/HLS, and there is no local
// download/offline mode in this app. This bridge exists so the renderer can
// reliably tell "running inside the Electron shell" apart from a plain
// browser tab (used e.g. to hide/guard Electron-only affordances).
const api = {
  isElectron: true as const,
  platform: process.platform
}

export type DesktopPlayerApi = typeof api

contextBridge.exposeInMainWorld('film2', api)
