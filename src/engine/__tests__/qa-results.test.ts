import { describe, expect, it } from "vitest";
import { createGame } from "../setup";
import { applyAction } from "../actions";
import { createRng } from "../rng";
import { computeResult } from "../voteModel";
import { initUsReplayLog, syncUsReplayLog } from "@store/usReplay";
import { createReplayLog, deriveReport } from "@lib/replay";
import { createUkGame, majorityForUk, projectUk, ukCompatible } from "../ukGame";
import { createCountryGame, majorityFor, projectCountry } from "../countryGame";
import { COUNTRIES } from "@content/countries";
import { advanceTurn, beginGame } from "../turn";
import { usOpponentEvMargin } from "../scoring";

describe("QA result reconciliation", () => {
  it("records the decided election result in the final replay snapshot", () => {
    const game = createGame({ seed: "qa-final", playerCandidate: "rep" });
    game.result = computeResult(game);
    game.phase = "result";
    const snapshot = initUsReplayLog(game, "casual").snapshots[0];
    expect(snapshot.standings.find((s) => s.id === "dem")?.units)
      .toBe(game.result.electoralVotes.dem);
    expect(snapshot.standings.find((s) => s.id === "rep")?.poll)
      .toBeCloseTo(game.result.popularShare.rep, 6);
    expect(snapshot.tossupUnits).toBe(0);
  });

  it("lists only the player's own actions as self-caused swings", () => {
    const game = createGame({ seed: "qa-attribution", playerCandidate: "rep" });
    applyAction(game, { type: "advertise", candidate: "dem", stateId: "GA", spend: 8_000_000 }, createRng("dem"));
    applyAction(game, { type: "advertise", candidate: "rep", stateId: "PA", spend: 8_000_000 }, createRng("rep"));
    const report = computeResult(game).postMortem;
    expect(report.length).toBeGreaterThan(0);
    expect(report.every((cause) => cause.stateId === "PA")).toBe(true);
  });

  it("uses the full two-party margin for decisive contests", () => {
    const log = createReplayLog({
      engine: "us", scenarioId: "2016", playerId: "rep", unitLabel: "electoral votes",
      unitTotal: 538, majority: 270, totalTurns: 9, mode: "casual",
      parties: [{ id: "dem", name: "Clinton", color: "blue" }, { id: "rep", name: "Trump", color: "red" }],
      contestNames: { GA: "Georgia" },
      snapshots: [{ turn: 9, leaderId: "dem", standings: [
        { id: "dem", poll: 0.53, units: 300 }, { id: "rep", poll: 0.47, units: 238 },
      ], tossupUnits: 0, contestShare: { GA: 0.47 }, actions: [], events: [] }],
    });
    const ga = deriveReport(log).decisiveContests[0];
    expect(ga.won).toBe(false);
    expect(ga.marginPts).toBeCloseTo(-6, 6);
  });

  it("reconciles every region's full seat pool with the national total", () => {
    const uk = createUkGame({ election: "2019", seed: "qa-regions" });
    expect(projectUk(uk).seatResults.reduce((sum, region) => sum + region.totalSeats, 0))
      .toBe(majorityForUk(uk).total);
    for (const countryId of ["FR", "DE", "CA"]) {
      const country = COUNTRIES[countryId];
      const elections = Object.keys(country.elections).sort();
      const election = elections[elections.length - 1]!;
      const game = createCountryGame(country, { election, seed: "qa-regions" });
      const result = projectCountry(game, country);
      expect(result.seatResults.reduce((sum, region) => sum + region.totalSeats, 0), countryId)
        .toBe(majorityFor(game, country).total);
      expect(Object.values(result.seats).reduce((sum, seats) => sum + seats, 0), countryId)
        .toBe(majorityFor(game, country).total);
    }
  });

  it("does not form a Conservative-SNP governing pact", () => {
    expect(ukCompatible("con", "snp")).toBe(false);
    expect(ukCompatible("snp", "con")).toBe(false);
    expect(ukCompatible("con", "ld")).toBe(true);
  });

  it("keeps every planned action and the cash change in the weekly review", () => {
    const game = beginGame(createGame({ scenario: "2016", seed: "qa-recap", playerCandidate: "rep" }));
    const actions = [
      { type: "rally", stateId: "PA" }, { type: "rally", stateId: "MI" },
      { type: "surrogate", stateId: "WI" }, { type: "fundraise", stateId: "TX" },
      { type: "ground_game", stateId: "FL" }, { type: "advertise", stateId: "GA", spend: 8_000_000 },
      { type: "oppo_research" },
    ] as const;
    const next = advanceTurn(game, actions.map((action, index) => ({ ...action, candidate: "rep" as const, day: index + 1 })), "qa-recap");
    const labels = next.lastRecap.map((item) => item.label);
    for (const label of ["Rally in PA", "Rally in MI", "Surrogate visit to WI", "Fundraising haul in TX", "Built field offices in FL", "Positive ads in GA"]) {
      expect(labels.some((item) => item.includes(label)), label).toBe(true);
    }
    expect(labels.some((label) => label.includes("Campaign cash"))).toBe(true);
    expect(next.lastRecap.filter((item) => item.label.startsWith("Plan bonus:"))
      .every((item) => item.marginDelta === undefined)).toBe(true);
  });

  it("reports the actual ticket-to-ticket electoral margin", () => {
    const result = { electoralVotes: { dem: 385, rep: 153 }, popularShare: { dem: 0.529, rep: 0.471 } };
    expect(usOpponentEvMargin(result, "rep")).toBe(-232);
    expect(usOpponentEvMargin(result, "dem")).toBe(232);
  });

  it("recovers the full timeline when a saved replay is missing or one week behind", () => {
    const opening = beginGame(createGame({ seed: "qa-timeline", playerCandidate: "dem" }));
    const next = advanceTurn(opening, [], "qa-timeline");
    const recovered = initUsReplayLog(next, "casual");
    expect(recovered.snapshots.map((snapshot) => snapshot.turn)).toEqual([0, 1]);
    const lagging = initUsReplayLog(opening, "casual");
    const snapshots = syncUsReplayLog(lagging, next).snapshots;
    expect(snapshots[snapshots.length - 1]?.turn).toBe(1);
  });
});
