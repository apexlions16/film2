import { join } from 'node:path'
import { app, BrowserWindow, ipcMain, shell } from 'electron'
import {
  enqueueOfflineDownload,
  initializeOfflineDownloads,
  listOfflineDownloads,
  localPlaybackFor,
  removeOfflineDownload,
  type OfflineEnqueueRequest
} from './offline'
import { readOfflineText } from './offline-text'

// Chromium exposes HTMLMediaElement.audioTracks/videoTracks behind this web-platform
// feature on builds where it is not enabled by default. Film2's single-MP4 multi-audio
// player uses it to switch embedded Turkish/English tracks without remuxing in the Player.
app.commandLine.appendSwitch('enable-experimental-web-platform-features')

const isWindows = process.platform === 'win32'
let ipcRegistered = false

function registerIpc(): void {
  if (ipcRegistered) return
  ipcRegistered = true
  ipcMain.handle('offline:list', () => listOfflineDownloads())
  ipcMain.handle('offline:enqueue', (_event, request: OfflineEnqueueRequest) => enqueueOfflineDownload(request))
  ipcMain.handle('offline:remove', (_event, key: string) => removeOfflineDownload(key))
  ipcMain.handle('offline:localPlayback', (_event, key: string) => localPlaybackFor(key))
  ipcMain.handle('offline:readText', (_event, fileUrl: string) => readOfflineText(fileUrl))
}

function createMainWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1040,
    minHeight: 640,
    show: false,
    backgroundColor: '#09090b',
    autoHideMenuBar: true,
    titleBarStyle: isWindows ? 'hidden' : 'hiddenInset',
    ...(isWindows
      ? {
          titleBarOverlay: {
            color: '#09090b',
            symbolColor: '#e4e4e7',
            height: 40
          }
        }
      : {}),
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false,
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  win.on('ready-to-show', () => win.show())
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  const devServerUrl = process.env['ELECTRON_RENDERER_URL']
  if (devServerUrl) win.loadURL(devServerUrl)
  else win.loadFile(join(__dirname, '../renderer/index.html'))

  void initializeOfflineDownloads(win)
  return win
}

app.whenReady().then(() => {
  registerIpc()
  createMainWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
