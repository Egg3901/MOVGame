// US (two-party) adapter for the engine-agnostic replay log. Turns GameState
// into the compact per-turn ReplaySnapshot shape. No engine math here — it only
// reads values the engine already computed (game.timeline) plus the player's
// queued actions and the events that fired.

import type { CampaignAction, CandidateId, GameState } from "@engine/index";
import { projectElection } from "@engine/index";
import { nationalPoll } from "@engine/index";
import { EVENTS_BY_ID } from "@content/events";
import { BLOC_LABELS } from "@ui/labels";
import {
  appendSnapshot,
  createReplayLog,
  type ReplayLog,
  type ReplayMode,
  type ReplaySnapshot,
} from "@lib/replay";

const ACTION_VERB: Record<CampaignAction["type"], string> = {
  advertise: "Ran ads",
  rally: "Held a rally",
  surrogate: "Sent a surrogate",
  fundraise: "Fundraised",
  ground_game: "Built ground game",
  gotv: "Ran GOTV",
  oppo_research: "Dug up oppo",
  debate_prep: "Prepped for debate",
  policy_prep: "Prepped on policy",
  issue_pivot: "Pivoted on an issue",
};

function describeAction(a: CampaignAction, game: GameState): string {
  const verb = ACTION_VERB[a.type] ?? a.type;
  const where = a.stateId ? game.states.find((s) => s.id === a.stateId)?.abbr ?? a.stateId : null;
  const who = a.blocId ? BLOC_LABELS[a.blocId] ?? a.blocId : null;
  const tail = where ? ` in ${where}` : who ? ` to ${who}` : "";
  return `${verb}${tail}`;
}

// The turn-0 opening baseline snapshot (no actions, no events yet).
function snapshotOf(
  game: GameState,
  actions: string[],
  events: string[],
  at?: NonNullable<GameState["timeline"]>[number],
): ReplaySnapshot {
  const point = at ?? game.timeline?.[game.timeline.length - 1];
  const final = game.phase === "result" && (!point || point.turn === game.turn) ? game.result : null;
  const proj = point || final ? null : projectElection(game);
  const demEV = final ? final.electoralVotes.dem : point ? point.demEV : proj!.ev.dem;
  const repEV = final ? final.electoralVotes.rep : point ? point.repEV : proj!.ev.rep;
  const tossup = final ? 0 : point ? point.tossupEV : proj!.tossupEv;
  const demPoll = final ? final.popularShare.dem : point ? point.demPoll : nationalPoll(game);
  const player = game.playerCandidate;

  // Per-state PLAYER two-party share.
  const contestShare: Record<string, number> = {};
  const shareByState = final
    ? Object.fromEntries(final.stateResults.map((state) => [state.stateId, state.demShare]))
    : point?.demShareByState ??
      Object.fromEntries(projectElection(game).contests.map((c) => [c.stateId, c.demShare]));
  for (const [id, demShare] of Object.entries(shareByState)) {
    contestShare[id] = player === "dem" ? demShare : 1 - demShare;
  }

  return {
    turn: point?.turn ?? game.turn,
    leaderId: demEV === repEV ? null : demEV > repEV ? "dem" : "rep",
    standings: [
      { id: "dem", poll: demPoll, units: demEV },
      { id: "rep", poll: 1 - demPoll, units: repEV },
    ],
    tossupUnits: tossup,
    playerCash: point ? (player === "dem" ? point.demCash : point.repCash) : game.resources[player].cash,
    playerMomentum: point ? (player === "dem" ? point.demMomentum : point.repMomentum) : game.resources[player].nationalMomentum,
    contestShare,
    actions,
    events,
  };
}

export function initUsReplayLog(game: GameState, mode: ReplayMode): ReplayLog {
  const log = createReplayLog({
    engine: "us",
    scenarioId: game.scenarioId ?? "2020",
    playerId: game.playerCandidate,
    unitLabel: "electoral votes",
    unitTotal: 538,
    majority: 270,
    totalTurns: game.totalTurns,
    mode,
    parties: (["dem", "rep"] as CandidateId[]).map((id) => ({
      id,
      name: game.candidates[id].shortName,
      color: game.candidates[id].color,
    })),
    contestNames: Object.fromEntries(game.states.map((s) => [s.id, s.name])),
  });
  const points = game.timeline ?? [];
  if (points.length === 0) return appendSnapshot(log, snapshotOf(game, [], []));
  return points.reduce((current, point) => appendSnapshot(current, snapshotOf(game, [], [], point)), log);
}

export function syncUsReplayLog(log: ReplayLog, game: GameState): ReplayLog {
  const lastTurn = log.snapshots.at(-1)?.turn ?? -1;
  if (lastTurn >= game.turn) return log;
  return appendSnapshot(log, snapshotOf(game, [], []));
}

// Record the week that just completed. `prev` is the pre-advance state (its
// queuedActions are the player's plays); `next` is the post-advance state.
export function recordUsWeek(log: ReplayLog, prev: GameState, next: GameState): ReplayLog {
  const player = next.playerCandidate;
  const actions = prev.queuedActions
    .filter((a) => a.candidate === player)
    .map((a) => describeAction(a, prev));

  const priorFired = new Set(prev.firedEventIds);
  const events = next.firedEventIds
    .filter((id) => !priorFired.has(id))
    .map((id) => EVENTS_BY_ID[id]?.title ?? id);
  const priorDebates = prev.debateHistory?.length ?? 0;
  for (const d of (next.debateHistory ?? []).slice(priorDebates)) {
    const outcome =
      d.winner === "tie" ? "a draw" : d.winner === player ? "a win" : "a loss";
    events.push(`Debate: ${d.title} (${outcome})`);
  }

  return appendSnapshot(log, snapshotOf(next, actions, events));
}
