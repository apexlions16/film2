import type { CastMember, CrewMember } from "@shared/types";
import { Button } from "./Button";

interface CastEditorProps {
  cast: CastMember[];
  onChange: (cast: CastMember[]) => void;
}

export function CastEditor({ cast, onChange }: CastEditorProps) {
  function update(index: number, patch: Partial<CastMember>) {
    onChange(cast.map((c, i) => (i === index ? { ...c, ...patch } : c)));
  }
  function remove(index: number) {
    onChange(cast.filter((_, i) => i !== index));
  }
  function add() {
    onChange([...cast, { name: "", character: "", profileUrl: "" }]);
  }

  return (
    <div className="stack">
      {cast.length === 0 && <p className="text-faint">Henuz oyuncu eklenmedi.</p>}
      {cast.map((member, i) => (
        <div key={i} className="field-grid field-grid--3" style={{ alignItems: "end" }}>
          <div className="field" style={{ margin: 0 }}>
            <label className="field__label">Oyuncu adi</label>
            <input className="input" value={member.name} onChange={(e) => update(i, { name: e.target.value })} />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label className="field__label">Karakter</label>
            <input className="input" value={member.character} onChange={(e) => update(i, { character: e.target.value })} />
          </div>
          <div className="row" style={{ gap: 8 }}>
            <input
              className="input"
              placeholder="Profil foto URL (opsiyonel)"
              value={member.profileUrl ?? ""}
              onChange={(e) => update(i, { profileUrl: e.target.value })}
            />
            <Button variant="danger" size="sm" onClick={() => remove(i)} type="button">
              Sil
            </Button>
          </div>
        </div>
      ))}
      <Button variant="ghost" size="sm" onClick={add} type="button" style={{ alignSelf: "flex-start" }}>
        + Oyuncu ekle
      </Button>
    </div>
  );
}

interface CrewEditorProps {
  crew: CrewMember[];
  onChange: (crew: CrewMember[]) => void;
}

export function CrewEditor({ crew, onChange }: CrewEditorProps) {
  function update(index: number, patch: Partial<CrewMember>) {
    onChange(crew.map((c, i) => (i === index ? { ...c, ...patch } : c)));
  }
  function remove(index: number) {
    onChange(crew.filter((_, i) => i !== index));
  }
  function add() {
    onChange([...crew, { name: "", job: "", profileUrl: "" }]);
  }

  return (
    <div className="stack">
      {crew.length === 0 && <p className="text-faint">Henuz ekip uyesi eklenmedi.</p>}
      {crew.map((member, i) => (
        <div key={i} className="field-grid field-grid--3" style={{ alignItems: "end" }}>
          <div className="field" style={{ margin: 0 }}>
            <label className="field__label">Ad</label>
            <input className="input" value={member.name} onChange={(e) => update(i, { name: e.target.value })} />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label className="field__label">Gorev</label>
            <input
              className="input"
              placeholder="Director, Writer, Creator..."
              value={member.job}
              onChange={(e) => update(i, { job: e.target.value })}
            />
          </div>
          <div className="row" style={{ gap: 8 }}>
            <input
              className="input"
              placeholder="Profil foto URL (opsiyonel)"
              value={member.profileUrl ?? ""}
              onChange={(e) => update(i, { profileUrl: e.target.value })}
            />
            <Button variant="danger" size="sm" onClick={() => remove(i)} type="button">
              Sil
            </Button>
          </div>
        </div>
      ))}
      <Button variant="ghost" size="sm" onClick={add} type="button" style={{ alignSelf: "flex-start" }}>
        + Ekip uyesi ekle
      </Button>
    </div>
  );
}
