import { app } from "electron";
import { spawn } from "node:child_process";
import { mkdir, rm } from "node:fs/promises";
import { extname, join } from "node:path";
import ffmpegStatic from "ffmpeg-static";
import type { UploadFileSelection } from "@shared/types";

// v1.1: input file extensions are advisory only; FFmpeg sniffs the actual container.
export interface PreparedMedia {
  videoPath: string;
  audioLanguages: string[];
  cleanup: () => Promise<void>;
  direct: boolean;
}

function normalizedLanguage(value: string): string {
  const key = value
    .trim()
    .toLocaleLowerCase("tr-TR")
    .replaceAll("ı", "i")
    .replaceAll("ğ", "g")
    .replaceAll("ü", "u")
    .replaceAll("ş", "s")
    .replaceAll("ö", "o")
    .replaceAll("ç", "c")
    .replace(/[^a-z0-9]/g, "");
  if (["en", "eng", "english", "ingilizce"].includes(key)) return "eng";
  if (["tr", "tur", "turkish", "turkce", "trke"].includes(key)) return "tur";
  if (["de", "deu", "ger", "german", "almanca"].includes(key)) return "deu";
  if (["fr", "fra", "fre", "french", "fransizca"].includes(key)) return "fra";
  if (["es", "spa", "spanish", "ispanyolca"].includes(key)) return "spa";
  return key.length === 3 ? key : "und";
}

function languageLabel(code: string): string {
  if (code === "eng") return "İngilizce";
  if (code === "tur") return "Türkçe";
  if (code === "deu") return "Almanca";
  if (code === "fra") return "Fransızca";
  if (code === "spa") return "İspanyolca";
  return code;
}

function ffmpegPath(): string {
  if (app.isPackaged) return join(process.resourcesPath, "bin", "ffmpeg.exe");
  if (!ffmpegStatic) throw new Error("FFmpeg binary bulunamadi.");
  return ffmpegStatic;
}

function runFfmpeg(args: string[], onMessage?: (message: string) => void): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(ffmpegPath(), args, { windowsHide: true });
    let stderr = "";
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk: string) => {
      stderr = `${stderr}${chunk}`.slice(-16_000);
      const time = /time=\s*([^\s]+)/.exec(chunk)?.[1];
      if (time) onMessage?.(`Yerel MP4 hazırlanıyor • ${time}`);
    });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`FFmpeg remux basarisiz (kod ${code}). ${stderr.slice(-3000)}`));
    });
  });
}

function addInput(args: string[], path: string): void {
  // FFmpeg dosyanin uzantisina degil gercek container/stream imzasina bakar.
  // +genpts, MPEG-TS kaynaklarinda eksik/garip PTS varsa MP4 icin kullanisli zaman damgalari uretir.
  args.push("-fflags", "+genpts", "-i", path);
}

async function remuxCombined(
  uploadId: string,
  input: string,
  onMessage?: (message: string) => void,
): Promise<PreparedMedia> {
  const root = join(app.getPath("temp"), "film2-studio-fast", uploadId);
  await mkdir(root, { recursive: true });
  const output = join(root, `video_${Date.now()}.mp4`);
  const args: string[] = ["-hide_banner", "-y"];
  addInput(args, input);
  args.push(
    "-map", "0:v:0",
    "-map", "0:a?",
    "-c", "copy",
    "-avoid_negative_ts", "make_zero",
    "-movflags", "+faststart",
    output,
  );

  try {
    onMessage?.("Gerçek medya formatı analiz ediliyor; uzantı önemsenmeden hızlı MP4 remux deneniyor…");
    await runFfmpeg(args, onMessage);
    return {
      videoPath: output,
      audioLanguages: [],
      cleanup: () => rm(root, { recursive: true, force: true }),
      direct: false,
    };
  } catch (error) {
    await rm(root, { recursive: true, force: true });
    throw error;
  }
}

export async function prepareFastMedia(
  uploadId: string,
  selection: UploadFileSelection,
  onMessage?: (message: string) => void,
): Promise<PreparedMedia | null> {
  if (selection.mode === "combined") {
    if (!selection.combinedFile) return null;
    const ext = extname(selection.combinedFile).toLowerCase();

    // Gercek MP4 zaten final konteyner; gereksiz yerel kopya yok.
    if (ext === ".mp4" || ext === ".m4v") {
      return {
        videoPath: selection.combinedFile,
        audioLanguages: [],
        cleanup: async () => {},
        direct: true,
      };
    }

    // .mkv yazsa bile gercekte MPEG-TS olabilir. FFmpeg content sniffing ile bunu
    // otomatik tanir ve H.264/HEVC + AAC gibi MP4-uyumlu streamleri encode etmeden tasir.
    return remuxCombined(uploadId, selection.combinedFile, onMessage);
  }

  if (!selection.videoFile) return null;
  const audioEntries = Object.entries(selection.audioFiles);
  if (audioEntries.length === 0) return null;

  // Uzanti filtresi YOK. video.mkv / tr.mkv / en.mkv gercekte MPEG-TS ise FFmpeg
  // container imzasindan tanir. Uyumlu streamler stream-copy ile tek MP4'e gider.
  const root = join(app.getPath("temp"), "film2-studio-fast", uploadId);
  await mkdir(root, { recursive: true });
  const output = join(root, `video_${Date.now()}.mp4`);
  const languages = audioEntries.map(([language]) => normalizedLanguage(language));

  const args: string[] = ["-hide_banner", "-y"];
  addInput(args, selection.videoFile);
  for (const [, path] of audioEntries) addInput(args, path);
  args.push("-map", "0:v:0");
  audioEntries.forEach((_, index) => args.push("-map", `${index + 1}:a:0`));
  args.push(
    "-c:v", "copy",
    "-c:a", "copy",
    "-avoid_negative_ts", "make_zero",
    "-movflags", "+faststart",
  );
  languages.forEach((language, index) => {
    args.push(`-metadata:s:a:${index}`, `language=${language}`);
    args.push(`-metadata:s:a:${index}`, `title=${languageLabel(language)}`);
    args.push(`-disposition:a:${index}`, index === 0 ? "default" : "0");
  });
  args.push(output);

  try {
    onMessage?.("Dosyaların gerçek formatı analiz ediliyor; MPEG-TS/MKV/MP4 uzantıdan bağımsız hızlı remux ediliyor (encode yok)…");
    await runFfmpeg(args, onMessage);
    return {
      videoPath: output,
      audioLanguages: languages,
      cleanup: () => rm(root, { recursive: true, force: true }),
      direct: false,
    };
  } catch (error) {
    await rm(root, { recursive: true, force: true });
    throw error;
  }
}
