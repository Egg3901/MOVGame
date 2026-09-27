import { describe, expect, it } from "vitest";
import { createCountryGame, countryAdvanceTurn, computeCountryResult, playablePartiesIn } from "../countryGame";
import { COUNTRIES } from "@content/countries";
import { computeScoreFromFacts, multipartyScoreFacts } from "../scoring";

describe("country bundles", () => {
  it("every election's seats total its chamber; every result region has meta", () => {
    // The bundle's newest election matches system.majority; older cycles may
    // override (Canada 2021: 338, Bundestag 2021: 735 with overhang).
    for (const [cid, country] of Object.entries(COUNTRIES)) {
      for (const election of Object.values(country.elections)) {
        const majority = election.majority ?? country.system.majority;
        expect(majority.threshold, `${cid} ${election.id} threshold`).toBe(Math.floor(majority.total / 2) + 1);
        let total = 0;
        for (const [rid, res] of Object.entries(election.regions)) {
          const meta = country.regions.find((r) => r.id === rid);
          expect(meta, `${cid}: region meta ${rid}`).toBeDefined();
          total += Object.values(res.s).reduce((s, x) => s + x, 0);
        }
        expect(total, `${cid} ${election.id} chamber`).toBe(majority.total);
      }
    }
  });

  it("neutral play reproduces each election's real result (calibration anchor)", () => {
    for (const country of Object.values(COUNTRIES)) {
      for (const election of Object.values(country.elections)) {
        let g = createCountryGame(country, { election: election.id, seed: 42 });
        for (let t = 0; t < g.totalTurns; t++) g = countryAdvanceTurn(g, country, { disableAi: true });
        const result = g.result ?? computeCountryResult(g, country);

        // Baseline seats per party across regions:
        const expected: Record<string, number> = {};
        for (const res of Object.values(election.regions)) {
          for (const [p, s] of Object.entries(res.s)) expected[p] = (expected[p] ?? 0) + s;
        }
        for (const [p, s] of Object.entries(expected)) {
          expect(
            Math.abs((result.seats[p] ?? 0) - s),
            `${country.id} ${election.id} ${p}: got ${result.seats[p]}, real ${s}`,
          ).toBeLessThanOrEqual(2); // largest-remainder wobble tolerance
        }
      }
    }
  });

  it("known winners: LPC largest in CA-2025, Union in DE-2025, Centre wins FR-2027", () => {
    const expectWinner = (cid: string, party: string) => {
      const country = COUNTRIES[cid];
      let g = createCountryGame(country, { seed: 7 });
      for (let t = 0; t < g.totalTurns; t++) g = countryAdvanceTurn(g, country, { disableAi: true });
      expect(g.result!.largestParty, cid).toBe(party);
    };
    expectWinner("CA", "lpc");
    expectWinner("DE", "cdu");
    expectWinner("FR", "ens");
  });

  it("Germany's firewall: no government ever includes the AfD as a partner", () => {
    const country = COUNTRIES.DE;
    for (const seed of [1, 2, 3, 4, 5]) {
      let g = createCountryGame(country, { seed, playerParty: "afd" });
      // autoResolvePlayerEvents: bots/tests pick the first choice so the loop
      // never stalls on a pending decision modal.
      for (let t = 0; t < g.totalTurns + 2; t++) {
        if (g.phase === "result") break;
        g = countryAdvanceTurn(g, country, { autoResolvePlayerEvents: true });
      }
      const gov = g.result!.government;
      if (gov.kind === "coalition") expect(gov.parties).not.toContain("afd");
      if (gov.kind === "confidence_supply") expect(gov.partner).not.toBe("afd");
    }
  });

  it("a played game runs to completion and scores sanely", () => {
    const country = COUNTRIES.CA;
    let g = createCountryGame(country, { seed: 99, playerParty: "cpc" });
    expect(playablePartiesIn(country, "2025")).toContain("cpc");
    for (let t = 0; t < g.totalTurns + 2; t++) {
      if (g.phase === "result") break;
      g.queuedActions = [
        { type: "rally", party: "cpc", regionId: "ON" },
        { type: "canvass", party: "cpc", regionId: "QC" },
        { type: "fundraise", party: "cpc" },
      ];
      g = countryAdvanceTurn(g, country, { autoResolvePlayerEvents: true });
    }
    expect(g.phase).toBe("result");
    const facts = multipartyScoreFacts(g.result!, "cpc", 172, 343, "normal");
    const score = computeScoreFromFacts(facts);
    expect(score).toBeGreaterThanOrEqual(0);
    expect(score).toBeLessThanOrEqual(1000);
  });

  it("France 2022 preserves the historic opening but rewards a national RN campaign", () => {
    const country = COUNTRIES.FR;
    const opening = createCountryGame(country, { election: "2022", playerParty: "rn", seed: "fr-opening" });
    const openingResult = computeCountryResult(opening, country);
    expect(openingResult.seats.rn).toBeLessThan(51);
    expect(openingResult.voteShare.rn).toBeCloseTo(0.4145, 1);

    let passiveWins = 0;
    let preparedWins = 0;
    for (let seed = 0; seed < 12; seed++) {
      for (const prepared of [false, true]) {
        let game = createCountryGame(country, {
          election: "2022", playerParty: "rn", difficulty: "normal", seed: `fr-probe-${seed}`,
        });
        while (game.phase !== "result") {
          if (prepared) {
            game.queuedActions = Array.from({ length: game.resources.rn.actions }, (_, action) =>
              action < 2
                ? { type: "broadcast" as const, party: "rn", spend: 0.5, mode: "contrast" as const, targetParty: "ens" }
                : { type: "policy_prep" as const, party: "rn" },
            );
          }
          game = countryAdvanceTurn(game, country, { autoResolvePlayerEvents: true });
        }
        const won = (game.result?.seats.rn ?? 0) >= 51;
        if (prepared && won) preparedWins++;
        if (!prepared && won) passiveWins++;
      }
    }
    expect(passiveWins).toBe(0);
    expect(preparedWins).toBeGreaterThanOrEqual(2);
    expect(preparedWins).toBeLessThanOrEqual(8);
  });
});
