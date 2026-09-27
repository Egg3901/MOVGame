import type { CampaignAction, CandidateId, GameState, TurnRecapItem } from "./types";
import { createRng } from "./rng";
import { applyAction } from "./actions";
import { planAiActions, type AiConfig, DIFFICULTY } from "./ai";
import { queueEventsForTurn, resolveAiEvents, aiChooseEvent, resolveEvent, resolveDebate } from "./events";
import { projectElection, computeResult } from "./voteModel";
import { nationalPoll } from "./polls";
import { OPPONENT_OF, CANDIDATES } from "@content/candidates";
import { orderedPlan, planBonusMultiplier, planBonusesForAction } from "./planBonuses";

// Appends a trend sample for the current state to game.timeline. Called once at
// game start (the opening baseline) and once at the end of every advanced week.
function recordTimelinePoint(game: GameState) {
  const proj = projectElection(game);
  const demShareByState: Record<string, number> = {};
  for (const c of proj.contests) demShareByState[c.stateId] = c.demShare;
  (game.timeline ??= []).push({
    turn: game.turn,
    demPoll: nationalPoll(game),
    demEV: proj.ev.dem,
    repEV: proj.ev.rep,
    tossupEV: proj.tossupEv,
    demMomentum: game.resources.dem.nationalMomentum,
    repMomentum: game.resources.rep.nationalMomentum,
    demCash: game.resources.dem.cash,
    repCash: game.resources.rep.cash,
    demShareByState,
  });
}

// Decays the transient, turn-scoped quantities. Ground game is sticky (no
// decay) — that's what makes early field investment pay off.
function decay(game: GameState) {
  for (const st of game.states) {
    st.momentum *= 0.7;
    if (Math.abs(st.momentum) < 0.3) st.momentum = 0;
    for (const bloc of st.blocs) {
      // Enthusiasm relaxes toward 1.0.
      bloc.enthusiasm = 1 + (bloc.enthusiasm - 1) * 0.6;
    }
  }
  for (const c of ["dem", "rep"] as CandidateId[]) {
    const res = game.resources[c];
    res.nationalMomentum *= 0.75;
    res.mediaNarrative *= 0.8;
    // Prep buffs (debate / policy) relax back toward the candidate's real
    // starting traits — gently (35%/week), so prepping a week or two before a
    // debate still pays off at showtime.
    const cand = game.candidates[c];
    const base = cand.baseTraits ?? CANDIDATES[c].traits;
    for (const k of ["debatePrep", "policyKnowledge", "debatingSkill"] as const) {
      cand.traits[k] = base[k] + (cand.traits[k] - base[k]) * 0.65;
    }
    // Refill candidate-days for the new week.
    res.actions = res.maxActions;
  }
}

function buildRecap(game: GameState, turn: number, evBefore: number, cashBefore: number): TurnRecapItem[] {
  const recap: TurnRecapItem[] = [];
  // Group this turn's causes by their human-readable label.
  const byCause = new Map<string, { label: string; delta: number; states: Set<string>; actor?: CandidateId }>();
  for (const c of game.causes) {
    if (c.turn !== turn) continue;
    const key = `${c.actor ?? "event"}:${c.cause}`;
    const entry = byCause.get(key) ?? { label: c.cause, delta: 0, states: new Set(), actor: c.actor };
    entry.delta += c.marginDelta;
    if (c.stateId) entry.states.add(c.stateId);
    byCause.set(key, entry);
  }
  const playerSign = game.playerCandidate === "dem" ? 1 : -1;
  for (const info of byCause.values()) {
    const bonus = info.label.startsWith("Plan bonus:");
    recap.push({
      label: info.label,
      detail: `${info.actor === game.playerCandidate ? "Your action" : info.actor ? "Opponent action" : "Campaign event"} · ${info.states.size > 0 ? `${info.states.size} contest(s)` : "nationwide"}`,
      marginDelta: bonus || info.delta === 0 ? undefined : info.delta * playerSign,
    });
  }
  recap.sort((a, b) => {
    const aOwn = a.detail.startsWith("Your action") ? 1 : 0;
    const bOwn = b.detail.startsWith("Your action") ? 1 : 0;
    const aBonus = a.label.startsWith("Plan bonus:") ? 1 : 0;
    const bBonus = b.label.startsWith("Plan bonus:") ? 1 : 0;
    return bOwn - aOwn || aBonus - bBonus || Math.abs(b.marginDelta ?? 0) - Math.abs(a.marginDelta ?? 0);
  });

  const evAfter = projectElection(game).ev[game.playerCandidate];
  recap.unshift({
    label: "Projected electoral votes",
    detail: `${game.candidates[game.playerCandidate].shortName} ${evAfter} (was ${evBefore})`,
    marginDelta: evAfter - evBefore,
  });
  const cashAfter = game.resources[game.playerCandidate].cash;
  const cashDelta = cashAfter - cashBefore;
  recap.splice(1, 0, {
    label: "Campaign cash",
    detail: `$${(cashAfter / 1_000_000).toFixed(1)}M after ${cashDelta >= 0 ? "+" : "-"}$${(Math.abs(cashDelta) / 1_000_000).toFixed(1)}M this week`,
  });
  const own = recap.slice(2).filter((item) => item.detail.startsWith("Your action"));
  const other = recap.slice(2).filter((item) => !item.detail.startsWith("Your action"));
  return [...recap.slice(0, 2), ...own, ...other.slice(0, 8)];
}

export interface AdvanceOptions {
  difficulty?: AiConfig;
  // If true, any player events left unanswered get a sensible default pick.
  autoResolvePlayerEvents?: boolean;
}

// THE pure turn function. advanceTurn(state, actions, seed) → newState.
// Same inputs always produce the same output (undo, replay, tests rely on it).
export function advanceTurn(
  state: GameState,
  actions: CampaignAction[],
  seed: number | string,
  opts: AdvanceOptions = {},
): GameState {
  const game: GameState = structuredClone(state);
  if (game.phase === "result") return game;

  const turn = game.turn;
  const rng = createRng(`${seed}:${game.seed}:${turn}:${game.rngState}`);
  const player = game.playerCandidate;
  const ai = OPPONENT_OF[player];
  const cfg = opts.difficulty ?? DIFFICULTY.normal;

  const evBefore = projectElection(game).ev[player];
  const cashBefore = game.resources[player].cash;

  // 1. Resolve any leftover player events with a sensible default. Debates are
  //    resolved head-to-head (both sides at once) so the scorecard momentum
  //    swing applies even when the player skips the decision.
  if (opts.autoResolvePlayerEvents !== false) {
    const pendingDebateIds = [
      ...new Set(
        game.pendingEvents
          .filter((p) => p.forCandidate === player && requireEvent(p.eventId).isDebate)
          .map((p) => p.eventId),
      ),
    ];
    for (const id of pendingDebateIds) resolveDebate(game, requireEvent(id), {});

    const leftover = game.pendingEvents.filter((p) => p.forCandidate === player);
    for (const p of leftover) {
      const choice = aiChooseEvent(game, requireEvent(p.eventId), player);
      resolveEvent(game, p.eventId, choice.id, player);
    }
  }

  // 2. AI resolves its own events.
  resolveAiEvents(game, ai);

  // 3. Apply the player's queued actions in day order (Day 1 → Day 7), so the
  //    week plays out as scheduled, then the AI's plan.
  const playerActions = orderedPlan(actions.filter((a) => a.candidate === player));
  const completedActions: CampaignAction[] = [];
  for (const action of playerActions) {
    const bonuses = planBonusesForAction(action, completedActions);
    const causeCount = game.causes.length;
    applyAction(game, action, rng, planBonusMultiplier(bonuses), bonuses);
    if (game.causes.length > causeCount) completedActions.push(action);
  }
  const aiActions = planAiActions(game, rng, cfg);
  for (const action of aiActions) applyAction(game, action, rng);

  // 4. Decay transient quantities and refill resources.
  decay(game);

  // 5. Recap + bookkeeping.
  game.lastRecap = buildRecap(game, turn, evBefore, cashBefore);
  game.queuedActions = [];
  game.pendingEvents = [];
  game.rngState = rng.state();
  game.turn = turn + 1;

  // 6. End of campaign? Otherwise queue the next week's events.
  if (game.turn >= game.totalTurns) {
    game.result = computeResult(game);
    game.phase = "result";
  } else {
    queueEventsForTurn(game, rng);
    game.phase = "intel";
  }
  recordTimelinePoint(game); // this week's close
  return game;
}

import { EVENTS_BY_ID } from "@content/events";
function requireEvent(id: string) {
  const e = EVENTS_BY_ID[id];
  if (!e) throw new Error(`Unknown event ${id}`);
  return e;
}

// Opens a freshly created game. The first week is deliberately event-free: the
// player lands on the dashboard to read the map and set a plan without a
// decision modal blocking the screen. Campaign events begin after the first
// "End Week" (advanceTurn queues turn 1's slate onward).
export function beginGame(game: GameState): GameState {
  const next: GameState = structuredClone(game);
  next.pendingEvents = [];
  next.phase = "intel";
  next.timeline = [];
  recordTimelinePoint(next); // opening baseline (turn 0)
  return next;
}
