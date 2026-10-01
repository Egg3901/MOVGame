import type {
  BlocId,
  Candidate,
  CandidateId,
  CandidateTraits,
  EventMode,
  GameState,
  Resources,
  RunningMate,
  StateBloc,
  StateContest,
} from "./types";
import { BLOCS, BLOC_IDS, logit } from "@content/blocs";
import { STATE_SEEDS, type StateSeed } from "@content/states";
import { ISSUES, ISSUE_IDS } from "@content/issues";
import { OPPONENT_OF } from "@content/candidates";
import { resolveRunningMate, defaultRunningMate } from "@content/runningMates";
import { getScenario, type Scenario, type ScenarioTicket } from "@content/scenarios";
import { STAFF_BY_ID, MAX_STAFF } from "@content/staff";

export function sigmoid(x: number): number {
  return 1 / (1 + Math.exp(-x));
}

// Solve a single additive shift so the turnout-weighted Biden share of a state's
// blocs equals the real 2020 target. Bisection — deterministic, no RNG.
function solveStateShift(
  blocs: { size: number; turnout: number; nationalLogit: number }[],
  targetDemShare: number,
): number {
  const weightedShare = (shift: number): number => {
    let num = 0;
    let den = 0;
    for (const b of blocs) {
      const w = b.size * b.turnout;
      num += w * sigmoid(b.nationalLogit + shift);
      den += w;
    }
    return den > 0 ? num / den : 0.5;
  };
  let lo = -10;
  let hi = 10;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    if (weightedShare(mid) < targetDemShare) lo = mid;
    else hi = mid;
  }
  return (lo + hi) / 2;
}

function buildBlocsForState(seed: StateSeed, targetDemShare: number): StateBloc[] {
  // Normalize the (national share × profile multiplier) into shares summing to 1.
  const raw = BLOC_IDS.map((id) => {
    const mult = seed.profile?.[id] ?? 1;
    return { id, share: BLOCS[id].nationalShare * mult };
  });
  const totalShare = raw.reduce((s, r) => s + r.share, 0);

  const prelim = raw.map((r) => {
    const arche = BLOCS[r.id];
    const size = (seed.electorate * r.share) / totalShare;
    return {
      blocId: r.id,
      size,
      turnout: arche.turnoutPropensity,
      nationalLogit: logit(arche.nationalDemShare),
    };
  });

  const shift = solveStateShift(
    prelim.map((p) => ({ size: p.size, turnout: p.turnout, nationalLogit: p.nationalLogit })),
    targetDemShare,
  );

  return prelim.map((p) => {
    const baselineMargin = p.nationalLogit + shift;
    const demSupport = sigmoid(baselineMargin);
    return {
      blocId: p.blocId,
      size: p.size,
      turnoutPropensity: p.turnout,
      baselineMargin,
      support: { dem: demSupport, rep: 1 - demSupport },
      campaignMargin: 0,
      enthusiasm: 1,
    };
  });
}

// Build the contest list. A scenario supplies per-state two-party Dem priors
// (falling back to the 2020 default) and electoral-vote overrides for its
// cycle's apportionment.
export function buildStates(
  priors?: Record<string, number>,
  evOverrides?: Record<string, number>,
): StateContest[] {
  return STATE_SEEDS.map((seed) => {
    const isAggregate = (seed.electorate ?? 0) <= 0 && seed.aggregateOf;
    const demShare = priors?.[seed.id] ?? seed.prior2020DemShare;
    const ev = evOverrides?.[seed.id] ?? seed.ev;
    return {
      id: seed.id,
      name: seed.name,
      abbr: seed.abbr,
      electoralVotes: ev,
      region: seed.region,
      prior2020DemShare: demShare,
      mediaMarketCost: seed.mediaCost,
      battleground: seed.battleground,
      blocs: isAggregate ? [] : buildBlocsForState(seed, demShare),
      groundGame: { dem: 0, rep: 0 },
      momentum: 0,
      aggregateOf: seed.aggregateOf,
    };
  });
}

// Player-side handicap by difficulty: extra weekly action-slots and starting
// cash, so a skilled human can fight an adverse map on easier settings. Applied
// only to the player's ticket; the AI keeps its baseline. Zero on hard — that's
// the pure-challenge setting — and untouched difficulty (calibration, tests)
// resolves to "normal".
export interface PlayerHandicap { actions: number; cash: number; persuasion: number; environment: number; }
const HANDICAP: Record<"easy" | "normal" | "hard", PlayerHandicap> = {
  // `persuasion` multiplies how far the player's own campaigning moves the map.
  // `environment` is a one-time pro-player shift to every bloc's baseline (logit;
  // ~0.04 ≈ 1pt two-party) — a favorable national climate that brings otherwise
  // out-of-reach states into play, so a skilled human can win even an adverse
  // year. Tuned to clear the gauntlet winnability floor on landslides (us-1984)
  // while leaving normal/hard historically calibrated (environment 0). UK/country
  // easy uses environment 0.9 on a multiparty appeal scale; US logits need a
  // larger shift because a 525-EV Reagan map starts ~25pts underwater.
  easy: { actions: 5, cash: 110_000_000, persuasion: 1.55, environment: 0.55 },
  normal: { actions: 1, cash: 25_000_000, persuasion: 1.15, environment: 0 },
  hard: { actions: 0, cash: 0, persuasion: 1.0, environment: 0 },
};

// Tilts every bloc's baseline toward the player by `env` logits — the easy-mode
// "favorable climate" that puts more of the map within reach. No-op when env=0.
function withEnvironment(states: StateContest[], player: CandidateId, env: number): StateContest[] {
  if (!env) return states;
  const sign = player === "dem" ? 1 : -1;
  for (const st of states) for (const bloc of st.blocs) bloc.baselineMargin += sign * env;
  return states;
}

function startingResources(
  candidate: CandidateId,
  vp: RunningMate,
  baseEnergy: number,
  handicap: PlayerHandicap = { actions: 0, cash: 0, persuasion: 1, environment: 0 },
): Resources {
  // Weekly action pool: a base 7 plus half the candidate's energy (on a 0–10
  // scale, rounded up), plus any energetic-VP bonus, plus the player handicap.
  // Spread across a 7-day plan. Energy 55→10, 72→11.
  const maxActions = 7 + Math.ceil(baseEnergy / 20) + (vp.candidateDayBonus ?? 0) + handicap.actions;
  return {
    // The Democratic ticket enters with a cash edge (true of 2020/2016/2024). A
    // fundraiser VP adds a one-time war-chest bump.
    cash: (candidate === "dem" ? 220_000_000 : 180_000_000) + (vp.cashBonus ?? 0) + handicap.cash,
    actions: maxActions,
    maxActions: maxActions,
    staffCapacity: 6,
    nationalMomentum: 0,
    mediaNarrative: 0,
  };
}

// Fold a chosen running mate's bonuses into the ticket: rename the VP, bump
// traits (clamped 0..100), and add bloc favorability. Mutates the cloned cand.
function applyRunningMate(cand: Candidate, vp: RunningMate) {
  cand.runningMate = vp.name;
  if (vp.traitBonuses) {
    for (const [k, v] of Object.entries(vp.traitBonuses)) {
      const key = k as keyof CandidateTraits;
      cand.traits[key] = Math.max(0, Math.min(100, cand.traits[key] + (v ?? 0)));
    }
  }
  if (vp.favorability) {
    for (const [b, v] of Object.entries(vp.favorability)) {
      const key = b as BlocId;
      cand.baseFavorability[key] = (cand.baseFavorability[key] ?? 0) + (v ?? 0);
    }
  }
}

// Build a slot candidate from a scenario ticket. The default (historical) VP
// name is set here; applyRunningMate may overwrite it with the player's pick.
function buildCandidate(slot: CandidateId, t: ScenarioTicket): Candidate {
  return {
    id: slot,
    name: t.name,
    shortName: t.shortName,
    party: t.party,
    color: t.color,
    runningMate: defaultRunningMate(t.runningMates).name,
    traits: { ...t.traits },
    baseTraits: { ...t.traits },
    issuePositions: { ...t.issuePositions },
    baseFavorability: { ...t.baseFavorability },
  };
}

export interface NewGameOptions {
  customScenario?: string;
  seed?: number | string;
  playerCandidate?: CandidateId;
  totalTurns?: number;
  granularity?: "week" | "day";
  // Chosen running mate id for the player's ticket (see content/runningMates).
  runningMate?: string;
  // Election scenario id (see content/scenarios); defaults to "2020".
  scenario?: string;
  // Event source; defaults to "historical".
  eventMode?: EventMode;
  // Difficulty — also sizes the player's resource handicap. Defaults to "normal".
  difficulty?: "easy" | "normal" | "hard";
  // Hired staffer ids for the player's ticket (up to MAX_STAFF; see content/staff).
  staff?: string[];
  // Free replayability modifiers (see GameState.modifiers).
  modifiers?: GameState["modifiers"];
}

// Builds a fresh, fully-initialized game state. Deterministic given the seed.
export function createGame(opts: NewGameOptions = {}): GameState {
  const seedInput = opts.seed ?? Date.now();
  const seed = typeof seedInput === "string"
    ? hashStr(seedInput)
    : seedInput >>> 0;

  // Pick the scenario, build the two slot tickets, and resolve running mates:
  // the player's chosen pick, the AI's historical default.
  const scenario: Scenario = getScenario(opts.scenario);

  // National issue salience — the scenario's year-specific values where given
  // (so COVID isn't an issue in 2012), otherwise the issue's base salience.
  const salience = {} as GameState["salience"];
  for (const id of ISSUE_IDS) salience[id] = scenario.issueSalience?.[id] ?? ISSUES[id].baseSalience;
  const player = opts.playerCandidate ?? "dem";
  const baseHandicap = HANDICAP[opts.difficulty ?? "normal"];
  // Mirror Match: the underdog boost — extra war chest + slots regardless of
  // difficulty (the "play the historical loser with better resources" mode).
  const handicap: PlayerHandicap = opts.modifiers?.mirrorMatch
    ? { ...baseHandicap, cash: baseHandicap.cash + 40_000_000, actions: baseHandicap.actions + 2 }
    : baseHandicap;
  const tickets: Record<CandidateId, ScenarioTicket> = { dem: scenario.dem, rep: scenario.rep };
  const candidates: Record<CandidateId, Candidate> = {
    dem: buildCandidate("dem", scenario.dem),
    rep: buildCandidate("rep", scenario.rep),
  };
  const vps = {
    [player]: resolveRunningMate(tickets[player].runningMates, opts.runningMate),
    [OPPONENT_OF[player]]: defaultRunningMate(tickets[OPPONENT_OF[player]].runningMates),
  } as Record<CandidateId, RunningMate>;
  applyRunningMate(candidates.dem, vps.dem);
  applyRunningMate(candidates.rep, vps.rep);

  // Staff: instant effects fold in at creation (trait bonuses now; the extra
  // action slots below via startingResources); in-flight multipliers are read
  // from game.staff during play. Salaries + loyalty live in the store layer.
  const hires = (opts.staff ?? []).filter((id) => STAFF_BY_ID[id]).slice(0, MAX_STAFF);
  let staffActionBonus = 0;
  for (const id of hires) {
    const def = STAFF_BY_ID[id];
    staffActionBonus += def.effects.maxActions ?? 0;
    if (def.effects.traitBonuses) {
      for (const [k, v] of Object.entries(def.effects.traitBonuses)) {
        const key = k as keyof CandidateTraits;
        candidates[player].traits[key] = Math.max(0, Math.min(100, candidates[player].traits[key] + (v ?? 0)));
      }
      // Staff trait boosts are permanent for the run: fold them into the decay
      // baseline so weekly prep-relaxation doesn't erase the hire.
      candidates[player].baseTraits = { ...candidates[player].traits };
    }
  }

  // What If: flip a chosen contest's prior to a pure tossup (free modifier).
  const priors = { ...(scenario.statePriors ?? {}) };
  if (opts.modifiers?.whatIfState) priors[opts.modifiers.whatIfState] = 0.5;

  // Pandemic Mode: COVID-era dynamics in any year.
  if (opts.modifiers?.pandemic) salience.covid_response = Math.max(salience.covid_response ?? 0, 0.85);

  const resources = {
    dem: startingResources("dem", vps.dem, scenario.dem.traits.energy, player === "dem" ? handicap : undefined),
    rep: startingResources("rep", vps.rep, scenario.rep.traits.energy, player === "rep" ? handicap : undefined),
  };
  resources[player].maxActions += staffActionBonus;
  resources[player].actions += staffActionBonus;

  return {
    seed,
    rngState: seed,
    turn: 0,
    totalTurns: opts.totalTurns ?? 9, // Sept 1 → Nov 3, weekly
    granularity: opts.granularity ?? "week",
    phase: "intel",
    playerCandidate: player,
    scenarioId: scenario.id,
    ...(opts.customScenario ? { customScenario: opts.customScenario } : {}),
    eventMode: opts.eventMode ?? "historical",
    playerEdge: handicap.persuasion,
    locations: {},
    candidates,
    issues: structuredClone(ISSUES),
    salience,
    states: withEnvironment(buildStates(priors, scenario.evOverrides), player, handicap.environment),
    resources,
    pendingEvents: [],
    firedEventIds: [],
    queuedActions: [],
    causes: [],
    lastRecap: [],
    runningMates: { dem: vps.dem.id, rep: vps.rep.id },
    staff: { [player]: hires },
    adSpend: { dem: 0, rep: 0 },
    debateHistory: [],
    modifiers: opts.modifiers,
  };
}

// Exported so daily-challenge detection can recompute the numeric seed a
// string seed produced (the UK/country engines use rng.hashSeed instead).
export function hashStr(s: string): number {
  let h = 2166136261 >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}
