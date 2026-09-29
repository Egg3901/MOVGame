import type { GameState } from "@engine/types";
import type { Projection } from "@engine/voteModel";
import type { UkGameState, UkResult } from "@engine/ukGame";
import type { CountryGameState, CountryResult } from "@engine/countryGame";
import { getScenario } from "@content/scenarios";

export function movAskSnapshot(game: GameState, projection: Projection) {
  const player = game.playerCandidate;
  const opponent = player === "dem" ? "rep" : "dem";
  const resources = game.resources[player];
  const names = new Map(game.states.map((state) => [state.id, state.name]));
  const sign = player === "dem" ? 1 : -1;
  return {
    version: 1,
    game: "electioneer",
    scenario: getScenario(game.scenarioId).label,
    country: "US",
    turn: game.turn,
    totalTurns: game.totalTurns,
    player,
    cash: resources.cash,
    momentum: resources.nationalMomentum,
    actionsLeft: Math.max(0, resources.actions - game.queuedActions.length),
    evPlayer: projection.ev[player],
    evOpponent: projection.ev[opponent],
    tossupEv: projection.tossupEv,
    states: projection.contests.map((contest) => ({
      name: names.get(contest.stateId) ?? contest.stateId,
      ev: contest.ev,
      playerMargin: (contest.demShare - 0.5) * 200 * sign,
    })),
    planned: game.queuedActions.map((action) => ({
      move: action.type,
      target: action.stateId ? (names.get(action.stateId) ?? action.stateId) : "National",
    })),
  };
}

export function movAskUrl(game: GameState, projection: Projection): string {
  return movAskUrlFromSnapshot(movAskSnapshot(game, projection));
}

export function movSeatAskSnapshot(game: UkGameState | CountryGameState, projection: UkResult | CountryResult, country: string) {
  const player = game.playerParty;
  const resources = game.resources[player];
  const names = new Map(game.regions.map((region) => [region.id, region.name]));
  return {
    version: 1,
    game: "electioneer",
    scenario: game.label,
    country,
    turn: game.turn,
    totalTurns: game.totalTurns,
    player,
    fundsMillions: resources.funds,
    momentum: resources.momentum,
    actionsLeft: Math.max(0, resources.actions - game.queuedActions.length),
    seatPlayer: projection.seats[player] ?? 0,
    seatTotal: Object.values(projection.seats).reduce((sum, seats) => sum + seats, 0),
    regions: projection.seatResults.map((result) => ({
      name: result.name,
      totalSeats: result.totalSeats,
      playerSeats: result.seatsByParty[player] ?? 0,
    })),
    planned: game.queuedActions.map((action) => ({
      move: action.type,
      target: action.regionId ? (names.get(action.regionId) ?? action.regionId) : "National",
    })),
  };
}

export function movAskUrlFromSnapshot(snapshot: object): string {
  const json = JSON.stringify(snapshot);
  const bytes = new TextEncoder().encode(json);
  if (bytes.length > 8_000) return "https://ask.lakesidegames.net/?game=electioneer";
  const encoded = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `https://ask.lakesidegames.net/from-mov#mov=${encoded}`;
}
