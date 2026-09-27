// ─────────────────────────────────────────────────────────────────────────
// REPLAY LOG — a compact, engine-agnostic per-turn record of a whole campaign.
//
// The point of this module is to store CHEAP AGGREGATE NUMBERS (a handful of
// floats + a few short strings per turn), never a copy of GameState. A 20-turn
// campaign is a few KB, so long games never blow up IndexedDB.
//
// It is deliberately engine-neutral: the US two-party game, the UK multiparty
// game, and the generic country game all normalise into the same shape here.
// "units" means electoral votes (US) or seats (UK / country); "poll" is a
// national vote share in 0..1. Report derivation below reads ONLY this log, so
// it works for every scenario type and degrades gracefully where a metric does
// not apply.
// ─────────────────────────────────────────────────────────────────────────

export const REPLAY_VERSION = 1;

export type ReplayEngine = "us" | "uk" | "country";
// "daily" games are gated: the live in-game timeline scrubber is hidden so it
// cannot be used to peek/rewind for an edge on the shared daily board.
export type ReplayMode = "casual" | "daily";

// One contestant's standing at a single point in time.
export interface ReplayStanding {
  id: string;
  poll: number; // national vote share, 0..1
  units: number; // electoral votes (US) or seats (UK / country)
}

// One turn's snapshot. turn 0 = the opening baseline; 1..totalTurns = the
// picture at the end of each played week.
export interface ReplaySnapshot {
  turn: number;
  leaderId: string | null; // projected unit leader this turn
  standings: ReplayStanding[];
  tossupUnits: number; // uncalled electoral votes / seats
  playerCash?: number;
  playerMomentum?: number;
  // Per-contest PLAYER share this turn (US: player's two-party share of a
  // state; UK / country: player's vote share of a region). Powers the
  // decisive-contest and per-turn map readouts.
  contestShare: Record<string, number>;
  // Plain-English lines: what the player did, and what happened, this week.
  actions: string[];
  events: string[];
}

export interface ReplayParty {
  id: string;
  name: string;
  color: string;
}

export interface ReplayLog {
  version: number;
  engine: ReplayEngine;
  scenarioId: string;
  playerId: string;
  unitLabel: string; // "electoral votes" | "seats"
  unitTotal: number; // 538 | total seats in the chamber
  majority: number; // 270 | seats needed for a majority
  totalTurns: number;
  mode: ReplayMode;
  parties: ReplayParty[];
  contestNames: Record<string, string>; // contest id -> display name
  snapshots: ReplaySnapshot[];
}

export function createReplayLog(init: Omit<ReplayLog, "version" | "snapshots"> & {
  snapshots?: ReplaySnapshot[];
}): ReplayLog {
  return { version: REPLAY_VERSION, snapshots: [], ...init };
}

// Append a snapshot, replacing any existing entry for the same turn (so an
// undo-then-replay of a week overwrites rather than duplicates).
export function appendSnapshot(log: ReplayLog, snap: ReplaySnapshot): ReplayLog {
  const snapshots = log.snapshots.filter((s) => s.turn < snap.turn);
  snapshots.push(snap);
  return { ...log, snapshots };
}

// Trim the log back to (and including) a turn — used to keep the log in step
// with the undo ring buffer when the player rewinds.
export function truncateToTurn(log: ReplayLog, turn: number): ReplayLog {
  return { ...log, snapshots: log.snapshots.filter((s) => s.turn <= turn) };
}

const partyName = (log: ReplayLog, id: string) =>
  log.parties.find((p) => p.id === id)?.name ?? id;

// ── Report derivation (Task B) ───────────────────────────────────────────
// Everything the post-game "why you won / lost" breakdown needs, computed
// purely from the log. Fields that do not apply to a scenario come back empty
// so the UI can omit them cleanly.

export interface TrendPoint {
  turn: number;
  value: number;
}

export interface DecisiveContest {
  id: string;
  name: string;
  finalPlayerShare: number; // 0..1
  marginPts: number; // player share vs 50%, in points (+ = player ahead)
  won: boolean;
}

export interface FlipEvent {
  turn: number;
  toPlayer: boolean; // true = the player took the unit lead this turn
}

export interface ReportData {
  hasData: boolean;
  playerId: string;
  playerName: string;
  topRivalId: string | null;
  topRivalName: string | null;
  unitLabel: string;
  majority: number;
  // National margin (player share minus the strongest rival's share) per turn,
  // in points. Empty if fewer than two contestants were tracked.
  marginTrend: TrendPoint[];
  // Player unit (EV / seat) count per turn.
  unitTrend: TrendPoint[];
  startMarginPts: number;
  finalMarginPts: number;
  swingPts: number;
  peakUnits: number;
  floorUnits: number;
  // Closest finishes on election eve, tightest first.
  decisiveContests: DecisiveContest[];
  // Turns where the projected unit lead changed hands to / from the player.
  flips: FlipEvent[];
  // Cash spent over the campaign (start cash minus end cash), when tracked.
  cashSpent: number | null;
  // Count of each action type the player took, most-used first.
  actionTally: { label: string; count: number }[];
  // Events and debates that fired, in order.
  events: { turn: number; text: string }[];
}

// The strongest rival across the whole campaign (by peak units), so a
// multiparty race names the right opponent rather than an also-ran.
function pickTopRival(log: ReplayLog): string | null {
  const peak: Record<string, number> = {};
  for (const s of log.snapshots) {
    for (const st of s.standings) {
      if (st.id === log.playerId) continue;
      peak[st.id] = Math.max(peak[st.id] ?? -Infinity, st.units);
    }
  }
  let best: string | null = null;
  let bestV = -Infinity;
  for (const [id, v] of Object.entries(peak)) {
    if (v > bestV) {
      bestV = v;
      best = id;
    }
  }
  return best;
}

const standingOf = (s: ReplaySnapshot, id: string) =>
  s.standings.find((st) => st.id === id) ?? null;

export function deriveReport(log: ReplayLog | null): ReportData {
  const empty: ReportData = {
    hasData: false,
    playerId: log?.playerId ?? "",
    playerName: log ? partyName(log, log.playerId) : "",
    topRivalId: null,
    topRivalName: null,
    unitLabel: log?.unitLabel ?? "electoral votes",
    majority: log?.majority ?? 270,
    marginTrend: [],
    unitTrend: [],
    startMarginPts: 0,
    finalMarginPts: 0,
    swingPts: 0,
    peakUnits: 0,
    floorUnits: 0,
    decisiveContests: [],
    flips: [],
    cashSpent: null,
    actionTally: [],
    events: [],
  };
  if (!log || log.snapshots.length === 0) return empty;

  const snaps = [...log.snapshots].sort((a, b) => a.turn - b.turn);
  const playerId = log.playerId;
  const rivalId = pickTopRival(log);

  // Trends.
  const marginTrend: TrendPoint[] = [];
  const unitTrend: TrendPoint[] = [];
  for (const s of snaps) {
    const me = standingOf(s, playerId);
    if (!me) continue;
    unitTrend.push({ turn: s.turn, value: me.units });
    if (rivalId) {
      const rv = standingOf(s, rivalId);
      if (rv) marginTrend.push({ turn: s.turn, value: (me.poll - rv.poll) * 100 });
    }
  }

  const startMarginPts = marginTrend[0]?.value ?? 0;
  const finalMarginPts = marginTrend[marginTrend.length - 1]?.value ?? 0;
  const peakUnits = unitTrend.length ? Math.max(...unitTrend.map((p) => p.value)) : 0;
  const floorUnits = unitTrend.length ? Math.min(...unitTrend.map((p) => p.value)) : 0;

  // Lead flips: sign changes of (player units - top rival units).
  const flips: FlipEvent[] = [];
  let prevSign = 0;
  for (const s of snaps) {
    const me = standingOf(s, playerId);
    const rv = rivalId ? standingOf(s, rivalId) : null;
    if (!me || !rv) continue;
    const sign = Math.sign(me.units - rv.units);
    if (sign !== 0 && prevSign !== 0 && sign !== prevSign) {
      flips.push({ turn: s.turn, toPlayer: sign > 0 });
    }
    if (sign !== 0) prevSign = sign;
  }

  // Decisive contests: election-eve shares closest to a coin flip.
  const last = snaps[snaps.length - 1];
  const decisiveContests: DecisiveContest[] = (log.engine === "us" ? Object.entries(last.contestShare) : [])
    .map(([id, share]) => ({
      id,
      name: log.contestNames[id] ?? id,
      finalPlayerShare: share,
      marginPts: (share - 0.5) * 200,
      won: share >= 0.5,
    }))
    .sort((a, b) => Math.abs(a.marginPts) - Math.abs(b.marginPts))
    .slice(0, 8);

  // Net cash change includes fundraising, so it cannot be presented as gross spending.
  const firstCash = snaps.find((s) => s.playerCash !== undefined)?.playerCash;
  const lastCash = [...snaps].reverse().find((s) => s.playerCash !== undefined)?.playerCash;
  const cashSpent =
    firstCash !== undefined && lastCash !== undefined ? firstCash - lastCash : null;

  // Action tally across every played week.
  const counts = new Map<string, number>();
  for (const s of snaps) for (const a of s.actions) counts.set(a, (counts.get(a) ?? 0) + 1);
  const actionTally = [...counts.entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((a, b) => b.count - a.count);

  // Events and debates in order.
  const events: { turn: number; text: string }[] = [];
  for (const s of snaps) for (const e of s.events) events.push({ turn: s.turn, text: e });

  return {
    hasData: true,
    playerId,
    playerName: partyName(log, playerId),
    topRivalId: rivalId,
    topRivalName: rivalId ? partyName(log, rivalId) : null,
    unitLabel: log.unitLabel,
    majority: log.majority,
    marginTrend,
    unitTrend,
    startMarginPts,
    finalMarginPts,
    swingPts: finalMarginPts - startMarginPts,
    peakUnits,
    floorUnits,
    decisiveContests,
    flips,
    cashSpent,
    actionTally,
    events,
  };
}
