// ELECTION NIGHT — the staged reveal that plays before each results screen.
// Results are already final when this mounts; the component is pure theater,
// sequencing the calls for drama: safe units flood in first in waves, the
// tightest races hold back as "too close to call" and resolve one by one, and
// the moment a party's running tally crosses the winning post a full-width
// projection banner fires. One shared component drives all three engines
// (US / UK / country) via the normalized RevealProps shape (see adapters.ts).
import { useEffect, useMemo, useRef, useState } from "react";
import "./electionNight.css";
import { buildSchedule, unitAllocation, OTHERS_ID, type RevealUnit } from "@lib/electionReveal";
export { buildSchedule, OTHERS_ID } from "@lib/electionReveal";
export type { RevealUnit, ScheduleEntry } from "@lib/electionReveal";

export interface RevealParty {
  id: string;
  short: string;
  color: string;
}

// Optional real-geography map: SVG paths keyed by unit id. Units without a
// shape (e.g. ME/NE congressional districts) simply don't appear on the map —
// their calls still show up in the callout and chip feed.
export interface RevealMap {
  viewBox: string;
  shapes: Record<string, { d: string; label?: [number, number] }>;
}

export interface RevealProps {
  title: string; // "Election Night · 1984"
  totalUnits: number; // 538 / 650 / 343…
  threshold: number; // 270 / 326 / 172…
  parties: RevealParty[]; // running tally rows (top 4 + others for multiparty)
  units: RevealUnit[]; // ALL units, with final winners
  playerPartyId: string;
  unitLabel?: string; // "EV" | "seats" | "points"
  map?: RevealMap; // live-filling map centerpiece; omitted ⇒ chip-only layout
  noMajorityLabel?: string; // hung-parliament / contingent-election banner text
  storageKey?: string; // sessionStorage key so refresh/resume doesn't replay
  onDone: () => void;
}

// Tally row id for the aggregated small parties in multiparty elections.


// Whether this environment can meaningfully play the reveal at all. jsdom /
// non-browser renders have no matchMedia; the results screens skip straight
// to the full results there instead of mounting dead theater.
export function revealSupported(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function";
}

export function hasSeenReveal(key: string): boolean {
  try {
    return typeof sessionStorage !== "undefined" && sessionStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}
function markSeen(key?: string) {
  if (!key) return;
  try {
    sessionStorage.setItem(key, "1");
  } catch {
    /* private mode etc. — replaying is harmless */
  }
}

const prefersReduced = () =>
  typeof window === "undefined" ||
  typeof window.matchMedia !== "function" ||
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// ── Choreography schedule ────────────────────────────────────────────────
// The whole reveal is precomputed as a flat timeline of entries with absolute
// times (ms at 1x). The driver just steps an index through it; speed changes
// scale the remaining gaps, "instant" jumps to the end.
type Speed = "1x" | "2x" | "instant";

export function ElectionNight(props: RevealProps) {
  const { title, totalUnits, threshold, parties, units, playerPartyId, storageKey, map, onDone } = props;
  const unitLabel = props.unitLabel ?? "seats";
  const noMajorityLabel = props.noMajorityLabel ?? "NO OVERALL MAJORITY: HUNG PARLIAMENT";

  const onDoneRef = useRef(onDone);
  onDoneRef.current = onDone;

  const { entries, waveCount } = useMemo(() => buildSchedule(units, threshold), [units, threshold]);

  // Reduced-motion users get the final board immediately (no theater).
  const [speed, setSpeed] = useState<Speed>(() => (prefersReduced() ? "instant" : "1x"));
  const [idx, setIdx] = useState(0); // number of schedule entries executed
  const finished = idx >= entries.length;

  // Already seen this game's reveal (refresh / resume) → straight through.
  useEffect(() => {
    if (storageKey && hasSeenReveal(storageKey)) onDoneRef.current();
  }, [storageKey]);

  // Driver: one timer per step; gaps scale with speed, instant jumps to end.
  useEffect(() => {
    if (finished) return;
    if (speed === "instant") {
      setIdx(entries.length);
      return;
    }
    const factor = speed === "2x" ? 2 : 1;
    const prevAt = idx === 0 ? 0 : entries[idx - 1].at;
    const delay = Math.max(30, (entries[idx].at - prevAt) / factor);
    const h = setTimeout(() => setIdx((i) => i + 1), delay);
    return () => clearTimeout(h);
  }, [idx, speed, finished, entries]);

  // Sequence complete: persist "seen"; in instant mode, hold briefly on the
  // final board and then advance to the full results automatically.
  useEffect(() => {
    if (!finished) return;
    markSeen(storageKey);
    if (speed === "instant") {
      const h = setTimeout(() => onDoneRef.current(), 1500);
      return () => clearTimeout(h);
    }
  }, [finished, speed, storageKey]);

  const skip = () => {
    markSeen(storageKey);
    onDoneRef.current();
  };

  // ── Derived board state from the executed prefix ──
  const executed = entries.slice(0, idx);
  const calls = executed.filter((e) => e.kind === "call");
  const tallies: Record<string, number> = {};
  let calledUnits = 0;
  const calledById = new Map<string, RevealUnit>();
  for (const c of calls) {
    const u = c.unit!;
    for (const [party, seats] of Object.entries(unitAllocation(u))) {
      tallies[party] = (tallies[party] ?? 0) + seats;
    }
    calledUnits += u.units;
    calledById.set(u.id, u);
  }
  const knownSum = parties.reduce((s, p) => (p.id === OTHERS_ID ? s : s + (tallies[p.id] ?? 0)), 0);
  const othersTotal = Math.max(0, calledUnits - knownSum);
  const remaining = Math.max(0, totalUnits - calledUnits);

  const lastCall = calls.length > 0 ? calls[calls.length - 1] : null;
  const inCliff = executed.some((e) => e.kind === "cliff");
  const currentWave = lastCall?.wave ?? executed.filter((e) => e.kind === "wave").length;

  // Projection: the executed call that crossed the threshold (if any).
  const projEntry = executed.find((e) => e.projectedId);
  const projected = projEntry
    ? (parties.find((p) => p.id === projEntry.projectedId) ?? {
        id: projEntry.projectedId!,
        short: projEntry.unit!.winnerShort,
        color: projEntry.unit!.winnerColor,
      })
    : null;

  // Too-close-to-call chips: cliff calls not yet executed.
  const pendingCliff = inCliff && !finished ? entries.slice(idx).filter((e) => e.isCliff) : [];

  const status = finished
    ? "ALL RESULTS IN"
    : inCliff
      ? "TOO CLOSE TO CALL"
      : calls.length === 0
        ? "POLLS ARE CLOSING"
        : `CALLS COMING IN · WAVE ${currentWave} OF ${waveCount}`;

  return (
    <div className="en-root">
      <div className={`card sheen en-stage${map ? " with-map" : ""}`}>
        <div className="en-top">
          <div>
            <div className="en-eyebrow">
              <span className="en-live" /> LIVE · ELECTION NIGHT
            </div>
            <h2 className="en-title">{title}</h2>
          </div>
          <div className="en-controls">
            {(["1x", "2x", "instant"] as const).map((s) => (
              <button
                key={s}
                className={`en-speed${speed === s ? " active" : ""}`}
                onClick={() => setSpeed(s)}
                title={s === "instant" ? "Skip straight to the final board" : `Play at ${s}`}
              >
                {s === "instant" ? "⚡" : s.replace("x", "×")}
              </button>
            ))}
            <button className="en-skip" onClick={skip}>Skip ⏭</button>
          </div>
        </div>

        <div className={`en-status${inCliff && !finished ? " gold" : ""}`}>{status}</div>

        {map && (
          <div className={`en-map${speed === "instant" ? " instant" : ""}`}>
            <svg viewBox={map.viewBox} preserveAspectRatio="xMidYMid meet" role="img" aria-label="Election map">
              {units.map((u) => {
                const shape = map.shapes[u.id];
                if (!shape) return null;
                const called = calledById.get(u.id);
                return (
                  <path
                    key={u.id}
                    d={shape.d}
                    className={`en-map-unit${called ? " called" : ""}`}
                    style={called ? { fill: called.winnerColor } : undefined}
                  >
                    <title>{called ? `${u.name} · ${called.winnerShort}` : u.name}</title>
                  </path>
                );
              })}
              {/* Upset rings render in a second pass so the gold pulse sits on top. */}
              {units.map((u) => {
                const called = calledById.get(u.id);
                if (!called?.upset || !map.shapes[u.id]) return null;
                return <path key={`ring-${u.id}`} d={map.shapes[u.id].d} className="en-map-upset-ring" />;
              })}
            </svg>
          </div>
        )}

        <div className="en-callout">
          {lastCall ? (
            <span className="en-call-flash" key={lastCall.unit!.id}>
              <span className="en-call-label">{lastCall.unit!.upset ? "UPSET" : "CALL"}</span>
              <span className="en-call-name">{lastCall.unit!.name}</span>
              <span className="en-call-winner" style={{ color: lastCall.unit!.winnerColor }}>
                → {lastCall.unit!.winnerShort}
              </span>
              <span className="en-call-margin">+{lastCall.unit!.margin.toFixed(1)}</span>
            </span>
          ) : (
            <span className="en-polls-closing">POLLS ARE CLOSING…</span>
          )}
        </div>

        {projected && (
          <div className="en-banner" style={{ background: projected.color }}>
            PROJECTION: {projected.short} WINS
          </div>
        )}
        {finished && !projected && <div className="en-banner hung">{noMajorityLabel}</div>}

        <div className="en-board">
          {parties.map((p) => {
            const val = p.id === OTHERS_ID ? othersTotal : (tallies[p.id] ?? 0);
            return (
              <div className={`en-row${p.id === playerPartyId ? " mine" : ""}`} key={p.id}>
                <span className="en-party" style={{ color: p.color }}>{p.short}</span>
                <div className="en-track">
                  <div className="en-fill" style={{ width: `${totalUnits > 0 ? (val / totalUnits) * 100 : 0}%`, background: p.color }} />
                  <div className="en-thresh" style={{ left: `${totalUnits > 0 ? (threshold / totalUnits) * 100 : 50}%` }} />
                </div>
                <span className="en-num">{val}</span>
              </div>
            );
          })}
        </div>

        <div className="en-remaining">
          {remaining} {unitLabel} still out · {threshold} to win
        </div>

        <div className="en-feed">
          {pendingCliff.map((e) => (
            <span className="en-chip tctc" key={`tctc-${e.unit!.id}`}>
              {e.unit!.abbr} · TOO CLOSE TO CALL
            </span>
          ))}
          {[...calls].reverse().slice(0, 14).map((c) => (
            <span className={`en-chip${c.unit!.upset ? " upset" : ""}`} key={c.unit!.id}>
              <span className="en-chip-call">CALL</span>
              {c.unit!.abbr}
              <span style={{ color: c.unit!.winnerColor }}>{c.unit!.winnerShort}</span>
              {c.unit!.upset && <span className="en-chip-upset-tag">UPSET</span>}
            </span>
          ))}
        </div>

        {finished && (
          <button className="primary en-cta" onClick={skip}>
            See full results →
          </button>
        )}
      </div>
    </div>
  );
}
