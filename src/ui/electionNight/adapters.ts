// Adapters: normalize each engine's final result into the shared RevealProps
// shape that drives the ElectionNight reveal. Pure functions over final game
// state — no store access, so they're trivially testable.
import type { GameState } from "@engine/types";
import type { UkGameState } from "@engine/ukGame";
import { majorityFor, type CountryBundle, type CountryGameState } from "@engine/countryGame";
import type { PartyId } from "@engine/system";
import type { SeatResult, StateContest } from "@engine/types";
import { SCENARIOS } from "@content/scenarios";
import { STATE_PATHS } from "@content/statePaths";
import { REGION_PATHS, UK_VIEWBOX } from "@content/uk/regionPaths";
import { partyColor as ukColor, partyShort as ukShort, sortBySeats as ukSortBySeats } from "../uk/parties";
import { partyColor as coColor, partyShort as coShort, sortBySeats as coSortBySeats } from "../country/helpers";
import { OTHERS_ID, type RevealUnit } from "@lib/electionReveal";
import type { RevealMap, RevealParty, RevealProps } from "./ElectionNight";

type RevealData = Omit<RevealProps, "onDone">;

const OTHERS_COLOR = "#5b6b8c";

// Same viewBox USMap.tsx uses to render STATE_PATHS in geo mode.
const US_MAP_VIEWBOX = "0 0 1000 650";

// Map geometry for the US reveal. At-large split units (ME-AL / NE-AL) reuse
// the parent state outline; the numbered congressional districts have no
// geometry of their own and stay chips-only.
function usRevealMap(units: RevealUnit[]): RevealMap {
  const shapes: RevealMap["shapes"] = {};
  for (const u of units) {
    const d =
      STATE_PATHS[u.id] ?? (u.id.endsWith("-AL") ? STATE_PATHS[u.id.split("-")[0]] : undefined);
    if (d) shapes[u.id] = { d };
  }
  return { viewBox: US_MAP_VIEWBOX, shapes };
}

// ── US (two-party electoral college) ─────────────────────────────────────
export function usReveal(game: GameState): RevealData {
  const result = game.result!;
  const cands = game.candidates;
  const stById = new Map(game.states.map((s) => [s.id, s]));

  const units: RevealUnit[] = result.stateResults.map((sr) => {
    const st = stById.get(sr.stateId);
    return {
      id: sr.stateId,
      name: st?.name ?? sr.stateId,
      abbr: st?.abbr ?? sr.stateId,
      winnerId: sr.winner,
      winnerColor: cands[sr.winner].color,
      winnerShort: cands[sr.winner].shortName,
      units: sr.electoralVotes,
      margin: sr.margin,
      // Upset = the call differs from the state's baseline lean (the real
      // prior-election two-party share the scenario was solved against).
      upset: st ? (sr.winner === "dem") !== (st.prior2020DemShare > 0.5) : false,
    };
  });

  const scenarioId = game.scenarioId ?? "2020";
  const year = SCENARIOS[scenarioId]?.year ?? scenarioId;
  return {
    title: `Election Night · ${year}`,
    totalUnits: units.reduce((s, u) => s + u.units, 0),
    threshold: 270,
    parties: [
      { id: "dem", short: cands.dem.shortName, color: cands.dem.color },
      { id: "rep", short: cands.rep.shortName, color: cands.rep.color },
    ],
    units,
    playerPartyId: game.playerCandidate,
    unitLabel: "EV",
    map: usRevealMap(units),
    noMajorityLabel: "269-269: ELECTION GOES TO THE HOUSE",
    storageKey: `reveal-${game.seed}-us-${scenarioId}`,
  };
}

// ── Shared multiparty helpers (UK + country) ─────────────────────────────
// Winner's regional margin in points = gap between the top two vote shares.
function regionMargin(sr: SeatResult): number {
  const shares = Object.values(sr.voteShare).sort((a, b) => b - a);
  return ((shares[0] ?? 0) - (shares[1] ?? 0)) * 100;
}

// Upset = the region's plurality winner differs from its real-baseline seat
// winner (StateContest.baselineSeats, the election's actual seat split).
function regionUpset(sr: SeatResult, region: StateContest | undefined): boolean {
  const base = region?.baselineSeats;
  if (!base) return false;
  const baseWinner = Object.entries(base).sort((a, b) => b[1] - a[1])[0]?.[0];
  return baseWinner !== undefined && baseWinner !== sr.winner;
}

function multipartyUnits(
  seatResults: SeatResult[],
  regions: StateContest[],
  color: (p: PartyId) => string,
  short: (p: PartyId) => string,
): RevealUnit[] {
  const regById = new Map(regions.map((r) => [r.id, r]));
  return seatResults.map((sr) => {
    const region = regById.get(sr.contestId);
    return {
      id: sr.contestId,
      name: sr.name,
      abbr: region?.abbr ?? sr.contestId,
      winnerId: sr.winner,
      winnerColor: color(sr.winner),
      winnerShort: short(sr.winner),
      units: sr.totalSeats,
      allocation: sr.seatsByParty,
      margin: regionMargin(sr),
      upset: regionUpset(sr, region),
    };
  });
}

// Tally rows: top 4 parties by final seats, plus an aggregated "others" row
// when more parties than that actually won seats.
function multipartyRows(
  seats: Record<PartyId, number>,
  order: PartyId[],
  color: (p: PartyId) => string,
  short: (p: PartyId) => string,
): RevealParty[] {
  const rows: RevealParty[] = order.slice(0, 4).map((p) => ({ id: p, short: short(p), color: color(p) }));
  const winners = Object.keys(seats).filter((p) => (seats[p] ?? 0) > 0);
  if (winners.length > rows.length) rows.push({ id: OTHERS_ID, short: "OTH", color: OTHERS_COLOR });
  return rows;
}

// ── UK (650-seat Westminster) ────────────────────────────────────────────
export function ukReveal(game: UkGameState): RevealData {
  const r = game.result!;
  const units = multipartyUnits(r.seatResults, game.regions, ukColor, ukShort);
  const total = units.reduce((s, u) => s + u.units, 0); // 650 in the modern era
  return {
    title: `Election Night · ${game.label}`,
    totalUnits: total,
    threshold: Math.floor(total / 2) + 1, // 326 of 650
    parties: multipartyRows(r.seats, ukSortBySeats(r.seats), ukColor, ukShort),
    units,
    playerPartyId: game.playerParty,
    unitLabel: "seats",
    map: { viewBox: UK_VIEWBOX, shapes: REGION_PATHS },
    noMajorityLabel: "NO OVERALL MAJORITY: HUNG PARLIAMENT",
    storageKey: `reveal-${game.seed}-uk-${game.electionId}`,
  };
}

// ── Country bundles (CA / DE / FR / AU — chamber sizes vary per election) ─
export function countryReveal(country: CountryBundle, game: CountryGameState): RevealData {
  const r = game.result!;
  const majority = majorityFor(game, country); // per-election winning post
  const color = (p: PartyId) => coColor(country, p);
  const short = (p: PartyId) => coShort(country, p);
  const units = multipartyUnits(r.seatResults, game.regions, color, short);
  return {
    title: `Election Night · ${game.label}`,
    totalUnits: majority.total,
    threshold: majority.threshold,
    parties: multipartyRows(r.seats, coSortBySeats(country, r.seats), color, short),
    units,
    playerPartyId: game.playerParty,
    unitLabel: country.unitNamePlural,
    map: country.map, // all shipped bundles carry one; undefined ⇒ chip-only
    noMajorityLabel: "NO OVERALL MAJORITY: HUNG PARLIAMENT",
    storageKey: `reveal-${game.seed}-${country.id.toLowerCase()}-${game.electionId}`,
  };
}
