import type {
  AdMode,
  CampaignAction,
  CandidateId,
  CauseEntry,
  GameState,
  IssueId,
  StateBloc,
  StateContest,
} from "./types";
import type { Rng } from "./rng";
import { BLOCS } from "@content/blocs";
import { OPPONENT_OF } from "@content/candidates";
import { staffEffects } from "@content/staff";
import type { PlanBonus } from "./planBonuses";

// All action effects route THROUGH the vote model: they write margin deltas
// (Biden − Trump) into blocs' campaignMargin, plus enthusiasm / momentum /
// ground-game / salience side-effects. Each writes a CauseEntry so every poll
// move is traceable (Pillar C).

// Sign convention: a positive margin delta favors Biden. We translate a
// candidate-favoring effect into the right sign here.
function favorSign(candidate: CandidateId): number {
  return candidate === "dem" ? 1 : -1;
}

// Diminishing returns: the more you've already moved a bloc this campaign, the
// less each additional push does. Keeps spend from snowballing infinitely.
function saturate(bloc: StateBloc, raw: number): number {
  // Hard(ish) cap on total campaign-induced logit shift per bloc: as the
  // accumulated push approaches the ceiling, each additional push fades to zero,
  // so sustained spend can't snowball a state arbitrarily far over a campaign.
  const ceiling = 0.7;
  const room = Math.max(0, 1 - Math.abs(bloc.campaignMargin) / ceiling);
  // Leans are sticky: a bloc that already leans hard (large baseline margin)
  // resists persuasion far more than a true tossup — what keeps a safe state
  // safe. You can't flip California (or New York) with a campaign of ad dumps.
  const leanResist = 1 / (1 + Math.abs(bloc.baselineMargin) * 1.8);
  return raw * room * leanResist;
}

// How well an action lands on a bloc, based on issue alignment & salience.
// Used to target the right voters with the right message.
export function issueAlignment(
  game: GameState,
  candidate: CandidateId,
  blocId: string,
  issueId?: IssueId,
): number {
  const arche = BLOCS[blocId as keyof typeof BLOCS];
  if (!arche) return 0.5;
  const cand = game.candidates[candidate];
  const issues: IssueId[] = issueId ? [issueId] : (Object.keys(arche.issueWeights) as IssueId[]);
  let num = 0;
  let den = 0;
  for (const id of issues) {
    const weight = (arche.issueWeights[id] ?? 0) * (game.salience[id] ?? 0.5);
    const ideal = arche.idealPositions[id] ?? 0;
    const pos = cand.issuePositions[id] ?? 0;
    const align = 1 - Math.abs(pos - ideal) / 2; // 0..1
    num += weight * align;
    den += weight;
  }
  return den > 0 ? num / den : 0.5;
}

function addCause(
  game: GameState,
  bloc: StateBloc,
  state: StateContest,
  cause: string,
  delta: number,
) {
  bloc.campaignMargin += delta;
  const entry: CauseEntry = {
    turn: game.turn,
    stateId: state.id,
    blocId: bloc.blocId,
    cause,
    marginDelta: delta,
  };
  game.causes.push(entry);
}

const findState = (game: GameState, id?: string) =>
  game.states.find((s) => s.id === id && s.blocs.length > 0);

// ── ADVERTISING ───────────────────────────────────────────────────────────
// Positive: raise own appeal. Contrast: lower opponent. Issue: raise salience
// of a chosen issue where you're strong. Cost scales with media-market cost.
function applyAdvertise(
  game: GameState,
  action: CampaignAction,
  rng: Rng,
  mult = 1,
  planMultiplier = 1,
) {
  const state = findState(game, action.stateId);
  if (!state) return;
  const c = action.candidate;
  const spend = Math.max(0, action.spend ?? 0);
  const res = game.resources[c];
  const actualSpend = Math.min(spend, res.cash);
  if (actualSpend <= 0) return;
  res.cash -= actualSpend;

  // Track lifetime ad spend (drives the Grassroots achievement).
  game.adSpend = game.adSpend ?? {};
  game.adSpend[c] = (game.adSpend[c] ?? 0) + actualSpend;

  const mode: AdMode = action.adMode ?? "positive";
  // Effective spend, scaled by media market, then run through a saturating curve
  // so a single enormous buy hits diminishing returns and can't single-handedly
  // carry a state. adReach asymptotes toward AD_SAT (~6 effective $M-equivalents).
  const effectiveMillions = actualSpend / 1_000_000 / state.mediaMarketCost;
  const AD_SAT = 6;
  // A media strategist on staff stretches every buy.
  const adReach = AD_SAT * (1 - Math.exp(-effectiveMillions / AD_SAT)) * staffEffects(game, c).adMult;
  const fundraisingBoost = 0.85 + game.candidates[c].traits.fundraisingProwess / 400;

  if (mode === "issue" && action.issueId) {
    // Issue ads raise that issue's national salience (toward your strong suit).
    const bump = Math.min(0.12, effectiveMillions * 0.015) * planMultiplier;
    game.salience[action.issueId] = Math.min(1, (game.salience[action.issueId] ?? 0.5) + bump);
    game.causes.push({
      turn: game.turn,
      stateId: state.id,
      cause: `Issue ads raised salience of ${action.issueId}`,
      marginDelta: 0,
    });
    return;
  }

  const targetBlocs = action.blocId
    ? state.blocs.filter((b) => b.blocId === action.blocId)
    : state.blocs;

  for (const bloc of targetBlocs) {
    const align = issueAlignment(game, c, bloc.blocId);
    // Positive ads land best on persuadable, well-aligned blocs.
    const responsiveness = 0.4 + align * 0.6;
    const share = action.blocId ? 1 : bloc.size / state.blocs.reduce((s, b) => s + b.size, 0);
    let raw = adReach * 0.06 * responsiveness * fundraisingBoost;
    if (!action.blocId) raw *= share * targetBlocs.length; // spread across the state
    raw = saturate(bloc, raw);
    // A little variance so repeated ads aren't perfectly predictable.
    raw *= 0.9 + rng.next() * 0.2;
    raw *= mult; // player difficulty handicap

    if (mode === "contrast") {
      // Negative ads move a bloc less than a positive buy and depress turnout
      // (the "everyone's bad" effect) — a tactical tool with a real trade-off,
      // not a strictly-better option.
      addCause(game, bloc, state, `Contrast ads in ${state.abbr}`, favorSign(c) * raw * 0.6);
      bloc.enthusiasm = Math.max(0.78, bloc.enthusiasm - 0.018);
    } else {
      addCause(game, bloc, state, `Positive ads in ${state.abbr}`, favorSign(c) * raw);
    }
  }
  // Going negative dents your own media narrative.
  if (mode === "contrast") res.mediaNarrative = clamp(res.mediaNarrative - 4, -100, 100);
}

// ── RALLY (candidate stop) ────────────────────────────────────────────────
// Spends candidate-days. Momentum + local enthusiasm + turnout. Gaffe risk
// rises as stamina falls and as the candidate rallies more.
function applyRally(game: GameState, action: CampaignAction, rng: Rng, mult = 1) {
  const state = findState(game, action.stateId);
  if (!state) return;
  const c = action.candidate;
  const res = game.resources[c];

  // The candidate is now campaigning here — drives the map marker.
  game.locations = game.locations ?? {};
  game.locations[c] = state.id;

  const energy = game.candidates[c].traits.energy;
  const charisma = game.candidates[c].traits.charisma;
  // state.momentum is signed Biden−Trump; nationalMomentum is the ticket's own.
  state.momentum = clamp(state.momentum + favorSign(c) * (6 + charisma / 20), -100, 100);
  res.nationalMomentum = clamp(res.nationalMomentum + 1.5, -100, 100);

  for (const bloc of state.blocs) {
    const align = issueAlignment(game, c, bloc.blocId);
    let raw = 0.05 * (0.5 + align * 0.5) * (0.9 + charisma / 300);
    raw = saturate(bloc, raw) * mult;
    addCause(game, bloc, state, `Rally in ${state.abbr}`, favorSign(c) * raw);
    bloc.enthusiasm = Math.min(1.25, bloc.enthusiasm + 0.01);
  }

  // Gaffe risk: more likely with low energy.
  const gaffeRisk = Math.max(0.02, (0.18 * (100 - energy)) / 100);
  if (rng.chance(gaffeRisk)) {
    const penalty = 0.04 + rng.next() * 0.05;
    for (const bloc of state.blocs) {
      addCause(game, bloc, state, `Gaffe at ${state.abbr} rally`, -favorSign(c) * penalty);
    }
    res.mediaNarrative = clamp(res.mediaNarrative - 8, -100, 100);
  }
}

// ── SURROGATE / VP stop ───────────────────────────────────────────────────
// Covers states the principal can't. Weaker, but costs no principal days.
function applySurrogate(game: GameState, action: CampaignAction, rng: Rng, mult = 1) {
  const state = findState(game, action.stateId);
  if (!state) return;
  const c = action.candidate;
  const res = game.resources[c];
  const cost = 250_000;
  if (res.cash < cost) return;
  res.cash -= cost;
  state.momentum = clamp(state.momentum + favorSign(c) * 2, -100, 100);
  for (const bloc of state.blocs) {
    let raw = 0.018 * (0.6 + issueAlignment(game, c, bloc.blocId) * 0.4);
    raw = saturate(bloc, raw) * (0.9 + rng.next() * 0.2) * mult;
    addCause(game, bloc, state, `Surrogate visit to ${state.abbr}`, favorSign(c) * raw);
  }
}

// ── FUNDRAISING ───────────────────────────────────────────────────────────
// Spends candidate-days for cash. Fundraising trait + small-dollar enthusiasm.
export function estimatedFundraise(game: GameState, candidate: CandidateId, stateId?: string): number {
  const res = game.resources[candidate];
  const trait = game.candidates[candidate].traits.fundraisingProwess;
  const momentumBonus = 1 + Math.max(0, res.nationalMomentum) / 200;
  const st = findState(game, stateId);
  const sizeMult = st ? 0.6 + Math.min(1.1, st.electoralVotes / 28) : 1;
  return (8_000_000 + trait * 120_000) * momentumBonus * sizeMult * staffEffects(game, candidate).fundraiseMult;
}

function applyFundraise(game: GameState, action: CampaignAction, rng: Rng) {
  const c = action.candidate;
  const res = game.resources[c];
  const st = findState(game, action.stateId);
  const haul = estimatedFundraise(game, c, action.stateId) * (0.85 + rng.next() * 0.3);
  res.cash += haul;
  game.fundsRaised = game.fundsRaised ?? {};
  game.fundsRaised[c] = (game.fundsRaised[c] ?? 0) + haul;
  game.causes.push({
    turn: game.turn,
    cause: st ? `Fundraising haul in ${st.abbr} (+$${(haul / 1_000_000).toFixed(1)}M)` : `Fundraising haul (+$${(haul / 1_000_000).toFixed(1)}M)`,
    marginDelta: 0,
  });
}

// ── GROUND GAME / FIELD OFFICES ───────────────────────────────────────────
// Slow to build, sticky. Builds staff-limited persistent GOTV capacity.
function applyGroundGame(game: GameState, action: CampaignAction) {
  const state = findState(game, action.stateId);
  if (!state) return;
  const c = action.candidate;
  const res = game.resources[c];
  const cost = 1_500_000;
  if (res.cash < cost || res.staffCapacity < 1) return;
  res.cash -= cost;
  res.staffCapacity -= 1;
  state.groundGame[c] = Math.min(1, state.groundGame[c] + 0.2);
  game.causes.push({
    turn: game.turn,
    stateId: state.id,
    cause: `Built field offices in ${state.abbr}`,
    marginDelta: 0,
  });
}

// ── GOTV ──────────────────────────────────────────────────────────────────
// Late-game turnout push; only effective where ground game exists.
function applyGotv(game: GameState, action: CampaignAction, mult = 1) {
  const state = findState(game, action.stateId);
  if (!state) return;
  const c = action.candidate;
  const res = game.resources[c];
  const cost = 1_000_000;
  if (res.cash < cost) return;
  res.cash -= cost;
  const ground = state.groundGame[c];
  if (ground <= 0.01) {
    // No infrastructure → wasted money (rewards earlier investment).
    game.causes.push({
      turn: game.turn,
      stateId: state.id,
      cause: `GOTV in ${state.abbr} fell flat (no field operation)`,
      marginDelta: 0,
    });
    return;
  }
  // Mobilization advantage → effective two-party margin shift toward you.
  // Runs through the same saturating curve as every other persuasion action, so
  // repeat GOTV in one state diminishes instead of stacking linearly (otherwise
  // late-game GOTV spam on a field-built state is a flat, uncapped margin print).
  for (const bloc of state.blocs) {
    const propensity = 1 - bloc.turnoutPropensity; // more upside for low-turnout blocs
    const raw = saturate(bloc, 0.12 * ground * (0.5 + propensity)) * mult;
    addCause(game, bloc, state, `GOTV mobilization in ${state.abbr}`, favorSign(c) * raw);
    bloc.enthusiasm = Math.min(1.3, bloc.enthusiasm + 0.03 * ground);
  }
}

// ── OPPOSITION RESEARCH ───────────────────────────────────────────────────
// Risky: can land a scandal on the opponent, or backfire as "desperate".
function applyOppoResearch(game: GameState, action: CampaignAction, rng: Rng, mult = 1) {
  const c = action.candidate;
  const opp = OPPONENT_OF[c];
  const res = game.resources[c];
  const cost = 2_000_000;
  if (res.cash < cost) return;
  res.cash -= cost;
  // A rapid-response director on the TARGET's staff blunts incoming hits.
  const success = rng.chance(Math.max(0.15, 0.55 - staffEffects(game, opp).oppoShield));
  const sign = favorSign(c);
  // National in reach (it hits persuadable blocs everywhere), but each hit runs
  // through the saturating curve: repeat oppo dumps diminish, and a bloc that
  // already leans hard resists — so a string of scandals can't snowball a
  // safe state the way an uncapped national shift could.
  if (success) {
    // Hits the opponent across persuadable blocs.
    for (const st of game.states) {
      if (st.blocs.length === 0) continue;
      for (const bloc of st.blocs) {
        if (bloc.blocId === "college_white" || bloc.blocId === "suburban_women") {
          addCause(game, bloc, st, `Scandal lands on ${game.candidates[opp].shortName}`, sign * saturate(bloc, 0.05) * mult);
        }
      }
    }
    res.mediaNarrative = clamp(res.mediaNarrative + 6, -100, 100);
  } else {
    // Backfire — looks desperate.
    res.mediaNarrative = clamp(res.mediaNarrative - 10, -100, 100);
    for (const st of game.states) {
      if (st.blocs.length === 0) continue;
      for (const bloc of st.blocs) {
        if (bloc.blocId === "suburban_women") {
          addCause(game, bloc, st, `Oppo dump backfires as "desperate"`, -sign * saturate(bloc, 0.03));
        }
      }
    }
  }
}

// ── DEBATE PREP ───────────────────────────────────────────────────────────
// Mock debates and message discipline. Raises debate readiness, which lifts the
// payoff (and limits the damage) of the next debate event. Decays gently.
function applyDebatePrep(game: GameState, action: CampaignAction) {
  const c = action.candidate;
  const bonus = 8 + staffEffects(game, c).debatePrepBonus;
  game.candidates[c].traits.debatePrep = Math.min(100, game.candidates[c].traits.debatePrep + bonus);
  game.causes.push({
    turn: game.turn,
    cause: `Debate prep (+8 debate readiness)`,
    marginDelta: 0,
  });
}

// ── POLICY PREP ───────────────────────────────────────────────────────────
// Briefing books and white papers. Raises policy command — substance that also
// pays off on the debate stage and unlocks wonk-gated answers.
function applyPolicyPrep(game: GameState, action: CampaignAction) {
  const c = action.candidate;
  game.candidates[c].traits.policyKnowledge = Math.min(100, game.candidates[c].traits.policyKnowledge + 8);
  game.causes.push({
    turn: game.turn,
    cause: `Policy prep (+8 policy command)`,
    marginDelta: 0,
  });
}

// ── ISSUE PIVOT ───────────────────────────────────────────────────────────
// Move your own position. Win a bloc, anger another, pay a flip-flopper tax.
function applyIssuePivot(game: GameState, action: CampaignAction, mult = 1) {
  const c = action.candidate;
  if (!action.issueId || action.newPosition === undefined) return;
  const cand = game.candidates[c];
  const old = cand.issuePositions[action.issueId];
  const next = clamp(action.newPosition, -1, 1);
  const move = next - old;
  if (Math.abs(move) < 0.01) return;
  cand.issuePositions[action.issueId] = next;

  // Recompute alignment-driven shift for every bloc that cares about the issue.
  for (const st of game.states) {
    if (st.blocs.length === 0) continue;
    for (const bloc of st.blocs) {
      const arche = BLOCS[bloc.blocId];
      const weight = (arche.issueWeights[action.issueId] ?? 0) * (game.salience[action.issueId] ?? 0.5);
      if (weight < 0.05) continue;
      const ideal = arche.idealPositions[action.issueId] ?? 0;
      const before = 1 - Math.abs(old - ideal) / 2;
      const after = 1 - Math.abs(next - ideal) / 2;
      const delta = (after - before) * weight * 0.5 * mult;
      addCause(game, bloc, st, `Pivot on ${action.issueId}`, favorSign(c) * delta);
    }
  }
  // Flip-flopper tax with suburban / college voters who prize authenticity.
  const tax = Math.abs(move) * 0.04;
  for (const st of game.states) {
    if (st.blocs.length === 0) continue;
    for (const bloc of st.blocs) {
      if (bloc.blocId === "college_white" || bloc.blocId === "suburban_women") {
        addCause(game, bloc, st, `"Flip-flopper" cost on ${action.issueId}`, -favorSign(c) * tax * 0.3);
      }
    }
  }
}

export function applyAction(
  game: GameState,
  action: CampaignAction,
  rng: Rng,
  planMultiplier = 1,
  planBonuses: readonly PlanBonus[] = [],
) {
  // Every action costs exactly one slot from the weekly pool. Invalid or
  // unaffordable actions do not burn a slot; a deliberate GOTV attempt with no
  // field operation still does, because the failed push is itself the result.
  const res = game.resources[action.candidate];
  if (res.actions < 1) return;
  // The player's campaigning is amplified by the difficulty handicap (1.0 for
  // the AI and on hard); it scales persuasion only, not cash/infra/prep.
  const mult = (action.candidate === game.playerCandidate ? (game.playerEdge ?? 1) : 1) * planMultiplier;
  const causeCount = game.causes.length;
  switch (action.type) {
    case "advertise": applyAdvertise(game, action, rng, mult, planMultiplier); break;
    case "rally": applyRally(game, action, rng, mult); break;
    case "surrogate": applySurrogate(game, action, rng, mult); break;
    case "fundraise": applyFundraise(game, action, rng); break;
    case "ground_game": applyGroundGame(game, action); break;
    case "gotv": applyGotv(game, action, mult); break;
    case "oppo_research": applyOppoResearch(game, action, rng, mult); break;
    case "debate_prep": applyDebatePrep(game, action); break;
    case "policy_prep": applyPolicyPrep(game, action); break;
    case "issue_pivot": applyIssuePivot(game, action, mult); break;
  }
  if (game.causes.length === causeCount) return;
  for (let index = causeCount; index < game.causes.length; index++) {
    game.causes[index].actor = action.candidate;
  }
  res.actions -= 1;
  // Only celebrate a combo when the payoff action produced an effect. This
  // keeps an unaffordable ad or a no-op pivot from earning a false recap item.
  for (const bonus of planBonuses) {
    game.causes.push({
      turn: game.turn,
      actor: action.candidate,
      stateId: action.stateId,
      cause: `Plan bonus: ${bonus.name} (+${Math.round((bonus.multiplier - 1) * 100)}%)`,
      marginDelta: 0,
    });
  }
}

export function clamp(x: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, x));
}
