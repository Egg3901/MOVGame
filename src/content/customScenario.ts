// ─────────────────────────────────────────────────────────────────────────
// CUSTOM SCENARIO — a player-authored election the US engine can run exactly
// like a registry scenario. This module owns the versioned on-disk shape, a
// hand-rolled validator used at every save/load/import boundary, and the
// adapter that turns a CustomScenario into the engine's Scenario record.
// Custom scenarios are always FREE to play (no entitlement gate).
// ─────────────────────────────────────────────────────────────────────────
import type {
  BlocId,
  CandidateTraits,
  EventMode,
  IssueId,
  Party,
  RunningMate,
} from "@engine/types";
import { ISSUE_IDS } from "@content/issues";
import { BLOC_IDS } from "@content/blocs";
import { SCENARIOS, type Scenario, type ScenarioTicket } from "@content/scenarios";
import { blocPartyShares } from "@engine/multiparty";
import {
  createUkGame,
  playablePartiesIn as ukPlayablePartiesIn,
  type UkGameState,
} from "@engine/ukGame";
import {
  createCountryGame,
  playablePartiesIn as countryPlayablePartiesIn,
  type CountryBundle,
  type CountryGameState,
  type CountryLeader,
} from "@engine/countryGame";
import { UK_ELECTIONS } from "@content/uk/elections";
import { PARTY_BY_ID } from "@content/uk/parties";
import { UK_LEADERS } from "@content/uk/leaders";
import { COUNTRIES } from "@content/countries";

// Bump when the shape changes in a breaking way. Version-1 docs still validate:
// they are migrated to version 2 as the "us" engine (see migrateToV2). Anything
// this app does not know how to read is rejected loudly, not silently.
export const CUSTOM_SCENARIO_VERSION = 2;

export const TRAIT_KEYS: (keyof CandidateTraits)[] = [
  "charisma",
  "energy",
  "debatePrep",
  "intelligence",
  "policyKnowledge",
  "debatingSkill",
  "fundraisingProwess",
];

const PARTIES: Party[] = ["Democratic", "Republican"];
const EVENT_MODES: EventMode[] = ["historical", "plausible"];

export interface CustomTicketInput {
  name: string;
  shortName: string;
  party: Party;
  color: string; // hex, e.g. "#2563eb"
  traits: CandidateTraits; // each 0..100
  issuePositions: Record<IssueId, number>; // each -1..1
  baseFavorability: Partial<Record<BlocId, number>>; // each -1..1
}

// Which game shell a custom scenario runs on. "us" is the original two-ticket
// race; "uk" and "country" are the multiparty shells. A version-1 doc has no
// engine field and is migrated to "us".
export type CustomEngine = "us" | "uk" | "country";

// Leader/party traits used by the multiparty engines. 0..100, same as the UK
// and country leader records.
export const MP_TRAIT_KEYS = ["charisma", "energy", "competence", "machine"] as const;
export type MpTraitKey = (typeof MP_TRAIT_KEYS)[number];

// One customizable party in a UK/country race. The player picks an existing
// election to build on (its geography, blocs, and calibrated priors are reused)
// and then tunes each party's label, leader, and a base-support nudge.
export interface CustomPartyInput {
  partyId: string;    // must be a playable party contesting the base election
  name: string;       // long name (country shell shows this; UK keeps its own)
  shortName: string;
  color: string;      // hex, e.g. "#e4003b"
  leaderName: string;
  charisma: number;   // 0..100
  energy: number;     // 0..100
  competence: number; // 0..100
  machine: number;    // 0..100
  // Base-support tilt for this party, -1..1. 0 reproduces the historical
  // result; positive lifts the party's starting appeal in every region it
  // contests, negative depresses it. This is a prior, not a live campaign move.
  baseSupport: number;
}

export interface CustomMultipartyConfig {
  countryId?: string;   // required for engine "country" (a COUNTRIES key)
  baseElection: string; // election id in the chosen engine's set
  parties: CustomPartyInput[];
}

export interface CustomScenario {
  version: number;
  engine: CustomEngine;
  id: string; // "custom-..."
  label: string; // "1968 · My Race v. Their Race"
  tagline: string; // one-line setup framing
  year: number;
  createdAt: number;
  updatedAt: number;
  // ── US slot ──────────────────────────────────────────────────────────────
  // Always present so version-1 docs load unchanged and the US adapter has a
  // stable shape. Ignored when engine is "uk" or "country".
  dem: CustomTicketInput; // fills the engine's "dem" slot
  rep: CustomTicketInput; // fills the engine's "rep" slot
  // Optional event-deck selection; defaults to "historical" at play time.
  eventMode?: EventMode;
  // ── Multiparty slot (present when engine is "uk" or "country") ────────────
  mp?: CustomMultipartyConfig;
}

export type ValidationResult =
  | { ok: true; value: CustomScenario }
  | { ok: false; errors: string[] };

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function isFiniteNumber(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v);
}

const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

// Fresh, valid defaults so the editor form always starts from a playable state.
export function makeDefaultTicket(side: "dem" | "rep"): CustomTicketInput {
  const dem = side === "dem";
  return {
    name: dem ? "Your Candidate" : "The Opponent",
    shortName: dem ? "Yours" : "Them",
    party: dem ? "Democratic" : "Republican",
    color: dem ? "#2563eb" : "#dc2626",
    traits: {
      charisma: 60,
      energy: 60,
      debatePrep: 60,
      intelligence: 60,
      policyKnowledge: 60,
      debatingSkill: 60,
      fundraisingProwess: 60,
    },
    issuePositions: Object.fromEntries(
      ISSUE_IDS.map((id) => [id, dem ? -0.3 : 0.3]),
    ) as Record<IssueId, number>,
    baseFavorability: {},
  };
}

export function makeDefaultCustomScenario(): CustomScenario {
  const now = Date.now();
  return {
    version: CUSTOM_SCENARIO_VERSION,
    engine: "us",
    id: newCustomId(),
    label: "My Custom Race",
    tagline: "A campaign of your own making. Ninety days to find 270.",
    year: 2024,
    createdAt: now,
    updatedAt: now,
    dem: makeDefaultTicket("dem"),
    rep: makeDefaultTicket("rep"),
    eventMode: "historical",
  };
}

// ── Multiparty defaults (UK / country editor) ──────────────────────────────
// The list of parties a player may customize for a base election: the human
// playable majors that actually contest it (no NI micro-parties, no parties
// that did not exist yet). Falls back to a small generic pair if the election
// or country is unknown, so the editor never renders an empty form.
function defaultUkParties(electionId: string): CustomPartyInput[] {
  const leaders = UK_LEADERS[electionId] ?? {};
  return ukPlayablePartiesIn(electionId).map((pid) => {
    const def = PARTY_BY_ID[pid];
    const ld = leaders[pid];
    return {
      partyId: pid,
      name: def?.name ?? pid.toUpperCase(),
      shortName: def?.shortName ?? pid.toUpperCase(),
      color: def?.color ?? "#9aa0a6",
      leaderName: ld?.name ?? `${def?.shortName ?? pid.toUpperCase()} leader`,
      charisma: ld?.charisma ?? 55,
      energy: ld?.energy ?? 60,
      competence: ld?.competence ?? 60,
      machine: ld?.machine ?? 55,
      baseSupport: 0,
    };
  });
}

function defaultCountryParties(country: CountryBundle, electionId: string): CustomPartyInput[] {
  const leaders = country.leaders[electionId] ?? {};
  return countryPlayablePartiesIn(country, electionId).map((pid) => {
    const def = country.system.parties.find((p) => p.id === pid);
    const ld = leaders[pid];
    return {
      partyId: pid,
      name: def?.name ?? pid.toUpperCase(),
      shortName: def?.shortName ?? pid.toUpperCase(),
      color: def?.color ?? "#9aa0a6",
      leaderName: ld?.name ?? `${def?.shortName ?? pid.toUpperCase()} leader`,
      charisma: ld?.charisma ?? 55,
      energy: ld?.energy ?? 60,
      competence: ld?.competence ?? 60,
      machine: ld?.machine ?? 55,
      baseSupport: 0,
    };
  });
}

// Newest election id in a set (matches createCountryGame's default: integer-like
// keys sort ascending regardless of insertion order, so pick by year).
function newestElection(ids: string[], yearOf: (id: string) => number): string {
  return [...ids].sort((a, b) => yearOf(b) - yearOf(a))[0];
}

// Build a fresh, valid multiparty custom scenario for the editor. `countryId`
// is required for engine "country"; ignored for "uk".
export function makeDefaultMultiparty(engine: "uk" | "country", countryId?: string): CustomScenario {
  const now = Date.now();
  const base: CustomScenario = {
    version: CUSTOM_SCENARIO_VERSION,
    engine,
    id: newCustomId(),
    label: engine === "uk" ? "My UK Election" : "My Election",
    tagline: "Your parties, your leaders. Build the campaign and fight it out.",
    year: 2024,
    createdAt: now,
    updatedAt: now,
    // Unused US slot kept valid so the shared shape always round-trips.
    dem: makeDefaultTicket("dem"),
    rep: makeDefaultTicket("rep"),
  };

  if (engine === "uk") {
    const election = newestElection(Object.keys(UK_ELECTIONS), (id) => UK_ELECTIONS[id].year);
    base.year = UK_ELECTIONS[election]?.year ?? 2024;
    base.mp = { baseElection: election, parties: defaultUkParties(election) };
    return base;
  }

  const country = (countryId && COUNTRIES[countryId]) || Object.values(COUNTRIES)[0];
  const election = newestElection(Object.keys(country.elections), (id) => country.elections[id].year);
  base.year = country.elections[election]?.year ?? 2024;
  base.mp = { countryId: country.id, baseElection: election, parties: defaultCountryParties(country, election) };
  return base;
}

// Re-seed a config's party list when the base election (or country) changes, so
// the editor always shows the parties that contest the newly chosen race.
export function multipartyPartiesFor(engine: "uk" | "country", countryId: string | undefined, electionId: string): CustomPartyInput[] {
  if (engine === "uk") return defaultUkParties(electionId);
  const country = (countryId && COUNTRIES[countryId]) || Object.values(COUNTRIES)[0];
  return defaultCountryParties(country, electionId);
}

export function newCustomId(): string {
  const rand =
    typeof crypto !== "undefined" && "randomUUID" in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `custom-${rand}`;
}

export function isCustomScenarioId(id: string | undefined): boolean {
  return !!id && id.startsWith("custom-");
}

// The registry scenarioId of the base election a custom scenario builds on, or
// null when the base is free-to-everyone. US customs run on the generic map
// (always free), so they return null. UK and country customs inherit the
// entitlement of the real election they reuse: a custom built on "uk-1997"
// needs the same pack "uk-1997" needs. Shared by the editor picker (locks) and
// the launch gate so an imported JSON cannot bypass the paywall.
export function baseScenarioId(
  engine: CustomEngine,
  countryId: string | undefined,
  baseElection: string | undefined,
): string | null {
  if (engine === "us" || !baseElection) return null;
  if (engine === "uk") return `uk-${baseElection}`;
  if (!countryId) return null;
  return `${countryId.toLowerCase()}-${baseElection}`;
}

export function baseScenarioIdForCustom(cs: CustomScenario): string | null {
  return baseScenarioId(cs.engine, cs.mp?.countryId, cs.mp?.baseElection);
}

function validateTicket(raw: unknown, side: "dem" | "rep", errors: string[]): CustomTicketInput | null {
  const where = side === "dem" ? "your candidate" : "the opponent";
  if (!isObject(raw)) {
    errors.push(`${where}: missing candidate data.`);
    return null;
  }
  const name = raw.name;
  const shortName = raw.shortName;
  if (typeof name !== "string" || name.trim() === "") errors.push(`${where}: name is required.`);
  if (typeof name === "string" && name.length > 40) errors.push(`${where}: name is too long (40 characters max).`);
  if (typeof shortName !== "string" || shortName.trim() === "") errors.push(`${where}: short name is required.`);
  if (typeof shortName === "string" && shortName.length > 16) errors.push(`${where}: short name is too long (16 characters max).`);
  if (!PARTIES.includes(raw.party as Party)) errors.push(`${where}: party must be Democratic or Republican.`);
  if (typeof raw.color !== "string" || !HEX_COLOR.test(raw.color)) errors.push(`${where}: color must be a hex value like #2563eb.`);

  const traits = {} as CandidateTraits;
  if (!isObject(raw.traits)) {
    errors.push(`${where}: traits are missing.`);
  } else {
    for (const key of TRAIT_KEYS) {
      const v = (raw.traits as Record<string, unknown>)[key];
      if (!isFiniteNumber(v)) {
        errors.push(`${where}: trait "${key}" must be a number.`);
      } else if (v < 0 || v > 100) {
        errors.push(`${where}: trait "${key}" must be between 0 and 100.`);
      } else {
        traits[key] = v;
      }
    }
  }

  const issuePositions = {} as Record<IssueId, number>;
  if (!isObject(raw.issuePositions)) {
    errors.push(`${where}: issue positions are missing.`);
  } else {
    for (const id of ISSUE_IDS) {
      const v = (raw.issuePositions as Record<string, unknown>)[id];
      if (!isFiniteNumber(v)) {
        errors.push(`${where}: position on "${id}" must be a number.`);
      } else if (v < -1 || v > 1) {
        errors.push(`${where}: position on "${id}" must be between -1 and 1.`);
      } else {
        issuePositions[id] = v;
      }
    }
  }

  const baseFavorability: Partial<Record<BlocId, number>> = {};
  if (raw.baseFavorability !== undefined) {
    if (!isObject(raw.baseFavorability)) {
      errors.push(`${where}: favorability data is malformed.`);
    } else {
      for (const [k, v] of Object.entries(raw.baseFavorability)) {
        if (!BLOC_IDS.includes(k as BlocId)) {
          errors.push(`${where}: unknown voter bloc "${k}".`);
        } else if (!isFiniteNumber(v)) {
          errors.push(`${where}: favorability for "${k}" must be a number.`);
        } else if (v < -1 || v > 1) {
          errors.push(`${where}: favorability for "${k}" must be between -1 and 1.`);
        } else if (v !== 0) {
          baseFavorability[k as BlocId] = v;
        }
      }
    }
  }

  if (errors.length > 0) return null;
  return {
    name: (name as string).trim(),
    shortName: (shortName as string).trim(),
    party: raw.party as Party,
    color: raw.color as string,
    traits,
    issuePositions,
    baseFavorability,
  };
}

const ENGINES: CustomEngine[] = ["us", "uk", "country"];

// Migration: a version-1 doc has no `engine` field. Read it as the US engine
// (its only shape) and stamp it version 2. Non-breaking: every version-1 field
// keeps its meaning. Only version-1 is migrated; other versions fail loudly.
function migrateToV2(raw: Record<string, unknown>): Record<string, unknown> {
  if (raw.version === 1 || raw.engine === undefined) {
    return { ...raw, version: 2, engine: raw.engine ?? "us" };
  }
  return raw;
}

// Validate a UK/country party list. Rejects unknown or duplicate parties, bad
// labels/colors, out-of-range traits, and out-of-range base support.
function validateMultipartyConfig(
  engine: "uk" | "country",
  raw: unknown,
  errors: string[],
): CustomMultipartyConfig | null {
  if (!isObject(raw)) {
    errors.push("Multiparty setup is missing.");
    return null;
  }

  let country: CountryBundle | undefined;
  if (engine === "country") {
    if (typeof raw.countryId !== "string" || !COUNTRIES[raw.countryId]) {
      errors.push("Pick a country to build on.");
    } else {
      country = COUNTRIES[raw.countryId];
    }
  }

  const baseElection = raw.baseElection;
  let allowed: string[] = [];
  if (typeof baseElection !== "string") {
    errors.push("Pick a base election to build on.");
  } else if (engine === "uk") {
    if (!UK_ELECTIONS[baseElection]) errors.push(`Unknown UK election "${baseElection}".`);
    else allowed = ukPlayablePartiesIn(baseElection);
  } else if (country) {
    if (!country.elections[baseElection]) errors.push(`Unknown election "${baseElection}" for ${country.label}.`);
    else allowed = countryPlayablePartiesIn(country, baseElection);
  }

  const partiesRaw = raw.parties;
  const parties: CustomPartyInput[] = [];
  if (!Array.isArray(partiesRaw)) {
    errors.push("Party list is missing.");
  } else if (partiesRaw.length < 2) {
    errors.push("A race needs at least two parties.");
  } else {
    const seen = new Set<string>();
    partiesRaw.forEach((p, i) => {
      const where = `party ${i + 1}`;
      if (!isObject(p)) { errors.push(`${where}: missing data.`); return; }
      const pid = p.partyId;
      if (typeof pid !== "string" || pid.trim() === "") {
        errors.push(`${where}: needs a party id.`);
      } else if (allowed.length > 0 && !allowed.includes(pid)) {
        errors.push(`${where}: "${pid}" does not contest this election.`);
      } else if (seen.has(pid)) {
        errors.push(`${where}: "${pid}" is listed twice.`);
      } else {
        seen.add(pid);
      }
      if (typeof p.name !== "string" || p.name.trim() === "") errors.push(`${where}: name is required.`);
      if (typeof p.name === "string" && p.name.length > 48) errors.push(`${where}: name is too long (48 characters max).`);
      if (typeof p.shortName !== "string" || p.shortName.trim() === "") errors.push(`${where}: short name is required.`);
      if (typeof p.shortName === "string" && p.shortName.length > 16) errors.push(`${where}: short name is too long (16 characters max).`);
      if (typeof p.color !== "string" || !HEX_COLOR.test(p.color)) errors.push(`${where}: color must be a hex value like #e4003b.`);
      if (typeof p.leaderName !== "string" || p.leaderName.trim() === "") errors.push(`${where}: leader name is required.`);
      if (typeof p.leaderName === "string" && p.leaderName.length > 48) errors.push(`${where}: leader name is too long (48 characters max).`);
      for (const key of MP_TRAIT_KEYS) {
        const v = (p as Record<string, unknown>)[key];
        if (!isFiniteNumber(v)) errors.push(`${where}: trait "${key}" must be a number.`);
        else if (v < 0 || v > 100) errors.push(`${where}: trait "${key}" must be between 0 and 100.`);
      }
      if (!isFiniteNumber(p.baseSupport)) errors.push(`${where}: base support must be a number.`);
      else if (p.baseSupport < -1 || p.baseSupport > 1) errors.push(`${where}: base support must be between -1 and 1.`);

      if (isObject(p)) {
        parties.push({
          partyId: String(pid),
          name: typeof p.name === "string" ? p.name.trim() : "",
          shortName: typeof p.shortName === "string" ? p.shortName.trim() : "",
          color: typeof p.color === "string" ? p.color : "#9aa0a6",
          leaderName: typeof p.leaderName === "string" ? p.leaderName.trim() : "",
          charisma: isFiniteNumber(p.charisma) ? p.charisma : 0,
          energy: isFiniteNumber(p.energy) ? p.energy : 0,
          competence: isFiniteNumber(p.competence) ? p.competence : 0,
          machine: isFiniteNumber(p.machine) ? p.machine : 0,
          baseSupport: isFiniteNumber(p.baseSupport) ? p.baseSupport : 0,
        });
      }
    });
  }

  if (errors.length > 0) return null;
  return {
    ...(engine === "country" ? { countryId: raw.countryId as string } : {}),
    baseElection: baseElection as string,
    parties,
  };
}

// The single gate every save, load, and import passes through. Returns readable
// errors instead of throwing, so the UI can show the player exactly what to fix.
export function validateCustomScenario(rawInput: unknown): ValidationResult {
  const errors: string[] = [];
  if (!isObject(rawInput)) {
    return { ok: false, errors: ["File is not a valid custom scenario."] };
  }
  const raw = migrateToV2(rawInput);
  if (raw.version !== CUSTOM_SCENARIO_VERSION) {
    return {
      ok: false,
      errors: [
        `Unsupported scenario version (${String(raw.version)}). This app reads version ${CUSTOM_SCENARIO_VERSION}.`,
      ],
    };
  }
  const engine = raw.engine;
  if (typeof engine !== "string" || !ENGINES.includes(engine as CustomEngine)) {
    return { ok: false, errors: [`Unknown engine (${String(engine)}).`] };
  }
  if (typeof raw.id !== "string" || !isCustomScenarioId(raw.id)) {
    errors.push('Scenario id must start with "custom-".');
  }
  if (typeof raw.label !== "string" || raw.label.trim() === "") errors.push("Scenario needs a title.");
  if (typeof raw.label === "string" && raw.label.length > 60) errors.push("Scenario title is too long (60 characters max).");
  if (typeof raw.tagline !== "string") errors.push("Scenario tagline must be text.");
  if (typeof raw.tagline === "string" && raw.tagline.length > 200) errors.push("Scenario tagline is too long (200 characters max).");
  if (!isFiniteNumber(raw.year) || raw.year < 1788 || raw.year > 2100) errors.push("Year must be between 1788 and 2100.");

  const now = Date.now();
  const common = {
    engine: engine as CustomEngine,
    id: raw.id as string,
    label: typeof raw.label === "string" ? raw.label.trim() : "",
    tagline: typeof raw.tagline === "string" ? raw.tagline.trim() : "",
    year: raw.year as number,
    createdAt: isFiniteNumber(raw.createdAt) ? (raw.createdAt as number) : now,
    updatedAt: isFiniteNumber(raw.updatedAt) ? (raw.updatedAt as number) : now,
  };

  if (engine === "uk" || engine === "country") {
    const mp = validateMultipartyConfig(engine, raw.mp, errors);
    if (errors.length > 0 || !mp) {
      return { ok: false, errors: errors.length > 0 ? errors : ["Scenario data is incomplete."] };
    }
    return {
      ok: true,
      value: {
        version: CUSTOM_SCENARIO_VERSION,
        ...common,
        // Valid but unused US slot keeps the shared shape round-trippable.
        dem: makeDefaultTicket("dem"),
        rep: makeDefaultTicket("rep"),
        mp,
      },
    };
  }

  if (raw.eventMode !== undefined && !EVENT_MODES.includes(raw.eventMode as EventMode)) {
    errors.push("Event mode must be historical or plausible.");
  }
  const dem = validateTicket(raw.dem, "dem", errors);
  const rep = validateTicket(raw.rep, "rep", errors);
  if (errors.length > 0 || !dem || !rep) {
    return { ok: false, errors: errors.length > 0 ? errors : ["Scenario data is incomplete."] };
  }
  return {
    ok: true,
    value: {
      version: CUSTOM_SCENARIO_VERSION,
      ...common,
      dem,
      rep,
      eventMode: (raw.eventMode as EventMode) ?? "historical",
    },
  };
}

// A single generated running mate per side, so the custom ticket carries a valid
// default the setup/engine paths can resolve. Custom scenarios do not ship a VP
// shortlist; the player runs with a generic partner.
function defaultMate(side: "dem" | "rep", ticket: CustomTicketInput): RunningMate {
  return {
    id: `${side}-runningmate`,
    name: "Running Mate",
    ticket: side,
    historical: true,
    blurb: `Runs alongside ${ticket.shortName}.`,
  };
}

function toTicket(side: "dem" | "rep", t: CustomTicketInput): ScenarioTicket {
  return {
    name: t.name,
    shortName: t.shortName,
    party: t.party,
    color: t.color,
    traits: { ...t.traits },
    issuePositions: { ...t.issuePositions },
    baseFavorability: { ...t.baseFavorability },
    runningMates: [defaultMate(side, t)],
  };
}

// Adapt a validated custom scenario to the engine's Scenario record. State
// priors and EV apportionment are left to the engine defaults (2020 map), so a
// custom race plays on the standard board without touching election math.
export function toScenario(cs: CustomScenario): Scenario {
  return {
    id: cs.id,
    year: cs.year,
    label: cs.label,
    tagline: cs.tagline,
    dem: toTicket("dem", cs.dem),
    rep: toTicket("rep", cs.rep),
  };
}

// Inject a US custom scenario into the runtime registry so getScenario(id) finds
// it and createGame can build the game exactly like a built-in year. Idempotent.
// No-op for the multiparty engines: they build their game directly from the doc
// (see buildUkCustomGame / buildCountryCustomGame) and never enter SCENARIOS.
export function registerCustomScenario(cs: CustomScenario): void {
  if (cs.engine !== "us") return;
  SCENARIOS[cs.id] = toScenario(cs);
}

// Serialize for the export-to-file flow (pretty-printed for hand editing).
export function serializeCustomScenario(cs: CustomScenario): string {
  return JSON.stringify(cs, null, 2);
}

// Parse + validate an imported file. Throws with a readable message on bad data.
export function parseCustomScenario(json: string): CustomScenario {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error("That file is not valid JSON.");
  }
  const result = validateCustomScenario(parsed);
  if (!result.ok) throw new Error(result.errors.join("\n"));
  return result.value;
}

// ─────────────────────────────────────────────────────────────────────────
// UK / country adapters. These build a ready-to-play multiparty game STATE
// directly from a custom doc, reusing the chosen election's geography, blocs,
// and calibrated priors. Players customize parties, leaders, and base support;
// the geography is not editable. Games are marked `custom` so the results
// screen keeps them casual only (no leaderboard post), matching the free-tier,
// no-entitlement-gate rule for custom scenarios.
// ─────────────────────────────────────────────────────────────────────────

// Logits added to a party's per-bloc appeal per unit of baseSupport (-1..1).
const SUPPORT_TILT = 1.5;

// Apply each party's base-support nudge to the regions it contests, then refresh
// live support. A zero nudge is a no-op, so a default config reproduces history.
function applyBaseSupport(
  regions: UkGameState["regions"],
  overrides: Map<string, CustomPartyInput>,
): void {
  for (const region of regions) {
    let touched = false;
    for (const bloc of region.blocs) {
      if (!bloc.appeal) continue;
      for (const [pid, cp] of overrides) {
        if (cp.baseSupport === 0) continue;
        if (bloc.appeal[pid] === undefined) continue;
        bloc.appeal[pid] += cp.baseSupport * SUPPORT_TILT;
        touched = true;
      }
    }
    if (touched) {
      for (const bloc of region.blocs) bloc.support = blocPartyShares(bloc) as typeof bloc.support;
    }
  }
}

function firstPlayable(parties: CustomPartyInput[], playable: string[]): string | undefined {
  return parties.find((p) => playable.includes(p.partyId))?.partyId;
}

// Build a playable UK game from a UK custom doc. The real UK party set contests
// the chosen election; the player's overrides tune each party's leader and base
// support. (UK party labels/colors come from global UK content, so the running
// UK shell keeps the historic names; the stored labels travel with the doc.)
export function buildUkCustomGame(cs: CustomScenario): UkGameState {
  if (cs.engine !== "uk" || !cs.mp) throw new Error("Not a UK custom scenario.");
  const mp = cs.mp;
  const overrides = new Map(mp.parties.map((p) => [p.partyId, p]));
  const playerParty = firstPlayable(mp.parties, ukPlayablePartiesIn(mp.baseElection));
  const game = createUkGame({
    election: mp.baseElection,
    playerParty: playerParty as UkGameState["playerParty"] | undefined,
    seed: cs.id,
    difficulty: "normal",
  });
  for (const [pid, cp] of overrides) {
    const ld = game.leaders[pid];
    if (!ld) continue;
    game.leaders[pid] = {
      ...ld,
      name: cp.leaderName,
      charisma: cp.charisma,
      energy: cp.energy,
      competence: cp.competence,
      machine: cp.machine,
    };
  }
  applyBaseSupport(game.regions, overrides);
  game.label = cs.label;
  game.tagline = cs.tagline;
  game.custom = true;
  return game;
}

// Build a playable country game from a country custom doc. Here the whole
// bundle is cloned with the player's party labels/colors and leaders, so the
// country shell shows the custom names throughout. The clone is keyed by the
// custom id and never entered into COUNTRIES.
export function buildCountryCustomGame(cs: CustomScenario): { country: CountryBundle; game: CountryGameState } {
  if (cs.engine !== "country" || !cs.mp || !cs.mp.countryId) throw new Error("Not a country custom scenario.");
  const mp = cs.mp;
  const countryId = cs.mp.countryId;
  const base = COUNTRIES[countryId];
  if (!base) throw new Error(`Unknown country "${mp.countryId}".`);
  const overrides = new Map(mp.parties.map((p) => [p.partyId, p]));

  const parties = base.system.parties.map((pd) => {
    const o = overrides.get(pd.id);
    return o ? { ...pd, name: o.name, shortName: o.shortName, color: o.color } : pd;
  });

  const baseLeaders = base.leaders[mp.baseElection] ?? {};
  const mergedLeaders: Partial<Record<string, CountryLeader>> = { ...baseLeaders };
  for (const [pid, cp] of overrides) {
    mergedLeaders[pid] = {
      partyId: pid,
      name: cp.leaderName,
      charisma: cp.charisma,
      energy: cp.energy,
      competence: cp.competence,
      machine: cp.machine,
    };
  }

  const country: CountryBundle = {
    ...base,
    id: cs.id, // custom id keeps this off the shared leaderboard board
    label: cs.label,
    system: { ...base.system, parties },
    leaders: { ...base.leaders, [mp.baseElection]: mergedLeaders },
  };

  const playerParty = firstPlayable(mp.parties, countryPlayablePartiesIn(country, mp.baseElection));
  const game = createCountryGame(country, {
    election: mp.baseElection,
    playerParty: playerParty as CountryGameState["playerParty"] | undefined,
    seed: cs.id,
    difficulty: "normal",
  });
  applyBaseSupport(game.regions, overrides);
  game.label = cs.label;
  game.tagline = cs.tagline;
  game.custom = true;
  return { country, game };
}

// Restore the authoring document carried by a portable US campaign save.
// Old browser saves can still resolve a scenario already in the editor library.
export function registerCampaignCustomScenario(game: { scenarioId?: string; customScenario?: string }): void {
  if (!isCustomScenarioId(game.scenarioId)) {
    if (game.customScenario !== undefined) throw new Error("Custom scenario does not match this campaign.");
    return;
  }
  if (game.customScenario === undefined) {
    if (!SCENARIOS[game.scenarioId!]) throw new Error("This custom campaign is missing its authoring document. Import its scenario JSON first.");
    return;
  }
  if (typeof game.customScenario !== "string") throw new Error("Custom scenario data is malformed.");
  const cs = parseCustomScenario(game.customScenario);
  if (cs.engine !== "us" || cs.id !== game.scenarioId) throw new Error("Custom scenario does not match this campaign.");
  registerCustomScenario(cs);
}
