import { resolve } from "node:path";
import { defineConfig, externalizeDepsPlugin } from "electron-vite";
import react from "@vitejs/plugin-react";

// Bu workspace paketleri (@film2/*) plain ESM JS kaynak dosyalari olarak dagitiliyor
// (node_modules/@film2/* altina npm workspaces tarafindan symlink'lenir). Electron'un
// main sureci CommonJS (package.json'da "type":"module" yok) oldugu icin bunlari
// runtime'da require() etmek yerine build zamaninda Rollup ile bundle icine gomuyoruz —
// externalizeDepsPlugin'in "exclude" listesine alarak. @huggingface/hub gibi gercek
// npm bagimliliklari ise CJS "require" export'una sahip oldugundan externalize edilmeye
// devam ediyor (node_modules'ten require() ile yuklenir).
const WORKSPACE_PACKAGES = [
  "@film2/tmdb-client",
  "@film2/hf-storage",
  "@film2/catalog-client",
  "@film2/catalog-schema",
];

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin({ exclude: WORKSPACE_PACKAGES })],
    resolve: {
      alias: {
        "@shared": resolve(__dirname, "src/shared"),
      },
    },
    build: {
      rollupOptions: {
        input: {
          index: resolve(__dirname, "src/main/index.ts"),
        },
      },
    },
  },
  preload: {
    plugins: [externalizeDepsPlugin()],
    build: {
      rollupOptions: {
        input: {
          index: resolve(__dirname, "src/preload/index.ts"),
        },
      },
    },
  },
  renderer: {
    root: resolve(__dirname, "src/renderer"),
    resolve: {
      alias: {
        "@renderer": resolve(__dirname, "src/renderer/src"),
        "@shared": resolve(__dirname, "src/shared"),
      },
    },
    build: {
      rollupOptions: {
        input: {
          index: resolve(__dirname, "src/renderer/index.html"),
        },
      },
    },
    plugins: [react()],
  },
});
