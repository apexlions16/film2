import type { AssetStatus } from "@shared/types";

const LABELS: Record<AssetStatus, string> = {
  pending: "Bekliyor",
  processing: "Isleniyor",
  ready: "Hazir",
  error: "Hata",
};

export function StatusBadge({ status }: { status: AssetStatus }) {
  return (
    <span className={`badge badge--${status}`}>
      <span className="badge__dot" />
      {LABELS[status]}
    </span>
  );
}
