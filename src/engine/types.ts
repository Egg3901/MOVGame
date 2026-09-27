// ─────────────────────────────────────────────────────────────────────────
// CAMPAIGN — core data model (Section 4 of the design).
// These types are the single shared contract between ENGINE, CONTENT and UI.
// The engine is pure: no React, no DOM, no browser globals appear here.
//
// MULTIPARTY NOTE: this game began as a two-party (dem/rep) U.S. simulator and
// that path is kept byte-identical (it's the N=2 special case). The UK
// expansion layers an N-party representation *alongside* it — see the optional
// `appeal` / `seats` / `seatResults` fields below, populated only under a
// multiparty PoliticalSystem. See engine/system.ts and engine/multiparty.ts.
// ─────────────────────────────────────────────────────────────────────────

import type { PartyId } from "./system";

export type IssueId =
  | "economy"
  | "covid_response"
  | "healthcare"
  | "immigration"
  | "race_policing"
  | "climate"
  | "taxes"
  | "law_and_order"
  | "abortion"
  | "trade";

// Demographic bloc archetypes. A clean partition of each state's electorate
// (sizes sum to the electorate) chosen for narrative utility: the classic 2020
// storylines (working-class whites, suburban women, seniors, youth turnout)
// each get their own scoreable bucket. The race/age/geography overlap is a
// deliberate game-model simplification.
export type BlocId =
  | "noncollege_white"
  | "college_white"
  | "suburban_women"
  | "black"
  | "hispanic"
  | "asian_other"
  | "seniors"
  | "youth";

// The two tickets. Player picks one; the other is AI.
export type CandidateId = "dem" | "rep";

// Campaign-event source: scripted real beats for the year, or a random draw
// from a year-agnostic plausible pool.
export type EventMode = "historical" | "plausible";

export type Party = "Democratic" | "Republican";

export interface Issue {
  id: IssueId;
  name: string;
  // National salience 0..1 — how much this issue weighs in the model right now.
  // Mutated by events (a covid spike raises covid salience, etc.).
  baseSalience: number;
  // Short description for tooltips/UI.
  blurb: string;
}

export interface CandidateTraits {
  charisma: number; // 0..100
  energy: number; // was stamina
  debatePrep: number; // was leadership (repurposed)
  intelligence: number; // was integrity (repurposed)
  policyKnowledge: number; // was experience (repurposed)
  debatingSkill: number; // was debating (renamed)
  fundraisingProwess: number; // was fundraising (renamed)
}

export interface Candidate {
  id: CandidateId;
  name: string;
  shortName: string;
  party: Party;
  runningMate: string;
  color: string;
  traits: CandidateTraits;
  // Immutable snapshot of the starting traits. Transient prep buffs (debate /
  // policy prep) relax back toward these each week — so decay targets the
  // candidate's real baseline, not the neutral content default.
  baseTraits?: CandidateTraits;
  // Issue stance, left(-1) ↔ right(+1). Mutable via Issue Pivot action.
  issuePositions: Record<IssueId, number>;
  // Baseline favorability per bloc, -1..+1. Mutated by ads/events.
  baseFavorability: Partial<Record<BlocId, number>>;
}

// A selectable running mate. Picked at setup; its bonuses are folded into the
// ticket's traits / bloc favorability / starting resources at game creation.
export interface RunningMate {
  id: string;
  name: string;
  // Which presidential nominee this VP runs with.
  ticket: CandidateId;
  // One-line newsroom-style description of the pick's strategic value.
  blurb: string;
  // The real 2020 running mate (used as each side's default).
  historical?: boolean;
  // Added to the ticket's candidate traits (clamped 0..100).
  traitBonuses?: Partial<CandidateTraits>;
  // Added to the ticket's baseline bloc favorability.
  favorability?: Partial<Record<BlocId, number>>;
  // One-time starting-cash bonus (fundraiser VPs).
  cashBonus?: number;
  // Extra candidate-days each week (energetic surrogates).
  candidateDayBonus?: number;
}

// A demographic bloc *inside a particular state*. The heart of the scoring model.
export interface StateBloc {
  blocId: BlocId;
  // Number of eligible voters of this bloc in the state.
  size: number;
  // 0..1: how strongly this bloc turns out at baseline.
  turnoutPropensity: number;
  // Biden-minus-Trump baseline appeal margin (logit space). Solved at game
  // init so the turnout-weighted state aggregate reproduces the real 2020
  // two-party result. Campaign effects add to this margin during play.
  baselineMargin: number;
  // Mutable per-turn state:
  // Two-party support for each candidate, 0..1, sums to 1. (Multiparty systems
  // store an N-party share here keyed by PartyId — Record<CandidateId> is a
  // Record<string>, so it widens cleanly.)
  support: Record<CandidateId, number>;
  // Accumulated campaign margin shift (Biden − Trump), layered onto baseline.
  campaignMargin: number;
  // Enthusiasm multiplier on turnout, around 1.0.
  enthusiasm: number;

  // ── Multiparty (UK) representation — present only under an N-party system ──
  // Baseline appeal per party in logit space (the N-party analog of
  // baselineMargin). softmax over (appeal + campaignAppeal) gives vote shares.
  appeal?: Record<PartyId, number>;
  // Accumulated campaign appeal shift per party (the analog of campaignMargin).
  campaignAppeal?: Record<PartyId, number>;
}

export type Region =
  | "Northeast"
  | "South"
  | "Midwest"
  | "West"
  | "Swing";

// A contest = an awardable electoral unit. Most are whole states; ME/NE
// at-large and congressional districts are modeled as separate contests.
export interface StateContest {
  id: string; // e.g. "PA", "ME-2", "NE-AL"
  name: string;
  abbr: string;
  electoralVotes: number;
  region: Region;
  // Real 2020 two-party Biden share, used only to solve baselineMargin.
  prior2020DemShare: number;
  // Cost multiplier for ads in this media market (1.0 = national average).
  mediaMarketCost: number;
  // True for the seven genuinely swingable contests (UI emphasis + AI focus).
  battleground: boolean;
  blocs: StateBloc[];
  // Built-up ground-game strength per ticket (0..1), persistent + sticky.
  groundGame: Record<CandidateId, number>;
  // Per-state momentum, -100..+100.
  momentum: number;
  // ME-AL / NE-AL at-large units award their EVs to whoever wins the combined
  // vote of these district contests. Such aggregate units carry no blocs.
  aggregateOf?: string[];

  // ── Multiparty (UK) seat allocation — present only under an N-party system ──
  // This contest is a region with a fixed pool of FPTP seats. Seats are awarded
  // by a swing-elasticity curve on a real baseline (engine/multiparty.ts):
  // neutral play reproduces `baselineSeats` exactly, and as a party's vote share
  // swings off `baselineShare` its seats move, amplified by `seatElasticity`
  // (FPTP's disproportionality), then largest-remainder rounded to sum to `seats`.
  seats?: number;
  // The region's real seat split for this election — the neutral baseline.
  baselineSeats?: Record<PartyId, number>;
  // The region's real vote share for this election — the swing reference point.
  baselineShare?: Record<PartyId, number>;
  // How hard seats swing per point of vote-share change (FPTP amplification,
  // ~2.5). Higher = more disproportional. Omitted ⇒ system default.
  seatElasticity?: number;
}

// ── Resources (player + AI each hold one) ────────────────────────────────
export interface Resources {
  cash: number; // dollars on hand
  actions: number; // action points for the principal this turn
  maxActions: number;
  staffCapacity: number; // limits simultaneous ground-game states
  nationalMomentum: number; // -100..+100
  mediaNarrative: number; // -100 (hostile) .. +100 (favorable)
}

// ── Actions (Section 6) ───────────────────────────────────────────────────
export type ActionType =
  | "advertise" // positive / contrast / issue
  | "rally" // candidate stop
  | "surrogate" // VP / surrogate stop
  | "fundraise"
  | "ground_game"
  | "gotv"
  | "oppo_research"
  | "debate_prep"
  | "policy_prep"
  | "issue_pivot";

export type AdMode = "positive" | "contrast" | "issue";

export interface CampaignAction {
  type: ActionType;
  candidate: CandidateId;
  stateId?: string;
  blocId?: BlocId;
  issueId?: IssueId;
  adMode?: AdMode;
  // Dollars committed (ads, fundraising venues, etc.).
  spend?: number;
  // Which day of the 7-day plan this action sits on (1–7). Organizational; the
  // pool/3-per-day caps are enforced in the UI, and the engine resolves the
  // week in day order. Legacy `days` is no longer used for cost.
  day?: number;
  days?: number;
  // For issue_pivot: new position -1..+1.
  newPosition?: number;
}

// ── Events (Section 7) — authored as DATA, applied by the engine ──────────
export interface BlocDelta {
  blocId: BlocId;
  // Direct shift to campaignMargin (Biden − Trump) for this bloc, nationwide.
  margin?: number;
  // Enthusiasm/turnout shift.
  enthusiasm?: number;
}

export interface EventEffect {
  // Margin deltas keyed by bloc, applied to the *answering* candidate's favor.
  // Positive favors the candidate who is the event's subject.
  blocDeltas?: BlocDelta[];
  salienceDeltas?: Partial<Record<IssueId, number>>;
  momentum?: number; // national momentum delta for subject
  cash?: number;
  narrative?: number; // media narrative delta
  favorability?: Partial<Record<BlocId, number>>;
  // Shifts the answering candidate's OWN issue positions (a policy promise —
  // the price of an endorsement). Applied directly, clamped to [-1, 1].
  positionShifts?: Partial<Record<IssueId, number>>;
}

export interface EventChoice {
  id: string;
  text: string;
  // Asymmetric events: a choice tagged with a side is only offered to that
  // ticket (e.g. a scandal's subject handles it; the opponent exploits it).
  // Untagged choices are shown to both (symmetric events like debates).
  side?: CandidateId;
  // Optional gate (e.g. requires a trait threshold or prior action).
  requires?: { trait?: keyof CandidateTraits; min?: number };
  effects: EventEffect;
  resultText: string;
}

export type EventTrigger =
  | { kind: "scheduled"; turn: number } // fires on a specific turn
  | { kind: "stochastic"; baseWeight: number }; // drawn from the random pool

export interface GameEvent {
  id: string;
  title: string;
  prompt: string;
  // Which candidate the event is "about" (whose choices we resolve). For
  // scripted shared events (debates) this is the viewing/player perspective;
  // the engine resolves the player's choice and lets the AI auto-pick.
  // Whose beat this is: a specific ticket (dem/rep, for scandals), "both"
  // (debates), or the legacy perspective tags. Informational/flavor only.
  subject: CandidateId | "both" | "player" | "opponent";
  trigger: EventTrigger;
  choices: EventChoice[];
  // Optional gating on game state for stochastic events.
  gate?: {
    minTurn?: number;
    maxTurn?: number;
    // Only fire if this candidate's stamina-driven gaffe risk is high, etc.
    requiresLowStamina?: boolean;
  };
  // Debate flag — affected by debate_prep.
  isDebate?: boolean;
  oncePerGame?: boolean;
}

// A queued event instance awaiting resolution this turn.
export interface PendingEvent {
  eventId: string;
  forCandidate: CandidateId;
}

// ── Causality log — every poll move traces to a cause (Pillar C) ──────────
export interface CauseEntry {
  turn: number;
  actor?: CandidateId;
  stateId?: string;
  blocId?: BlocId;
  cause: string; // human-readable, e.g. "Positive ads in PA"
  marginDelta: number; // Biden − Trump contribution
}

// ── Top-level game state ──────────────────────────────────────────────────
export type GamePhase = "setup" | "intel" | "events" | "allocate" | "result";

export interface TurnRecapItem {
  label: string;
  detail: string;
  marginDelta?: number;
  stateId?: string;
}

// One sample of the national picture at the end of a week, accumulated across
// the campaign so the UI can chart polling/EV trends. Recorded by the engine
// (beginGame seeds turn 0; advanceTurn appends each completed week), so it
// rewinds correctly with undo and survives into the final result.
export interface TurnPoint {
  turn: number; // 0 = opening baseline, then 1..totalTurns
  demPoll: number; // national poll average, Dem two-party share (0..1)
  demEV: number;
  repEV: number;
  tossupEV: number;
  demMomentum: number;
  repMomentum: number;
  demCash: number;
  repCash: number;
  // True two-party Dem share per vote-bearing state this week — powers the
  // per-state polling trend sparklines in the State Detail panel.
  demShareByState: Record<string, number>;
}

export interface GameState {
  seed: number;
  rngState: number;
  turn: number; // 0-based; 0 = first playable week
  totalTurns: number;
  granularity: "week" | "day";
  phase: GamePhase;
  playerCandidate: CandidateId;
  // Election scenario id (see content/scenarios); absent on pre-scenario saves.
  scenarioId?: string;
  // How campaign events are drawn (see content/events). Absent → "historical".
  eventMode?: EventMode;
  // Difficulty handicap: multiplies how far the *player's* own campaigning moves
  // the map (1.0 = no edge). Set at creation; absent on old saves → treat as 1.
  playerEdge?: number;
  // Each candidate's current campaign stop (state id of their last rally), for
  // the map markers. Absent until a candidate has rallied somewhere.
  locations?: Partial<Record<CandidateId, string>>;
  candidates: Record<CandidateId, Candidate>;
  issues: Record<IssueId, Issue>;
  // Live national salience, starts from issue.baseSalience, shifts via events.
  salience: Record<IssueId, number>;
  states: StateContest[];
  resources: Record<CandidateId, Resources>;
  pendingEvents: PendingEvent[];
  firedEventIds: string[];
  // Actions queued this turn by the player (resolved on endTurn).
  queuedActions: CampaignAction[];
  causes: CauseEntry[];
  lastRecap: TurnRecapItem[];
  // Chosen running mate id per ticket (drives the applied bonuses + UI). Optional
  // for backward compatibility with saves predating VP selection.
  runningMates?: Record<CandidateId, string>;
  // Hired staffer ids per ticket (see content/staff). Passive bonuses applied at
  // creation + in actions; weekly salaries and loyalty run in the store layer.
  staff?: Partial<Record<CandidateId, string[]>>;
  // Cumulative ad dollars spent per ticket (drives the Grassroots achievement).
  adSpend?: Partial<Record<CandidateId, number>>;
  // Cumulative fundraising hauls per ticket (drives the Money Machine achievement).
  fundsRaised?: Partial<Record<CandidateId, number>>;
  // Every resolved debate, in order (drives the Debate Dominator achievement).
  debateHistory?: DebateResult[];
  // Optional scenario modifiers chosen at setup (all free features).
  modifiers?: {
    whatIfState?: string;   // flip this contest's prior to a pure tossup
    mirrorMatch?: boolean;  // underdog boost: extra cash + action slots
    pandemic?: boolean;     // COVID-era salience in any year
  };
  // Per-week trend log (national poll, EV split, momentum, cash). Optional for
  // backward compatibility with saves predating the stats screen.
  timeline?: TurnPoint[];
  // Set once the game is decided.
  result?: GameResult;
}

// The head-to-head outcome of a debate: both sides scored 0..100, a winner,
// and the momentum swing applied (a lopsided loss is a "meltdown").
export interface DebateResult {
  eventId: string;
  title: string;
  scores: Record<CandidateId, number>;
  choiceText: Record<CandidateId, string>;
  resultText: Record<CandidateId, string>;
  winner: CandidateId | "tie";
  margin: number;
  meltdown: boolean;
  momentumSwing: number; // +winner / −loser
}

export interface StateResult {
  stateId: string;
  electoralVotes: number;
  demShare: number; // two-party
  winner: CandidateId;
  margin: number; // winner's two-party margin in points
}

// ── Multiparty (UK) result shapes ────────────────────────────────────────
// One region's seat split under the regional seats curve.
export interface SeatResult {
  contestId: string;
  name: string;
  totalSeats: number;
  seatsByParty: Record<PartyId, number>;
  voteShare: Record<PartyId, number>;
  winner: PartyId; // largest party in the region
}

// Who can form a government after the seats fall, the UK analog of the U.S.
// 269–269 contingent-election ending.
export type Government =
  | { kind: "majority"; party: PartyId; seats: number }
  | { kind: "minority"; party: PartyId; seats: number }
  | { kind: "coalition"; parties: PartyId[]; seats: number }
  | { kind: "confidence_supply"; lead: PartyId; partner: PartyId; seats: number }
  | { kind: "hung"; largest: PartyId };

export interface GameResult {
  electoralVotes: Record<CandidateId, number>;
  winner: CandidateId | "tie";
  popularVote: Record<CandidateId, number>; // raw vote totals
  popularShare: Record<CandidateId, number>; // two-party share
  stateResults: StateResult[];
  // Post-mortem: biggest swings the player caused.
  postMortem: CauseEntry[];

  // ── Multiparty (UK) — present only under an N-party system ──
  // Total seats won per party (the analog of electoralVotes).
  seats?: Record<PartyId, number>;
  // National vote share per party.
  voteShare?: Record<PartyId, number>;
  // Per-region seat splits.
  seatResults?: SeatResult[];
  // Largest party by seats.
  largestParty?: PartyId;
  // True when no party reached the (effective) majority threshold.
  hung?: boolean;
  // The government-formation call.
  government?: Government;
}
