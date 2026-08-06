import { Button } from "./Button";
import { EmptyState } from "./EmptyState";

export function SettingsGate({ onOpenSettings }: { onOpenSettings: () => void }) {
  return (
    <EmptyState
      title="Once token'lari girin"
      description="TMDB, Hugging Face ve GitHub token'lari ayarlanmadan Studio'nun geri kalani kullanilamaz."
      action={
        <Button variant="primary" onClick={onOpenSettings}>
          Ayarlara git
        </Button>
      }
    />
  );
}
