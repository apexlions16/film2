// window.api (preload contextBridge) cagrilarini sarmalayip IpcResult zarfini acar.
// Basarisizlikta normal bir Error (ApiError) firlatir — sayfa bileşenleri try/catch
// ile yakalayip kullaniciya gosterir, hicbir hata sessizce yutulmaz.
import type { IpcResult } from "@shared/types";

export class ApiError extends Error {
  detail?: string;

  constructor(message: string, detail?: string) {
    super(message);
    this.name = "ApiError";
    this.detail = detail;
  }
}

export async function unwrap<T>(promise: Promise<IpcResult<T>>): Promise<T> {
  const result = await promise;
  if (result.ok) return result.data;
  throw new ApiError(result.error.message, result.error.detail);
}

export function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

export function errorDetail(err: unknown): string | undefined {
  if (err instanceof ApiError) return err.detail;
  return undefined;
}
