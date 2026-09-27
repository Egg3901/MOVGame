// Multiparty seat panel — majority threshold bar, coalition math, hung state.
// Shared by UK and country results after the Election Night theater.
import type { Government } from "@engine/types";
import type { PartyId } from "@engine/system";
import "./shell.css";

export interface SeatSegment {
  id: PartyId;
  seats: number;
  short: string;
  color: string;
}

export interface MultipartySeatPanelProps {
  segments: SeatSegment[];
  total: number;
  threshold: number;
  government: Government;
  /** Resolve a party id to a display name for the coalition equation. */
  nameOf: (id: PartyId) => string;
  unitPlural?: string; // "seats" | "MPs" | …
  mathLabel?: string;
}

function coalitionParts(g: Government): PartyId[] | null {
  switch (g.kind) {
    case "coalition":
      return g.parties;
    case "confidence_supply":
      return [g.lead, g.partner];
    case "majority":
    case "minority":
      return [g.party];
    case "hung":
      return null;
  }
}

export function MultipartySeatPanel({
  segments,
  total,
  threshold,
  government,
  nameOf,
  unitPlural = "seats",
  mathLabel = "COALITION MATH",
}: MultipartySeatPanelProps) {
  const hung = government.kind === "hung" || government.kind === "minority" ||
    government.kind === "coalition" || government.kind === "confidence_supply";
  const majority = government.kind === "majority";
  const parts = coalitionParts(government);
  const seatOf = (id: PartyId) => segments.find((s) => s.id === id)?.seats ?? 0;

  let mathLine: string | null = null;
  if (parts && parts.length >= 2) {
    const bits = parts.map((p) => `${nameOf(p)} ${seatOf(p)}`);
    const sum = parts.reduce((s, p) => s + seatOf(p), 0);
    mathLine = `${bits.join(" + ")} = ${sum}`;
  } else if (majority && parts) {
    mathLine = `${nameOf(parts[0])} ${seatOf(parts[0])}, majority of ${threshold}`;
  }

  const w = (n: number) => `${total > 0 ? (n / total) * 100 : 0}%`;

  return (
    <div className="ens-seat-panel" data-testid="multiparty-seat-panel">
      {hung && government.kind === "hung" && (
        <div className="ens-hung" data-testid="ens-hung">
          HUNG PARLIAMENT: no workable majority
        </div>
      )}
      {hung && government.kind !== "hung" && (
        <div className="ens-hung soft" data-testid="ens-hung">
          {government.kind === "minority"
            ? "NO OVERALL CONTROL: minority government"
            : government.kind === "confidence_supply"
              ? "HUNG: confidence & supply"
              : "HUNG PARLIAMENT: coalition arithmetic"}
        </div>
      )}

      <div className="ens-seat-bar" title={`${threshold} for a majority of ${total}`}>
        <div className="ens-seat-track">
          {segments.map((s) => (
            <div
              key={s.id}
              className="ens-seat-seg"
              style={{ width: w(s.seats), background: s.color }}
              title={`${s.short}: ${s.seats}`}
            >
              {s.seats / total >= 0.07 ? s.short : ""}
            </div>
          ))}
          <div
            className="ens-seat-thresh"
            style={{ left: w(threshold) }}
            title={`Majority: ${threshold}`}
          />
        </div>
        <div className="ens-seat-meta">
          <span>{segments[0] ? `${segments[0].short} ${segments[0].seats}` : "·"}</span>
          <span className="muted">{threshold} to win · {total} {unitPlural}</span>
        </div>
      </div>

      {mathLine && (
        <div className="ens-coalition" data-testid="ens-coalition-math">
          <span className="ens-coalition-tag">{mathLabel}</span>
          <span className="ens-coalition-eq">{mathLine}</span>
        </div>
      )}
    </div>
  );
}
