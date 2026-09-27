// The UK game: state, setup, actions, turn loop, and result. Reuses the shared
// multiparty vote model + seats curve (multiparty.ts), the seedable RNG, and the
// PoliticalSystem abstraction. Pure + deterministic: same seed ⇒ same game.

import type { PartyId } from "./system";
import type { CauseEntry, Government, SeatResult, StateContest } from "./types";
import { createRng, hashSeed, type Rng } from "./rng";
import { computeSeatsResult, blocPartyShares, tallyRegion } from "./multiparty";
import { UK_ABSTAINING, PARTY_BY_ID } from "@content/uk/parties";
import { UK_ELECTIONS, type UkElectionData, majorityForElection, type UkMajority } from "@content/uk/elections";
import { leaderFor, type UkLeader } from "@content/uk/leaders";
import { UK_EVENTS, UK_EVENT_CHANCE, headlineFor, pickTarget, type UkEvent } from "@content/uk/events";
import { UK_ELECTION_EVENTS } from "@content/uk/electionEvents";
import { buildUkRegions } from "./ukSetup";
import { planMultipartyAi, type MpDifficulty } from "./multipartyAi";
import {
  buildCauseRecap,
  decayMultipartyTurn,
  finalizeMpCampaignTurn,
  resolvePendingChoiceGate,
} from "./mpTurnHelpers";
import {
  orderedPlan,
  planBonusMultiplier,
  planBonusesForAction,
  type PlanBonus,
} from "./planBonuses";

// Parties a human may lead (GB-wide majors). NI parties + 'oth' are AI/fixed.
export const UK_PLAYABLE: PartyId[] = ["lab", "con", "ld", "ref", "grn", "snp", "pc"];

export interface UkResources {
  funds: number;     // £ millions on hand
  actions: number;   // action slots this week
  maxActions: number;
  momentum: number;  // -100..100
}

// The UK action set mirrors the U.S. one one-for-one (so both games offer the
// same campaign verbs), renamed to British idiom:
//   advertise→broadcast · rally→rally · surrogate→surrogate · fundraise→fundraise
//   ground_game→ground_game (field offices) · gotv→gotv · oppo_research→oppo_research
//   debate_prep→debate_prep · policy_prep→policy_prep (manifesto) · issue_pivot→issue_pivot
// `canvass` is a lightweight, free region shortcut (doorstep campaigning) kept
// alongside the grid; the AI and the region panel both use it.
export type UkActionType =
  | "broadcast"
  | "rally"
  | "surrogate"
  | "fundraise"
  | "ground_game"
  | "gotv"
  | "canvass"
  | "oppo_research"
  | "debate_prep"
  | "policy_prep"
  | "issue_pivot";

export type UkAdMode = "positive" | "contrast" | "issue";

export interface UkAction {
  type: UkActionType;
  party: PartyId;
  regionId?: string;       // region-targeted actions; omitted ⇒ national
  targetParty?: PartyId;   // contrast broadcast / oppo research
  mode?: UkAdMode;         // broadcast mode
  issueId?: string;        // issue broadcast / issue pivot
  spend?: number;          // £M for broadcast
  day?: number;            // 1..7 planner slot (engine applies in queue order)
}

export type UkPhase = "setup" | "campaign" | "result";

export interface UkResult {
  seats: Record<PartyId, number>;
  voteShare: Record<PartyId, number>;
  seatResults: SeatResult[];
  largestParty: PartyId;
  hung: boolean;
  government: Government;
  postMortem: CauseEntry[];
}

export interface UkRecapItem {
  label: string;
  detail: string;
  marginDelta?: number;
}

export interface UkPendingEvent {
  eventId: string;
  // Resolved target party (always the player when pending).
  targetParty: PartyId;
}

export interface UkGameState {
  systemId: "UK";
  seed: number;
  rngState: number;
  turn: number;
  totalTurns: number;
  phase: UkPhase;
  electionId: string;
  label: string;
  tagline: string;
  playerParty: PartyId;
  parties: PartyId[];           // active parties this election
  leaders: Record<PartyId, UkLeader>;
  salience: Record<string, number>;
  regions: StateContest[];
  resources: Record<PartyId, UkResources>;
  causes: CauseEntry[];
  queuedActions: UkAction[];
  abstaining: PartyId[];
  // Rolling campaign-news log (latest first) from fired events.
  news: { turn: number; text: string }[];
  // Election-deck event ids that already fired (they never repeat in a game).
  firedEvents?: string[];
  // Underdog handicap setting (defaults to "normal" = identity for old saves).
  difficulty?: Difficulty;
  // Chamber size for THIS election's boundary set.
  majority?: UkMajority;
  // Week-in-review lines after each turn (US lastRecap parity).
  lastRecap: UkRecapItem[];
  // Player-choice event waiting on a decision (blocks End Week).
  pendingEvent?: UkPendingEvent | null;
  // Player-authored (Campaign Editor) game: casual only, never posts a score.
  custom?: boolean;
  result?: UkResult;
}

/** Winning post for this election's boundary set (650/326 default). */
export function majorityForUk(g: UkGameState): UkMajority {
  return g.majority ?? majorityForElection(g.electionId);
}

// Active parties = the union of parties appearing in any region's result.
function activeParties(election: UkElectionData): PartyId[] {
  const set = new Set<PartyId>();
  for (const r of Object.values(election.regions)) {
    for (const p of Object.keys(r.v)) set.add(p);
    for (const p of Object.keys(r.s)) set.add(p);
  }
  // Stable, sensible order: playable majors first, then the rest.
  const order = [...UK_PLAYABLE, "dup", "sf", "uup", "sdlp", "apni", "oth"];
  return [...set].sort((a, b) => order.indexOf(a) - order.indexOf(b));
}

// The human-playable parties that actually contest a given election (e.g. no
// Reform/Green before they existed) — used to gate the setup party picker.
export function playablePartiesIn(electionId: string): PartyId[] {
  const election = UK_ELECTIONS[electionId];
  if (!election) return [...UK_PLAYABLE];
  const active = new Set(activeParties(election));
  return UK_PLAYABLE.filter((p) => active.has(p));
}

export type Difficulty = "easy" | "normal" | "hard";

// Underdog handicap — see the country engine's COUNTRY_HANDICAP for the full
// rationale. `normal` is the identity element (calibration stays byte-identical);
// `easy` gives a favorable climate + bigger operation + sloppier opponent so a
// skilled human can overturn a landslide; `hard` is a mild headwind.
export interface UkHandicap {
  funds: number; actions: number; persuasion: number; aiPersuasion: number; environment: number;
}
export const UK_HANDICAP: Record<Difficulty, UkHandicap> = {
  easy:   { funds: 6, actions: 2, persuasion: 1.5, aiPersuasion: 0.5, environment: 0.9 },
  normal: { funds: 0, actions: 0, persuasion: 1.0, aiPersuasion: 1.0, environment: 0 },
  // Hard is a campaign-only headwind: no baseline (environment) shift, so a
  // dominant winner stays dominant — only genuinely knife-edge elections tip.
  hard:   { funds: 0, actions: 0, persuasion: 0.85, aiPersuasion: 1.1, environment: 0 },
};

// One-time favorable-climate tilt toward the player, then refresh live support.
function applyUkEnvironment(regions: StateContest[], player: PartyId, env: number) {
  if (!env) return;
  for (const region of regions) {
    for (const bloc of region.blocs) {
      if (!bloc.appeal) continue;
      bloc.appeal[player] = (bloc.appeal[player] ?? 0) + env;
      bloc.support = blocPartyShares(bloc) as typeof bloc.support;
    }
  }
}

export interface NewUkGameOptions {
  seed?: number | string;
  election?: string;
  playerParty?: PartyId;
  totalTurns?: number;
  difficulty?: Difficulty;
}

export function createUkGame(opts: NewUkGameOptions = {}): UkGameState {
  const election = UK_ELECTIONS[opts.election ?? "2024"] ?? UK_ELECTIONS["2024"];
  const seed = typeof opts.seed === "string" ? hashSeed(opts.seed) : (opts.seed ?? Date.now()) >>> 0;
  const parties = activeParties(election);
  // Guard: a party not contesting this election (e.g. Reform in 1997) can't be
  // led. Fall back to the largest playable party that actually stands.
  const playerParty =
    opts.playerParty && parties.includes(opts.playerParty)
      ? opts.playerParty
      : parties.find((p) => UK_PLAYABLE.includes(p)) ?? parties[0];

  const difficulty: Difficulty = opts.difficulty ?? "normal";
  const hc = UK_HANDICAP[difficulty];

  const leaders: Record<PartyId, UkLeader> = {};
  for (const p of parties) leaders[p] = leaderFor(election.id, p, p.toUpperCase());

  const resources: Record<PartyId, UkResources> = {};
  for (const p of parties) {
    const machine = leaders[p].machine;
    const bonusF = p === playerParty ? hc.funds : 0;
    const bonusA = p === playerParty ? hc.actions : 0;
    resources[p] = {
      funds: 4 + machine / 10 + bonusF,
      actions: 5 + Math.round(leaders[p].energy / 40) + bonusA,
      maxActions: 5 + Math.round(leaders[p].energy / 40) + bonusA,
      momentum: 0,
    };
  }

  const regions = buildUkRegions(election);
  applyUkEnvironment(regions, playerParty, hc.environment);

  return {
    systemId: "UK",
    seed,
    rngState: seed,
    turn: 0,
    totalTurns: opts.totalTurns ?? 6,
    phase: "campaign",
    electionId: election.id,
    label: election.label,
    tagline: election.tagline,
    playerParty,
    parties,
    leaders,
    salience: { ...election.salience },
    regions,
    resources,
    causes: [],
    queuedActions: [],
    abstaining: [...UK_ABSTAINING],
    news: [],
    firedEvents: [],
    difficulty,
    majority: { ...(election.majority ?? majorityForElection(election.id)) },
    lastRecap: [],
    pendingEvent: null,
  };
}

// ── Helpers ────────────────────────────────────────────────────────────────
const clamp = (x: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, x));
const findRegion = (g: UkGameState, id?: string) => g.regions.find((r) => r.id === id);

// Diminishing returns on how far a party's appeal has already been pushed in a
// region (mirrors the U.S. saturation): sustained spend can't run away.
function saturate(bloc: StateContest["blocs"][number], party: PartyId, raw: number): number {
  const acc = Math.abs(bloc.campaignAppeal?.[party] ?? 0);
  const room = Math.max(0.15, 1 - acc / 0.8);
  return raw * room;
}

function addAppeal(g: UkGameState, region: StateContest, party: PartyId, cause: string, delta: number) {
  // Difficulty scales campaigning: player effort by `persuasion`, rivals by
  // `aiPersuasion`. Both 1.0 on normal (identity).
  const hc = UK_HANDICAP[g.difficulty ?? "normal"];
  const scaled = delta * (party === g.playerParty ? hc.persuasion : hc.aiPersuasion);
  for (const bloc of region.blocs) {
    if (!bloc.campaignAppeal) bloc.campaignAppeal = {};
    bloc.campaignAppeal[party] = (bloc.campaignAppeal[party] ?? 0) + saturate(bloc, party, scaled);
  }
  g.causes.push({ turn: g.turn, stateId: region.id, cause, marginDelta: scaled });
}

// ── Actions ──────────────────────────────────────────────────────────────
// Regions where a party actually stands (SNP → Scotland, Plaid → Wales): a
// party absent from the baseline isn't on the ballot there.
const standsIn = (g: UkGameState, party: PartyId) =>
  g.regions.filter((r) => r.baselineShare?.[party] !== undefined);

// The strongest rival to `party` in a region (for contrast / oppo targeting).
function topRivalIn(region: StateContest, party: PartyId): PartyId | undefined {
  const { shareByParty } = tallyRegion(region);
  return Object.keys(shareByParty)
    .filter((p) => p !== party && (shareByParty[p] ?? 0) > 0)
    .sort((x, y) => (shareByParty[y] ?? 0) - (shareByParty[x] ?? 0))[0];
}

function applyUkAction(
  g: UkGameState,
  a: UkAction,
  rng: Rng,
  planMultiplier = 1,
  planBonuses: readonly PlanBonus[] = [],
) {
  const res = g.resources[a.party];
  if (res.actions < 1) return;
  const leader = g.leaders[a.party];
  const skill = 0.85 + leader.charisma / 400;
  const compSkill = 0.85 + leader.competence / 400;
  const region = findRegion(g, a.regionId);
  const spend = (need: number) => { if (res.funds < need) return false; res.funds -= need; return true; };
  const causeCount = g.causes.length;

  switch (a.type) {
    case "canvass": {
      if (!region) return;
      res.actions -= 1;
      addAppeal(g, region, a.party, `${leader.name} doorstep campaign in ${region.abbr}`, 0.05 * skill * (0.9 + rng.next() * 0.2));
      break;
    }
    case "ground_game": {
      // Field offices — a paid, harder push than a free canvass.
      if (!region) return;
      if (!spend(1.5)) return;
      res.actions -= 1;
      region.momentum = clamp(region.momentum + 2, -100, 100);
      addAppeal(g, region, a.party, `${leader.name} field operation in ${region.abbr}`, 0.075 * (0.85 + leader.machine / 300));
      break;
    }
    case "gotv": {
      // Get-out-the-vote — turnout push, strongest where you already lead.
      if (!region) return;
      if (!spend(1)) return;
      res.actions -= 1;
      addAppeal(g, region, a.party, `${leader.name} GOTV drive in ${region.abbr}`, 0.06 * (0.85 + leader.energy / 300) * planMultiplier);
      break;
    }
    case "rally": {
      if (!region) return;
      res.actions -= 1;
      region.momentum = clamp(region.momentum + 6, -100, 100);
      res.momentum = clamp(res.momentum + 2, -100, 100);
      addAppeal(g, region, a.party, `${leader.name} rally in ${region.abbr}`, 0.045 * skill);
      break;
    }
    case "surrogate": {
      // A big-name stand-in works a region for a modest fee — no momentum bump.
      if (!region) return;
      if (!spend(0.25)) return;
      res.actions -= 1;
      addAppeal(g, region, a.party, `Surrogate stumps for ${leader.name} in ${region.abbr}`, 0.035 * (0.85 + leader.energy / 300));
      break;
    }
    case "broadcast": {
      // Air war. Regional buy if a region is set, else national across every
      // region the party contests. Cost scales with the £M spend slider.
      const cost = Math.max(0.5, a.spend ?? 1.5);
      if (!spend(cost)) return;
      res.actions -= 1;
      const mode = a.mode ?? "positive";
      const targets = region ? [region] : standsIn(g, a.party);
      // Diminishing returns on spend; national spend spreads thinner per region.
      const power = 0.02 * Math.sqrt(cost / 1.5) * (0.85 + leader.machine / 300) * planMultiplier;
      const per = region ? power : power * 0.8;
      if (mode === "issue" && a.issueId) {
        g.salience[a.issueId] = clamp((g.salience[a.issueId] ?? 0.4) + 0.05 * planMultiplier, 0, 1);
        for (const t of targets) addAppeal(g, t, a.party, `${leader.name} issue broadcast`, per * 0.7);
      } else if (mode === "contrast") {
        const scope = region ? [region] : targets;
        for (const t of scope) {
          const foe = a.targetParty ?? topRivalIn(t, a.party);
          if (foe) addAppeal(g, t, foe, `${leader.name} contrast broadcast`, -per * 0.9);
        }
      } else {
        for (const t of targets) addAppeal(g, t, a.party, `${leader.name} broadcast`, per);
      }
      res.momentum = clamp(res.momentum + 1, -100, 100);
      break;
    }
    case "oppo_research": {
      // Dig dirt, then drop it — depresses the strongest rival, region or nation.
      if (!spend(2)) return;
      res.actions -= 1;
      const scope = region ? [region] : standsIn(g, a.party);
      for (const t of scope) {
        const foe = a.targetParty ?? topRivalIn(t, a.party);
        if (foe) addAppeal(g, t, foe, `${leader.name}'s team releases dossier on ${foe.toUpperCase()}`, -0.05 * compSkill);
      }
      break;
    }
    case "debate_prep": {
      // Sharpen the leader for the TV debates — national lift + momentum.
      res.actions -= 1;
      for (const t of standsIn(g, a.party)) addAppeal(g, t, a.party, `${leader.name} debate performance`, 0.012 * compSkill);
      res.momentum = clamp(res.momentum + 4, -100, 100);
      break;
    }
    case "policy_prep": {
      // Manifesto launch — competence-driven national credibility bump.
      res.actions -= 1;
      for (const t of standsIn(g, a.party)) addAppeal(g, t, a.party, `${leader.name} manifesto launch`, 0.014 * compSkill);
      res.momentum = clamp(res.momentum + 2, -100, 100);
      break;
    }
    case "issue_pivot": {
      // Reframe the campaign onto a chosen issue: raise its salience + a lift.
      res.actions -= 1;
      if (a.issueId) g.salience[a.issueId] = clamp((g.salience[a.issueId] ?? 0.4) + 0.08 * planMultiplier, 0, 1);
      for (const t of standsIn(g, a.party)) addAppeal(g, t, a.party, `${leader.name} pivots the campaign`, 0.008 * skill * planMultiplier);
      break;
    }
    case "fundraise": {
      res.actions -= 1;
      const haul = (1.5 + leader.machine / 50) * (0.85 + rng.next() * 0.3);
      res.funds += haul;
      g.causes.push({ turn: g.turn, cause: `${leader.name} fundraising (+£${haul.toFixed(1)}M)`, marginDelta: 0 });
      break;
    }
  }
  if (g.causes.length > causeCount) {
    for (const bonus of planBonuses) {
      g.causes.push({
        turn: g.turn,
        stateId: a.regionId,
        cause: `Plan bonus: ${bonus.name} (+${Math.round((bonus.multiplier - 1) * 100)}%)`,
        marginDelta: 0,
      });
    }
  }
}

// ── Multiparty AI (difficulty-tiered; shared with the balance harness) ─────
function planAiActions(g: UkGameState, party: PartyId, rng: Rng): UkAction[] {
  const res = g.resources[party];
  const difficulty = (g.difficulty ?? "normal") as MpDifficulty;
  return planMultipartyAi(
    {
      regions: g.regions,
      turn: g.turn,
      totalTurns: g.totalTurns,
      funds: res.funds,
      actions: res.actions,
      majority: majorityForUk(g),
      abstaining: g.abstaining,
      compatible: ukCompatible,
    },
    party,
    difficulty,
    rng,
  ) as UkAction[];
}

function eventById(id: string, electionId: string): UkEvent | undefined {
  const deck = UK_ELECTION_EVENTS[electionId] ?? [];
  return deck.find((e) => e.id === id) ?? UK_EVENTS.find((e) => e.id === id);
}

function topRivalParty(g: UkGameState, party: PartyId): PartyId | undefined {
  const proj = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining);
  return Object.keys(proj.seats)
    .filter((p) => p !== party)
    .sort((a, b) => (proj.seats[b] ?? 0) - (proj.seats[a] ?? 0))[0] as PartyId | undefined;
}

function applyAppealNational(g: UkGameState, target: PartyId, appeal: number, cause: string) {
  for (const region of g.regions) {
    if (region.baselineShare?.[target] === undefined) continue;
    for (const bloc of region.blocs) {
      if (!bloc.campaignAppeal) bloc.campaignAppeal = {};
      bloc.campaignAppeal[target] = (bloc.campaignAppeal[target] ?? 0) + appeal;
    }
  }
  g.causes.push({ turn: g.turn, cause, marginDelta: appeal });
}

// Apply one event's default (non-choice) effects. Fixed party target when pinned.
function applyUkEventEffects(g: UkGameState, ev: UkEvent, target: PartyId) {
  if (ev.appeal) applyAppealNational(g, target, ev.appeal, `${ev.id}: ${target.toUpperCase()}`);
  if (ev.momentum && g.resources[target]) {
    g.resources[target].momentum = clamp(g.resources[target].momentum + ev.momentum, -100, 100);
  }
  const short = PARTY_BY_ID[target]?.shortName ?? target.toUpperCase();
  g.news = [{ turn: g.turn, text: headlineFor(ev, short) }, ...g.news].slice(0, 12);
}

function resolveUkEventTarget(g: UkGameState, ev: UkEvent, rng: Rng): PartyId | undefined {
  const proj = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining);
  return ev.party && g.parties.includes(ev.party)
    ? ev.party
    : pickTarget(ev.role, g.playerParty, proj.largestParty, g.parties, (xs) => rng.pick(xs));
}

// Apply or queue one event. Choice events targeting the player become pending.
function applyUkEvent(g: UkGameState, ev: UkEvent, rng: Rng) {
  const target = resolveUkEventTarget(g, ev, rng);
  if (!target) return;

  if (ev.choices && ev.choices.length > 0 && target === g.playerParty && !g.pendingEvent) {
    g.pendingEvent = { eventId: ev.id, targetParty: target };
    const short = PARTY_BY_ID[target]?.shortName ?? target.toUpperCase();
    g.news = [{ turn: g.turn, text: headlineFor(ev, short) }, ...g.news].slice(0, 12);
    return;
  }
  applyUkEventEffects(g, ev, target);
}

/** Resolve a pending player-choice event. Pure: returns a new game state. */
export function resolveUkPlayerEvent(g: UkGameState, choiceId: string): UkGameState {
  const next = structuredClone(g);
  const pending = next.pendingEvent;
  if (!pending) return next;
  const ev = eventById(pending.eventId, next.electionId);
  next.pendingEvent = null;
  if (!ev?.choices) return next;
  const choice = ev.choices.find((c) => c.id === choiceId) ?? ev.choices[0];
  const target = pending.targetParty;
  if (choice.appeal) applyAppealNational(next, target, choice.appeal, `${ev.id}: ${choice.text}`);
  if (choice.momentum && next.resources[target]) {
    next.resources[target].momentum = clamp(next.resources[target].momentum + choice.momentum, -100, 100);
  }
  if (choice.rivalAppeal) {
    const rival = topRivalParty(next, target);
    if (rival) applyAppealNational(next, rival, choice.rivalAppeal, `${ev.id}: hit on ${rival.toUpperCase()}`);
  }
  next.lastRecap = [
    { label: ev.headline.replace("{party}", PARTY_BY_ID[target]?.shortName ?? target), detail: choice.resultText, marginDelta: choice.appeal },
    ...(next.lastRecap ?? []),
  ].slice(0, 12);
  // A decision that landed on the final week still ends the campaign.
  if (next.turn >= next.totalTurns) {
    next.phase = "result";
    next.result = computeUkResult(next);
  }
  return next;
}

// Fire this week's events: scheduled story beats from the election's named
// deck first (deterministic, once each), then at most one random draw from
// the unscheduled deck events + the generic pool.
function fireUkEvent(g: UkGameState, rng: Rng) {
  if (!g.firedEvents) g.firedEvents = []; // saves predating election decks
  const firedList = g.firedEvents;
  const fired = new Set(firedList);
  const deck = UK_ELECTION_EVENTS[g.electionId] ?? [];

  for (const ev of deck) {
    if (ev.turn === undefined || ev.turn !== g.turn || fired.has(ev.id)) continue;
    applyUkEvent(g, ev, rng);
    fired.add(ev.id);
    firedList.push(ev.id);
  }

  // Skip the random draw if a player decision is already pending this week.
  if (g.pendingEvent) return;

  const pool = [
    ...deck.filter((e) => e.turn === undefined && !fired.has(e.id)),
    ...UK_EVENTS,
  ];
  if (pool.length === 0 || !rng.chance(UK_EVENT_CHANCE)) return;
  const ev = rng.weightedPick(pool, pool.map((e) => e.weight));
  applyUkEvent(g, ev, rng);
  if (deck.some((d) => d.id === ev.id)) firedList.push(ev.id);
}

function buildUkRecap(g: UkGameState, turn: number, seatsBefore: number): UkRecapItem[] {
  const seatsAfter = computeUkResult(g).seats[g.playerParty] ?? 0;
  return buildCauseRecap(
    g.causes,
    turn,
    {
      label: "Projected seats",
      detail: `${PARTY_BY_ID[g.playerParty]?.shortName ?? g.playerParty} ${seatsAfter} (was ${seatsBefore})`,
      marginDelta: seatsAfter - seatsBefore,
    },
  );
}

// ── Turn loop ──────────────────────────────────────────────────────────────
export interface UkAdvanceOptions {
  // Suppress the AI parties (used by the calibration anchor: with nobody
  // campaigning, neutral play must reproduce the real result exactly).
  disableAi?: boolean;
  // Auto-pick the first choice on pending player events (bots / tests).
  autoResolvePlayerEvents?: boolean;
}

export function ukAdvanceTurn(g: UkGameState, opts: UkAdvanceOptions = {}): UkGameState {
  let next: UkGameState = structuredClone(g);
  const gate = resolvePendingChoiceGate(
    next,
    opts,
    (game, choiceId) => resolveUkPlayerEvent(game, choiceId),
    (game) => {
      const ev = game.pendingEvent ? eventById(game.pendingEvent.eventId, game.electionId) : undefined;
      return ev?.choices?.[0]?.id;
    },
  );
  if (gate.blocked) return g;
  next = gate.game;

  // Pending resolution on the final week may have already closed the campaign.
  if (next.phase === "result") return next;
  if (next.turn >= next.totalTurns) {
    next.phase = "result";
    next.result = computeUkResult(next);
    return next;
  }

  const rng = createRng(next.rngState);
  const seatsBefore = computeUkResult(next).seats[next.playerParty] ?? 0;
  const turn = next.turn;

  // 1. Player's queued actions.
  const playerPlan = orderedPlan(next.queuedActions);
  const completedActions: UkAction[] = [];
  for (const a of playerPlan) {
    const bonuses = planBonusesForAction(a, completedActions);
    const causeCount = next.causes.length;
    applyUkAction(next, a, rng, planBonusMultiplier(bonuses), bonuses);
    if (next.causes.length > causeCount) completedActions.push(a);
  }
  next.queuedActions = [];

  // 2. AI for every other active major party.
  if (!opts.disableAi) {
    for (const p of next.parties) {
      if (p === next.playerParty || !UK_PLAYABLE.includes(p)) continue;
      for (const a of planAiActions(next, p, rng)) applyUkAction(next, a, rng);
    }
  }

  // 3. A campaign event may fire, nudging a party nationally. Suppressed under
  // the calibration anchor so neutral play reproduces history exactly.
  if (!opts.disableAi) fireUkEvent(next, rng);

  // 4. Momentum decays; refresh action pools and live support.
  decayMultipartyTurn(next.resources, next.regions, next.parties);

  next.lastRecap = buildUkRecap(next, turn, seatsBefore);
  next.rngState = rng.state();
  return finalizeMpCampaignTurn(next, (game) => computeUkResult(game));
}

// Coalition compatibility for the strongest political red lines. The two main
// rivals do not govern together, and the SNP does not prop up Conservatives.
export function ukCompatible(lead: PartyId, partner: PartyId): boolean {
  const rivals = new Set(["con", "lab"]);
  if (rivals.has(lead) && rivals.has(partner)) return false;
  if ((lead === "con" && partner === "snp") || (lead === "snp" && partner === "con")) return false;
  return true;
}

export function computeUkResult(g: UkGameState): UkResult {
  const r = computeSeatsResult(g.regions, majorityForUk(g), g.abstaining, ukCompatible);
  // The post-mortem shows only the player's own persuasive actions.
  const playerName = g.leaders[g.playerParty]?.name ?? "";
  const postMortem = g.causes
    .filter((c) => playerName.length > 0 && c.marginDelta !== 0 && c.cause.includes(playerName))
    .sort((a, b) => Math.abs(b.marginDelta) - Math.abs(a.marginDelta))
    .slice(0, 8);
  return { ...r, postMortem };
}

// Live national projection during play (for the seat bar / map).
export function projectUk(g: UkGameState): UkResult {
  return computeUkResult(g);
}

// "What-if" projection: apply the player's currently-queued actions to a clone
// (no AI, no events, no turn advance) and project — so the planner can show the
// seat delta this week's plan would buy. Pure: never mutates the live game.
export function projectUkPreview(g: UkGameState): UkResult {
  if (g.queuedActions.length === 0) return computeUkResult(g);
  const clone = structuredClone(g);
  const rng = createRng(clone.rngState);
  const plan = orderedPlan(clone.queuedActions);
  const completed: UkAction[] = [];
  for (const a of plan) {
    const bonuses = planBonusesForAction(a, completed);
    const causeCount = clone.causes.length;
    applyUkAction(clone, a, rng, planBonusMultiplier(bonuses), bonuses);
    if (clone.causes.length > causeCount) completed.push(a);
  }
  for (const region of clone.regions)
    for (const bloc of region.blocs) bloc.support = blocPartyShares(bloc) as typeof bloc.support;
  return computeUkResult(clone);
}
