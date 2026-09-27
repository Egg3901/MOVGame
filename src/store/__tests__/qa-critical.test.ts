import "fake-indexeddb/auto";
import { beforeEach, describe, expect, it } from "vitest";
import { localProvider } from "@persistence/local";
import { useGameStore } from "../gameStore";

const flushSave = () => new Promise((resolve) => setTimeout(resolve, 30));

describe("QA campaign safety", () => {
  beforeEach(async () => {
    useGameStore.setState({ game: null, history: [], replay: null });
    await localProvider.remove("autosave");
  });

  it("restores a queued action from the autosave after a reload", async () => {
    useGameStore.getState().newGame({ seed: "qa-autosave", playerCandidate: "dem" });
    useGameStore.getState().queueAction({
      type: "rally", candidate: "dem", stateId: "PA", day: 2,
    });
    await flushSave();
    const saved = await localProvider.load("autosave");
    expect(saved?.state.queuedActions).toHaveLength(1);
    expect(saved?.state.queuedActions[0].day).toBe(2);
  });

  it("rejects an ad that would exceed the queued cash budget", () => {
    useGameStore.getState().newGame({ seed: "qa-spend", playerCandidate: "dem" });
    const before = useGameStore.getState().game!.resources.dem.cash;
    for (let index = 0; index < 11; index++) {
      useGameStore.getState().queueAction({
        type: "advertise", candidate: "dem", stateId: "PA", day: Math.floor(index / 3) + 1,
        adMode: "positive", spend: 30_000_000,
      });
    }
    const game = useGameStore.getState().game!;
    expect(game.queuedActions.reduce((sum, action) => sum + (action.spend ?? 0), 0))
      .toBeLessThanOrEqual(before);
  });
});
