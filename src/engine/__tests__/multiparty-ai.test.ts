import { describe, it, expect } from "vitest";
import { createRng } from "../rng";
import { createUkGame, ukAdvanceTurn, resolveUkPlayerEvent } from "../ukGame";
import { planMultipartyAi, mpFocusedActions, mpTargets, MP_DIFFICULTY } from "../multipartyAi";
import { nationalMpPoll } from "../multipartyPolls";
import { COUNTRIES } from "@content/countries";
import { createCountryGame } from "../countryGame";
import { UK_EVENTS } from "@content/uk/events";

describe("multiparty AI + polls + player events", () => {
  it("focused AI fills the action pool on close regions", () => {
    const g = createUkGame({ election: "2024", seed: 7, playerParty: "lab" });
    const res = g.resources.con;
    const actions = mpFocusedActions(
      { regions: g.regions, turn: g.turn, totalTurns: g.totalTurns, funds: res.funds, actions: res.actions },
      "con",
      MP_DIFFICULTY.hard,
    );
    expect(actions.length).toBe(res.actions);
    expect(actions.some((a) => a.type === "rally" || a.type === "canvass" || a.type === "ground_game")).toBe(true);
  });

  it("preserves content order for Germany's mathematically tied targets", () => {
    const game = createCountryGame(COUNTRIES.DE, { election: "2025", seed: "replay-DE", playerParty: "lnk" });
    const targets = mpTargets({ regions: game.regions, turn: 0, totalTurns: 6, funds: 10, actions: 7 }, "cdu", 7);
    expect(targets.slice(0, 3).map((target) => target.region.id)).toEqual(["NORTH", "NE", "NRW"]);
  });

  it("easy AI sometimes scattershots (deterministic with seed)", () => {
    const g = createUkGame({ election: "2024", seed: 3, playerParty: "lab" });
    const rng = createRng(99);
    const actions = planMultipartyAi(
      {
        regions: g.regions,
        turn: 0,
        totalTurns: 6,
        funds: g.resources.con.funds,
        actions: g.resources.con.actions,
      },
      "con",
      "easy",
      rng,
    );
    expect(actions.length).toBeGreaterThan(0);
  });

  it("national multiparty poll returns shares that sum ~1", () => {
    const g = createUkGame({ election: "2024", seed: 11, playerParty: "lab" });
    const poll = nationalMpPoll(g.seed, g.turn, g.regions);
    const sum = Object.values(poll).reduce((a, b) => a + b, 0);
    expect(sum).toBeCloseTo(1, 2);
    expect(poll.lab).toBeGreaterThan(0.2);
  });

  it("player-choice events queue as pending and resolve via choice", () => {
    // Force a choice event by planting it as a scheduled beat on turn 0 via
    // the generic pool: advance with AI on until a pending event appears, or
    // inject one directly.
    let g = createUkGame({ election: "2024", seed: 55, playerParty: "lab" });
    const choiceEv = UK_EVENTS.find((e) => e.choices && e.choices.length > 0)!;
    expect(choiceEv).toBeDefined();
    g.pendingEvent = { eventId: choiceEv.id, targetParty: "lab" };
    // End Week refuses while pending.
    const blocked = ukAdvanceTurn(g);
    expect(blocked.pendingEvent?.eventId).toBe(choiceEv.id);
    expect(blocked.turn).toBe(g.turn);

    const resolved = resolveUkPlayerEvent(g, choiceEv.choices![0].id);
    expect(resolved.pendingEvent).toBeNull();
    expect(resolved.causes.length + (resolved.lastRecap?.length ?? 0)).toBeGreaterThan(0);
  });

  it("week recap is populated after a turn with AI", () => {
    let g = createUkGame({ election: "2024", seed: 8, playerParty: "lab" });
    g.queuedActions = [{ type: "canvass", party: "lab", regionId: "LON" }];
    g = ukAdvanceTurn(g, { autoResolvePlayerEvents: true });
    expect(g.lastRecap.length).toBeGreaterThan(0);
    expect(g.lastRecap[0].label).toMatch(/Projected seats/i);
  });

  it("coalition-awareness pass fires in the final two turns of a hung map", () => {
    // uk-2017 is the hung-parliament coin flip — late-game focused AI should
    // still fill the pool, and with majority context it biases toward partner
    // / rival-vulnerable regions (coalitionBoost path in multipartyAi).
    const g = createUkGame({ election: "2017", seed: 42, playerParty: "con", totalTurns: 6 });
    g.turn = 4; // two turns left
    const res = g.resources.con;
    const actions = mpFocusedActions(
      {
        regions: g.regions,
        turn: g.turn,
        totalTurns: g.totalTurns,
        funds: res.funds,
        actions: res.actions,
        majority: { total: 650, threshold: 326 },
        abstaining: g.abstaining,
        compatible: (a, b) => !(new Set(["con", "lab"]).has(a) && new Set(["con", "lab"]).has(b)),
      },
      "con",
      MP_DIFFICULTY.hard,
    );
    expect(actions.length).toBe(res.actions);
    expect(actions.some((a) => a.regionId)).toBe(true);
  });
});
