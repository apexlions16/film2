import type { SettingsPresence } from "@shared/types";
import type { Route } from "../lib/route";

interface SidebarProps {
  route: Route;
  presence: SettingsPresence | null;
  onNavigate: (route: Route) => void;
}

const NAV_ITEMS: Array<{ name: "catalog" | "add" | "editorial" | "settings"; label: string; icon: string }> = [
  { name: "catalog", label: "Katalog", icon: "▦" },
  { name: "add", label: "Yeni İçerik Ekle", icon: "+" },
  { name: "editorial", label: "Editoryal / Ana Sayfa", icon: "✦" },
  { name: "settings", label: "Ayarlar", icon: "⚙" },
];

export function Sidebar({ route, presence, onNavigate }: SidebarProps) {
  const allSet = presence ? presence.tmdbApiKey && presence.hfAccountsCount > 0 && presence.githubToken : null;

  return (
    <aside className="sidebar">
      <div className="sidebar__brand">
        <span className="sidebar__brand-mark">Film2</span>
        <span className="sidebar__brand-tag">Studio</span>
      </div>
      <nav className="sidebar__nav">
        {NAV_ITEMS.map((item) => {
          const active = route.name === item.name || (route.name === "upload" && item.name === "catalog");
          return (
            <button
              key={item.name}
              className={`sidebar__link${active ? " sidebar__link--active" : ""}`}
              onClick={() => onNavigate({ name: item.name })}
            >
              <span className="sidebar__link-icon" aria-hidden="true">{item.icon}</span>
              {item.label}
              {item.name === "settings" && allSet !== null && (
                <span className={`sidebar__gate-dot ${allSet ? "sidebar__gate-dot--ok" : "sidebar__gate-dot--missing"}`} />
              )}
            </button>
          );
        })}
      </nav>
      <div className="sidebar__spacer" />
      <div style={{ padding: "10px 12px", fontSize: 11, color: "var(--text-faint)" }}>apexlions16/film2</div>
    </aside>
  );
}
