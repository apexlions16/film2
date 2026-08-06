import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/outfit";
import "./styles/global.css";
import { App } from "./App";

const container = document.getElementById("root");
if (!container) {
  throw new Error("#root elementi bulunamadi");
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
