import { useState } from "react";
import { useGameStore } from "@store/gameStore";
import { GRID, SPLIT_UNITS, GRID_COLS, GRID_ROWS } from "@content/mapLayout";
import { STATE_PATHS } from "@content/statePaths";
import { tallyContest, computeResult, type CandidateId, type GameState } from "@engine/index";
import { shareToColor } from "./colors";
import { MapPin } from "lucide-react";

// Where each candidate currently is on the map. The player's marker reflects a
// queued rally optimistically (instant feedback when you "Travel here"); the
// opponent's reflects their last committed campaign stop.
function candidateLocation(game: GameState, c: CandidateId): string | undefined {
  if (c === game.playerCandidate) {
    const queued = [...game.queuedActions].reverse()
      .find((a) => a.type === "rally" && a.candidate === c)?.stateId;
    if (queued) return queued;
  }
  return game.locations?.[c];
}

// Blue (dem) / red (rep) pins, rendered as overlay grid items on the tile
// cartogram. dem sits top-left of its cell, rep top-right, so they never collide.
function CandidateMarkers({ game }: { game: GameState }) {
  const pins: { c: CandidateId; color: string; side: "start" | "end" }[] = [
    { c: "dem", color: "var(--dem-bright)", side: "start" },
    { c: "rep", color: "var(--gop-bright)", side: "end" },
  ];
  return (
    <>
      {pins.map((p) => {
        const id = candidateLocation(game, p.c);
        const cell = id ? GRID.find((t) => t.id === id) : null;
        if (!cell) return null;
        return (
          <div key={p.c} style={{
            gridColumn: cell.col + 1, gridRow: cell.row + 1,
            alignSelf: "start", justifySelf: p.side, padding: 1,
            pointerEvents: "none", zIndex: 6, lineHeight: 0,
          }}>
            <MapPin size={15} color="#fff" fill={p.color} strokeWidth={2}
              style={{ filter: "drop-shadow(0 1px 2px rgba(0,0,0,0.75))" }} />
          </div>
        );
      })}
    </>
  );
}

function contestShare(game: GameState, id: string): number {
  const st = game.states.find((s) => s.id === id);
  if (!st) return 0.5;
  if (st.blocs.length > 0) return tallyContest(st).demShare;
  const sr = computeResult(game).stateResults.find((s) => s.stateId === id);
  return sr?.demShare ?? 0.5;
}

function GeoState({ id, game, onClick, selected }: { id: string; game: GameState; onClick: () => void; selected: boolean }) {
  const st = game.states.find((s) => s.id === id);
  if (!st) return null;
  const share = contestShare(game, id);
  // Split-state districts (ME-1, NE-2, etc.) use the parent state path
  const parentId = id.split("-")[0];
  const path = STATE_PATHS[id] ?? STATE_PATHS[parentId];
  if (!path) return null;

  return (
    <g
      onClick={onClick}
      style={{ cursor: "pointer" }}
      className={selected ? "geo-selected" : ""}
    >
      <path
        d={path}
        fill={shareToColor(share)}
        stroke={selected ? "var(--gold)" : "#1a2744"}
        strokeWidth={selected ? 2 : 0.5}
      />
    </g>
  );
}

function Tile({ id, game, size }: { id: string; game: GameState; size?: number }) {
  const selected = useGameStore((s) => s.selectedStateId === id);
  const select = useGameStore((s) => s.selectState);
  const st = game.states.find((s) => s.id === id);
  if (!st) return null;
  const share = contestShare(game, id);
  return (
    <div
      className={`tile${selected ? " selected" : ""}${st.battleground ? " bg" : ""}`}
      style={{ background: shareToColor(share), width: size }}
      onClick={() => select(id)}
      title={`${st.name} · ${st.electoralVotes} EV`}
    >
      <span>{st.abbr}</span>
      <span className="ev">{st.electoralVotes}</span>
    </div>
  );
}

export function USMap() {
  const game = useGameStore((s) => s.game)!;
  const [mode, setMode] = useState<"geo" | "square">("square");
  const select = useGameStore((s) => s.selectState);
  const selectedId = useGameStore((s) => s.selectedStateId);
  const mapToggle = (
    <div className="map-mode-toggle" role="group" aria-label="Map view">
      <button aria-pressed={mode === "square"} onClick={() => setMode("square")}>EV tiles</button>
      <button aria-pressed={mode === "geo"} onClick={() => setMode("geo")}>Geographic</button>
    </div>
  );

  if (mode === "geo") {
    return (
      <div className="card mapwrap sheen">
        <div className="mapcontrols" style={{ justifyContent: "space-between" }}>
          <h3 style={{ margin: 0, fontSize: 13, textTransform: "uppercase", letterSpacing: 0.8, color: "var(--muted)" }}>Electoral Map</h3>
          {mapToggle}
        </div>
        <svg viewBox="0 0 1000 650" className="geo-map" preserveAspectRatio="xMidYMid meet">
          {GRID.map((t) => (
            <GeoState
              key={t.id}
              id={t.id}
              game={game}
              onClick={() => select(t.id)}
              selected={selectedId === t.id}
            />
          ))}
          {/* Split states: only render at-large units on geo map */}
          {SPLIT_UNITS.map((grp) => {
            const atLarge = grp.ids.find((id) => id.endsWith("-AL"));
            return atLarge ? (
              <GeoState
                key={atLarge}
                id={atLarge}
                game={game}
                onClick={() => select(atLarge)}
                selected={selectedId === atLarge}
              />
            ) : null;
          })}
        </svg>
        <div className="legend">
          <span>{game.candidates.rep.shortName}</span>
          <span className="bar" />
          <span>{game.candidates.dem.shortName}</span>
          <span style={{ marginLeft: 12 }}>◻ gold outline = battleground</span>
        </div>
      </div>
    );
  }

  return (
    <div className="card mapwrap sheen">
      <div className="mapcontrols" style={{ justifyContent: "space-between" }}>
        <h3 style={{ margin: 0, fontSize: 13, textTransform: "uppercase", letterSpacing: 0.8, color: "var(--muted)" }}>Electoral Map</h3>
        {mapToggle}
      </div>
      <div
        className="tilegrid"
        style={{
          gridTemplateColumns: `repeat(${GRID_COLS}, 1fr)`,
          gridTemplateRows: `repeat(${GRID_ROWS}, 1fr)`,
          aspectRatio: `${GRID_COLS} / ${GRID_ROWS}`,
        }}
      >
        {GRID.map((t) => (
          <div key={t.id} style={{ gridColumn: t.col + 1, gridRow: t.row + 1 }}>
            <Tile id={t.id} game={game} />
          </div>
        ))}
        <CandidateMarkers game={game} />
      </div>

      <div className="splitstrip">
        {SPLIT_UNITS.map((grp) => (
          <div className="splitgroup" key={grp.label}>
            <span className="lab">{grp.label}</span>
            <div className="row">
              {grp.ids.map((id) => (
                <Tile key={id} id={id} game={game} size={38} />
              ))}
            </div>
          </div>
        ))}
      </div>

      <div className="legend">
        <span>{game.candidates.rep.shortName}</span>
        <span className="bar" />
        <span>{game.candidates.dem.shortName}</span>
        <span style={{ marginLeft: 12 }}>◻ gold = battleground</span>
        <span style={{ marginLeft: 12, display: "inline-flex", alignItems: "center", gap: 4 }}>
          <MapPin size={12} color="#fff" fill="var(--dem-bright)" /><MapPin size={12} color="#fff" fill="var(--gop-bright)" /> stops
        </span>
      </div>
    </div>
  );
}
