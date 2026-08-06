import type { DesktopPlayerApi } from './index'

declare global {
  interface Window {
    film2?: DesktopPlayerApi
  }
}
