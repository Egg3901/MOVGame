import { useState } from "react";
import { useUkStore } from "@store/ukStore";
import { majorityForUk } from "@engine/ukGame";
import { tallyRegion, allocateRegionSeats } from "@engine/multiparty";
import { REGION_PATHS, UK_VIEWBOX } from "@content/uk/regionPaths";
import { partyColor, partyShort } from "./parties";
import type { StateContest } from "@engine/types";

// Rough geographic tile layout for the square cartogram fallback (north at top).
const TILES: Record<string, { row: number; col: number }> = {
  SCO: { row: 0, col: 1 },
  NI: { row: 1, col: 0 }, NE: { row: 1, col: 2 },
  NW: { row: 2, col: 1 }, YH: { row: 2, col: 2 },
  WAL: { row: 3, col: 0 }, WM: { row: 3, col: 1 }, EM: { row: 3, col: 2 }, EE: { row: 3, col: 3 },
  SW: { row: 4, col: 1 }, LON: { row: 4, col: 2 }, SE: { row: 4, col: 3 },
};

function regionWinner(region: StateContest): { winner: string; seats: Record<string, number>; lead: number } {
  const { shareByParty } = tallyRegion(region);
  const seats = allocateRegionSeats(region, shareByParty);
  let winner = "";
  let best = -1;
  let total = 0;
  for (const p of Object.keys(seats)) { total += seats[p]; if (seats[p] > best) { best = seats[p]; winner = p; } }
  // How dominant the leader is (0.5 = split, 1 = sweep) → map opacity.
  const lead = total > 0 ? best / total : 0.5;
  return { winner, seats, lead };
}

export function UkMap() {
  const game = useUkStore((s) => s.game)!;
  const selected = useUkStore((s) => s.selectedRegionId);
  const select = useUkStore((s) => s.selectRegion);
  const [mode, setMode] = useState<"geo" | "square">("geo");
  const seatsTotal = majorityForUk(game).total;

  const header = (
    <div className="mapcontrols" style={{ justifyContent: "space-between" }}>
      <h3 style={{ margin: 0, fontSize: 13, textTransform: "uppercase", letterSpacing: 0.8, color: "var(--muted)" }}>Seat Map · {seatsTotal} seats</h3>
      <div className="row" style={{ gap: 4 }}>
        <button className={`ghost small${mode === "geo" ? " active" : ""}`} onClick={() => setMode("geo")}>Geo</button>
        <button className={`ghost small${mode === "square" ? " active" : ""}`} onClick={() => setMode("square")}>Square</button>
      </div>
    </div>
  );

  const legend = (
    <div className="legend" style={{ flexWrap: "wrap", gap: 8 }}>
      <span className="muted small">Labels show leader seats / region total.</span>
      {["lab", "con", "ld", "ref", "snp", "grn", "pc"].map((p) => (
        <span key={p} className="row" style={{ gap: 4, alignItems: "center" }}>
          <span style={{ width: 10, height: 10, borderRadius: 2, background: partyColor(p) }} />
          {partyShort(p)}
        </span>
      ))}
    </div>
  );

  if (mode === "geo") {
    return (
      <div className="card mapwrap sheen">
        {header}
        <svg viewBox={UK_VIEWBOX} className="geo-map" preserveAspectRatio="xMidYMid meet">
          {game.regions.map((region) => {
            const shape = REGION_PATHS[region.id];
            if (!shape) return null;
            const { winner, seats, lead } = regionWinner(region);
            const isSel = selected === region.id;
            return (
              <g key={region.id} style={{ cursor: "pointer" }} onClick={() => select(isSel ? null : region.id)}>
                <path
                  d={shape.d}
                  fill={partyColor(winner)}
                  fillOpacity={0.45 + lead * 0.55}
                  stroke={isSel ? "var(--gold)" : "#0c1322"}
                  strokeWidth={isSel ? 3 : 1}
                />
                <text x={shape.label[0]} y={shape.label[1]} textAnchor="middle"
                  style={{ fontSize: 17, fontWeight: 800, fill: "#fff", pointerEvents: "none", paintOrder: "stroke", stroke: "rgba(0,0,0,0.45)", strokeWidth: 3 }}>
                  {region.abbr}
                </text>
                <text x={shape.label[0]} y={shape.label[1] + 18} textAnchor="middle"
                  style={{ fontSize: 13, fontWeight: 700, fill: "#fff", pointerEvents: "none", paintOrder: "stroke", stroke: "rgba(0,0,0,0.45)", strokeWidth: 3 }}>
                  {winner ? `${partyShort(winner)} ${seats[winner]}/${region.seats}` : ""}
                </text>
              </g>
            );
          })}
        </svg>
        {legend}
      </div>
    );
  }

  return (
    <div className="card mapwrap sheen">
      {header}
      <div className="tilegrid" style={{ gridTemplateColumns: "repeat(4, 1fr)", gridTemplateRows: "repeat(5, 1fr)", aspectRatio: "4 / 5" }}>
        {game.regions.map((region) => {
          const pos = TILES[region.id] ?? { row: 5, col: 0 };
          const { winner, seats } = regionWinner(region);
          const isSel = selected === region.id;
          return (
            <div key={region.id} style={{ gridColumn: pos.col + 1, gridRow: pos.row + 1 }}>
              <div
                className={`tile${isSel ? " selected" : ""}`}
                style={{ background: partyColor(winner), width: "100%" }}
                onClick={() => select(isSel ? null : region.id)}
                title={`${region.name}: ${region.seats} seats`}
              >
                <span>{region.abbr}</span>
                <span className="ev">{winner ? `${partyShort(winner)} ${seats[winner]}/${region.seats}` : region.seats}</span>
              </div>
            </div>
          );
        })}
      </div>
      {legend}
    </div>
  );
}
