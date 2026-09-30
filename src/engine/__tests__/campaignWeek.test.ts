import { describe, it, expect } from "vitest";
import { createGame } from "../setup";
import { advanceTurn } from "../turn";
import { advanceCampaignWeek } from "../campaignWeek";
import { DIFFICULTY } from "../ai";

describe("complete campaign week", () => {
  it.each(["easy", "normal", "hard"] as const)("uses the normalized seed and %s AI without changing the input", difficulty => {
    const game = createGame({seed: "daily-2026-09-30", difficulty});
    const before = JSON.stringify(game);
    expect(advanceCampaignWeek(game, difficulty)).toEqual(
      advanceTurn(game, game.queuedActions, game.seed, {difficulty: DIFFICULTY[difficulty]}));
    expect(JSON.stringify(game)).toBe(before);
  });

  it("charges weekly staff salaries but skips payroll on election night", () => {
    const game = createGame({seed: "payroll", staff: ["body_man"]});
    const raw = advanceTurn(game, [], game.seed, {difficulty: DIFFICULTY.normal});
    const next = advanceCampaignWeek(game, "normal");
    expect(next.resources.dem.cash).toBe(raw.resources.dem.cash - 150_000);
    expect(next.lastRecap.some(r => r.label === "Staff payroll")).toBe(true);
    let end = next;
    while (end.phase !== "result") end = advanceCampaignWeek(end, "normal");
    expect(end.lastRecap.some(r => r.label === "Staff payroll")).toBe(false);
    expect(advanceCampaignWeek(end, "normal")).toBe(end);
  });
});
