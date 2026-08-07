import { useCallback, useEffect, useState } from "react";
import type { SettingsPresence } from "@shared/types";
import { Sidebar } from "./components/Sidebar";
import { ErrorBanner } from "./components/ErrorBanner";
import { SettingsPage } from "./pages/SettingsPage";
import { AddContentPage } from "./pages/AddContentPage";
import { CatalogPage } from "./pages/CatalogPage";
import { UploadPage } from "./pages/UploadPage";
import { EditorialPage } from "./pages/EditorialPage";
import { errorMessage, unwrap } from "./lib/api";
import type { Route, UploadRouteTarget } from "./lib/route";

export function App() {
  const [route, setRoute] = useState<Route>({ name: "catalog" });
  const [presence, setPresence] = useState<SettingsPresence | null>(null);
  const [presenceError, setPresenceError] = useState<string | null>(null);
  const [hasAutoRedirected, setHasAutoRedirected] = useState(false);

  const refreshPresence = useCallback(async () => {
    try {
      const data = await unwrap(window.api.settings.getPresence());
      setPresence(data);
      setPresenceError(null);
      return data;
    } catch (err) {
      setPresenceError(errorMessage(err));
      return null;
    }
  }, []);

  useEffect(() => { void refreshPresence(); }, [refreshPresence]);

  useEffect(() => {
    if (hasAutoRedirected || !presence) return;
    const complete = presence.tmdbApiKey && presence.hfAccountsCount > 0 && presence.githubToken;
    if (!complete) setRoute({ name: "settings" });
    setHasAutoRedirected(true);
  }, [presence, hasAutoRedirected]);

  const gated = presence ? !(presence.tmdbApiKey && presence.hfAccountsCount > 0 && presence.githubToken) : false;
  const goToUpload = (target: UploadRouteTarget) => setRoute({ name: "upload", ...target });

  return (
    <div className="app-shell">
      <Sidebar route={route} presence={presence} onNavigate={setRoute} />
      <main className="main">
        <div className="page">
          {presenceError && <ErrorBanner message="Ayarlar okunamadi" detail={presenceError} />}
          {route.name === "settings" && <SettingsPage onSaved={refreshPresence} />}
          {route.name === "catalog" && (
            <CatalogPage
              gated={gated}
              onOpenSettings={() => setRoute({ name: "settings" })}
              onAddContent={() => setRoute({ name: "add" })}
              onAttachFiles={goToUpload}
            />
          )}
          {route.name === "editorial" && <EditorialPage gated={gated} onOpenSettings={() => setRoute({ name: "settings" })} />}
          {route.name === "add" && (
            <AddContentPage
              gated={gated}
              onOpenSettings={() => setRoute({ name: "settings" })}
              onSavedMovie={(titleId) => goToUpload({ titleId, kind: "movie" })}
              onSavedSeries={() => setRoute({ name: "catalog" })}
            />
          )}
          {route.name === "upload" && (
            <UploadPage
              target={{ titleId: route.titleId, kind: route.kind, seasonNumber: route.seasonNumber, episodeNumber: route.episodeNumber }}
              onDone={() => setRoute({ name: "catalog" })}
              onBack={() => setRoute({ name: "catalog" })}
            />
          )}
        </div>
      </main>
    </div>
  );
}
