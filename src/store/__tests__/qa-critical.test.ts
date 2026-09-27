import "fake-indexeddb/auto";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { localProvider } from "@persistence/local";
import { useGameStore } from "../gameStore";
import { useUkStore } from "../ukStore";
import { useCountryStore } from "../countryStore";
import { mpPlannedCost } from "@engine/mpBudget";

const flushSave = () => new Promise((resolve) => setTimeout(resolve, 30));

describe("QA campaign safety", () => {
  afterEach(() => vi.unstubAllGlobals());
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

  it("recovers the synchronous session snapshot if refresh interrupts IndexedDB", async () => {
    const entries = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      setItem: (key: string, value: string) => entries.set(key, value),
      getItem: (key: string) => entries.get(key) ?? null,
      removeItem: (key: string) => entries.delete(key),
    });
    useGameStore.getState().newGame({ seed: "qa-session", playerCandidate: "rep" });
    useGameStore.getState().queueAction({ type: "rally", candidate: "rep", stateId: "PA", day: 1 });
    await flushSave();
    await localProvider.remove("autosave");
    useGameStore.getState().unload();
    await useGameStore.getState().loadGame("autosave");
    expect(useGameStore.getState().game?.queuedActions[0]?.stateId).toBe("PA");
    await flushSave();
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

  it("reserves fixed and broadcast costs in UK and country plans", () => {
    useUkStore.getState().newGame("2019", "lab", "qa-uk-budget");
    const ukBefore = useUkStore.getState().game!.resources.lab.funds;
    for (let day = 1; day <= 7; day++) {
      useUkStore.getState().queueAction({ type: "broadcast", party: "lab", spend: 8, day });
    }
    expect(mpPlannedCost(useUkStore.getState().game!.queuedActions)).toBeLessThanOrEqual(ukBefore);

    useCountryStore.getState().newGame("FR", "2022", "ens", "qa-fr-budget");
    const frBefore = useCountryStore.getState().game!.resources.ens.funds;
    for (let day = 1; day <= 7; day++) {
      useCountryStore.getState().queueAction({ type: "broadcast", party: "ens", spend: 8, day });
      useCountryStore.getState().queueAction({ type: "ground_game", party: "ens", regionId: "IDF", day });
    }
    expect(mpPlannedCost(useCountryStore.getState().game!.queuedActions)).toBeLessThanOrEqual(frBefore);
  });
});
